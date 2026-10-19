// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

class FramePacerTest
{
	private val interval60 = 16_666_667L
	private val vsync120 = 8_333_333L

	private fun pacer(framegen: Boolean = true) = FramePacer(interval60, framegen)

	@Test
	fun realFrameAlwaysPresented()
	{
		val p = pacer()
		assertEquals(PaceDecision.PRESENT_REAL, p.decide(0L, newFrameAvailable = true))
	}

	@Test
	fun noGenerationBeforeTwoRealFrames()
	{
		val p = pacer()
		p.decide(0L, true)
		assertEquals(PaceDecision.SKIP, p.decide(vsync120, false))
	}

	@Test
	fun steadyStateAlternatesRealAndGenerated()
	{
		val p = pacer()
		p.decide(0L, true)
		p.decide(interval60, true)
		assertEquals(PaceDecision.PRESENT_GENERATED, p.decide(interval60 + vsync120, false))
		assertEquals(PaceDecision.PRESENT_REAL, p.decide(2 * interval60, true))
		assertEquals(PaceDecision.PRESENT_GENERATED, p.decide(2 * interval60 + vsync120, false))
	}

	@Test
	fun neverTwoGeneratedInARow()
	{
		val p = pacer()
		p.decide(0L, true)
		p.decide(interval60, true)
		assertEquals(PaceDecision.PRESENT_GENERATED, p.decide(interval60 + vsync120, false))
		assertEquals(PaceDecision.SKIP, p.decide(interval60 + 2 * vsync120, false))
	}

	@Test
	fun noGenerationWhenLastRealFrameIsStale()
	{
		val p = pacer()
		p.decide(0L, true)
		p.decide(interval60, true)
		// two intervals after the last real frame: too old to predict from
		assertEquals(PaceDecision.SKIP, p.decide(interval60 + 2 * interval60, false))
	}

	@Test
	fun generationDisabledMeansSkip()
	{
		val p = pacer(framegen = false)
		p.decide(0L, true)
		p.decide(interval60, true)
		assertEquals(PaceDecision.SKIP, p.decide(interval60 + vsync120, false))
	}

	@Test
	fun realFrameWinsOverGenerationWhenBothPossible()
	{
		val p = pacer()
		p.decide(0L, true)
		p.decide(interval60, true)
		assertEquals(PaceDecision.PRESENT_REAL, p.decide(interval60 + vsync120, true))
	}

	@Test
	fun resetForgetsHistory()
	{
		val p = pacer()
		p.decide(0L, true)
		p.decide(interval60, true)
		p.reset()
		assertEquals(PaceDecision.SKIP, p.decide(interval60 + vsync120, false))
	}

	@Test
	fun thirtyFpsIntervalAllowsLaterGeneration()
	{
		val interval30 = 33_333_333L
		val p = FramePacer(interval30, true)
		p.decide(0L, true)
		p.decide(interval30, true)
		// 25 ms after the last real frame is still < 1.5 * 33 ms
		assertEquals(PaceDecision.PRESENT_GENERATED, p.decide(interval30 + 25_000_000L, false))
	}
}
