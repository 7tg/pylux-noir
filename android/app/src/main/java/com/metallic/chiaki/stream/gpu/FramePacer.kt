// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream.gpu

enum class PaceDecision { PRESENT_REAL, PRESENT_GENERATED, SKIP }

/**
 * Decides, once per display vsync, whether to present a freshly decoded frame, a
 * hardware-extrapolated frame, or nothing. Pure logic, no GL, so it is unit-testable.
 *
 * Rules (priority order):
 * 1. A new decoded frame is always presented.
 * 2. Otherwise generate one frame if generation is enabled, at least two real frames
 *    have been seen, the previously presented frame was real, and the last real frame
 *    is younger than [MAX_REAL_AGE_INTERVALS] stream intervals.
 * 3. Otherwise skip (leave the last frame on screen).
 */
class FramePacer(private val streamIntervalNs: Long, @Volatile var frameGenerationEnabled: Boolean)
{
	companion object
	{
		const val MAX_REAL_AGE_INTERVALS = 1.5
	}

	var realFramesSeen = 0L
		private set
	private var lastRealNs = Long.MIN_VALUE
	private var lastPresentedWasGenerated = false

	fun decide(nowNs: Long, newFrameAvailable: Boolean): PaceDecision
	{
		if(newFrameAvailable)
		{
			realFramesSeen++
			lastRealNs = nowNs
			lastPresentedWasGenerated = false
			return PaceDecision.PRESENT_REAL
		}
		val fresh = lastRealNs != Long.MIN_VALUE
			&& (nowNs - lastRealNs) < (streamIntervalNs * MAX_REAL_AGE_INTERVALS).toLong()
		if(frameGenerationEnabled && realFramesSeen >= 2 && !lastPresentedWasGenerated && fresh)
		{
			lastPresentedWasGenerated = true
			return PaceDecision.PRESENT_GENERATED
		}
		return PaceDecision.SKIP
	}

	fun reset()
	{
		realFramesSeen = 0
		lastRealNs = Long.MIN_VALUE
		lastPresentedWasGenerated = false
	}
}
