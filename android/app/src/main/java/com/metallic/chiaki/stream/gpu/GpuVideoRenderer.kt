// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream.gpu

import android.content.res.AssetManager
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES31
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User tuning for the GPU path. [extrapolationScale] is the glExtrapolateTex2DQCOM scale factor:
 * how far past the newest real frame the generated frame targets, in frame intervals (0.5 = the
 * midpoint between two 60 fps frames, which is where a 120 Hz vsync falls).
 */
data class GpuTuning(
	val sgsrEdgeSharpness: Float = GlShaders.SGSR1_DEFAULT_EDGE_SHARPNESS,
	val sgsrEdgeThreshold255: Int = GlShaders.SGSR1_DEFAULT_EDGE_THRESHOLD_255,
	val sgsrEdgeDirection: Boolean = true,
	val extrapolationScale: Float = 0.5f)

/**
 * GPU video path: MediaCodec decodes into [inputSurface]; every display vsync this thread
 * presents either the newest decoded frame or a hardware-extrapolated one, upscaled with
 * SGSR1 (or a plain blit) onto the SurfaceView's surface.
 *
 * HDR ([hdr]): the decoder's 10-bit BT.2020 PQ frames are converted explicitly from Y'CbCr
 * (GL_EXT_YUV_target), kept PQ-encoded in RGBA16F ring slots (a format the extrapolation
 * extension accepts), and presented on a 10-bit window surface tagged BT.2020 PQ, which
 * SurfaceFlinger composes as an HDR10 layer like a video player's output.
 *
 * Threading: all GL work happens on the private render thread, whose [HandlerThread] starts
 * in the constructor, so construct this only when the GPU path will actually be used. Public
 * methods are safe to call from any thread other than the render thread; [start] and [release]
 * block until the render thread is done, so they must never be called from the render thread itself.
 */
class GpuVideoRenderer(
	private val assets: AssetManager,
	private val streamWidth: Int,
	private val streamHeight: Int,
	streamFps: Int,
	private val upscalingRequested: Boolean,
	private val frameGenerationRequested: Boolean,
	private val hdr: Boolean = false,
	private val tuning: GpuTuning = GpuTuning()
)
{
	companion object
	{
		private const val TAG = "GpuVideoRenderer"
		private const val STATS_WINDOW_NS = 1_000_000_000L
		private const val SURFACE_TEARDOWN_TIMEOUT_MS = 500L
		// EGL_KHR_gl_colorspace and EGL_EXT_gl_colorspace_bt2020_pq; android.opengl exposes neither constant.
		private const val EGL_GL_COLORSPACE_KHR = 0x309D
		private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
		private const val MAX_CONFIGS = 16
	}

	/** [outputWidth]/[outputHeight] are the window surface size, 0 while no surface is attached. */
	data class Stats(val presentedFps: Double, val generatedFps: Double, val frameGenerationActive: Boolean, val upscalingActive: Boolean,
		val outputWidth: Int, val outputHeight: Int)

	private val thread = HandlerThread("GpuVideoRenderer").also { it.start() }
	private val handler = Handler(thread.looper)
	private val pacer = FramePacer(1_000_000_000L / streamFps.coerceAtLeast(1), frameGenerationRequested)
	// A single flag suffices: the SurfaceTexture producer is non-blocking, queueBuffer replaces the
	// last un-acquired buffer, and updateTexImage always latches the newest frame, so MediaCodec
	// never stalls on us and no FIFO latency can build up between decoder and vsync.
	private val frameAvailable = AtomicBoolean(false)
	@Volatile private var released = false
	/** Set when decoding has ended (updateTexImage failed); no further GL work happens afterwards. */
	@Volatile private var stopped = false
	private var started = false

	// EGL
	private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
	private var eglConfig: EGLConfig? = null
	private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
	private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
	private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
	@Volatile private var windowWidth = 0
	@Volatile private var windowHeight = 0

	// GL objects
	private var oesTexture = 0
	private val ring = IntArray(2)
	private val ringFbo = IntArray(2)
	private var newestSlot = 0 // index into ring of the most recent real frame
	private var generatedTexture = 0
	private var oesCopyProgram = 0
	private var oesTransformLoc = -1
	private var presentProgram = 0 // SGSR1 or blit
	@Volatile private var presentIsSgsr = false
	@Volatile private var frameGenerationActive = false
	private val transform = FloatArray(16)

	private var surfaceTexture: SurfaceTexture? = null
	lateinit var inputSurface: Surface
		private set

	// Stats (1 s window, Choreographer frame time base)
	private var windowStartNs = Long.MIN_VALUE
	private var presentedInWindow = 0
	private var generatedInWindow = 0
	@Volatile private var presentedFps = 0.0
	@Volatile private var generatedFps = 0.0
	private val loggedGlErrors = HashSet<String>()

	private val vsyncCallback = object: Choreographer.FrameCallback
	{
		override fun doFrame(frameTimeNanos: Long)
		{
			if(released) return
			Choreographer.getInstance().postFrameCallback(this)
			onVsync(frameTimeNanos)
		}
	}

	/** Initializes EGL/GL on the render thread. Returns false (after logging and cleaning up) if the GPU path is unusable. */
	fun start(): Boolean
	{
		check(Looper.myLooper() != thread.looper) { "start() must not be called from the render thread" }
		check(!released) { "start() after release()" }
		check(!started) { "start() called twice" }
		started = true
		var ok = false
		val latch = CountDownLatch(1)
		handler.post {
			try
			{
				initGl()
				Choreographer.getInstance().postFrameCallback(vsyncCallback)
				ok = true
			}
			catch(e: Throwable)
			{
				// Throwable: an UnsatisfiedLinkError or OOM must not leave the caller waiting forever.
				Log.e(TAG, "GPU path init failed: ${e.message}", e)
			}
			finally
			{
				latch.countDown()
			}
		}
		latch.await()
		if(!ok) release()
		return ok
	}

	/**
	 * New SurfaceView surface (or null when it is gone). Only the EGL window surface changes; decoding continues.
	 *
	 * The null case blocks (bounded) until the render thread has destroyed the window surface, because the
	 * SurfaceHolder contract requires that drawing has stopped before surfaceDestroyed returns. The
	 * non-null case stays asynchronous. The render thread never waits on the caller, so this cannot deadlock.
	 */
	fun setOutputSurface(surface: Surface?, width: Int, height: Int)
	{
		val gone = if(surface == null) CountDownLatch(1) else null
		val posted = handler.post {
			try
			{
				if(!released)
					applyOutputSurface(surface, width, height)
			}
			finally
			{
				gone?.countDown()
			}
		}
		if(gone != null && posted && !gone.await(SURFACE_TEARDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS))
			Log.w(TAG, "render thread did not release the window surface within ${SURFACE_TEARDOWN_TIMEOUT_MS} ms")
	}

	fun stats(): Stats = Stats(presentedFps, generatedFps, frameGenerationActive, presentIsSgsr, windowWidth, windowHeight)

	/**
	 * Stops presenting and frees all EGL/GL resources, blocking until the render thread is done.
	 * Must be called from a thread other than the render thread (it would deadlock on itself).
	 * The caller must stop the native decoder first (native setSurface(null) / session stop):
	 * releasing the SurfaceTexture abandons its BufferQueue, and a decoder still queueing into
	 * [inputSurface] would fail.
	 */
	fun release()
	{
		check(Looper.myLooper() != thread.looper) { "release() must not be called from the render thread" }
		if(released) return
		released = true
		val latch = CountDownLatch(1)
		handler.post {
			try
			{
				Choreographer.getInstance().removeFrameCallback(vsyncCallback)
				releaseGl()
			}
			finally
			{
				latch.countDown()
			}
		}
		if(!latch.await(2, TimeUnit.SECONDS))
			Log.e(TAG, "release(): render thread did not finish GL teardown within 2 s")
		thread.quitSafely()
	}

	// ---- render thread below ----

	private fun applyOutputSurface(surface: Surface?, width: Int, height: Int)
	{
		destroyWindowSurface()
		if(surface == null || !surface.isValid) return
		val surfaceAttribs = if(hdr) intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT, EGL14.EGL_NONE)
			else intArrayOf(EGL14.EGL_NONE)
		windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
		if(windowSurface == EGL14.EGL_NO_SURFACE)
		{
			Log.e(TAG, "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
			return
		}
		if(!EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext))
		{
			Log.e(TAG, "eglMakeCurrent(window) failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
			destroyWindowSurface()
			return
		}
		EGL14.eglSwapInterval(eglDisplay, 1)
		val w = IntArray(1)
		val h = IntArray(1)
		EGL14.eglQuerySurface(eglDisplay, windowSurface, EGL14.EGL_WIDTH, w, 0)
		EGL14.eglQuerySurface(eglDisplay, windowSurface, EGL14.EGL_HEIGHT, h, 0)
		windowWidth = if(w[0] > 0) w[0] else width
		windowHeight = if(h[0] > 0) h[0] else height
		Log.i(TAG, "output surface ${windowWidth}x${windowHeight} (stream ${streamWidth}x${streamHeight}, upscale=$presentIsSgsr, framegen=$frameGenerationActive, hdr=$hdr)")
	}


	private fun initGl()
	{
		eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
		check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
		val version = IntArray(2)
		check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
		if(hdr)
		{
			val eglExtensions = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS) ?: ""
			check(eglExtensions.contains("EGL_EXT_gl_colorspace_bt2020_pq")) { "HDR needs EGL_EXT_gl_colorspace_bt2020_pq" }
		}
		// SDR: RGBA8. HDR: RGB10_A2, which Android maps to a RGBA_1010102 window buffer.
		eglConfig = chooseConfig(colorBits = if(hdr) 10 else 8, alphaBits = if(hdr) 2 else 8)
		eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
			intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
		check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
		pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig,
			intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
		check(pbufferSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
		check(EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)) { "eglMakeCurrent failed" }
		Log.i(TAG, "GL ${GLES31.glGetString(GLES31.GL_VERSION)} on ${GLES31.glGetString(GLES31.GL_RENDERER)}, hdr=$hdr")
		if(hdr)
		{
			val glExtensions = GLES31.glGetString(GLES31.GL_EXTENSIONS) ?: ""
			check(glExtensions.split(' ').contains("GL_EXT_YUV_target")) { "HDR needs GL_EXT_YUV_target" }
		}

		// Programs: the copy shader is mandatory; SGSR failure only degrades to the blit.
		oesCopyProgram = GlShaders.buildOesCopyProgram(hdr)
		presentProgram = if(upscalingRequested)
		{
			try
			{
				val program = GlShaders.buildSgsr1Program(assets, tuning.sgsrEdgeSharpness, tuning.sgsrEdgeThreshold255, tuning.sgsrEdgeDirection)
				presentIsSgsr = true
				program
			}
			catch(e: ShaderCompileException)
			{
				Log.w(TAG, "SGSR1 unavailable, using bilinear blit: ${e.message}")
				presentIsSgsr = false
				GlShaders.buildBlitProgram()
			}
		}
		else GlShaders.buildBlitProgram()

		// Uniforms that never change are set once; only the OES transform is per frame.
		GLES31.glUseProgram(oesCopyProgram)
		GLES31.glUniform1i(GLES31.glGetUniformLocation(oesCopyProgram, "uTex"), 0)
		oesTransformLoc = GLES31.glGetUniformLocation(oesCopyProgram, "uTransform")
		GLES31.glUseProgram(presentProgram)
		GLES31.glUniform1i(GLES31.glGetUniformLocation(presentProgram, "ps0"), 0)
		if(presentIsSgsr)
		{
			var loc = GLES31.glGetUniformLocation(presentProgram, "ViewportInfo")
			if(loc < 0) loc = GLES31.glGetUniformLocation(presentProgram, "ViewportInfo[0]")
			GLES31.glUniform4f(loc, 1f / streamWidth, 1f / streamHeight, streamWidth.toFloat(), streamHeight.toFloat())
		}
		GLES31.glUseProgram(0)

		// Textures: external input, 2-slot ring, generated output (stream size, identical format: RGBA8,
		// or RGBA16F for PQ-encoded HDR; both are accepted by glExtrapolateTex2DQCOM).
		val ringFormat = if(hdr) GLES31.GL_RGBA16F else GLES31.GL_RGBA8
		val ids = IntArray(4)
		GLES31.glGenTextures(4, ids, 0)
		oesTexture = ids[0]
		ring[0] = ids[1]
		ring[1] = ids[2]
		generatedTexture = ids[3]
		GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
		setSamplerParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
		for(tex in intArrayOf(ring[0], ring[1], generatedTexture))
		{
			GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, tex)
			GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, ringFormat, streamWidth, streamHeight)
			setSamplerParams(GLES31.GL_TEXTURE_2D)
		}
		GLES31.glGenFramebuffers(2, ringFbo, 0)
		for(i in 0..1)
		{
			GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, ringFbo[i])
			GLES31.glFramebufferTexture2D(GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0, GLES31.GL_TEXTURE_2D, ring[i], 0)
			val status = GLES31.glCheckFramebufferStatus(GLES31.GL_FRAMEBUFFER)
			check(status == GLES31.GL_FRAMEBUFFER_COMPLETE) { "ring FBO $i incomplete: 0x${Integer.toHexString(status)}" }
		}
		GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
		checkGlError("init")

		frameGenerationActive = frameGenerationRequested && AdrenoExt.nativeProbeFrameExtrapolation()
		pacer.frameGenerationEnabled = frameGenerationActive
		Log.i(TAG, "frame extrapolation supported=$frameGenerationActive requested=$frameGenerationRequested, tuning=$tuning")

		val st = SurfaceTexture(oesTexture)
		st.setDefaultBufferSize(streamWidth, streamHeight)
		st.setOnFrameAvailableListener({ frameAvailable.set(true) }, handler)
		surfaceTexture = st
		inputSurface = Surface(st)
	}

	/**
	 * Picks an ES3 window+pbuffer config with exactly [colorBits] per colour channel. eglChooseConfig
	 * sorts deeper colour buffers first, so an exact match is enforced here rather than taking its first result.
	 */
	private fun chooseConfig(colorBits: Int, alphaBits: Int): EGLConfig
	{
		val attribs = intArrayOf(
			EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
			EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
			EGL14.EGL_RED_SIZE, colorBits, EGL14.EGL_GREEN_SIZE, colorBits, EGL14.EGL_BLUE_SIZE, colorBits, EGL14.EGL_ALPHA_SIZE, alphaBits,
			EGL14.EGL_NONE)
		val configs = arrayOfNulls<EGLConfig>(MAX_CONFIGS)
		val num = IntArray(1)
		check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, MAX_CONFIGS, num, 0) && num[0] > 0) { "no ES3 EGL config with $colorBits-bit colour" }
		val value = IntArray(1)
		for(i in 0 until num[0])
		{
			val config = configs[i] ?: continue
			EGL14.eglGetConfigAttrib(eglDisplay, config, EGL14.EGL_RED_SIZE, value, 0)
			if(value[0] != colorBits) continue
			EGL14.eglGetConfigAttrib(eglDisplay, config, EGL14.EGL_ALPHA_SIZE, value, 0)
			if(value[0] == alphaBits) return config
		}
		throw IllegalStateException("no EGL config with exactly R${colorBits}G${colorBits}B${colorBits}A$alphaBits")
	}

	private fun setSamplerParams(target: Int)
	{
		GLES31.glTexParameteri(target, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR)
		GLES31.glTexParameteri(target, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR)
		GLES31.glTexParameteri(target, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
		GLES31.glTexParameteri(target, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
	}

	private fun onVsync(frameTimeNanos: Long)
	{
		if(stopped) return
		val st = surfaceTexture ?: return
		// Peek only: the pacer may hold an early real frame for one vsync (PRESENT_GENERATED while a
		// frame is pending), so the flag is cleared only when the frame is actually consumed.
		val newFrame = frameAvailable.get()
		when(pacer.decide(frameTimeNanos, newFrame))
		{
			PaceDecision.PRESENT_REAL ->
			{
				frameAvailable.set(false)
				try
				{
					st.updateTexImage()
				}
				catch(e: RuntimeException)
				{
					// The decoder side is gone: treat it as the end of the session. The pacer has already
					// recorded this frame, so reset it rather than let a later vsync extrapolate from a stale pair.
					Log.w(TAG, "updateTexImage failed, stopping GPU path: ${e.message}")
					pacer.reset()
					presentedFps = 0.0
					generatedFps = 0.0
					stopped = true
					return
				}
				st.getTransformMatrix(transform)
				newestSlot = 1 - newestSlot
				copyOesToSlot(newestSlot)
				present(ring[newestSlot])
			}
			PaceDecision.PRESENT_GENERATED ->
			{
				// Without a window surface the extrapolation would be wasted GPU work; the pacer's
				// bookkeeping is the same either way.
				if(windowSurface != EGL14.EGL_NO_SURFACE)
				{
					AdrenoExt.nativeExtrapolateTex2D(ring[1 - newestSlot], ring[newestSlot], generatedTexture, tuning.extrapolationScale)
					checkGlError("extrapolate")
					if(present(generatedTexture))
						generatedInWindow++
				}
			}
			PaceDecision.SKIP -> {}
		}
		updateStats(frameTimeNanos)
	}

	private fun copyOesToSlot(slot: Int)
	{
		GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, ringFbo[slot])
		GLES31.glViewport(0, 0, streamWidth, streamHeight)
		GLES31.glUseProgram(oesCopyProgram)
		GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
		GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
		GLES31.glUniformMatrix4fv(oesTransformLoc, 1, false, transform, 0)
		GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
		GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
		checkGlError("copy")
	}

	/**
	 * Draws [sourceTexture] to the window surface and swaps. Returns false when there is no window
	 * surface or the swap failed. A failed swap means the surface is gone (or about to be); it is
	 * destroyed so this logs once instead of at vsync rate, and the next [setOutputSurface] recreates it.
	 */
	private fun present(sourceTexture: Int): Boolean
	{
		if(windowSurface == EGL14.EGL_NO_SURFACE) return false
		GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
		GLES31.glViewport(0, 0, windowWidth, windowHeight)
		GLES31.glUseProgram(presentProgram)
		GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
		GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, sourceTexture)
		GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
		checkGlError("present")
		if(!EGL14.eglSwapBuffers(eglDisplay, windowSurface))
		{
			Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}, dropping window surface")
			destroyWindowSurface()
			return false
		}
		presentedInWindow++
		return true
	}

	private fun updateStats(nowNs: Long)
	{
		if(windowStartNs == Long.MIN_VALUE)
		{
			windowStartNs = nowNs
			return
		}
		val elapsed = nowNs - windowStartNs
		if(elapsed >= STATS_WINDOW_NS)
		{
			presentedFps = presentedInWindow * 1e9 / elapsed
			generatedFps = generatedInWindow * 1e9 / elapsed
			presentedInWindow = 0
			generatedInWindow = 0
			windowStartNs = nowNs
		}
	}

	private fun checkGlError(where: String)
	{
		val err = GLES31.glGetError()
		if(err != GLES31.GL_NO_ERROR && loggedGlErrors.add("$where:$err"))
			Log.w(TAG, "GL error 0x${Integer.toHexString(err)} in $where (logged once per site)")
	}

	private fun destroyWindowSurface()
	{
		if(windowSurface != EGL14.EGL_NO_SURFACE)
		{
			EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
			EGL14.eglDestroySurface(eglDisplay, windowSurface)
			windowSurface = EGL14.EGL_NO_SURFACE
		}
	}

	/** Tolerates partially initialised state so that a failed [initGl] still cleans up whatever it created. */
	private fun releaseGl()
	{
		surfaceTexture?.release()
		surfaceTexture = null
		if(this::inputSurface.isInitialized) inputSurface.release()
		if(eglDisplay == EGL14.EGL_NO_DISPLAY) return
		destroyWindowSurface()
		if(eglContext != EGL14.EGL_NO_CONTEXT && pbufferSurface != EGL14.EGL_NO_SURFACE
			&& EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext))
		{
			// Names of 0 are silently ignored by glDelete*, so unset objects need no guard.
			GLES31.glDeleteProgram(oesCopyProgram)
			GLES31.glDeleteProgram(presentProgram)
			GLES31.glDeleteFramebuffers(2, ringFbo, 0)
			GLES31.glDeleteTextures(4, intArrayOf(oesTexture, ring[0], ring[1], generatedTexture), 0)
		}
		EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
		if(pbufferSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
		if(eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
		EGL14.eglTerminate(eglDisplay)
		pbufferSurface = EGL14.EGL_NO_SURFACE
		eglContext = EGL14.EGL_NO_CONTEXT
		eglConfig = null
		eglDisplay = EGL14.EGL_NO_DISPLAY
		Log.i(TAG, "released")
	}
}
