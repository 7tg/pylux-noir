// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream.gpu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GlShadersTest
{
	private val raw: String = File("src/main/assets/shaders/sgsr1_shader_mobile.frag").readText()

	@Test
	fun patchedSourceTargetsEs310()
	{
		val patched = GlShaders.patchSgsr1Source(raw)
		assertTrue(patched.startsWith("#version 310 es"))
		assertFalse(patched.contains("#version 300 es"))
	}

	@Test
	fun patchedSourceDropsLocationQualifier()
	{
		val patched = GlShaders.patchSgsr1Source(raw)
		assertTrue(patched.contains("in highp vec4 in_TEXCOORD0;"))
		assertFalse(patched.contains("layout(location=0) in "))
		// the fragment output keeps its location
		assertTrue(patched.contains("layout(location=0) out vec4 out_Target0;"))
	}

	@Test
	fun assetKeepsQualcommLicenseHeader()
	{
		assertTrue(raw.contains("SPDX-License-Identifier: BSD-3-Clause"))
		assertTrue(raw.contains("Qualcomm"))
	}

	@Test
	fun assetDeclaresExpectedUniforms()
	{
		assertTrue(raw.contains("uniform highp vec4 ViewportInfo[1];"))
		assertTrue(raw.contains("uniform mediump sampler2D ps0;"))
	}
}
