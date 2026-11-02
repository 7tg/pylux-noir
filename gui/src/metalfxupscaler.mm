#include "metalfxupscaler.h"

#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_metal.h>

#include <QLoggingCategory>

Q_DECLARE_LOGGING_CATEGORY(chiakiGui);

struct MetalFxUpscaler
{
	pl_vulkan vk;
	PFN_vkExportMetalObjectsEXT exportMetalObjects;
	id<MTLDevice> device;
	id<MTLCommandQueue> queue;
	id<MTLFXSpatialScaler> scaler;
	// Timeline semaphore shared with Metal (MoltenVK backs it with an MTLSharedEvent): Vulkan
	// signals when libplacebo is done with the textures, Metal signals when MetalFX is done.
	VkSemaphore sem;
	id<MTLSharedEvent> event;
	uint64_t value;
	NSUInteger in_w, in_h, out_w, out_h;
	MTLPixelFormat in_fmt, out_fmt;
};

static id<MTLTexture> metal_texture(MetalFxUpscaler *u, pl_tex tex)
{
	VkImage image = pl_vulkan_unwrap(u->vk->gpu, tex, nullptr, nullptr);
	if(!image)
		return nil;
	VkExportMetalTextureInfoEXT info = {};
	info.sType = VK_STRUCTURE_TYPE_EXPORT_METAL_TEXTURE_INFO_EXT;
	info.image = image;
	info.plane = VK_IMAGE_ASPECT_PLANE_0_BIT;
	VkExportMetalObjectsInfoEXT objects = {};
	objects.sType = VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT;
	objects.pNext = &info;
	u->exportMetalObjects(u->vk->device, &objects);
	return info.mtlTexture;
}

MetalFxUpscaler *metalfx_create(pl_vulkan vk)
{
	auto get_device_proc = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
		vk->get_proc_addr(vk->instance, "vkGetDeviceProcAddr"));
	auto export_fn = get_device_proc ? reinterpret_cast<PFN_vkExportMetalObjectsEXT>(
		get_device_proc(vk->device, "vkExportMetalObjectsEXT")) : nullptr;
	if(!export_fn)
	{
		qCWarning(chiakiGui) << "MetalFX: vkExportMetalObjectsEXT unavailable";
		return nullptr;
	}

	VkExportMetalDeviceInfoEXT device_info = {};
	device_info.sType = VK_STRUCTURE_TYPE_EXPORT_METAL_DEVICE_INFO_EXT;
	VkExportMetalObjectsInfoEXT objects = {};
	objects.sType = VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT;
	objects.pNext = &device_info;
	export_fn(vk->device, &objects);
	id<MTLDevice> device = device_info.mtlDevice;
	if(!device || ![MTLFXSpatialScalerDescriptor supportsDevice:device])
	{
		qCWarning(chiakiGui) << "MetalFX: spatial scaler not supported on this device";
		return nullptr;
	}

	pl_vulkan_sem_params sem_params = {};
	sem_params.type = VK_SEMAPHORE_TYPE_TIMELINE;
	VkSemaphore sem = pl_vulkan_sem_create(vk->gpu, &sem_params);
	VkExportMetalSharedEventInfoEXT event_info = {};
	event_info.sType = VK_STRUCTURE_TYPE_EXPORT_METAL_SHARED_EVENT_INFO_EXT;
	event_info.semaphore = sem;
	VkExportMetalObjectsInfoEXT event_objects = {};
	event_objects.sType = VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT;
	event_objects.pNext = &event_info;
	if(sem)
		export_fn(vk->device, &event_objects);
	if(!event_info.mtlSharedEvent)
	{
		qCWarning(chiakiGui) << "MetalFX: can't share a timeline semaphore with Metal";
		pl_vulkan_sem_destroy(vk->gpu, &sem);
		return nullptr;
	}

	auto u = new MetalFxUpscaler();
	u->vk = vk;
	u->exportMetalObjects = export_fn;
	u->device = device;
	u->queue = [device newCommandQueue];
	u->sem = sem;
	u->event = event_info.mtlSharedEvent;
	u->value = 0;
	qCInfo(chiakiGui) << "MetalFX: spatial scaler ready on" << QString::fromNSString(device.name);
	return u;
}

void metalfx_destroy(MetalFxUpscaler **upscaler)
{
	pl_vulkan_sem_destroy((*upscaler)->vk->gpu, &(*upscaler)->sem);
	delete *upscaler; // ARC releases the Metal objects
	*upscaler = nullptr;
}

bool metalfx_upscale(MetalFxUpscaler *u, pl_tex in, pl_tex out)
{
	id<MTLTexture> in_tex = metal_texture(u, in);
	id<MTLTexture> out_tex = metal_texture(u, out);
	if(!in_tex || !out_tex)
		return false;

	if(!u->scaler || u->in_w != in_tex.width || u->in_h != in_tex.height
		|| u->out_w != out_tex.width || u->out_h != out_tex.height
		|| u->in_fmt != in_tex.pixelFormat || u->out_fmt != out_tex.pixelFormat)
	{
		MTLFXSpatialScalerDescriptor *desc = [MTLFXSpatialScalerDescriptor new];
		desc.inputWidth = in_tex.width;
		desc.inputHeight = in_tex.height;
		desc.outputWidth = out_tex.width;
		desc.outputHeight = out_tex.height;
		desc.colorTextureFormat = in_tex.pixelFormat;
		desc.outputTextureFormat = out_tex.pixelFormat;
		// Input stays in the stream's own transfer (gamma or PQ), i.e. perceptual.
		desc.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;
		u->scaler = [desc newSpatialScalerWithDevice:u->device];
		if(!u->scaler)
		{
			qCWarning(chiakiGui) << "MetalFX: failed to create scaler";
			return false;
		}
		u->in_w = in_tex.width; u->in_h = in_tex.height;
		u->out_w = out_tex.width; u->out_h = out_tex.height;
		u->in_fmt = in_tex.pixelFormat; u->out_fmt = out_tex.pixelFormat;
		qCInfo(chiakiGui) << "MetalFX:" << in_tex.width << "x" << in_tex.height << "->"
			<< out_tex.width << "x" << out_tex.height << "format" << (int)in_tex.pixelFormat
			<< "in usage" << (unsigned)in_tex.usage << "needs" << (unsigned)u->scaler.colorTextureUsage
			<< "out usage" << (unsigned)out_tex.usage << "needs" << (unsigned)u->scaler.outputTextureUsage
			<< "out storage" << (unsigned)out_tex.storageMode;
	}

	pl_gpu gpu = u->vk->gpu;
	// Timeline values must strictly increase per signal: one per hold, one for Metal.
	uint64_t vk_done = 0;
	for(pl_tex tex : { in, out })
	{
		pl_vulkan_hold_params hold = {};
		hold.tex = tex;
		hold.layout = VK_IMAGE_LAYOUT_GENERAL;
		hold.qf = VK_QUEUE_FAMILY_IGNORED;
		hold.semaphore = { u->sem, vk_done = ++u->value };
		if(!pl_vulkan_hold_ex(gpu, &hold))
			return false;
	}
	const uint64_t mtl_done = ++u->value;

	u->scaler.colorTexture = in_tex;
	u->scaler.outputTexture = out_tex;
	u->scaler.inputContentWidth = in_tex.width;
	u->scaler.inputContentHeight = in_tex.height;
	id<MTLCommandBuffer> cmd = [u->queue commandBuffer];
	[cmd encodeWaitForEvent:u->event value:vk_done];
	[u->scaler encodeToCommandBuffer:cmd];
	[cmd encodeSignalEvent:u->event value:mtl_done];
	[cmd commit];

	for(pl_tex tex : { in, out })
	{
		pl_vulkan_release_params release = {};
		release.tex = tex;
		release.layout = VK_IMAGE_LAYOUT_GENERAL;
		release.qf = VK_QUEUE_FAMILY_IGNORED;
		release.semaphore = { u->sem, mtl_done };
		pl_vulkan_release_ex(gpu, &release);
	}
	return true;
}
