#pragma once

// Apple MetalFX spatial upscaling for the libplacebo/MoltenVK render path (macOS only).
// libplacebo renders the stream at its own resolution into `in`; MetalFX scales it into
// `out`, reaching the Metal textures behind the Vulkan images via VK_EXT_metal_objects.

extern "C" {
#include <libplacebo/vulkan.h>
}

struct MetalFxUpscaler;

// Null when the device can't run MetalFX or MoltenVK lacks VK_EXT_metal_objects.
MetalFxUpscaler *metalfx_create(pl_vulkan vk);
void metalfx_destroy(MetalFxUpscaler **upscaler);

// Runs the scaler over the whole of `in` into the whole of `out`. GPU-side ordering only: the
// textures are held from libplacebo behind a timeline semaphore that Metal waits on, and
// released back once Metal signals it. Never blocks the CPU (a pl_gpu_finish here deadlocked
// on the pending swapchain frame under MoltenVK).
bool metalfx_upscale(MetalFxUpscaler *upscaler, pl_tex in, pl_tex out);
