package me.cortex.voxy.client.core.vk.mcnative;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.nio.ByteOrder;

/**
 * Minecraft's block atlas, read through Minecraft's own Blaze3D API on its Vulkan device —
 * no GL (native instance mode, EXPERIMENTAL).
 *
 * <p>Voxy's model bakery samples the block atlas in software; its only GL use was reading the
 * atlas pixels ({@code SoftwareModelTextureBakery.setupTexture}). On Minecraft's Vulkan backend
 * that aborted the JVM. Here the pixels are copied with {@code copyTextureToBuffer} (mip 0) and
 * handed to the bakery when Minecraft's callback runs — a later frame, so the real-section
 * experiment skips samples ("atlas-pending") until it has arrived.
 */
public final class McNativeAtlas {
    public enum State { NOT_REQUESTED, PENDING, READY, FAILED }

    private static volatile State state = State.NOT_REQUESTED;
    private static volatile String failure;
    private static volatile int width, height;
    /** The atlas GpuTexture the current pixels came from (identity), and how often it was read. */
    private static volatile Object source;
    private static volatile int reads, closeFailures, generation;

    private McNativeAtlas() {}

    public static State state() { return state; }
    public static String failure() { return failure; }
    public static int width() { return width; }
    public static int height() { return height; }
    public static int reads() { return reads; }
    public static int closeFailures() { return closeFailures; }
    /** Bumped each time the supplied pixels change; a scene baked with an older generation is stale. */
    public static int generation() { return generation; }

    /**
     * Round-20 review R20-ATLAS-RELOAD: the atlas was read once and cached for the JVM. If Minecraft
     * has replaced the block-atlas texture (a resource reload creates a new one), drop the cached
     * pixels and read again. Returns true when a refresh was started.
     */
    public static boolean refreshIfReplaced() {
        Object current = currentAtlasTexture();
        if (current == null || state == State.PENDING || state == State.NOT_REQUESTED) return false;
        if (current == source) return false;
        reset();
        requestOnce();
        return true;
    }

    /** Forget the pixels (disconnect, replacement): the bakery falls back to "not supplied". */
    public static void reset() {
        SoftwareModelTextureBakery.supplyAtlas(null);
        source = null;
        state = State.NOT_REQUESTED;
        failure = null;
    }

    private static Object currentAtlasTexture() {
        try {
            return Minecraft.getInstance().getTextureManager()
                .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"))
                .getTexture();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Request the copy once (render thread). */
    public static void requestOnce() {
        if (state != State.NOT_REQUESTED) return;
        state = State.PENDING;
        GpuBuffer buffer = null;
        try {
            var tex = Minecraft.getInstance().getTextureManager()
                .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"))
                .getTexture();
            int w = tex.getWidth(0), h = tex.getHeight(0);
            long bytes = (long) w * h * 4;
            var gpu = RenderSystem.getDevice();
            buffer = gpu.createBuffer(() -> "voxy native block atlas readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            final GpuBuffer readback = buffer;
            final Object from = tex;
            gpu.createCommandEncoder().copyTextureToBuffer(tex, readback, 0,
                () -> receive(readback, w, h, from), 0);
            buffer = null;
            Logger.info("[native-vk] requested the block atlas (" + w + "x" + h + ") through Blaze3D");
        } catch (Throwable t) {
            failure = "the block-atlas copy could not be requested: " + t;
            state = State.FAILED;
        } finally {
            if (buffer != null) {
                try { buffer.close(); } catch (Throwable t) { closeFailures++; }
            }
        }
    }

    private static void receive(GpuBuffer buffer, int w, int h, Object from) {
        try (var view = new GpuBufferSlice(buffer, 0, buffer.size()).map(true, false)) {
            var data = view.data().order(ByteOrder.LITTLE_ENDIAN);
            int[] pixels = new int[w * h];
            boolean anyOpaque = false;
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = data.getInt(i * 4);
                anyOpaque |= (pixels[i] & 0xFF000000) != 0;
            }
            if (!anyOpaque) {
                failure = "the block atlas read back fully transparent (" + w + "x" + h + ")";
                state = State.FAILED;
                return;
            }
            SoftwareModelTextureBakery.supplyAtlas(new SoftwareModelTextureBakery.AtlasPixels(pixels, w, h));
            width = w;
            height = h;
            source = from;
            reads++;
            generation++;
            state = State.READY;
            Logger.info("[native-vk] block atlas read through Blaze3D: " + w + "x" + h);
        } catch (Throwable t) {
            failure = "the block-atlas readback failed: " + t;
            state = State.FAILED;
        } finally {
            try { buffer.close(); } catch (Throwable t) { closeFailures++; }
        }
    }
}
