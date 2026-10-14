package me.cortex.voxy.testsupport;

/**
 * Minecraft's registries, once per test JVM, for the CPU tests that run Voxy's real world code
 * ({@code WorldEngine} creates a {@code Mapper}, which maps {@code Blocks.AIR}). No game, no window,
 * no GPU: {@code Bootstrap.bootStrap()} only fills the built-in registries.
 */
public final class MinecraftBootstrap {
    private static boolean done;

    private MinecraftBootstrap() {}

    public static synchronized void ensure() {
        if (done) return;
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        done = true;
    }
}
