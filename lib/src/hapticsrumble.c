// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <chiaki/hapticsrumble.h>

#include <string.h>

CHIAKI_EXPORT void chiaki_haptics_rumble_init(ChiakiHapticsRumble *hr, bool smooth)
{
	memset(hr, 0, sizeof(*hr));
	hr->smooth = smooth;
	hr->console_scale = 1.0f;
}

CHIAKI_EXPORT void chiaki_haptics_rumble_set_intensity(ChiakiHapticsRumble *hr, ChiakiDualSenseEffectIntensity intensity)
{
	// Same mapping as the Qt client's rumble_multiplier
	switch(intensity)
	{
		case Off: hr->console_scale = 0.0f; break;
		case Weak: hr->console_scale = 0.33f; break;
		case Medium: hr->console_scale = 0.5f; break;
		case Strong:
		default: hr->console_scale = 1.0f; break;
	}
}

// Sum of max(|l|, |r|) over the interleaved stereo int16 frame; returns the sample count.
static size_t frame_abs_sum(const uint8_t *buf, size_t buf_size, uint64_t *sum)
{
	const size_t sample_size = 2 * sizeof(int16_t);
	size_t n = buf_size / sample_size;
	for(size_t i = 0; i < n; i++)
	{
		int16_t l = 0, r = 0;
		memcpy(&l, buf + i * sample_size, sizeof(int16_t));
		memcpy(&r, buf + i * sample_size + sizeof(int16_t), sizeof(int16_t));
		int al = l < 0 ? -(int)l : l, ar = r < 0 ? -(int)r : r;
		*sum += (uint64_t)(al > ar ? al : ar);
	}
	return n;
}

static bool feed_raw(ChiakiHapticsRumble *hr, const uint8_t *buf, size_t buf_size, uint8_t *amp)
{
	uint64_t sum = 0;
	size_t n = frame_abs_sum(buf, buf_size, &sum);
	if(n == 0)
		return false;
	uint32_t avg = (uint32_t)(sum / n);
	uint8_t a = avg >= 32767 ? 255 : (uint8_t)((avg * 255) / 32767);
	if(a == hr->last_amp)
	{
		if(a == 0 || ++hr->same_amp_count < CHIAKI_HAPTICS_RUMBLE_RAW_REARM_FRAMES)
			return false;
	}
	hr->same_amp_count = 0;
	hr->last_amp = a;
	*amp = a;
	return true;
}

static bool feed_smooth(ChiakiHapticsRumble *hr, const uint8_t *buf, size_t buf_size, uint8_t *amp)
{
	size_t n = frame_abs_sum(buf, buf_size, &hr->window_sum);
	if(n == 0)
		return false;
	hr->window_samples += n;
	if(++hr->window_frames < CHIAKI_HAPTICS_RUMBLE_WINDOW_FRAMES)
		return false;

	uint32_t avg = (uint32_t)(hr->window_sum / hr->window_samples);
	hr->window_sum = 0;
	hr->window_samples = 0;
	hr->window_frames = 0;

	uint8_t a = 0;
	if(avg > CHIAKI_HAPTICS_RUMBLE_NOISE_FLOOR)
	{
		// Rescale above the floor so the amplitude ramps up from 0 instead of jumping
		float v = (float)(avg - CHIAKI_HAPTICS_RUMBLE_NOISE_FLOOR) * 255.0f
			/ (float)(32767 - CHIAKI_HAPTICS_RUMBLE_NOISE_FLOOR) * hr->console_scale;
		a = v >= 255.0f ? 255 : (uint8_t)v;
	}

	bool emit;
	if(a == 0)
		emit = hr->last_amp != 0; // stop once
	else if(hr->last_amp == 0)
		emit = true; // start
	else
	{
		int delta = (int)a - (int)hr->last_amp;
		if(delta < 0)
			delta = -delta;
		// Small changes ride along on the next re-arm instead of a new command
		emit = delta >= CHIAKI_HAPTICS_RUMBLE_MIN_DELTA
			|| ++hr->same_amp_count >= CHIAKI_HAPTICS_RUMBLE_SMOOTH_REARM_WINDOWS;
	}
	if(!emit)
		return false;
	hr->same_amp_count = 0;
	hr->last_amp = a;
	*amp = a;
	return true;
}

CHIAKI_EXPORT bool chiaki_haptics_rumble_feed(ChiakiHapticsRumble *hr, const uint8_t *buf, size_t buf_size, uint8_t *amp)
{
	if(!buf)
		return false;
	return hr->smooth ? feed_smooth(hr, buf, buf_size, amp) : feed_raw(hr, buf, buf_size, amp);
}
