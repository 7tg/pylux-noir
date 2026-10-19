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
	 * 1. `#version 300 es` becomes `310 es`, since textureGather's non-constant-offset forms and our vertex shader need 3.10.
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

	fun buildOesCopyProgram(): Int = link(VERTEX_SRC, OES_COPY_FRAG_SRC, "oes-copy")
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
