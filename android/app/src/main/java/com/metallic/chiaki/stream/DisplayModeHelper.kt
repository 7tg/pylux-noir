// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Surface

data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * Switches the stream window to the display mode with the highest refresh rate at the
 * current resolution (120 Hz on the Tab S9). The window attribute is per-window, so the
 * system default returns when the activity goes away.
 */
object DisplayModeHelper
{
	private const val TAG = "DisplayMode"

	fun pickHighestRefresh(current: DisplayModeInfo, all: List<DisplayModeInfo>): DisplayModeInfo?
	{
		val best = all
			.filter { it.width == current.width && it.height == current.height }
			.maxByOrNull { it.refreshRate } ?: return null
		return if(best.refreshRate > current.refreshRate + 0.5f) best else null
	}

	/** Applies the preferred mode to [activity]'s window. Returns the refresh rate now in use. */
	@Suppress("DEPRECATION")
	fun applyHighestRefresh(activity: Activity): Float
	{
		val display = activity.windowManager.defaultDisplay
		val current = display.mode.let { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
		val all = display.supportedModes.map { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
		val best = pickHighestRefresh(current, all)
		if(best == null)
		{
			Log.i(TAG, "Keeping ${current.width}x${current.height} @ ${current.refreshRate} Hz (no higher mode)")
			return current.refreshRate
		}
		val attrs = activity.window.attributes
		attrs.preferredDisplayModeId = best.id
		activity.window.attributes = attrs
		Log.i(TAG, "Requested ${best.width}x${best.height} @ ${best.refreshRate} Hz (mode ${best.id})")
		return best.refreshRate
	}

	/** Tells SurfaceFlinger the surface wants [fps]; no-op before Android 11. */
	fun hintSurfaceFrameRate(surface: Surface, fps: Float)
	{
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
		{
			try
			{
				surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
			}
			catch(e: IllegalArgumentException)
			{
				Log.w(TAG, "setFrameRate($fps) rejected: ${e.message}")
			}
		}
	}
}
