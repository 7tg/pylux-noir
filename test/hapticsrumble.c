// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
//
// Tests for the haptic-audio -> rumble converter (chiaki_haptics_rumble_feed):
// raw mode keeps the original per-frame behavior, smooth mode windows, gates
// low-level haptic texture and honors the console's vibration intensity.

#include <munit.h>

#include <string.h>

#include <chiaki/hapticsrumble.h>

#define FRAME_SAMPLES 30 // 120 byte frames, interleaved stereo int16

// Constant-magnitude frame with alternating sign, like a haptic waveform.
static void make_frame(uint8_t *buf, int16_t level)
{
	for(size_t i = 0; i < FRAME_SAMPLES; i++)
	{
		int16_t v = (i & 1) ? (int16_t)-level : level;
		memcpy(buf + i * 4, &v, 2);
		memcpy(buf + i * 4 + 2, &v, 2);
	}
}

// Feeds frames until one emits (or max frames pass); returns frames fed, 0 if none emitted.
static int feed_until_emit(ChiakiHapticsRumble *hr, int16_t level, int max, uint8_t *amp)
{
	uint8_t buf[FRAME_SAMPLES * 4];
	make_frame(buf, level);
	for(int i = 1; i <= max; i++)
		if(chiaki_haptics_rumble_feed(hr, buf, sizeof(buf), amp))
			return i;
	return 0;
}

static MunitResult test_raw_matches_legacy(const MunitParameter params[], void *user)
{
	ChiakiHapticsRumble hr;
	chiaki_haptics_rumble_init(&hr, false);
	uint8_t amp = 0;

	// any non-zero level emits on the first frame
	munit_assert_int(feed_until_emit(&hr, 200, 1, &amp), ==, 1);
	munit_assert_uint8(amp, ==, (200 * 255) / 32767);

	// unchanged level re-arms after RAW_REARM_FRAMES
	munit_assert_int(feed_until_emit(&hr, 200, 100, &amp), ==, CHIAKI_HAPTICS_RUMBLE_RAW_REARM_FRAMES);

	// silence emits a single 0, then nothing
	munit_assert_int(feed_until_emit(&hr, 0, 1, &amp), ==, 1);
	munit_assert_uint8(amp, ==, 0);
	munit_assert_int(feed_until_emit(&hr, 0, 200, &amp), ==, 0);
	return MUNIT_OK;
}

static MunitResult test_smooth_gates_texture(const MunitParameter params[], void *user)
{
	ChiakiHapticsRumble hr;
	chiaki_haptics_rumble_init(&hr, true);
	uint8_t amp = 0;

	// below the noise floor never rumbles
	munit_assert_int(feed_until_emit(&hr, CHIAKI_HAPTICS_RUMBLE_NOISE_FLOOR, 300, &amp), ==, 0);

	// a strong effect starts after one window
	munit_assert_int(feed_until_emit(&hr, 16000, 10, &amp), ==, CHIAKI_HAPTICS_RUMBLE_WINDOW_FRAMES);
	munit_assert_uint8(amp, >, 100);

	// sustained: re-armed every SMOOTH_REARM_WINDOWS windows
	munit_assert_int(feed_until_emit(&hr, 16000, 100, &amp), ==,
		CHIAKI_HAPTICS_RUMBLE_WINDOW_FRAMES * CHIAKI_HAPTICS_RUMBLE_SMOOTH_REARM_WINDOWS);

	// falling back to texture level stops once
	munit_assert_int(feed_until_emit(&hr, 300, 10, &amp), ==, CHIAKI_HAPTICS_RUMBLE_WINDOW_FRAMES);
	munit_assert_uint8(amp, ==, 0);
	munit_assert_int(feed_until_emit(&hr, 300, 300, &amp), ==, 0);
	return MUNIT_OK;
}

static MunitResult test_smooth_console_intensity(const MunitParameter params[], void *user)
{
	ChiakiHapticsRumble strong, weak, off;
	chiaki_haptics_rumble_init(&strong, true);
	chiaki_haptics_rumble_init(&weak, true);
	chiaki_haptics_rumble_init(&off, true);
	chiaki_haptics_rumble_set_intensity(&weak, Weak);
	chiaki_haptics_rumble_set_intensity(&off, Off);
	uint8_t amp_strong = 0, amp_weak = 0, amp_off = 0;

	munit_assert_int(feed_until_emit(&strong, 20000, 10, &amp_strong), !=, 0);
	munit_assert_int(feed_until_emit(&weak, 20000, 10, &amp_weak), !=, 0);
	munit_assert_uint8(amp_weak, <, amp_strong / 2);
	munit_assert_int(feed_until_emit(&off, 20000, 300, &amp_off), ==, 0);
	return MUNIT_OK;
}

MunitTest tests_haptics_rumble[] = {
	{ "/raw_matches_legacy", test_raw_matches_legacy, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL },
	{ "/smooth_gates_texture", test_smooth_gates_texture, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL },
	{ "/smooth_console_intensity", test_smooth_console_intensity, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL },
	{ NULL, NULL, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL }
};
