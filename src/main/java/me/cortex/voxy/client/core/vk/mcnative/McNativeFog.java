package me.cortex.voxy.client.core.vk.mcnative;

import net.caffeinemc.mods.sodium.client.util.FogParameters;

/**
 * Minecraft's fog for the native path: Sodium's {@code FogParameters}, captured at the same hook as
 * the camera ({@code MixinDefaultChunkRenderer}), as GL Voxy's viewport keeps them
 * ({@code viewport.fogParameters}) for its final blit.
 */
public final class McNativeFog {
    private static float red, green, blue, alpha, envStart, envEnd;
    private static boolean captured;

    private McNativeFog() {}

    public static synchronized void capture(FogParameters fog) {
        if (fog == null) return;
        red = fog.red(); green = fog.green(); blue = fog.blue(); alpha = fog.alpha();
        envStart = fog.environmentalStart();
        envEnd = fog.environmentalEnd();
        captured = true;
    }

    /** {red, green, blue, alpha, environmentalStart, environmentalEnd}, or null before a capture. */
    public static synchronized float[] latest() {
        return captured ? new float[] {red, green, blue, alpha, envStart, envEnd} : null;
    }
}
