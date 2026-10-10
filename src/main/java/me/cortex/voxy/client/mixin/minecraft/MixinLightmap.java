package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.Lightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The native path reads Minecraft's lightmap back (McNativeLightmap, GL Voxy samples it directly);
 * Minecraft 26.2 creates it with usage 13 (copy-dst, binding, attachment), and a copy out needs
 * copy-src too (VUID-vkCmdCopyImageToBuffer-srcImage-00186). Only with a native flag set; the
 * texture is otherwise unchanged.
 */
@Mixin(Lightmap.class)
public class MixinLightmap {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"),
        index = 1)
    private int voxy$copyableLightmap(int usage) {
        boolean nativeOn = Boolean.getBoolean(me.cortex.voxy.client.core.vk.mcnative.McNativeHierarchicalLoad.FLAG)
            || me.cortex.voxy.client.core.vk.mcnative.McNativeRender.on();
        return nativeOn ? usage | GpuTexture.USAGE_COPY_SRC : usage;
    }
}
