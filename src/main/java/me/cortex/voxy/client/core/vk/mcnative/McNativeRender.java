package me.cortex.voxy.client.core.vk.mcnative;

/**
 * <b>The product switch for Voxy on Minecraft's own Vulkan backend</b> (flag
 * {@code voxy.native.render}, off by default).
 *
 * <p>One switch for the whole native path, without diagnostics: Voxy's shader features requested
 * on Minecraft's device ({@link McNativeDeviceFeatures}), that device adopted into Voxy's context
 * ({@link McNativeVkContext}), Voxy's instance — world engine, storage, ingest — under Minecraft
 * Vulkan ({@code VoxyClient.nativeInstanceMode}), and the hierarchical scene rendered and
 * composited on every frame ({@link McNativeHierarchicalLoad}) by Voxy's GL composition rule. Each
 * of those keeps its own diagnostic flag; this switch implies them. The ladder, its judged
 * samples, the instance probe's sampling and every other diagnostic stay off unless their own
 * flags are set, and evidence files are written only under the harness.
 *
 * <p>Read where each consumer reads its own flag, so the switch works however early that is
 * (device features are requested while Minecraft creates its device).
 */
public final class McNativeRender {
    public static final String FLAG = "voxy.native.render";
    /** The kill switch: set, the product path is off whatever {@link #FLAG} says. */
    public static final String DISABLE_FLAG = "voxy.native.disable";

    private McNativeRender() {}

    public static boolean on() { return Boolean.getBoolean(FLAG) && !Boolean.getBoolean(DISABLE_FLAG); }
}
