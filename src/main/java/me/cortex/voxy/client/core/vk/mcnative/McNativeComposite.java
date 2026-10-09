package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.VkDepth;
import me.cortex.voxy.client.core.vk.VkGraphicsPipeline;
import me.cortex.voxy.client.core.vk.VkRenderTarget;
import me.cortex.voxy.client.core.vk.VkSampler;
import me.cortex.voxy.client.core.vk.VkTexture;
import me.cortex.voxy.client.core.vk.shader.VkAutoBindingShader;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The native composite: Voxy's own target colour (and its depth reprojected into Minecraft's
 * space, which marks where Voxy drew) drawn into a pass that LOADs Minecraft's colour and depth.
 * Voxy's GL composition rule: a Voxy pixel lands only where Minecraft's depth is still the clear
 * value — the fragment depth is 0 and the test is {@link VkDepth#COMPARE_OP} (GREATER_OR_EQUAL),
 * writes off. Pixels Voxy did not draw are discarded and Minecraft's stay untouched.
 *
 * <p>Shape taken from {@code VkDepthResolve} (full-screen triangle, texelFetch, Voxy's shader and
 * pipeline helpers). Differences: two sampled sources, a colour AND depth attachment (Minecraft's
 * formats, which equal Voxy's: RGBA8 and D32_SFLOAT), and no render-pass begin/end — the pass is
 * Minecraft's, opened by the caller.
 *
 * <h2>Lifetime</h2>
 * The pipeline, shader and the sampled textures are referenced by Minecraft's command buffer;
 * the owner must not free them before Minecraft's submission has completed (the caller retires
 * them through Minecraft's destroy queue).
 */
final class McNativeComposite {
    private final VkAutoBindingShader shader;
    private final VkGraphicsPipeline pipeline;
    private final VkTexture colour, depth;
    private boolean freed;

    McNativeComposite(VkTexture colour, VkTexture depth) {
        this.colour = colour;
        this.depth = depth;
        this.shader = VkDepth.defines(VkShader.makeAuto().name("vk-native-composite")
                .addSource(ShaderType.VERTEX, VkShaderLoader.parse("voxy:lod/vk/native_composite.vert"))
                .addSource(ShaderType.FRAGMENT, VkShaderLoader.parse("voxy:lod/vk/native_composite.frag")))
            .compile();
        this.shader.texture(0, colour, VkSampler.nearestClamp());
        this.shader.texture(1, depth, VkSampler.nearestClamp());
        var missing = this.shader.unboundBindings();
        if (!missing.isEmpty()) {
            this.shader.free();
            throw new IllegalStateException("the native composite has unbound descriptors: " + missing);
        }
        this.pipeline = VkGraphicsPipeline.builder(this.shader)
            .colorFormat(VkRenderTarget.FORMAT_COLOR)
            .depthFormat(VkRenderTarget.FORMAT_DEPTH)
            // Voxy's GL rule: only where Minecraft's depth is still clear (native_composite.frag)
            .depthTest(true).depthWrite(false)
            .depthCompare(VkDepth.COMPARE_OP)
            .cullMode(VK_CULL_MODE_NONE)
            .build();
    }

    /**
     * In Voxy's own submission, after the scene wrote the target (and any readbacks): make both
     * sources readable by the fragment shader of Minecraft's later pass. On a handed sample the
     * submission is fence waited before Minecraft's pass is recorded; on an every-frame frame it
     * is not — that path relies on Voxy's submission preceding Minecraft's on the same queue, with
     * this barrier's scope reaching the later composite (see
     * {@code McNativeHierarchicalLoad.drawEveryFrame}).
     */
    void prepareSources(VkCommandBuffer cmd) {
        this.colour.barrier(cmd, 0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        this.depth.barrier(cmd, 0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    /** Inside Minecraft's open pass (LOAD colour + depth): one full-screen triangle. */
    void recordInPass(VkCommandBuffer cmd, int width, int height) {
        if (this.freed) throw new IllegalStateException("the native composite was freed");
        try (MemoryStack stack = stackPush()) {
            var vp = VkViewport.calloc(1, stack)
                .x(0).y(0).width(width).height(height).minDepth(0).maxDepth(1);
            var sc = VkRect2D.calloc(1, stack);
            sc.offset().set(0, 0);
            sc.extent().set(width, height);
            vkCmdSetViewport(cmd, 0, vp);
            vkCmdSetScissor(cmd, 0, sc);
            this.pipeline.bind(cmd);
            this.shader.bind(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS);
            vkCmdDraw(cmd, 3, 1, 0, 0);
        }
    }

    /** Free after Minecraft's submissions that recorded {@link #recordInPass} have completed. */
    void free() {
        if (this.freed) return;
        this.freed = true;
        this.pipeline.free();
        this.shader.free();
    }
}
