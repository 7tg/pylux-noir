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

## Status (2026-11-02): working on the MacBook Air M2
- Stream from the PS5 Pro (1080p) on the 3420x2214 Retina window: `renderMetalFx` →
  `metalfx_upscale` in the render path every frame; PS5 home screen text visibly sharper than
  libplacebo's default upscale, edge variance 283 vs 225, no colour shift.
- Two freezes fixed on the way:
  1. `pl_swapchain_colorspace_hint` blocked forever on MoltenVK (PASS_THROUGH colour space →
     swapchain recreate → waits on never-signalled present fences). Now hinted only on change,
     and on macOS only for HDR.
  2. A CPU `pl_gpu_finish` before MetalFX deadlocked on the pending swapchain frame. Sync is now
     GPU-only: `pl_vulkan_hold_ex` both textures behind a timeline semaphore exported as an
     `MTLSharedEvent`, Metal waits/signals it, `pl_vulkan_release_ex` hands them back.

## Open
- HDR untested (on macOS the colour-space hint is only used for HDR, which can still hit freeze 1).
- The test bundle carries MoltenVK 1.4.2 copied in by hand; build-macos.sh pins 1.2.9 (untested
  with these fixes).
- One `stream ... --fullscreen` CLI run never opened a connection; plain `stream` runs were fine.
- Mac setup: `~/src/pylux-noir` on the MacBook (`barbarosgoren@192.168.31.32`); test config reuses
  chiaki-ng's PS5-709 registration; wake the PS5 with a raw WAKEUP packet first.
