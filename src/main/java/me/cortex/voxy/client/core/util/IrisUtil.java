package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.util.FogParameters;

/**
 * Vulkan/macOS フォーク: Iris 連携は削除済み。
 * shaderpack は GL GLSL 前提のため Vulkan バックエンドとは原理的に両立しない。
 * 呼び出し側の差分を抑えるため、スタブとして残している。
 */
public class IrisUtil {

    public record CapturedViewportParameters(ChunkRenderMatrices matrices, FogParameters parameters, int width, int height, double x, double y, double z) {
        public Viewport<?> apply(VoxyRenderSystem vrs) {
            return vrs.setupViewport(this.matrices.projection(), this.matrices.modelView(), this.parameters, this.width, this.height, this.x, this.y, this.z);
        }
    }

    public static boolean USED_IRIS_VIEWPORT;
    public static CapturedViewportParameters CAPTURED_VIEWPORT_PARAMETERS;

    public static final boolean IRIS_INSTALLED = false;
    public static final boolean SHADER_SUPPORT = false;

    public static boolean irisShadowActive() { return false; }
    public static boolean irisShaderPackEnabled() { return false; }
    public static boolean irisShadersEnabledInConfig() { return false; }

    public static void clearIrisSamplers() {}
    public static void reload() {}
    public static void disableIrisShaders() {}
}
