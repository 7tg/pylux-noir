// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream.gpu

enum class PaceDecision { PRESENT_REAL, PRESENT_GENERATED, SKIP }

/**
 * Decides, once per display vsync, whether to present a freshly decoded frame, a
 * hardware-extrapolated frame, or nothing. Pure logic, no GL, so it is unit-testable.
 *
 * Rules (priority order):
 * 1. A new decoded frame that arrives "early", less than [HOLD_THRESHOLD_INTERVALS] stream
 *    intervals after the previous real frame was presented (one 120 Hz vsync after a 60 fps
 *    frame), is held for one vsync and a generated frame is presented in its place, provided
 *    generation is possible (rule 3's conditions). Returning [PaceDecision.PRESENT_GENERATED]
 *    while `newFrameAvailable` was true therefore means "keep the real frame pending and present
 *    it at the next vsync"; the caller must not consume it. This keeps the real/generated
 *    alternation when the console's delivery drifts against the display vsync, instead of
 *    presenting two real frames on consecutive vsyncs and then holding for a vsync with nothing
 *    to generate. The added latency is bounded to one vsync.
 * 2. Otherwise a new decoded frame is presented.
 * 3. Otherwise generate one frame if generation is enabled, at least two real frames
 *    have been seen, the previously presented frame was real, and the last real frame
 *    is younger than [MAX_REAL_AGE_INTERVALS] stream intervals.
 * 4. Otherwise skip (leave the last frame on screen).
 *
 * [decide] and [reset] must be called from the render thread only (their state is not
 * volatile); only [frameGenerationEnabled] may be set from another thread. [reset] leaves
 * [frameGenerationEnabled] untouched. The recorded real-frame time is the vsync at which the
 * frame was consumed (up to one vsync later than its arrival), which the 1.5-interval margin absorbs.
 */
class FramePacer(private val streamIntervalNs: Long, @Volatile var frameGenerationEnabled: Boolean)
{
	companion object
	{
		const val MAX_REAL_AGE_INTERVALS = 1.5
		/** A real frame younger than this (in stream intervals) since the last presented real frame is held one vsync. */
		const val HOLD_THRESHOLD_INTERVALS = 0.75
	}

	/** Consecutive real frames since the last reset or stall; exposed for stats/debugging. */
	var realFramesSeen = 0L
		private set
	private val maxRealAgeNs = (streamIntervalNs * MAX_REAL_AGE_INTERVALS).toLong()
	private val holdThresholdNs = (streamIntervalNs * HOLD_THRESHOLD_INTERVALS).toLong()
	/** Vsync time at which the last PRESENT_REAL was returned. */
	private var lastRealNs = Long.MIN_VALUE
	private var lastPresentedWasGenerated = false

	fun decide(nowNs: Long, newFrameAvailable: Boolean): PaceDecision
	{
		val canGenerate = frameGenerationEnabled && realFramesSeen >= 2 && !lastPresentedWasGenerated
		if(newFrameAvailable && canGenerate && (nowNs - lastRealNs) < holdThresholdNs)
		{
			// Early real frame: hold it one vsync and fill this one with a generated frame so the
			// real/generated alternation survives phase drift. The caller keeps the frame pending.
			lastPresentedWasGenerated = true
			return PaceDecision.PRESENT_GENERATED
		}
		if(newFrameAvailable)
		{
			if(lastRealNs != Long.MIN_VALUE && nowNs - lastRealNs >= maxRealAgeNs)
				realFramesSeen = 1 // stall: the previous pair is too old to extrapolate from
			else
				realFramesSeen++
			lastRealNs = nowNs
			lastPresentedWasGenerated = false
			return PaceDecision.PRESENT_REAL
		}
		val fresh = lastRealNs != Long.MIN_VALUE
			&& (nowNs - lastRealNs) < maxRealAgeNs
		if(canGenerate && fresh)
		{
			lastPresentedWasGenerated = true
			return PaceDecision.PRESENT_GENERATED
		}
		return PaceDecision.SKIP
	}

	fun reset()
	{
		realFramesSeen = 0L
		lastRealNs = Long.MIN_VALUE
		lastPresentedWasGenerated = false
	}
}
