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
MoltenVK + VK_EXT_swapchain_maintenance1). Same with MoltenVK 1.2.9 and 1.4.2. Last change
(hint only when the colour space changes) was built but not tested.

## Next
1. Test the hint-on-change fix; otherwise avoid swapchain recreation before the first present.
2. Once video shows: check the MetalFX log line (texture usage/storage), compare on/off with
   Ctrl+U, measure frame time.
3. Mac setup: repo at `~/src/pylux-noir` on the MacBook (`barbarosgoren@192.168.31.32`),
   test config reuses chiaki-ng's PS5-709 registration.
