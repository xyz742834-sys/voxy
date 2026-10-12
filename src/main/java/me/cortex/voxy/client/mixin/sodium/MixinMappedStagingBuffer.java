package me.cortex.voxy.client.mixin.sodium;

import com.mojang.blaze3d.buffers.GpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Sodium's staging ring splits an upload that does not fit before its end; when the previous upload
 * ended exactly at the end ({@code pos == capacity}) the first part is empty, and Sodium queues a
 * zero-byte copy. GL takes that as a no-op; Vulkan does not (VUID-VkBufferCopy-size-01988, seen on
 * Minecraft's Vulkan backend in runs 20261010T023920-410812Z and 20261012T105033-302901Z). Dropping the
 * empty transfer is what GL does with it: nothing is written and nothing is copied.
 */
@Mixin(targets = {"net.caffeinemc.mods.sodium.client.gpu.arena.staging.MappedStagingBuffer"}, remap = false)
public class MixinMappedStagingBuffer {
    @Inject(method = "addTransfer", at = @At("HEAD"), cancellable = true)
    private void voxy$dropEmptyTransfer(ByteBuffer data, GpuBuffer dst, long readOffset, long writeOffset, CallbackInfo ci) {
        if (!data.hasRemaining()) ci.cancel();
    }
}
