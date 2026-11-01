# macOS MetalFX upscaling — work in progress (paused 2026-11-02)

Goal: Apple's MetalFX spatial upscaler as an option in the Mac app (Settings → MetalFX Upscaling,
Ctrl+U in stream). Frame generation dropped: the MacBook Air M2 panel is 60 Hz.

## Done
- `gui/src/metalfxupscaler.mm` / `gui/include/metalfxupscaler.h`: MetalFX spatial scaler over
  libplacebo textures via `VK_EXT_metal_objects` (enabled in `opt_dev_extensions` on macOS).
- `QmlMainWindow::renderMetalFx`: stream → native-size RGBA (source colour space) → MetalFX →
  1:1 pass onto the video rect with the UI overlay; falls back to the normal path (Zoom mode,
  window not bigger than the stream, MetalFX unavailable). Setting `settings/metalfx_upscaling`.
- Homebrew drift fixes so the Mac app builds at all: FFmpeg 8 `AVVulkanDeviceContext.qf[]`,
  libplacebo `pl_frame_mix::timestamps` as double.
- Builds on the MacBook Air M2 (macOS 27, CLT only) and launches; MoltenVK exposes
  `VK_EXT_metal_objects`.

## Blocker (not caused by MetalFX)
The app freezes before the first video frame: the render thread waits forever in
`pl_swapchain_colorspace_hint` → `vkWaitForFences` (retired-swapchain present fences under
MoltenVK + VK_EXT_swapchain_maintenance1). Same with MoltenVK 1.2.9 and 1.4.2. Fix: call the
hint only when the colour space changes — with it the app launches and the UI renders (render
thread idle, 2026-11-02). Not yet tested with a stream (PS5 was fully off). The bundle on the Mac
currently carries MoltenVK 1.4.2 copied in by hand; build-macos.sh still pins 1.2.9.

## Next
1. Start a stream (PS5 in rest mode or on; wake via raw WAKEUP packet) and confirm video shows.
2. Once video shows: check the MetalFX log line (texture usage/storage), compare on/off with
   Ctrl+U, measure frame time.
3. Mac setup: repo at `~/src/pylux-noir` on the MacBook (`barbarosgoren@192.168.31.32`),
   test config reuses chiaki-ng's PS5-709 registration.
