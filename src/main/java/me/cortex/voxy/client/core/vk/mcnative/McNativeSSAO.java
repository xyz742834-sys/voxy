package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.VkDepth;
import me.cortex.voxy.client.core.vk.VkRenderTarget;
import me.cortex.voxy.client.core.vk.VkSampler;
import me.cortex.voxy.client.core.vk.VkTexture;
import me.cortex.voxy.client.core.vk.shader.VkAutoBindingShader;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import me.cortex.voxy.common.Logger;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkFormatProperties;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GL's SSAO pass natively ({@code SSAO}, {@code post/ssao.comp}), which GL's pipeline runs between the
 * terrain and the final blit: it darkens Voxy's colour by screen-space ambient occlusion where the
 * scene's alpha metadata asks for it (quads.frag packs face/LoD/AO there) and writes alpha 1. The
 * post pass then reads this output, as GL's final blit reads {@code colourSSAOTex}.
 *
 * <p>Variant: BASIC, which GL's AUTO mode picks when it cannot query GPU memory on a non-AMD device —
 * GL Voxy's case on macOS. The BETTER/BEST variants also read Minecraft's depth
 * ({@code sourceDepthTex}), which Voxy's own submission does not hold; a configured BETTER/BEST
 * runs BASIC here, and says so once.
 */
final class McNativeSSAO {
    static final int PUSH_SIZE = 64 * 2;
    final VkTexture output;
    private final VkAutoBindingShader shader;
    private final long pipeline;
    private final int width, height;
    private boolean freed;
    private static boolean variantNoted;

    McNativeSSAO(VkTexture colour, VkTexture depth, int width, int height) {
        this.width = width;
        this.height = height;
        var mode = me.cortex.voxy.client.config.VoxyConfig.CONFIG.getSSAOMode();
        if (mode != me.cortex.voxy.client.core.SSAO.SSAOMode.BASIC
                && mode != me.cortex.voxy.client.core.SSAO.SSAOMode.AUTO && !variantNoted) {
            variantNoted = true;
            Logger.info("[native-vk] SSAO mode " + mode + " runs as BASIC on the native path"
                + " (BETTER/BEST read Minecraft's depth, which Voxy's submission does not hold)");
        }
        this.output = new VkTexture(VkRenderTarget.FORMAT_COLOR, 1, width, height,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
            .name("voxySSAO");
        this.shader = VkDepth.defines(VkShader.makeAuto().name("vk-native-ssao")
                .define("VK_SSAO_PUSH")
                .addSource(ShaderType.COMPUTE, VkShaderLoader.parse("voxy:post/ssao.comp")))
            .compile();
        this.shader.storageImage(0, this.output, 0);
        this.shader.texture(1, colour, VkSampler.nearestClamp());
        // GL's BASIC sampler filters depth linearly; only where the depth format supports it
        this.shader.texture(2, depth, depthLinear() ? VkSampler.linearClamp() : VkSampler.nearestClamp());
        var missing = this.shader.unboundBindings();
        if (!missing.isEmpty() || this.shader.pushConstantSize() < PUSH_SIZE) {
            this.shader.free();
            this.output.free();
            throw new IllegalStateException("the native SSAO pass has unbound descriptors " + missing
                + " or " + this.shader.pushConstantSize() + " bytes of push constants");
        }
        try (MemoryStack stack = stackPush()) {
            var ci = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                .stage(this.shader.stageInfos(stack).get(0))
                .layout(this.shader.pipelineLayout());
            long[] p = new long[1];
            VkContext.check(vkCreateComputePipelines(VkContext.get().device, VK_NULL_HANDLE, ci, null, p),
                "vkCreateComputePipelines");
            this.pipeline = p[0];
        }
    }

    private static boolean depthLinear() {
        try (MemoryStack stack = stackPush()) {
            var props = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(VkContext.get().physical, VkRenderTarget.FORMAT_DEPTH, props);
            return (props.optimalTilingFeatures() & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
        }
    }

    /**
     * In Voxy's submission, after the scene and before the post pass: GL's computeSSAO with Voxy's
     * MVP (the scene's anchor-relative one; BASIC only compares positions with each other).
     */
    void record(VkCommandBuffer cmd, VkTexture colour, VkTexture depth, Matrix4fc mvp) {
        if (this.freed) throw new IllegalStateException("the native SSAO pass was freed");
        colour.barrier(cmd, 0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        depth.barrier(cmd, 0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        // the previous frame's post pass read it; the compute overwrites all of it
        this.output.barrier(cmd, 0, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            var buf = stack.malloc(PUSH_SIZE);
            mvp.get(0, buf);
            new Matrix4f(mvp).invert().get(64, buf);
            this.shader.pushBytes(0, buf);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            this.shader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
            this.shader.flushPushConstants(cmd);
            vkCmdDispatch(cmd, (this.width + 7) / 8, (this.height + 7) / 8, 1);
        }
        // the post pass samples it in its fragment shader
        this.output.barrier(cmd, 0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    void free() {
        if (this.freed) return;
        this.freed = true;
        vkDestroyPipeline(VkContext.get().device, this.pipeline, null);
        this.shader.free();
        this.output.free();
    }
}
