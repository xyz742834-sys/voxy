package me.cortex.voxy.client.core.vk.mcnative;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;

/**
 * <b>Minecraft's lightmap, for the native scene</b>. GL Voxy samples Minecraft's lightmap texture
 * directly ({@code LightMapHelper}: {@code gameRenderer.levelLightmap()}); natively it is a Blaze3D
 * texture on Minecraft's device, so it is copied out with {@code copyTextureToBuffer} — one 16x16
 * copy in flight at a time — and the newest pixels are uploaded into the scene's own lightmap
 * ({@code VkTerrainResources.setLightmap}) in Voxy's submission. Until the first copy arrives the
 * scene keeps its uniform synthetic lightmap.
 */
public final class McNativeLightmap {
    private static final int SIZE = 16;
    private static byte[] latest;
    private static long reads, sequence;
    private static int inFlight, closeFailures;
    private static String failure, format;

    private McNativeLightmap() {}

    /** Request a copy of this frame's lightmap unless one is in flight (render thread). */
    public static void request() {
        if (inFlight > 0 || failure != null) return;
        GpuBuffer buffer = null;
        try {
            var tex = Minecraft.getInstance().gameRenderer.levelLightmap().texture();
            format = String.valueOf(tex.getFormat());
            if (tex.getWidth(0) != SIZE || tex.getHeight(0) != SIZE
                    || tex.getFormat() != com.mojang.blaze3d.GpuFormat.RGBA8_UNORM) {
                failure = "Minecraft's lightmap is " + tex.getWidth(0) + "x" + tex.getHeight(0) + " "
                    + tex.getFormat() + ", not 16x16 RGBA8";
                Logger.warn("[native-vk] " + failure + "; keeping the synthetic lightmap");
                return;
            }
            var gpu = RenderSystem.getDevice();
            buffer = gpu.createBuffer(() -> "voxy native lightmap readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, SIZE * SIZE * 4);
            final GpuBuffer readback = buffer;
            inFlight++;
            gpu.createCommandEncoder().copyTextureToBuffer(tex, readback, 0, () -> receive(readback), 0);
            buffer = null;
        } catch (Throwable t) {
            failure = "the lightmap copy could not be requested: " + t;
            Logger.warn("[native-vk] " + failure);
        } finally {
            if (buffer != null) {
                inFlight--;
                try { buffer.close(); } catch (Throwable t) { closeFailures++; }
            }
        }
    }

    private static void receive(GpuBuffer buffer) {
        try (var view = new GpuBufferSlice(buffer, 0, buffer.size()).map(true, false)) {
            var data = view.data();
            byte[] px = new byte[SIZE * SIZE * 4];
            data.get(0, px);
            synchronized (McNativeLightmap.class) {
                latest = px;
                sequence++;
                reads++;
            }
        } catch (Throwable t) {
            failure = "the lightmap readback failed: " + t;
        } finally {
            inFlight--;
            try { buffer.close(); } catch (Throwable t) { closeFailures++; }
        }
    }

    /** The newest pixels if newer than {@code seenSequence}, else null. */
    static synchronized byte[] newerThan(long seenSequence) {
        return sequence > seenSequence ? latest : null;
    }

    static synchronized long sequence() { return sequence; }
    public static long reads() { return reads; }
    public static int inFlight() { return inFlight; }
    public static int closeFailures() { return closeFailures; }
    public static String failure() { return failure; }
    public static String format() { return format; }
}
