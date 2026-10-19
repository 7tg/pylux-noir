// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream.gpu

import android.content.res.AssetManager
import android.opengl.GLES31

/** Thrown for shader compile failures, program link failures and an unexpected SGSR1 asset. */
class ShaderCompileException(message: String): RuntimeException(message)

/** Compiles the programs used by [GpuVideoRenderer]. Call on the render thread with a GLES 3.1 context current. */
object GlShaders
{
	/** Full-screen triangle from gl_VertexID; no vertex buffers needed. Output name matches SGSR1's input. */
	internal const val VERTEX_SRC = """#version 310 es
out highp vec4 in_TEXCOORD0;
void main()
{
	vec2 pos = vec2(float((gl_VertexID & 1) << 2) - 1.0, float((gl_VertexID & 2) << 1) - 1.0);
	in_TEXCOORD0 = vec4(pos * 0.5 + 0.5, 0.0, 0.0);
	gl_Position = vec4(pos, 0.0, 1.0);
}
"""

	/** Copies the decoder's external texture into a plain RGBA8 texture, honouring the SurfaceTexture transform. */
	internal const val OES_COPY_FRAG_SRC = """#version 310 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
uniform highp mat4 uTransform;
in highp vec4 in_TEXCOORD0;
out vec4 fragColor;
void main()
{
	highp vec2 tc = (uTransform * vec4(in_TEXCOORD0.xy, 0.0, 1.0)).xy;
	fragColor = vec4(texture(uTex, tc).rgb, 1.0);
}
"""

	/**
	 * HDR variant of the copy: HEVC Main10 frames arrive as 10-bit BT.2020 Y'CbCr. The implicit
	 * samplerExternalOES conversion is driver-defined for that case, so the raw Y'CbCr is read through
	 * GL_EXT_YUV_target and converted explicitly (BT.2020 non-constant luminance, limited range). The
	 * result stays PQ-encoded; it is written to an RGBA16F ring slot and later to a BT.2020 PQ window.
	 */
	internal const val OES_COPY_HDR_FRAG_SRC = """#version 310 es
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT uTex;
uniform highp mat4 uTransform;
in highp vec4 in_TEXCOORD0;
out vec4 fragColor;
void main()
{
	highp vec2 tc = (uTransform * vec4(in_TEXCOORD0.xy, 0.0, 1.0)).xy;
	vec3 yuv = texture(uTex, tc).xyz;
	// 10-bit limited range: Y' 64..940, Cb/Cr 64..960 (of 1023), centred on 512.
	float y = (yuv.x - 64.0 / 1023.0) * (1023.0 / 876.0);
	float u = (yuv.y - 512.0 / 1023.0) * (1023.0 / 896.0);
	float v = (yuv.z - 512.0 / 1023.0) * (1023.0 / 896.0);
	vec3 rgb = vec3(y + 1.4746 * v, y - 0.164553 * u - 0.571353 * v, y + 1.8814 * u);
	fragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""

	/** Bilinear blit used when SGSR upscaling is off. Uniform name matches SGSR1 so the renderer treats both alike. */
	internal const val BLIT_FRAG_SRC = """#version 310 es
precision mediump float;
uniform mediump sampler2D ps0;
in highp vec4 in_TEXCOORD0;
out vec4 fragColor;
void main()
{
	fragColor = vec4(texture(ps0, in_TEXCOORD0.xy).rgb, 1.0);
}
"""

	private val SGSR1_PATCHES = listOf(
		"#version 300 es" to "#version 310 es",
		"layout(location=0) in highp vec4 in_TEXCOORD0;" to "in highp vec4 in_TEXCOORD0;",
		"int mode = OperationMode;" to "const int mode = OperationMode;",
		"highp vec2(" to "vec2(")

	/**
	 * Adapts the verbatim SGSR1 asset to GLSL ES 3.10 in memory:
	 * 1. `#version 300 es` becomes `310 es`, since textureGather only exists from GLSL ES 3.10 and our vertex shader is 3.10.
	 * 2. The `layout(location=0)` qualifier on the input is dropped, as the vertex shader output has no location.
	 * 3. `int mode` becomes `const int mode`, because textureGather's component argument must be a compile-time constant.
	 * 4. `highp vec2(` becomes `vec2(` (all occurrences), since a precision qualifier is not allowed on a constructor call.
	 * Throws [ShaderCompileException] if the asset no longer contains one of the expected patterns.
	 */
	fun patchSgsr1Source(raw: String): String
	{
		var result = raw
		for((pattern, replacement) in SGSR1_PATCHES)
		{
			if(!result.contains(pattern))
				throw ShaderCompileException("SGSR1 asset missing expected pattern: $pattern")
			result = result.replace(pattern, replacement)
		}
		return result
	}

	fun loadSgsr1Source(assets: AssetManager): String
	{
		val raw = assets.open("shaders/sgsr1_shader_mobile.frag").bufferedReader().use { it.readText() }
		return patchSgsr1Source(raw)
	}

	/** [hdr] selects the explicit BT.2020 conversion; the caller must have checked GL_EXT_YUV_target. */
	fun buildOesCopyProgram(hdr: Boolean = false): Int =
		if(hdr) link(VERTEX_SRC, OES_COPY_HDR_FRAG_SRC, "oes-copy-hdr") else link(VERTEX_SRC, OES_COPY_FRAG_SRC, "oes-copy")
	fun buildBlitProgram(): Int = link(VERTEX_SRC, BLIT_FRAG_SRC, "blit")
	fun buildSgsr1Program(assets: AssetManager): Int = link(VERTEX_SRC, loadSgsr1Source(assets), "sgsr1")

	private fun compile(type: Int, src: String, name: String): Int
	{
		val shader = GLES31.glCreateShader(type)
		GLES31.glShaderSource(shader, src)
		GLES31.glCompileShader(shader)
		val status = IntArray(1)
		GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, status, 0)
		if(status[0] == 0)
		{
			val log = GLES31.glGetShaderInfoLog(shader)
			GLES31.glDeleteShader(shader)
			throw ShaderCompileException("$name shader compile failed: $log")
		}
		return shader
	}

	private fun link(vertexSrc: String, fragmentSrc: String, name: String): Int
	{
		val vs = compile(GLES31.GL_VERTEX_SHADER, vertexSrc, "$name vertex")
		val fs = try { compile(GLES31.GL_FRAGMENT_SHADER, fragmentSrc, "$name fragment") }
			catch(e: ShaderCompileException) { GLES31.glDeleteShader(vs); throw e }
		val program = GLES31.glCreateProgram()
		GLES31.glAttachShader(program, vs)
		GLES31.glAttachShader(program, fs)
		GLES31.glLinkProgram(program)
		GLES31.glDeleteShader(vs)
		GLES31.glDeleteShader(fs)
		val status = IntArray(1)
		GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, status, 0)
		if(status[0] == 0)
		{
			val log = GLES31.glGetProgramInfoLog(program)
			GLES31.glDeleteProgram(program)
			throw ShaderCompileException("$name program link failed: $log")
		}
		return program
	}
}
