// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_HAPTICSRUMBLE_H
#define CHIAKI_HAPTICSRUMBLE_H

#include "common.h"
#include "streamconnection.h"

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Converts PS5 DualSense haptic audio (interleaved stereo int16 PCM, ~100 frames/s)
 * into single-amplitude rumble for clients without a DualSense voice-coil path
 * (Android, iOS, non-DualSense pads). Feed every haptics frame; when
 * chiaki_haptics_rumble_feed returns true, emit a rumble event with *amp.
 *
 * raw mode: per-frame mean amplitude, emitted on every change and re-armed every
 *   CHIAKI_HAPTICS_RUMBLE_RAW_REARM_FRAMES while unchanged (consumer plays ~1s effects).
 * smooth mode: averages CHIAKI_HAPTICS_RUMBLE_WINDOW_FRAMES frames, drops the low-level
 *   haptic "texture" below CHIAKI_HAPTICS_RUMBLE_NOISE_FLOOR, applies the console's
 *   vibration intensity, ignores tiny changes and re-arms every
 *   CHIAKI_HAPTICS_RUMBLE_SMOOTH_REARM_WINDOWS windows (consumer plays short effects,
 *   CHIAKI_HAPTICS_RUMBLE_SMOOTH_EFFECT_MS, so rumble stops soon after the audio does).
 */
#define CHIAKI_HAPTICS_RUMBLE_RAW_REARM_FRAMES 50
#define CHIAKI_HAPTICS_RUMBLE_WINDOW_FRAMES 3
#define CHIAKI_HAPTICS_RUMBLE_NOISE_FLOOR 1024 // mean |sample| of int16, ~3% of full scale
#define CHIAKI_HAPTICS_RUMBLE_MIN_DELTA 12
#define CHIAKI_HAPTICS_RUMBLE_SMOOTH_REARM_WINDOWS 3
#define CHIAKI_HAPTICS_RUMBLE_SMOOTH_EFFECT_MS 150

typedef struct chiaki_haptics_rumble_t
{
	bool smooth;
	float console_scale; // from the console's vibration intensity setting (smooth mode only)
	uint64_t window_sum;
	size_t window_samples;
	unsigned int window_frames;
	uint8_t last_amp;
	unsigned int same_amp_count; // frames (raw) or windows (smooth) since the last emit
} ChiakiHapticsRumble;

CHIAKI_EXPORT void chiaki_haptics_rumble_init(ChiakiHapticsRumble *hr, bool smooth);
CHIAKI_EXPORT void chiaki_haptics_rumble_set_intensity(ChiakiHapticsRumble *hr, ChiakiDualSenseEffectIntensity intensity);
CHIAKI_EXPORT bool chiaki_haptics_rumble_feed(ChiakiHapticsRumble *hr, const uint8_t *buf, size_t buf_size, uint8_t *amp);

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_HAPTICSRUMBLE_H
