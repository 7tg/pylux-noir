# Adreno Frame Generation + SGSR Upscaling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display PS5 Remote Play at 120 Hz on a Galaxy Tab S9 by inserting an OpenGL ES stage into Pylux Android that upscales each 1080p60 frame with SGSR1 and synthesizes every other frame with the Adreno Frame Motion Engine (`GL_QCOM_frame_extrapolation`).

**Architecture:** MediaCodec keeps decoding (native code untouched) but into a `SurfaceTexture` owned by a new Kotlin `GpuVideoRenderer` thread. A Choreographer callback at display rate presents either a fresh decoded frame or a hardware-extrapolated one, both through the SGSR1 shader onto the SurfaceView. A ~60-line C shim in the existing `chiaki-jni` library exposes the Qualcomm extension. The feature ships in a personal fork (`7tg/pylux-noir`) with its own applicationId and keystore.

**Tech Stack:** Kotlin, EGL14 / GLES31 (android.opengl), JNI + C (NDK r28.2, CMake 3.30.4), JUnit 4, Gradle (AGP), `gh` CLI.

**Spec:** `docs/superpowers/specs/2026-10-19-adreno-framegen-sgsr-design.md`

**User's stream settings to test against:** 1080p, 60 fps, 50 Mbps (set in Pylux Settings → Stream: Resolution 1080p, FPS 60, Bitrate 50000).

---

## Conventions for every task

- Repo root: `/home/neo/Projects/Personal/Pylux`. Branch: `noir`. All paths below are relative to the repo root.
- Kotlin style in this codebase: tabs, Allman braces (`{` on its own line), `// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL` as the first line of each source file.
- Commit messages: plain conventional style, **no Claude attribution or Co-Authored-By lines** (user rule).
- Build + install on the Tab S9 (USB debugging on, tablet plugged in):
  `./android/build-local.sh --quick --install` (debug APK, arm64 only). Logs: `./android/build-local.sh --logs-dump | grep -E 'GpuVideoRenderer|AdrenoExt|DisplayMode|StreamSession'`.
  The first run installs NDK 28.2.13676358 and CMake 3.30.4 through sdkmanager; JDK 21 is at `~/.jdks/jdk-21.0.12.1+1` (export `JAVA_HOME` to it if the script cannot find one).
- Unit tests: `cd android && JAVA_HOME=~/.jdks/jdk-21.0.12.1+1 ./gradlew :app:testDebugUnitTest --tests '<class>'` (the gradle wrapper lives in `android/`).

## File structure

| File | Status | Responsibility |
|------|--------|----------------|
| `android/app/build.gradle` | modify | fork applicationId, `INTEGRITY_CHECK` flag, arm64-only ABI, junit dependency |
| `android/app/src/main/AndroidManifest.xml` | modify | file provider authority from applicationId |
| `android/app/src/main/res/values/strings.xml` | modify | app name, new preference keys/titles/summaries |
| `android/app/src/main/java/com/metallic/chiaki/common/LogManager.kt` | modify | authority from `BuildConfig.APPLICATION_ID` |
| `android/app/src/main/java/com/metallic/chiaki/common/AppIntegrityManager.kt` | modify | honour `INTEGRITY_CHECK` |
| `android/app/src/main/java/com/metallic/chiaki/stream/gpu/FramePacer.kt` | create | pure pacing decision (real / generated / skip) |
| `android/app/src/test/java/com/metallic/chiaki/stream/gpu/FramePacerTest.kt` | create | JUnit tests for pacing |
| `android/app/src/main/java/com/metallic/chiaki/stream/DisplayModeHelper.kt` | create | choose + apply highest-refresh display mode |
| `android/app/src/test/java/com/metallic/chiaki/stream/DisplayModeHelperTest.kt` | create | JUnit tests for mode selection |
| `android/app/src/main/java/com/metallic/chiaki/common/Preferences.kt` | modify | three new boolean prefs |
| `android/app/src/main/res/xml/preferences.xml` | modify | three new switches |
| `android/app/src/main/java/com/metallic/chiaki/settings/SettingsFragment.kt` | modify | data-store mapping + disable framegen switch when unsupported |
| `android/app/src/main/cpp/adreno-ext.c` | create | JNI shim for `GL_QCOM_frame_extrapolation` |
| `android/app/CMakeLists.txt` | modify | compile shim, link GLESv3 + EGL |
| `android/app/src/main/java/com/metallic/chiaki/stream/gpu/AdrenoExt.kt` | create | JNI bindings + cached pbuffer probe |
| `android/app/src/main/assets/shaders/sgsr1_shader_mobile.frag` | create | verbatim Qualcomm SGSR1 shader (BSD-3) |
| `android/app/src/main/java/com/metallic/chiaki/stream/gpu/GlShaders.kt` | create | compile/link vertex, OES copy, SGSR1, blit programs |
| `android/app/src/main/java/com/metallic/chiaki/stream/gpu/GpuVideoRenderer.kt` | create | render thread, EGL, textures, vsync loop, stats |
| `android/app/src/main/java/com/metallic/chiaki/session/StreamSession.kt` | modify | route video through renderer or direct path |
| `android/app/src/main/java/com/metallic/chiaki/stream/StreamViewModel.kt` | modify | pass pipeline config from preferences |
| `android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt` | modify | display mode, frame-rate hint, stats overlay |
| `LICENSES/BSD-3-Clause-SGSR.txt` | create | license text for the bundled shader |

---

### Task 1: Fork identity, integrity flag, signing

**Files:**
- Modify: `android/app/build.gradle:57-75`, `android/app/build.gradle:97-100`
- Modify: `android/app/src/main/AndroidManifest.xml:24`
- Modify: `android/app/src/main/res/values/strings.xml:2`
- Modify: `android/app/src/main/java/com/metallic/chiaki/common/LogManager.kt:12`
- Modify: `android/app/src/main/java/com/metallic/chiaki/common/AppIntegrityManager.kt:33-40`

- [ ] **Step 1: Create the GitHub fork and wire remotes**

```bash
cd /home/neo/Projects/Personal/Pylux
gh repo fork ForWard-Technologies-LLC/Pylux --fork-name pylux-noir --remote=false --clone=false
git remote rename origin upstream
git remote add origin https://github.com/7tg/pylux-noir.git
git remote -v
```
Expected: `origin` → `7tg/pylux-noir`, `upstream` → `ForWard-Technologies-LLC/Pylux`.

- [ ] **Step 2: Change applicationId, ABI filter, and add the integrity flag**

In `android/app/build.gradle` replace line 65 `applicationId "com.pylux.stream"` with:
```groovy
        applicationId "com.pylux.stream.noir"
        buildConfigField "boolean", "INTEGRITY_CHECK", "false"
```
Replace the `abiFilters` line (74) with:
```groovy
            abiFilters "arm64-v8a"
```
(The fork only targets the Tab S9; this also makes `assembleRelease` fast.)

- [ ] **Step 3: Make the file provider authority follow the applicationId**

`android/app/src/main/AndroidManifest.xml` line 24: change
`android:authorities="com.pylux.stream.fileprovider"` to
`android:authorities="${applicationId}.fileprovider"`.

`android/app/src/main/java/com/metallic/chiaki/common/LogManager.kt` line 12: change to
```kotlin
val fileProviderAuthority = "${com.pylux.stream.BuildConfig.APPLICATION_ID}.fileprovider"
```

- [ ] **Step 4: Rename the app and bypass the Play integrity check**

`android/app/src/main/res/values/strings.xml` line 2: `<string name="app_name">Pylux Noir</string>`.

In `AppIntegrityManager.validateAppState` (line 33) change the debug bypass to:
```kotlin
		// Skip validation in debug builds and in forks without Play distribution
		if (BuildConfig.DEBUG || !BuildConfig.INTEGRITY_CHECK)
		{
			Log.w(TAG, "=== Integrity validation bypassed (debug=${BuildConfig.DEBUG}, check=${BuildConfig.INTEGRITY_CHECK}) ===")
			onResult(true)
			return
		}
```

- [ ] **Step 5: Create the keystore and local signing config (untracked)**

`android/build-local.sh` rewrites `android/local.properties` on every run and only adds the
`chiaki*` signing keys when it finds `secrets/android/release.jks` plus
`secrets/android/credentials.env` (both gitignored via `.gitignore` lines 46-49). So the
keystore and credentials go there, not into `local.properties` by hand.

```bash
mkdir -p ~/keystores
keytool -genkeypair -v -keystore ~/keystores/pylux-noir.jks -alias pylux-noir \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass pylux-noir-7tg -keypass pylux-noir-7tg \
  -dname "CN=pylux-noir, OU=7tg, O=7tg, L=Istanbul, C=TR"
cp ~/keystores/pylux-noir.jks /home/neo/Projects/Personal/Pylux/secrets/android/release.jks
cat > /home/neo/Projects/Personal/Pylux/secrets/android/credentials.env <<'EOF'
ANDROID_KEYSTORE_PASSWORD=pylux-noir-7tg
ANDROID_KEY_ALIAS=pylux-noir
ANDROID_KEY_PASSWORD=pylux-noir-7tg
EOF
git -C /home/neo/Projects/Personal/Pylux status --short secrets
```
Expected: nothing listed (both files ignored). After the next build-script run, `android/local.properties` contains the four `chiaki*` keys and the APK is signed by `CN=pylux-noir` (check with `~/Android/Sdk/build-tools/35.0.0/apksigner verify --print-certs <apk>`). `~/keystores/pylux-noir.jks` is the backup copy; tell the user to back it up elsewhere too.

- [ ] **Step 6: Build to confirm nothing broke**

```bash
cd /home/neo/Projects/Personal/Pylux && ./android/build-local.sh --quick
```
Expected: `BUILD SUCCESSFUL`; the script prints the APK path (with `--quick` it is under `android/app/build/intermediates/apk/debug/`).

- [ ] **Step 7: Commit**

```bash
git add android/app/build.gradle android/app/src/main/AndroidManifest.xml android/app/src/main/res/values/strings.xml android/app/src/main/java/com/metallic/chiaki/common/LogManager.kt android/app/src/main/java/com/metallic/chiaki/common/AppIntegrityManager.kt
git commit -m "fork: pylux-noir identity, arm64-only, skip Play integrity check"
```

---

### Task 2: FramePacer (pure pacing logic, TDD)

**Files:**
- Modify: `android/app/build.gradle:182` (dependencies)
- Create: `android/app/src/main/java/com/metallic/chiaki/stream/gpu/FramePacer.kt`
- Create: `android/app/src/test/java/com/metallic/chiaki/stream/gpu/FramePacerTest.kt`

- [ ] **Step 1: Add JUnit to the dependencies block**

In `android/app/build.gradle`, inside `dependencies {` (line 182) add:
```groovy
    testImplementation 'junit:junit:4.13.2'
```

- [ ] **Step 2: Write the failing tests**

`android/app/src/test/java/com/metallic/chiaki/stream/gpu/FramePacerTest.kt`:
```kotlin
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
		// 1.5 intervals after the last real frame: too old to predict from
		assertEquals(PaceDecision.SKIP, p.decide(interval60 + (interval60 * 3 / 2), false))
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
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
cd /home/neo/Projects/Personal/Pylux/android && JAVA_HOME=~/.jdks/jdk-21.0.12.1+1 ./gradlew :app:testDebugUnitTest --tests 'com.metallic.chiaki.stream.gpu.FramePacerTest'
```
Expected: compilation error `Unresolved reference: FramePacer`.

- [ ] **Step 4: Implement FramePacer**

`android/app/src/main/java/com/metallic/chiaki/stream/gpu/FramePacer.kt`:
```kotlin
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
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd /home/neo/Projects/Personal/Pylux/android && JAVA_HOME=~/.jdks/jdk-21.0.12.1+1 ./gradlew :app:testDebugUnitTest --tests 'com.metallic.chiaki.stream.gpu.FramePacerTest'
```
Expected: `BUILD SUCCESSFUL`, 9 tests passed (see `android/app/build/reports/tests/testDebugUnitTest/index.html`).

- [ ] **Step 6: Commit**

```bash
git add android/app/build.gradle android/app/src/main/java/com/metallic/chiaki/stream/gpu/FramePacer.kt android/app/src/test/java/com/metallic/chiaki/stream/gpu/FramePacerTest.kt
git commit -m "feat(gpu): FramePacer decides real/generated/skip per vsync"
```

---

### Task 3: DisplayModeHelper (TDD for selection, thin Android wrapper)

**Files:**
- Create: `android/app/src/main/java/com/metallic/chiaki/stream/DisplayModeHelper.kt`
- Create: `android/app/src/test/java/com/metallic/chiaki/stream/DisplayModeHelperTest.kt`

- [ ] **Step 1: Write the failing tests**

`android/app/src/test/java/com/metallic/chiaki/stream/DisplayModeHelperTest.kt`:
```kotlin
// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayModeHelperTest
{
	private val m60 = DisplayModeInfo(id = 1, width = 2560, height = 1600, refreshRate = 60f)
	private val m120 = DisplayModeInfo(id = 2, width = 2560, height = 1600, refreshRate = 120f)
	private val m90 = DisplayModeInfo(id = 3, width = 2560, height = 1600, refreshRate = 90f)
	private val low120 = DisplayModeInfo(id = 4, width = 1920, height = 1200, refreshRate = 120f)

	@Test
	fun picksHighestRefreshAtSameResolution()
	{
		assertEquals(m120, DisplayModeHelper.pickHighestRefresh(m60, listOf(m60, m90, m120, low120)))
	}

	@Test
	fun ignoresModesWithDifferentResolution()
	{
		assertNull(DisplayModeHelper.pickHighestRefresh(m60, listOf(m60, low120)))
	}

	@Test
	fun returnsNullWhenCurrentIsAlreadyHighest()
	{
		assertNull(DisplayModeHelper.pickHighestRefresh(m120, listOf(m60, m90, m120)))
	}

	@Test
	fun treatsTinyRateDifferencesAsEqual()
	{
		val m120b = m120.copy(id = 9, refreshRate = 120.0001f)
		assertNull(DisplayModeHelper.pickHighestRefresh(m120, listOf(m120, m120b)))
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd /home/neo/Projects/Personal/Pylux/android && JAVA_HOME=~/.jdks/jdk-21.0.12.1+1 ./gradlew :app:testDebugUnitTest --tests 'com.metallic.chiaki.stream.DisplayModeHelperTest'
```
Expected: `Unresolved reference: DisplayModeInfo`.

- [ ] **Step 3: Implement DisplayModeHelper**

`android/app/src/main/java/com/metallic/chiaki/stream/DisplayModeHelper.kt`:
```kotlin
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
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd /home/neo/Projects/Personal/Pylux/android && JAVA_HOME=~/.jdks/jdk-21.0.12.1+1 ./gradlew :app:testDebugUnitTest --tests 'com.metallic.chiaki.stream.DisplayModeHelperTest'
```
Expected: 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/metallic/chiaki/stream/DisplayModeHelper.kt android/app/src/test/java/com/metallic/chiaki/stream/DisplayModeHelperTest.kt
git commit -m "feat(stream): DisplayModeHelper picks the highest refresh mode"
```

---

### Task 4: Preferences and settings switches

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml:116-117` and `:320`
- Modify: `android/app/src/main/res/xml/preferences.xml:115-119`
- Modify: `android/app/src/main/java/com/metallic/chiaki/common/Preferences.kt:153-156`
- Modify: `android/app/src/main/java/com/metallic/chiaki/settings/SettingsFragment.kt` (DataStore `getBoolean`/`putBoolean`, around lines 42-60)

- [ ] **Step 1: Add string resources**

In `android/app/src/main/res/values/strings.xml`, after line 117 (`preferences_pip_enabled_summary`) add:
```xml
    <string name="preferences_gpu_upscaling_enabled_title">GPU upscaling (Snapdragon GSR)</string>
    <string name="preferences_gpu_upscaling_enabled_summary">Upscale the stream to the screen resolution with a sharpening filter</string>
    <string name="preferences_frame_generation_enabled_title">Frame generation (Adreno Frame Motion Engine)</string>
    <string name="preferences_frame_generation_enabled_summary">Predict an extra frame between decoded frames to double the displayed frame rate</string>
    <string name="preferences_frame_generation_unsupported_summary">Not supported on this device (needs GL_QCOM_frame_extrapolation)</string>
    <string name="preferences_prefer_high_refresh_title">Prefer high refresh rate</string>
    <string name="preferences_prefer_high_refresh_summary">Switch the display to its highest refresh rate while streaming</string>
```
After line 320 (`preferences_pip_enabled_key`) add:
```xml
    <string name="preferences_gpu_upscaling_enabled_key">gpu_upscaling_enabled</string>
    <string name="preferences_frame_generation_enabled_key">frame_generation_enabled</string>
    <string name="preferences_prefer_high_refresh_key">prefer_high_refresh</string>
```

- [ ] **Step 2: Add the switches to the Stream category**

In `android/app/src/main/res/xml/preferences.xml`, after the Codec `ListPreference` (closing `/>` on line 119, before `</PreferenceCategory>`) add:
```xml

        <SwitchPreference
            app:key="@string/preferences_gpu_upscaling_enabled_key"
            app:title="@string/preferences_gpu_upscaling_enabled_title"
            app:summary="@string/preferences_gpu_upscaling_enabled_summary"
            app:defaultValue="true"
            app:icon="@drawable/ic_resolution"/>

        <SwitchPreference
            app:key="@string/preferences_frame_generation_enabled_key"
            app:title="@string/preferences_frame_generation_enabled_title"
            app:summary="@string/preferences_frame_generation_enabled_summary"
            app:defaultValue="true"
            app:icon="@drawable/ic_fps"/>

        <SwitchPreference
            app:key="@string/preferences_prefer_high_refresh_key"
            app:title="@string/preferences_prefer_high_refresh_title"
            app:summary="@string/preferences_prefer_high_refresh_summary"
            app:defaultValue="true"
            app:icon="@drawable/ic_fps"/>
```

- [ ] **Step 3: Add the properties to Preferences**

In `android/app/src/main/java/com/metallic/chiaki/common/Preferences.kt`, after the `pipEnabled` property (line 156) add:
```kotlin

	val gpuUpscalingEnabledKey get() = resources.getString(R.string.preferences_gpu_upscaling_enabled_key)
	var gpuUpscalingEnabled
		get() = sharedPreferences.getBoolean(gpuUpscalingEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(gpuUpscalingEnabledKey, value).apply() }

	val frameGenerationEnabledKey get() = resources.getString(R.string.preferences_frame_generation_enabled_key)
	var frameGenerationEnabled
		get() = sharedPreferences.getBoolean(frameGenerationEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(frameGenerationEnabledKey, value).apply() }

	val preferHighRefreshKey get() = resources.getString(R.string.preferences_prefer_high_refresh_key)
	var preferHighRefresh
		get() = sharedPreferences.getBoolean(preferHighRefreshKey, true)
		set(value) { sharedPreferences.edit().putBoolean(preferHighRefreshKey, value).apply() }
```

- [ ] **Step 4: Map the keys in the settings DataStore**

In `SettingsFragment.kt`, class `DataStore`, add to `getBoolean`'s `when` (before `else -> defValue`):
```kotlin
		preferences.gpuUpscalingEnabledKey -> preferences.gpuUpscalingEnabled
		preferences.frameGenerationEnabledKey -> preferences.frameGenerationEnabled
		preferences.preferHighRefreshKey -> preferences.preferHighRefresh
```
and to `putBoolean`'s `when`:
```kotlin
			preferences.gpuUpscalingEnabledKey -> preferences.gpuUpscalingEnabled = value
			preferences.frameGenerationEnabledKey -> preferences.frameGenerationEnabled = value
			preferences.preferHighRefreshKey -> preferences.preferHighRefresh = value
```
Note: the `pipEnabled` switch is not in this DataStore today, which means it reads straight from the default SharedPreferences. The new keys go through the DataStore so they behave like the other switches.

- [ ] **Step 5: Build and check the switches appear**

```bash
cd /home/neo/Projects/Personal/Pylux && ./android/build-local.sh --quick --install
```
Open Settings → Stream on the tablet. Expected: three new switches, all on by default; toggling persists after leaving and reopening Settings.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/res/values/strings.xml android/app/src/main/res/xml/preferences.xml android/app/src/main/java/com/metallic/chiaki/common/Preferences.kt android/app/src/main/java/com/metallic/chiaki/settings/SettingsFragment.kt
git commit -m "feat(settings): GPU upscaling, frame generation, high refresh switches"
```

---

### Task 5: Native shim for GL_QCOM_frame_extrapolation + Kotlin probe

**Files:**
- Create: `android/app/src/main/cpp/adreno-ext.c`
- Modify: `android/app/CMakeLists.txt:27-40` (sources) and `:51-54` (libs)
- Create: `android/app/src/main/java/com/metallic/chiaki/stream/gpu/AdrenoExt.kt`
- Modify: `android/app/src/main/java/com/metallic/chiaki/settings/SettingsFragment.kt` (`onCreatePreferences`, after the FPS list population ~line 212)

- [ ] **Step 1: Write the C shim**

`android/app/src/main/cpp/adreno-ext.c`:
```c
// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
//
// JNI shim for the Qualcomm Adreno Frame Motion Engine, exposed to OpenGL ES as
// GL_QCOM_frame_extrapolation (Khronos OpenGL ES extension #333). Both functions must be
// called on a thread with an EGL/GLES 3.x context current (the GpuVideoRenderer thread).

#include <jni.h>
#include <string.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>

#define LOG_TAG "AdrenoExt"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define ADRENO_JNI(name) Java_com_metallic_chiaki_stream_gpu_AdrenoExt_##name

typedef void (GL_APIENTRYP PFNGLEXTRAPOLATETEX2DQCOMPROC)(GLuint src1, GLuint src2, GLuint output, GLfloat scaleFactor);

static PFNGLEXTRAPOLATETEX2DQCOMPROC s_extrapolate = NULL;

static int gl_has_extension(const char *name)
{
	GLint count = 0;
	glGetIntegerv(GL_NUM_EXTENSIONS, &count);
	for(GLint i = 0; i < count; i++)
	{
		const char *ext = (const char *)glGetStringi(GL_EXTENSIONS, (GLuint)i);
		if(ext && strcmp(ext, name) == 0)
			return 1;
	}
	return 0;
}

JNIEXPORT jboolean JNICALL ADRENO_JNI(nativeProbeFrameExtrapolation)(JNIEnv *env, jclass cls)
{
	(void)env; (void)cls;
	if(eglGetCurrentContext() == EGL_NO_CONTEXT)
	{
		LOGW("probe called without a current EGL context");
		return JNI_FALSE;
	}
	if(!gl_has_extension("GL_QCOM_frame_extrapolation"))
	{
		LOGI("GL_QCOM_frame_extrapolation not advertised by driver (renderer: %s)", (const char *)glGetString(GL_RENDERER));
		return JNI_FALSE;
	}
	s_extrapolate = (PFNGLEXTRAPOLATETEX2DQCOMPROC)eglGetProcAddress("glExtrapolateTex2DQCOM");
	LOGI("GL_QCOM_frame_extrapolation supported=%d (renderer: %s)", s_extrapolate != NULL, (const char *)glGetString(GL_RENDERER));
	return s_extrapolate != NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL ADRENO_JNI(nativeExtrapolateTex2D)(JNIEnv *env, jclass cls, jint src1, jint src2, jint output, jfloat scale)
{
	(void)env; (void)cls;
	if(!s_extrapolate)
		return;
	s_extrapolate((GLuint)src1, (GLuint)src2, (GLuint)output, scale);
}
```

- [ ] **Step 2: Compile and link it in CMake**

In `android/app/CMakeLists.txt`, add `src/main/cpp/adreno-ext.c` to the `add_library(chiaki-jni SHARED ...)` list (after `src/main/cpp/circular-fifo.hpp`), and after line 54 (`target_link_libraries(chiaki-jni "${ANDROID_LIB_LOG}" ...)`) add:
```cmake
find_library(ANDROID_LIB_GLESV3 GLESv3)
find_library(ANDROID_LIB_EGL EGL)
target_link_libraries(chiaki-jni "${ANDROID_LIB_GLESV3}" "${ANDROID_LIB_EGL}")
```

- [ ] **Step 3: Write the Kotlin binding with a cached pbuffer probe**

`android/app/src/main/java/com/metallic/chiaki/stream/gpu/AdrenoExt.kt`:
```kotlin
// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream.gpu

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log

/**
 * Bindings for the Adreno Frame Motion Engine (GL_QCOM_frame_extrapolation).
 * [nativeProbeFrameExtrapolation] and [nativeExtrapolateTex2D] require a current GL context.
 */
object AdrenoExt
{
	private const val TAG = "AdrenoExt"
	private const val EGL_OPENGL_ES3_BIT_KHR = 0x40

	init
	{
		System.loadLibrary("chiaki-jni")
	}

	@JvmStatic external fun nativeProbeFrameExtrapolation(): Boolean
	@JvmStatic external fun nativeExtrapolateTex2D(src1: Int, src2: Int, output: Int, scale: Float)

	@Volatile private var cachedSupport: Boolean? = null

	/**
	 * Probes support on a throwaway 1x1 pbuffer context. Safe to call from any thread
	 * without a GL context (e.g. the settings screen). Result is cached per process.
	 */
	fun isFrameExtrapolationSupported(): Boolean
	{
		cachedSupport?.let { return it }
		val result = try { probeOnPbuffer() } catch(e: Exception) { Log.w(TAG, "probe failed: ${e.message}"); false }
		cachedSupport = result
		return result
	}

	private fun probeOnPbuffer(): Boolean
	{
		val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
		if(display == EGL14.EGL_NO_DISPLAY) return false
		val version = IntArray(2)
		if(!EGL14.eglInitialize(display, version, 0, version, 1)) return false
		try
		{
			val configAttribs = intArrayOf(
				EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
				EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
				EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
				EGL14.EGL_NONE)
			val configs = arrayOfNulls<EGLConfig>(1)
			val numConfigs = IntArray(1)
			if(!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) return false
			val config = configs[0] ?: return false
			val context: EGLContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
				intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
			if(context == EGL14.EGL_NO_CONTEXT) return false
			val surface: EGLSurface = EGL14.eglCreatePbufferSurface(display, config,
				intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
			try
			{
				if(surface == EGL14.EGL_NO_SURFACE) return false
				if(!EGL14.eglMakeCurrent(display, surface, surface, context)) return false
				return nativeProbeFrameExtrapolation()
			}
			finally
			{
				EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
				if(surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
				EGL14.eglDestroyContext(display, context)
			}
		}
		finally
		{
			EGL14.eglTerminate(display)
		}
	}
}
```

- [ ] **Step 4: Disable the frame generation switch when unsupported**

In `SettingsFragment.onCreatePreferences`, right after the FPS `ListPreference` block (ends ~line 212) add:
```kotlin
		preferenceScreen.findPreference<SwitchPreference>(getString(R.string.preferences_frame_generation_enabled_key))?.let {
			if(!com.metallic.chiaki.stream.gpu.AdrenoExt.isFrameExtrapolationSupported())
			{
				it.isEnabled = false
				it.summary = getString(R.string.preferences_frame_generation_unsupported_summary)
			}
		}
```
Add `import androidx.preference.SwitchPreference` to the file's imports if it is not already there (`ListPreference` is imported from the same package).

- [ ] **Step 5: Build, install, verify the probe on the tablet**

```bash
cd /home/neo/Projects/Personal/Pylux && ./android/build-local.sh --quick --install
```
Open Settings → Stream. Then:
```bash
./android/build-local.sh --logs-dump | grep AdrenoExt
```
Expected on the Tab S9: `GL_QCOM_frame_extrapolation supported=1 (renderer: Adreno (TM) 740)` and the switch stays enabled. (On an emulator it would log "not advertised" and the switch is greyed out with the unsupported summary.)

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/cpp/adreno-ext.c android/app/CMakeLists.txt android/app/src/main/java/com/metallic/chiaki/stream/gpu/AdrenoExt.kt android/app/src/main/java/com/metallic/chiaki/settings/SettingsFragment.kt
git commit -m "feat(gpu): JNI shim and probe for GL_QCOM_frame_extrapolation"
```

---

### Task 6: Shaders (SGSR1 asset, OES copy, blit, program builder)

**Files:**
- Create: `android/app/src/main/assets/shaders/sgsr1_shader_mobile.frag`
- Create: `LICENSES/BSD-3-Clause-SGSR.txt`
- Create: `android/app/src/main/java/com/metallic/chiaki/stream/gpu/GlShaders.kt`

- [ ] **Step 1: Vendor the SGSR1 shader verbatim**

```bash
cd /home/neo/Projects/Personal/Pylux
mkdir -p android/app/src/main/assets/shaders
gh api repos/SnapdragonStudios/snapdragon-gsr/contents/sgsr/v1/include/glsl/sgsr1_shader_mobile.frag -H 'Accept: application/vnd.github.raw' > android/app/src/main/assets/shaders/sgsr1_shader_mobile.frag
gh api repos/SnapdragonStudios/snapdragon-gsr/contents/LICENSE -H 'Accept: application/vnd.github.raw' > LICENSES/BSD-3-Clause-SGSR.txt
head -12 android/app/src/main/assets/shaders/sgsr1_shader_mobile.frag
```
Expected: the file starts with `#version 300 es` and carries the Qualcomm BSD-3-Clause header; `LICENSES/BSD-3-Clause-SGSR.txt` contains the BSD 3-Clause text.

- [ ] **Step 2: Write GlShaders**

The vendored fragment shader uses `textureGather` and `layout(location=0) in`, which need GLSL ES 3.10. The loader patches two lines in memory (the asset itself stays verbatim).

`android/app/src/main/java/com/metallic/chiaki/stream/gpu/GlShaders.kt`:
```kotlin
// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream.gpu

import android.content.res.AssetManager
import android.opengl.GLES20
import android.opengl.GLES31

class ShaderCompileException(message: String): RuntimeException(message)

/** Compiles the programs used by [GpuVideoRenderer]. Call on the render thread with a GLES 3.1 context current. */
object GlShaders
{
	/** Full-screen triangle from gl_VertexID; no vertex buffers needed. Output name matches SGSR1's input. */
	private const val VERTEX_SRC = """#version 310 es
out highp vec4 in_TEXCOORD0;
void main()
{
	vec2 pos = vec2(float((gl_VertexID & 1) << 2) - 1.0, float((gl_VertexID & 2) << 1) - 1.0);
	in_TEXCOORD0 = vec4(pos * 0.5 + 0.5, 0.0, 0.0);
	gl_Position = vec4(pos, 0.0, 1.0);
}
"""

	/** Copies the decoder's external texture into a plain RGBA8 texture, honouring the SurfaceTexture transform. */
	private const val OES_COPY_FRAG_SRC = """#version 310 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
uniform mat4 uTransform;
in highp vec4 in_TEXCOORD0;
out vec4 fragColor;
void main()
{
	highp vec2 tc = (uTransform * vec4(in_TEXCOORD0.xy, 0.0, 1.0)).xy;
	fragColor = vec4(texture(uTex, tc).rgb, 1.0);
}
"""

	/** Bilinear blit used when SGSR upscaling is off. Uniform name matches SGSR1 so the renderer treats both alike. */
	private const val BLIT_FRAG_SRC = """#version 310 es
precision mediump float;
uniform sampler2D ps0;
in highp vec4 in_TEXCOORD0;
out vec4 fragColor;
void main()
{
	fragColor = vec4(texture(ps0, in_TEXCOORD0.xy).rgb, 1.0);
}
"""

	fun loadSgsr1Source(assets: AssetManager): String
	{
		val raw = assets.open("shaders/sgsr1_shader_mobile.frag").bufferedReader().use { it.readText() }
		return raw
			.replaceFirst("#version 300 es", "#version 310 es")
			.replaceFirst("layout(location=0) in highp vec4 in_TEXCOORD0;", "in highp vec4 in_TEXCOORD0;")
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
		GLES31.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
		if(status[0] == 0)
		{
			val log = GLES31.glGetProgramInfoLog(program)
			GLES31.glDeleteProgram(program)
			throw ShaderCompileException("$name program link failed: $log")
		}
		return program
	}
}
```

- [ ] **Step 3: Build to check it compiles**

```bash
cd /home/neo/Projects/Personal/Pylux && ./android/build-local.sh --quick
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/assets/shaders/sgsr1_shader_mobile.frag LICENSES/BSD-3-Clause-SGSR.txt android/app/src/main/java/com/metallic/chiaki/stream/gpu/GlShaders.kt
git commit -m "feat(gpu): vendor SGSR1 shader and add GL program builder"
```

---

### Task 7: GpuVideoRenderer

**Files:**
- Create: `android/app/src/main/java/com/metallic/chiaki/stream/gpu/GpuVideoRenderer.kt`

- [ ] **Step 1: Write the renderer**

```kotlin
// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream.gpu

import android.content.res.AssetManager
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES31
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GPU video path: MediaCodec decodes into [inputSurface]; every display vsync this thread
 * presents either the newest decoded frame or a hardware-extrapolated one, upscaled with
 * SGSR1 (or a plain blit) onto the SurfaceView's surface.
 *
 * Threading: all GL work happens on the private render thread. Public methods are safe to
 * call from any thread; [start] and [release] block until the render thread is done.
 */
class GpuVideoRenderer(
	private val assets: AssetManager,
	private val streamWidth: Int,
	private val streamHeight: Int,
	streamFps: Int,
	private val upscalingRequested: Boolean,
	private val frameGenerationRequested: Boolean
)
{
	companion object
	{
		private const val TAG = "GpuVideoRenderer"
		private const val EGL_OPENGL_ES3_BIT_KHR = 0x40
		private const val EXTRAPOLATION_SCALE = 0.5f
	}

	data class Stats(val presentedFps: Double, val generatedFps: Double, val frameGenerationActive: Boolean, val upscalingActive: Boolean)

	private val thread = HandlerThread("GpuVideoRenderer").also { it.start() }
	private val handler = Handler(thread.looper)
	private val pacer = FramePacer(1_000_000_000L / streamFps.coerceAtLeast(1), frameGenerationRequested)
	private val frameAvailable = AtomicBoolean(false)
	@Volatile private var released = false

	// EGL
	private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
	private var eglConfig: EGLConfig? = null
	private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
	private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
	private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
	private var windowWidth = 0
	private var windowHeight = 0

	// GL objects
	private var oesTexture = 0
	private val ring = IntArray(2)
	private val ringFbo = IntArray(2)
	private var newestSlot = 0 // index into ring of the most recent real frame
	private var generatedTexture = 0
	private var oesCopyProgram = 0
	private var presentProgram = 0 // SGSR1 or blit
	private var presentIsSgsr = false
	private var frameGenerationActive = false
	private val transform = FloatArray(16)

	private var surfaceTexture: SurfaceTexture? = null
	lateinit var inputSurface: Surface
		private set

	// Stats (1 s window)
	private var windowStartNs = 0L
	private var presentedInWindow = 0
	private var generatedInWindow = 0
	@Volatile private var presentedFps = 0.0
	@Volatile private var generatedFps = 0.0
	private val loggedGlErrors = HashSet<Int>()

	private val vsyncCallback = object: Choreographer.FrameCallback
	{
		override fun doFrame(frameTimeNanos: Long)
		{
			if(released) return
			Choreographer.getInstance().postFrameCallback(this)
			onVsync(frameTimeNanos)
		}
	}

	/** Initializes EGL/GL on the render thread. Returns false (after logging) if the GPU path is unusable. */
	fun start(): Boolean
	{
		var ok = false
		val latch = CountDownLatch(1)
		handler.post {
			ok = try { initGl(); true } catch(e: Exception) { Log.e(TAG, "GPU path init failed: ${e.message}", e); false }
			if(ok) Choreographer.getInstance().postFrameCallback(vsyncCallback)
			latch.countDown()
		}
		latch.await()
		if(!ok) release()
		return ok
	}

	/** New SurfaceView surface (or null when it is gone). Only the EGL window surface changes; decoding continues. */
	fun setOutputSurface(surface: Surface?, width: Int, height: Int)
	{
		handler.post {
			if(released) return@post
			destroyWindowSurface()
			if(surface != null && surface.isValid)
			{
				windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
				if(windowSurface == EGL14.EGL_NO_SURFACE)
				{
					Log.e(TAG, "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
					return@post
				}
				EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)
				EGL14.eglSwapInterval(eglDisplay, 1)
				val w = IntArray(1); val h = IntArray(1)
				EGL14.eglQuerySurface(eglDisplay, windowSurface, EGL14.EGL_WIDTH, w, 0)
				EGL14.eglQuerySurface(eglDisplay, windowSurface, EGL14.EGL_HEIGHT, h, 0)
				windowWidth = if(w[0] > 0) w[0] else width
				windowHeight = if(h[0] > 0) h[0] else height
				Log.i(TAG, "output surface ${windowWidth}x${windowHeight} (stream ${streamWidth}x${streamHeight}, upscale=$presentIsSgsr, framegen=$frameGenerationActive)")
			}
		}
	}

	fun stats(): Stats = Stats(presentedFps, generatedFps, frameGenerationActive, presentIsSgsr)

	fun release()
	{
		if(released) return
		released = true
		val latch = CountDownLatch(1)
		handler.post {
			Choreographer.getInstance().removeFrameCallback(vsyncCallback)
			releaseGl()
			latch.countDown()
		}
		latch.await()
		thread.quitSafely()
	}

	// ---- render thread below ----

	private fun initGl()
	{
		eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
		check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
		val version = IntArray(2)
		check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
		val configAttribs = intArrayOf(
			EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
			EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
			EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
			EGL14.EGL_NONE)
		val configs = arrayOfNulls<EGLConfig>(1)
		val num = IntArray(1)
		check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "no ES3 EGL config" }
		eglConfig = configs[0]
		eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
			intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
		check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
		pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig,
			intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
		check(pbufferSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
		check(EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)) { "eglMakeCurrent failed" }
		Log.i(TAG, "GL ${GLES31.glGetString(GLES31.GL_VERSION)} on ${GLES31.glGetString(GLES31.GL_RENDERER)}")

		// Programs: the copy shader is mandatory; SGSR failure only degrades to the blit.
		oesCopyProgram = GlShaders.buildOesCopyProgram()
		presentProgram = if(upscalingRequested)
		{
			try { presentIsSgsr = true; GlShaders.buildSgsr1Program(assets) }
			catch(e: ShaderCompileException) { Log.w(TAG, "SGSR1 unavailable, using bilinear blit: ${e.message}"); presentIsSgsr = false; GlShaders.buildBlitProgram() }
		}
		else GlShaders.buildBlitProgram()

		// Textures: external input, 2-slot ring, generated output (RGBA8, stream size, identical).
		val ids = IntArray(4)
		GLES31.glGenTextures(4, ids, 0)
		oesTexture = ids[0]; ring[0] = ids[1]; ring[1] = ids[2]; generatedTexture = ids[3]
		GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
		setSamplerParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
		for(tex in intArrayOf(ring[0], ring[1], generatedTexture))
		{
			GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, tex)
			GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES31.GL_RGBA8, streamWidth, streamHeight)
			setSamplerParams(GLES31.GL_TEXTURE_2D)
		}
		GLES31.glGenFramebuffers(2, ringFbo, 0)
		for(i in 0..1)
		{
			GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, ringFbo[i])
			GLES31.glFramebufferTexture2D(GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0, GLES31.GL_TEXTURE_2D, ring[i], 0)
			val status = GLES31.glCheckFramebufferStatus(GLES31.GL_FRAMEBUFFER)
			check(status == GLES31.GL_FRAMEBUFFER_COMPLETE) { "ring FBO $i incomplete: 0x${Integer.toHexString(status)}" }
		}
		GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)

		frameGenerationActive = frameGenerationRequested && AdrenoExt.nativeProbeFrameExtrapolation()
		pacer.frameGenerationEnabled = frameGenerationActive
		Log.i(TAG, "frame extrapolation supported=$frameGenerationActive requested=$frameGenerationRequested")

		val st = SurfaceTexture(oesTexture)
		st.setDefaultBufferSize(streamWidth, streamHeight)
		st.setOnFrameAvailableListener({ frameAvailable.set(true) }, handler)
		surfaceTexture = st
		inputSurface = Surface(st)
		windowStartNs = System.nanoTime()
	}

	private fun setSamplerParams(target: Int)
	{
		GLES31.glTexParameteri(target, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR)
		GLES31.glTexParameteri(target, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR)
		GLES31.glTexParameteri(target, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
		GLES31.glTexParameteri(target, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
	}

	private fun onVsync(frameTimeNanos: Long)
	{
		val st = surfaceTexture ?: return
		val newFrame = frameAvailable.getAndSet(false)
		when(pacer.decide(frameTimeNanos, newFrame))
		{
			PaceDecision.PRESENT_REAL ->
			{
				try { st.updateTexImage() } catch(e: RuntimeException) { Log.w(TAG, "updateTexImage failed: ${e.message}"); return }
				st.getTransformMatrix(transform)
				newestSlot = 1 - newestSlot
				copyOesToSlot(newestSlot)
				present(ring[newestSlot])
			}
			PaceDecision.PRESENT_GENERATED ->
			{
				AdrenoExt.nativeExtrapolateTex2D(ring[1 - newestSlot], ring[newestSlot], generatedTexture, EXTRAPOLATION_SCALE)
				generatedInWindow++
				present(generatedTexture)
			}
			PaceDecision.SKIP -> {}
		}
		updateStats(frameTimeNanos)
	}

	private fun copyOesToSlot(slot: Int)
	{
		GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, ringFbo[slot])
		GLES31.glViewport(0, 0, streamWidth, streamHeight)
		GLES31.glUseProgram(oesCopyProgram)
		GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
		GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
		GLES31.glUniform1i(GLES31.glGetUniformLocation(oesCopyProgram, "uTex"), 0)
		GLES31.glUniformMatrix4fv(GLES31.glGetUniformLocation(oesCopyProgram, "uTransform"), 1, false, transform, 0)
		GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
		GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
		checkGlError("copy")
	}

	private fun present(sourceTexture: Int)
	{
		if(windowSurface == EGL14.EGL_NO_SURFACE) return
		GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
		GLES31.glViewport(0, 0, windowWidth, windowHeight)
		GLES31.glUseProgram(presentProgram)
		GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
		GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, sourceTexture)
		GLES31.glUniform1i(GLES31.glGetUniformLocation(presentProgram, "ps0"), 0)
		if(presentIsSgsr)
		{
			val loc = GLES31.glGetUniformLocation(presentProgram, "ViewportInfo")
			GLES31.glUniform4f(loc, 1f / streamWidth, 1f / streamHeight, streamWidth.toFloat(), streamHeight.toFloat())
		}
		GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
		checkGlError("present")
		if(!EGL14.eglSwapBuffers(eglDisplay, windowSurface))
			Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
		presentedInWindow++
	}

	private fun updateStats(nowNs: Long)
	{
		val elapsed = nowNs - windowStartNs
		if(elapsed >= 1_000_000_000L)
		{
			presentedFps = presentedInWindow * 1e9 / elapsed
			generatedFps = generatedInWindow * 1e9 / elapsed
			presentedInWindow = 0
			generatedInWindow = 0
			windowStartNs = nowNs
		}
	}

	private fun checkGlError(where: String)
	{
		val err = GLES31.glGetError()
		if(err != GLES31.GL_NO_ERROR && loggedGlErrors.add(err))
			Log.w(TAG, "GL error 0x${Integer.toHexString(err)} in $where (logged once)")
	}

	private fun destroyWindowSurface()
	{
		if(windowSurface != EGL14.EGL_NO_SURFACE)
		{
			EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
			EGL14.eglDestroySurface(eglDisplay, windowSurface)
			windowSurface = EGL14.EGL_NO_SURFACE
		}
	}

	private fun releaseGl()
	{
		if(eglDisplay == EGL14.EGL_NO_DISPLAY) return
		destroyWindowSurface()
		if(eglContext != EGL14.EGL_NO_CONTEXT)
		{
			EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
			if(oesCopyProgram != 0) GLES31.glDeleteProgram(oesCopyProgram)
			if(presentProgram != 0) GLES31.glDeleteProgram(presentProgram)
			GLES31.glDeleteFramebuffers(2, ringFbo, 0)
			GLES31.glDeleteTextures(4, intArrayOf(oesTexture, ring[0], ring[1], generatedTexture), 0)
		}
		surfaceTexture?.release()
		surfaceTexture = null
		if(this::inputSurface.isInitialized) inputSurface.release()
		EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
		if(pbufferSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
		if(eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
		EGL14.eglTerminate(eglDisplay)
		eglDisplay = EGL14.EGL_NO_DISPLAY
		Log.i(TAG, "released")
	}
}
```

- [ ] **Step 2: Build to check it compiles**

```bash
cd /home/neo/Projects/Personal/Pylux && ./android/build-local.sh --quick
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/metallic/chiaki/stream/gpu/GpuVideoRenderer.kt
git commit -m "feat(gpu): GpuVideoRenderer with vsync-paced SGSR1 present and AFME extrapolation"
```

---

### Task 8: Route the session through the renderer

**Files:**
- Modify: `android/app/src/main/java/com/metallic/chiaki/session/StreamSession.kt` (constructor line 22, fields 34-43, `shutdown` 52-80, both `session.setSurface(surface)` call sites ~193-195 and ~227-229, `attachToSurfaceView` 277-309, `metrics()` 344)
- Modify: `android/app/src/main/java/com/metallic/chiaki/stream/StreamViewModel.kt:20`

- [ ] **Step 1: Add the pipeline config and renderer fields to StreamSession**

Change the class header (line 22) to:
```kotlin
/** Which GPU features the session should use; null means the direct MediaCodec→SurfaceView path. */
data class GpuPipelineConfig(val upscaling: Boolean, val frameGeneration: Boolean)
{
	val enabled get() = upscaling || frameGeneration
}

class StreamSession(val connectInfo: ConnectInfo, val logManager: LogManager, val logVerbose: Boolean, val input: StreamInput,
	private val assets: android.content.res.AssetManager, private val gpuConfig: GpuPipelineConfig?)
```
Add imports at the top: `import com.metallic.chiaki.stream.gpu.GpuVideoRenderer`.

After `private var surface: Surface? = null` (line 35) add:
```kotlin
	private var surfaceWidth = 0
	private var surfaceHeight = 0
	private var gpuRenderer: GpuVideoRenderer? = null
	/** Non-null while the GPU path is active for the current session. */
	val gpuStats get() = gpuRenderer?.stats()
```

- [ ] **Step 2: Attach video output through one helper**

Add this method to `StreamSession` (after `resumeLocalConnection`):
```kotlin
	/**
	 * Points the native decoder at either the GPU renderer's input surface or the
	 * SurfaceView directly. Falls back to the direct path if the renderer cannot start.
	 */
	private fun attachVideoOutput(session: Session)
	{
		val cfg = gpuConfig
		if(cfg != null && cfg.enabled)
		{
			val vp = connectInfo.videoProfile
			val renderer = GpuVideoRenderer(assets, vp.width, vp.height, vp.maxFPS, cfg.upscaling, cfg.frameGeneration)
			if(renderer.start())
			{
				gpuRenderer = renderer
				renderer.setOutputSurface(surface, surfaceWidth, surfaceHeight)
				session.setSurface(renderer.inputSurface)
				Log.i("StreamSession", "video output: GPU path (upscale=${cfg.upscaling}, framegen=${cfg.frameGeneration})")
				return
			}
			Log.w("StreamSession", "video output: GPU path unavailable, using direct path")
		}
		val s = surface
		if(s != null)
			session.setSurface(s)
	}
```
Replace both occurrences of
```kotlin
				val surface = surface
				if(surface != null)
					session.setSurface(surface)
```
(in `resumePsnConnection` and `resumeLocalConnection`) with:
```kotlin
				attachVideoOutput(session)
```

- [ ] **Step 3: Release the renderer on shutdown**

In `shutdown()`, directly after `session?.stop()` (line 60) add:
```kotlin
			gpuRenderer?.release()
			gpuRenderer = null
```

- [ ] **Step 4: Route SurfaceHolder callbacks**

Replace the bodies of `surfaceChanged` and `surfaceDestroyed` in `attachToSurfaceView`:
```kotlin
			override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int)
			{
				Log.i("StreamSession", "surfaceChanged: ${width}x${height}, session=${session != null}, gpu=${gpuRenderer != null}")
				val surface = holder.surface
				this@StreamSession.surface = surface
				surfaceWidth = width
				surfaceHeight = height
				val renderer = gpuRenderer
				if(renderer != null)
					renderer.setOutputSurface(surface, width, height)
				else
					session?.setSurface(surface)
				Log.i("StreamSession", "surfaceChanged: done")
			}

			override fun surfaceDestroyed(holder: SurfaceHolder)
			{
				Log.i("StreamSession", "surfaceDestroyed: session=${session != null}, gpu=${gpuRenderer != null}, skipNativeCleanup=$skipNativeSurfaceCleanup")
				this@StreamSession.surface = null
				val renderer = gpuRenderer
				if(renderer != null)
				{
					// GPU path: the decoder keeps writing to the SurfaceTexture; only the EGL window goes away.
					renderer.setOutputSurface(null, 0, 0)
				}
				else if(!skipNativeSurfaceCleanup)
				{
					// Direct path, normal cleanup - session is being shut down or activity is finishing
					session?.setSurface(null)
					Log.i("StreamSession", "surfaceDestroyed: setSurface(null) done")
				}
				// Direct path with skipNativeSurfaceCleanup (PiP transition): don't call
				// setSurface(null) - it blocks the native decoder. The new surface
				// will be provided via surfaceChanged shortly after.
			}
```

- [ ] **Step 5: Pass the config from StreamViewModel**

`android/app/src/main/java/com/metallic/chiaki/stream/StreamViewModel.kt` line 20, replace with:
```kotlin
	val session = StreamSession(connectInfo, logManager, preferences.logVerbose, input, application.assets,
		GpuPipelineConfig(upscaling = preferences.gpuUpscalingEnabled, frameGeneration = preferences.frameGenerationEnabled))
```
Add `import com.metallic.chiaki.session.GpuPipelineConfig` if the file does not already import the whole package.

- [ ] **Step 6: Build, install, stream**

```bash
cd /home/neo/Projects/Personal/Pylux && ./android/build-local.sh --quick --install
```
Start a Remote Play session (1080p / 60 / 50000). Then:
```bash
./android/build-local.sh --logs-dump | grep -E 'GpuVideoRenderer|StreamSession: video output'
```
Expected: `video output: GPU path (upscale=true, framegen=true)`, `frame extrapolation supported=true requested=true`, `output surface 2560x1440 (stream 1920x1080, upscale=true, framegen=true)` (1440 because the 16:9 FIT layout on a 16:10 panel), and video visible on screen. No `GL error` lines.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/metallic/chiaki/session/StreamSession.kt android/app/src/main/java/com/metallic/chiaki/stream/StreamViewModel.kt
git commit -m "feat(session): route video through GpuVideoRenderer when enabled"
```

---

### Task 9: Display mode, frame-rate hint, stats overlay

**Files:**
- Modify: `android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt` (`onCreate` after `setContentView` ~line 116; `onResume` ~line 266; `formatStats` ~line 520)

- [ ] **Step 1: Apply the display mode and surface hint in onCreate**

Right after `setContentView(binding.root)` (line ~116) add:
```kotlin
		applyDisplayModePreference()
		binding.surfaceView.holder.addCallback(object: SurfaceHolder.Callback {
			override fun surfaceCreated(holder: SurfaceHolder) {}
			override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int)
			{
				if(wantsHighRefresh())
					DisplayModeHelper.hintSurfaceFrameRate(holder.surface, targetRefreshRate)
			}
			override fun surfaceDestroyed(holder: SurfaceHolder) {}
		})
```
Add these members to the activity (near the other private vals at the top of the class):
```kotlin
	/** Refresh rate the window asked for; used for the stats overlay and the surface hint. */
	private var targetRefreshRate = 60f

	private fun wantsHighRefresh() = viewModel.preferences.preferHighRefresh || viewModel.preferences.frameGenerationEnabled

	private fun applyDisplayModePreference()
	{
		if(!wantsHighRefresh()) return
		targetRefreshRate = DisplayModeHelper.applyHighestRefresh(this)
	}
```
In `onResume`, after `hideSystemUI()` add `applyDisplayModePreference()`.
Add `import android.view.SurfaceHolder` if missing (the file already imports `android.view.*` in some versions; check the import block).

- [ ] **Step 2: Extend the stats overlay**

In `formatStats`, before `return parts.joinToString(sep)` add:
```kotlin
		viewModel.session.gpuStats?.let { g ->
			parts.add(String.format(Locale.US, "out %.0f fps (gen %.0f)", g.presentedFps, g.generatedFps))
			parts.add(String.format(Locale.US, "%.0f Hz", targetRefreshRate))
		}
```

- [ ] **Step 3: Build, install, verify on the tablet**

```bash
cd /home/neo/Projects/Personal/Pylux && ./android/build-local.sh --quick --install
```
Stream, open the overlay, turn on the stats switch. Expected overlay tail: `out 120 fps (gen 60)   •   120 Hz` (values may wobble ±2). Logs:
```bash
./android/build-local.sh --logs-dump | grep DisplayMode
```
Expected: `Requested 2560x1600 @ 120.0 Hz (mode N)`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt
git commit -m "feat(stream): request 120 Hz display mode and show GPU path stats"
```

---

### Task 10: On-device verification matrix

**Files:** none (verification only; fix anything found and commit each fix separately).

Stream settings: 1080p, 60 fps, 50000 kbps, H265. Each row is a separate connect.

- [ ] **Step 1: Default (upscale on, framegen on, high refresh on)**
  - Logs: `frame extrapolation supported=true`, `Requested ... @ 120.0 Hz`.
  - Overlay: `60 FPS` from the stream, `out 120 fps (gen 60)`.
  - Visual: image sharper than the Play Store build; no tearing; pan the camera fast in a game and confirm only mild warping on generated frames.

- [ ] **Step 2: Upscale on, framegen off**
  - Expected log: `frame extrapolation supported=false requested=false` is NOT required; instead `requested=false`. Overlay `out 60 fps (gen 0)`.

- [ ] **Step 3: Upscale off, framegen on**
  - Expected log: `upscale=false, framegen=true`; overlay `out 120 fps (gen 60)`; image is bilinear (softer).

- [ ] **Step 4: Both off (direct path)**
  - Expected log: `video output: GPU path unavailable` must NOT appear; instead no `GpuVideoRenderer` lines at all and no `out ...` overlay segment. Behaviour identical to upstream.

- [ ] **Step 5: PiP and rotation on the GPU path**
  - Press Home during a stream → PiP window keeps playing; return → fullscreen resumes. Logs show `output surface ...` twice and no `setSurface(null)` on the GPU path.
  - Rotate the tablet 180° (landscape to reverse landscape) → stream continues.

- [ ] **Step 6: Disconnect / reconnect**
  - Disconnect button → `GpuVideoRenderer: released` logged, no crash. Reconnect → fresh `GL ... on Adreno (TM) 740` line.

- [ ] **Step 7: 15-minute thermal check**
  - Play for 15 minutes. `./android/build-local.sh --logs-dump | grep -iE 'thermal|throttl'` shows nothing from the app; overlay still reports ~120 out.

- [ ] **Step 8: Commit any fixes**

```bash
git add -A android/app/src && git commit -m "fix(gpu): <what was found on device>"
```

---

### Task 11: First release of pylux-noir

**Files:**
- Modify: `CMakeLists.txt:75-77` (only if a version bump is wanted; otherwise keep upstream 2.11.4)

- [ ] **Step 1: Push the branch**

```bash
cd /home/neo/Projects/Personal/Pylux && git push -u origin noir
```

- [ ] **Step 2: Build the signed release APK**

```bash
cd /home/neo/Projects/Personal/Pylux && ./android/build-local.sh --quick >/dev/null   # refreshes local.properties signing keys from secrets/android/
cd android && JAVA_HOME=~/.jdks/jdk-21.0.12.1+1 ./gradlew :app:assembleRelease
ls -la app/build/outputs/apk/release/
```
Expected: `app-release.apk` (signed by the noir keystore because `local.properties` has `chiakiKeystore`). Verify:
```bash
~/Android/Sdk/build-tools/35.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk | head -3
```
Expected: certificate with `CN=pylux-noir`.

- [ ] **Step 3: Install the release build on the tablet and smoke-test one stream**

```bash
adb install -r /home/neo/Projects/Personal/Pylux/android/app/build/outputs/apk/release/app-release.apk
```
Expected: installs alongside the Play Store Pylux (different app id), launches without an integrity dialog, streams at 120 Hz.

- [ ] **Step 4: Tag and publish**

```bash
cd /home/neo/Projects/Personal/Pylux
cp android/app/build/outputs/apk/release/app-release.apk /tmp/pylux-noir-arm64-release.apk
git tag v2.11.4-noir.1 && git push origin v2.11.4-noir.1
gh release create v2.11.4-noir.1 /tmp/pylux-noir-arm64-release.apk --repo 7tg/pylux-noir \
  --title "pylux-noir v2.11.4-noir.1" \
  --notes "Pylux 2.11.4 + Adreno Frame Motion Engine frame generation (GL_QCOM_frame_extrapolation), SGSR1 upscaling, and 120 Hz display mode for Snapdragon 8 Gen 2 devices (tested on Galaxy Tab S9 SM-X710). App id com.pylux.stream.noir, arm64-v8a only."
```
Expected: release URL printed. Add the repo to Obtainium on the tablet.

---

## Self-review

**Spec coverage:** fork identity/integrity/signing/release → Tasks 1, 11. FramePacer rules → Task 2. Display mode + frame-rate hint → Tasks 3, 9. Preferences + settings + unsupported summary → Tasks 4, 5. JNI shim + probe → Task 5. SGSR1 asset + license + shaders → Task 6. Renderer, textures, lifecycle, stats, GL error logging → Task 7. Session routing + fallback + PiP semantics → Task 8. Stats overlay → Task 9. Verification matrix incl. PiP, rotation, thermal → Task 10.

**Placeholder scan:** none.

**Type consistency:** `FramePacer(streamIntervalNs, frameGenerationEnabled)` / `decide(nowNs, newFrameAvailable)` / `reset()` used identically in Tasks 2 and 7. `DisplayModeHelper.applyHighestRefresh(Activity): Float` and `hintSurfaceFrameRate(Surface, Float)` match Tasks 3 and 9. `GpuVideoRenderer(assets, w, h, fps, upscaling, framegen)`, `start(): Boolean`, `setOutputSurface(Surface?, Int, Int)`, `stats(): Stats`, `release()`, `inputSurface` match Tasks 7 and 8. `GpuPipelineConfig(upscaling, frameGeneration)` matches Tasks 8 and StreamViewModel. `AdrenoExt.nativeProbeFrameExtrapolation()` / `nativeExtrapolateTex2D(Int, Int, Int, Float)` / `isFrameExtrapolationSupported()` match Tasks 5, 7 and the C symbol names `Java_com_metallic_chiaki_stream_gpu_AdrenoExt_*`.
