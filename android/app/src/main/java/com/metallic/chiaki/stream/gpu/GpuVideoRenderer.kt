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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GPU video path: MediaCodec decodes into [inputSurface]; every display vsync this thread
 * presents either the newest decoded frame or a hardware-extrapolated one, upscaled with
 * SGSR1 (or a plain blit) onto the SurfaceView's surface.
 *
 * Threading: all GL work happens on the private render thread. Public methods are safe to
 * call from any thread other than the render thread; [start] and [release] block until the
 * render thread is done, so they must never be called from the render thread itself.
 */
class GpuVideoRenderer(
	private val assets: AssetManager,
	private val streamWidth: Int,
	private val streamHeight: Int,
	streamFps: Int,
	private val upscalingRequested: Boolean,
	private val frameGenerationRequested: Boolean
)
{
	companion object
	{
		private const val TAG = "GpuVideoRenderer"
		private const val EXTRAPOLATION_SCALE = 0.5f
		private const val STATS_WINDOW_NS = 1_000_000_000L
	}

	data class Stats(val presentedFps: Double, val generatedFps: Double, val frameGenerationActive: Boolean, val upscalingActive: Boolean)

	private val thread = HandlerThread("GpuVideoRenderer").also { it.start() }
	private val handler = Handler(thread.looper)
	private val pacer = FramePacer(1_000_000_000L / streamFps.coerceAtLeast(1), frameGenerationRequested)
	private val frameAvailable = AtomicBoolean(false)
	@Volatile private var released = false

	// EGL
	private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
	private var eglConfig: EGLConfig? = null
	private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
	private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
	private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
	private var windowWidth = 0
	private var windowHeight = 0

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
	private val loggedGlErrors = HashSet<Int>()

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
		var ok = false
		val latch = CountDownLatch(1)
		handler.post {
			ok = try { initGl(); true } catch(e: Exception) { Log.e(TAG, "GPU path init failed: ${e.message}", e); false }
			if(ok) Choreographer.getInstance().postFrameCallback(vsyncCallback)
			latch.countDown()
		}
		latch.await()
		if(!ok) release()
		return ok
	}

	/** New SurfaceView surface (or null when it is gone). Only the EGL window surface changes; decoding continues. */
	fun setOutputSurface(surface: Surface?, width: Int, height: Int)
	{
		handler.post {
			if(released) return@post
			destroyWindowSurface()
			if(surface != null && surface.isValid)
			{
				windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
				if(windowSurface == EGL14.EGL_NO_SURFACE)
				{
					Log.e(TAG, "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
					return@post
				}
				if(!EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext))
				{
					Log.e(TAG, "eglMakeCurrent(window) failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
					destroyWindowSurface()
					return@post
				}
				EGL14.eglSwapInterval(eglDisplay, 1)
				val w = IntArray(1)
				val h = IntArray(1)
				EGL14.eglQuerySurface(eglDisplay, windowSurface, EGL14.EGL_WIDTH, w, 0)
				EGL14.eglQuerySurface(eglDisplay, windowSurface, EGL14.EGL_HEIGHT, h, 0)
				windowWidth = if(w[0] > 0) w[0] else width
				windowHeight = if(h[0] > 0) h[0] else height
				Log.i(TAG, "output surface ${windowWidth}x${windowHeight} (stream ${streamWidth}x${streamHeight}, upscale=$presentIsSgsr, framegen=$frameGenerationActive)")
			}
		}
	}

	fun stats(): Stats = Stats(presentedFps, generatedFps, frameGenerationActive, presentIsSgsr)

	/**
	 * Stops presenting and frees all EGL/GL resources, blocking until the render thread is done.
	 * Must be called from a thread other than the render thread (it would deadlock on itself).
	 */
	fun release()
	{
		check(Looper.myLooper() != thread.looper) { "release() must not be called from the render thread" }
		if(released) return
		released = true
		val latch = CountDownLatch(1)
		handler.post {
			Choreographer.getInstance().removeFrameCallback(vsyncCallback)
			releaseGl()
			latch.countDown()
		}
		latch.await()
		thread.quitSafely()
	}

	// ---- render thread below ----

	private fun initGl()
	{
		eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
		check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
		val version = IntArray(2)
		check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
		val configAttribs = intArrayOf(
			EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
			EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
			EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
			EGL14.EGL_NONE)
		val configs = arrayOfNulls<EGLConfig>(1)
		val num = IntArray(1)
		check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "no ES3 EGL config" }
		eglConfig = checkNotNull(configs[0]) { "eglChooseConfig returned no config" }
		eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
			intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
		check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
		pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig,
			intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
		check(pbufferSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
		check(EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)) { "eglMakeCurrent failed" }
		Log.i(TAG, "GL ${GLES31.glGetString(GLES31.GL_VERSION)} on ${GLES31.glGetString(GLES31.GL_RENDERER)}")

		// Programs: the copy shader is mandatory; SGSR failure only degrades to the blit.
		oesCopyProgram = GlShaders.buildOesCopyProgram()
		presentProgram = if(upscalingRequested)
		{
			try
			{
				val program = GlShaders.buildSgsr1Program(assets)
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

		// Textures: external input, 2-slot ring, generated output (RGBA8, stream size, identical).
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
			GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES31.GL_RGBA8, streamWidth, streamHeight)
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
		Log.i(TAG, "frame extrapolation supported=$frameGenerationActive requested=$frameGenerationRequested")

		val st = SurfaceTexture(oesTexture)
		st.setDefaultBufferSize(streamWidth, streamHeight)
		st.setOnFrameAvailableListener({ frameAvailable.set(true) }, handler)
		surfaceTexture = st
		inputSurface = Surface(st)
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
		val st = surfaceTexture ?: return
		val newFrame = frameAvailable.getAndSet(false)
		when(pacer.decide(frameTimeNanos, newFrame))
		{
			PaceDecision.PRESENT_REAL ->
			{
				try
				{
					st.updateTexImage()
				}
				catch(e: RuntimeException)
				{
					Log.w(TAG, "updateTexImage failed: ${e.message}")
					return
				}
				st.getTransformMatrix(transform)
				newestSlot = 1 - newestSlot
				copyOesToSlot(newestSlot)
				present(ring[newestSlot])
			}
			PaceDecision.PRESENT_GENERATED ->
			{
				AdrenoExt.nativeExtrapolateTex2D(ring[1 - newestSlot], ring[newestSlot], generatedTexture, EXTRAPOLATION_SCALE)
				checkGlError("extrapolate")
				if(present(generatedTexture))
					generatedInWindow++
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

	/** Draws [sourceTexture] to the window surface and swaps. Returns false when there is no window surface. */
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
			Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
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
		if(err != GLES31.GL_NO_ERROR && loggedGlErrors.add(err))
			Log.w(TAG, "GL error 0x${Integer.toHexString(err)} in $where (logged once)")
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
			if(oesCopyProgram != 0) GLES31.glDeleteProgram(oesCopyProgram)
			if(presentProgram != 0) GLES31.glDeleteProgram(presentProgram)
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
