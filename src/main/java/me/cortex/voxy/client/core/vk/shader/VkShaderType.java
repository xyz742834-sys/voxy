package me.cortex.voxy.client.core.vk.shader;

import org.lwjgl.util.shaderc.Shaderc;

import static org.lwjgl.vulkan.VK10.*;

public enum VkShaderType {
    VERTEX(VK_SHADER_STAGE_VERTEX_BIT, Shaderc.shaderc_vertex_shader),
    FRAGMENT(VK_SHADER_STAGE_FRAGMENT_BIT, Shaderc.shaderc_fragment_shader),
    COMPUTE(VK_SHADER_STAGE_COMPUTE_BIT, Shaderc.shaderc_compute_shader);

    public final int stage;
    public final int shadercKind;

    VkShaderType(int stage, int shadercKind) {
        this.stage = stage;
        this.shadercKind = shadercKind;
    }

    /** 既存の GL 側 ShaderType からの変換。MESH/TASK は未使用のため非対応。 */
    public static VkShaderType from(me.cortex.voxy.client.core.gl.shader.ShaderType t) {
        return switch (t) {
            case VERTEX -> VERTEX;
            case FRAGMENT -> FRAGMENT;
            case COMPUTE -> COMPUTE;
            default -> throw new IllegalArgumentException("unsupported shader type: " + t);
        };
    }
}
