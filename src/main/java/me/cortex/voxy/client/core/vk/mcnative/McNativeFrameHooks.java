package me.cortex.voxy.client.core.vk.mcnative;

/**
 * <b>Where the native path draws in Minecraft's frame</b>: GL Voxy's point — Sodium's cutout terrain
 * pass, before its draws ({@code MixinDefaultChunkRenderer}, injected before
 * {@code ShaderChunkRenderer.end}). Sodium fills its command data and calls {@code end} before it
 * opens its Blaze3D render pass for that terrain pass (26.2 bytecode), so no render pass is open
 * there: the native passes and readbacks can be recorded into Minecraft's frame. Minecraft's solid
 * terrain is drawn; its cutout and translucent terrain, entities, particles and weather come after
 * and test against the depth Voxy writes, as on GL.
 *
 * <p>The ladder and every experiment it hands samples to run here, in their usual order, so each
 * measurement and its judgement see the frame as it is at Voxy's composite point. The environment
 * launch's own probes (depth probe, terrain probe, marker, instance sampling) stay at the
 * level-render tail.
 */
public final class McNativeFrameHooks {
    private McNativeFrameHooks() {}

    /** At Sodium's cutout pass, once per captured camera frame. Inert without native flags. */
    public static void atCutout() {
        McNativeDepthLadder.renderIfEnabled();
        McNativeTerrainLoad.renderIfEnabled();
        McNativeRealLoad.renderIfEnabled();
        McNativeHierarchicalLoad.renderIfEnabled();
    }
}
