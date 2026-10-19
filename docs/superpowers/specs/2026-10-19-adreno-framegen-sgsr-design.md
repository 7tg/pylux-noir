# Adreno frame generation + SGSR upscaling for Pylux Android (Galaxy Tab S9)

Date: 2026-10-19
Status: approved design, pending implementation plan

## Goal

Make PS5 Remote Play in Pylux on a Samsung Galaxy Tab S9 (SM-X710, Snapdragon 8 Gen 2
for Galaxy, Adreno 740, 2560x1600 @ 120 Hz) display at 120 Hz with the stream itself
staying at its 60 fps maximum:

- Every other displayed frame is synthesized on the GPU with Qualcomm's Adreno Frame
  Motion Engine, exposed as the OpenGL ES extension `GL_QCOM_frame_extrapolation`.
- The 1080p stream is upscaled to the panel's native resolution with Snapdragon Game
  Super Resolution v1 (SGSR1), a single-pass BSD-3 fragment shader.
- The display is switched to its 120 Hz mode for the duration of the stream.

This is a personal fork (`github.com/7tg/pylux-noir`), released as GitHub releases for
Obtainium, following the same pattern as the user's `artemis-noir` fork.

## Non-goals

- Adreno Neural Fusion (ANF). It requires Snapdragon 8 Elite Gen 6 or newer and engine
  motion vectors and depth, none of which apply here.
- SGSR2 or any temporal upscaler (needs motion vectors).
- Interpolation using `GL_QCOM_motion_estimation` plus a custom warp shader. Possible
  later as a second mode; out of scope now.
- iOS, desktop (Qt), or lib/ changes. Everything lives in `android/`.
- HDR. Pylux Android does not stream HDR today.
- Upstreaming to ForWard Technologies. Keep changes tidy, but no PR is planned.

## Hardware facts this design relies on

| Fact | Source |
|------|--------|
| `GL_QCOM_frame_extrapolation` supported on Adreno 740 (75/75 reports) | dev.prineside.com GL extension database |
| `glExtrapolateTex2DQCOM(src1, src2, output, scaleFactor)`; all three textures same size/format; RGBA8 allowed; scale 0.5 = half an interval ahead | Khronos spec, OpenGL ES extension #333 |
| Qualcomm's own sample alternates real frame / extrapolated frame with scale 0.5 to double frame rate | SnapdragonGameStudios/adreno-gpu-opengl-es-code-sample-framework, `samples/amfe_power_saving` |
| SGSR1 needs one color texture plus `ViewportInfo = (1/w, 1/h, w, h)`; ~0.3 ms at 1240x576 on SD 8 Gen 2 | SnapdragonStudios/snapdragon-gsr, `sgsr/v1` |

Extension presence is still verified at runtime; nothing is assumed from the device model.

## Current Pylux Android video path (unchanged on the fallback path)

`StreamActivity` inflates a `SurfaceView` inside `AspectRatioFrameLayout`.
`StreamSession.attachToSurfaceView` forwards the `SurfaceHolder` surface to the native
session via `session.setSurface()`, which calls `AMediaCodec_configure(..., window)` /
`AMediaCodec_setOutputSurface` in `android/app/src/main/cpp/video-decoder.c`. The
decoder output thread releases every buffer with `render = true` immediately. There is
no GPU stage and no display-mode handling.

## Architecture (Option A: Kotlin renderer + C shim)

```
MediaCodec (native, unchanged)
   │  Surface(SurfaceTexture)                      ← new: instead of SurfaceView surface
   ▼
SurfaceTexture ──OnFrameAvailable──► GpuVideoRenderer thread (EGL/GLES 3.0)
                                        │ Choreographer vsync @ display rate
                                        │
                     ┌──────────────────┴──────────────────┐
                     │ new frame?                           │ no new frame & pacer says GENERATE
                     ▼                                      ▼
            updateTexImage                      glExtrapolateTex2DQCOM(older, newer, gen, 0.5)
            OES → ring[i] (RGBA8, stream res)              (via JNI shim, adreno-ext.c)
                     │                                      │
                     └──────────────► SGSR1 (or blit) ◄─────┘
                                        │
                                        ▼
                               EGL window surface on SurfaceView
```

### Components

| Component | Location | Responsibility |
|-----------|----------|----------------|
| `GpuVideoRenderer` | `android/app/src/main/java/com/metallic/chiaki/stream/gpu/GpuVideoRenderer.kt` | Owns render `HandlerThread`, EGL display/context/window surface, SurfaceTexture + OES texture, 2-slot RGBA8 ring + 1 generated texture (all stream resolution), shader programs, Choreographer callback, counters. Exposes `inputSurface: Surface`, `setOutputSurface(Surface?, w, h)`, `release()`, `stats()`. |
| `FramePacer` | `.../stream/gpu/FramePacer.kt` | Pure Kotlin, stateful decision logic owned by the renderer: constructed with `(streamIntervalNs, frameGenerationEnabled)`, `decide(nowNs, newFrameAvailable)` returns `PRESENT_REAL`, `PRESENT_GENERATED`, or `SKIP` and tracks real-frame count, last real timestamp and whether the last presented frame was generated internally. After a stall (previous real frame older than 1.5 intervals) it waits for a fresh consecutive pair before generating again. No GL imports. Unit tested. |
| `GlShaders` | `.../stream/gpu/GlShaders.kt` | Compiles/links: OES copy (applies SurfaceTexture transform matrix), SGSR1 upscale, plain blit. Loads SGSR1 source from `assets/shaders/sgsr1_shader_mobile.frag` (verbatim from Qualcomm, BSD-3 header kept). |
| `AdrenoExt` | `.../stream/gpu/AdrenoExt.kt` + `android/app/src/main/cpp/adreno-ext.c` | JNI: `nativeProbeFrameExtrapolation(): Boolean` (checks `GL_EXTENSIONS` for the string and `eglGetProcAddress("glExtrapolateTex2DQCOM") != NULL` on the current context) and `nativeExtrapolateTex2D(src1, src2, out, scale)`. Both must be called on the render thread. Links `GLESv3` and `EGL`. |
| `DisplayModeHelper` | `.../stream/DisplayModeHelper.kt` | Picks the `Display.Mode` with the same physical size as the current mode and the highest refresh rate, sets `window.attributes.preferredDisplayModeId`, and on API 30+ calls `Surface.setFrameRate(120f, FRAME_RATE_COMPATIBILITY_DEFAULT, CHANGE_FRAME_RATE_ALWAYS)` (2-arg form on Android 11) on the SurfaceView surface. Returns the chosen rate for logging/stats. |
| `Preferences` additions | `.../common/Preferences.kt`, `res/xml/preferences.xml`, `res/values/strings.xml` | `gpuUpscalingEnabled` (default true), `frameGenerationEnabled` (default true), `preferHighRefreshRate` (default true). |
| `StreamSession` routing | `.../session/StreamSession.kt` | Decides per session: GPU path if `gpuUpscalingEnabled || frameGenerationEnabled`, else direct path. On GPU path, passes `renderer.inputSurface` to `session.setSurface()` and forwards SurfaceHolder changes to `renderer.setOutputSurface()`. |
| Stats overlay | `StreamActivity.updateStatsOverlay()` | Appends `out <presentedFps> FPS (gen <generatedFps>)` and the active display refresh rate. |

### Data flow per vsync (render thread)

1. Choreographer delivers `frameTimeNanos`.
2. `newFrame = frameAvailable.getAndSet(false)` (set by `OnFrameAvailableListener`, which
   posts to the render handler).
3. `decision = pacer.decide(...)`.
4. `PRESENT_REAL`: `surfaceTexture.updateTexImage()`; read transform matrix; draw OES
   texture into `ring[next]` FBO with the copy shader; `ring` index flips;
   (the pacer updated its own real-frame bookkeeping inside `decide`); present `ring[newest]`.
5. `PRESENT_GENERATED`: `AdrenoExt.nativeExtrapolateTex2D(ring[older], ring[newer], gen, 0.5f)`;
   `generated++`; present `gen`.
6. `SKIP`: return without swapping.
7. "Present" = bind default framebuffer, viewport = window surface size, draw full-screen
   triangle with SGSR1 (`ps0` = source texture, `ViewportInfo = (1/sw, 1/sh, sw, sh)` where
   `sw,sh` = stream resolution) or the blit shader when upscaling is off, then
   `eglSwapBuffers`. `presented++`.

### FramePacer rules (priority order)

1. `newFrameAvailable` → `PRESENT_REAL`. A real frame always beats a prediction.
2. `frameGenerationEnabled && realFramesSeen >= 2 && !lastPresentedWasGenerated &&
   (nowNs - lastRealNs) < 1.5 * streamIntervalNs` → `PRESENT_GENERATED`. `realFramesSeen`
   counts consecutive fresh real frames: a real frame arriving 1.5 intervals or more after
   the previous one restarts the count at 1, so the two ring slots fed to the extrapolator
   are never a stale pair.
3. Otherwise → `SKIP`.

Consequences: at most one generated frame between two real frames; a stalled or paused
stream shows its last real frame rather than a chain of predictions; on a 120 Hz display
fed by a 60 fps stream the steady state is real, generated, real, generated.
`streamIntervalNs` comes from the session's FPS preset (60 → 16.67 ms, 30 → 33.3 ms).

### Textures and formats

- Ring slots and generated texture: `GL_RGBA8`, `stream width x stream height` taken from
  `connectInfo.videoProfile`. The extension requires identical size and format for all
  three, and RGBA8 is in its supported list.
- OES copy uses the SurfaceTexture transform matrix, so decoder cropping/padding is
  respected regardless of the actual buffer size.
- Sampling: `GL_LINEAR`, `CLAMP_TO_EDGE`. SGSR1 uses `textureGather`, so the input must
  be a plain 2D texture (which the ring slots are).

### Display mode

Applied in `StreamActivity.onCreate` (and re-applied in `onResume`) when
`frameGenerationEnabled || preferHighRefreshRate`. The window attribute is per-window, so
leaving the activity restores the system default. The requested mode is logged with tag
`DisplayMode`; the switch is asynchronous and may be refused (user peak-refresh setting,
power saver), so the stats overlay shows the display's measured current refresh rate, not
the requested one.

### Surface lifecycle on the GPU path

- `surfaceChanged` → `renderer.setOutputSurface(holder.surface, w, h)`: destroys and
  recreates only the EGL window surface. The SurfaceTexture, context, and ring textures
  survive, so the decoder keeps running.
- `surfaceDestroyed` → `renderer.setOutputSurface(null, 0, 0)`; Choreographer callbacks
  keep consuming frames (updateTexImage) so the decoder never blocks; nothing is drawn.
- PiP enter/exit and rotation therefore never call native `setSurface` on the GPU path.
  `skipNativeSurfaceCleanup` remains in place for the direct path only.
- Session stop: native `setSurface(null)` first (decoder stops using the SurfaceTexture),
  then `renderer.release()` (Choreographer removed, GL objects deleted, EGL torn down,
  SurfaceTexture released, thread quit).

### Probe for the settings screen

`AdrenoExt.isFrameExtrapolationSupported(context)` creates a throwaway EGL pbuffer
context on the calling thread, runs the native probe, tears it down, and caches the
boolean in memory for the process lifetime. The settings fragment disables the frame
generation switch and sets its summary to "Not supported on this device" when false.
The renderer also re-runs the native probe on its own context at startup and treats a
negative result as "generation off".

## Failure handling

| Failure | Behaviour |
|---------|-----------|
| EGL init, context, or window surface creation fails | Log cause; `StreamSession` falls back to the direct path for this session (passes the SurfaceView surface to native `setSurface`). |
| OES copy or blit shader compile/link fails | Same fallback as above (logged with the GL info log). |
| SGSR1 shader compile/link fails | Upscaling degrades to a bilinear blit; frame generation unaffected (logged). |
| Extension missing or `eglGetProcAddress` null | Frame generation off, upscaling on. Logged once. |
| `glGetError` after a pass | Logged once per error code per session; rendering continues. |
| `updateTexImage` throws (surface abandoned) | Treated as session end; renderer stops drawing. |
| No `Display.Mode` with higher refresh | Keep default mode; log. |

## Settings UI

Stream category of `preferences.xml`, after Codec:

| Key (string resource) | Type | Default | Title |
|---|---|---|---|
| `preferences_gpu_upscaling_enabled_key` | SwitchPreference | true | GPU upscaling (Snapdragon GSR) |
| `preferences_frame_generation_enabled_key` | SwitchPreference | true | Frame generation (Adreno Frame Motion Engine) |
| `preferences_prefer_high_refresh_key` | SwitchPreference | true | Prefer high refresh rate display mode |

Summaries explain in one line what each does. Changing them takes effect on the next
stream (same as resolution/fps today).

## Fork setup

| Item | Decision |
|------|----------|
| GitHub repo | `7tg/pylux-noir`, created with `gh repo fork` from ForWard-Technologies-LLC/Pylux; `origin` = fork, `upstream` = ForWard |
| Branch | `noir` off `master`; releases tagged `vX.Y.Z-noir.N` |
| applicationId | `com.pylux.stream.noir` (namespace stays `com.pylux.stream`, so no import changes) |
| File provider | manifest authority `${applicationId}.fileprovider`; `LogManager.fileProviderAuthority` built from `BuildConfig.APPLICATION_ID` |
| Play integrity check | `buildConfigField "boolean", "INTEGRITY_CHECK", "false"`; `AppIntegrityManager.validateAppState` returns true immediately when false |
| Signing | new keystore `~/keystores/pylux-noir.jks`, alias `pylux-noir`; configured through `android/local.properties` keys `chiakiKeystore`, `chiakiKeystorePW`, `chiakiKeyAlias`, `chiakiKeyPW` (already read by `build.gradle`); `local.properties` stays untracked |
| Build | `./android/build-local.sh --quick --install` for iteration; release APK via gradle `assembleRelease` with `abiFilters` narrowed to `arm64-v8a` for the fork |
| Release | `gh release create` with the arm64 release APK as the asset; Obtainium tracks the fork repo |
| App name | "Pylux Noir" so both installs are distinguishable |

## Testing

The user's stream settings, used for every on-device check: 1080p, 60 fps, 50000 kbps.

Unit (JUnit, no device):
- `FramePacerTest`: real frame wins over generation; generation only after two real
  frames; never two generated in a row; no generation when last real frame is older than
  1.5 intervals; generation disabled flag; 30 fps interval.
- `DisplayModeHelperTest`: mode selection picks same-size highest-rate mode and returns
  null when none is better (uses a plain `DisplayModeInfo` data class, so no Android
  types are needed on the JVM).

On device (Tab S9, `./android/build-local.sh --quick --install` then `--logs-dump`):
1. Logcat shows `GpuVideoRenderer: frame extrapolation supported=true` and
   `DisplayMode: Requested 2560x1600 @ 120.0 Hz`.
2. Stats overlay reads approximately `out 120 FPS (gen 60)` during a 60 fps stream.
3. Visual: upscaled image is sharper than the direct path; generated frames show no
   tearing or flashing; fast camera pans show at most mild warping.
4. Toggle each switch off and reconnect: upscale-only, generation-only, and direct path
   each work.
5. PiP enter and exit, device rotation, and disconnect/reconnect do not stall the decoder
   (no `setSurface` blocking logs on the GPU path).
6. Thermal/power sanity: 15-minute session, no thermal throttling warnings in logcat.

## Risks and accepted trade-offs

- Extrapolation guesses the future; scene cuts and very fast motion can warp for one
  frame. Accepted; the switch lets the user disable it.
- The extension is a vendor black box with no documented cost figure for 1080p; if it
  exceeds the ~7 ms budget at 120 Hz the pacer naturally drops to real frames only
  (rule 1 always wins). Measured on device during testing.
- The Choreographer-driven path adds up to one 120 Hz vsync (8.3 ms) between decode and
  present, comparable to the 60 Hz direct path today.
