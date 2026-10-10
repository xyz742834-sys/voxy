package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import net.minecraft.core.SectionPos;

/**
 * <b>The vanilla sections the native depth bound is drawn from</b> — the native counterpart of
 * {@code VoxyRenderSystem.visbleSectionStream} ({@code StreamedBoundStore}), fed by the same Sodium
 * mixins at the same points (reset in {@code RenderSectionManager}, a put per built, visible
 * section in the visible-chunk collectors), only while native rendering runs and no GL renderer
 * exists. {@link McNativeHierarchicalLoad} copies it into the scene each frame.
 */
public final class McNativeBounds {
    private static int[] xyz = new int[3 * 4096];
    private static int count;

    private McNativeBounds() {}

    /** Whether the mixins should feed this store. */
    public static boolean collecting() {
        return McNativeHierarchicalLoad.enabled() && IVoxyRenderSystemHolder.getNullable() == null;
    }

    public static synchronized void reset() { count = 0; }

    public static synchronized void put(long sectionPos) {
        if ((count + 1) * 3 > xyz.length) xyz = java.util.Arrays.copyOf(xyz, xyz.length * 2);
        xyz[count * 3] = SectionPos.x(sectionPos);
        xyz[count * 3 + 1] = SectionPos.y(sectionPos);
        xyz[count * 3 + 2] = SectionPos.z(sectionPos);
        count++;
    }

    /** Copy the current list into {@code into} (grown as needed); returns {array, count}. */
    static synchronized Object[] snapshot(int[] into) {
        int[] out = into == null || into.length < count * 3 ? new int[Math.max(count * 3, 3 * 4096)] : into;
        System.arraycopy(xyz, 0, out, 0, count * 3);
        return new Object[] {out, count};
    }
}
