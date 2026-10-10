package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkCmd;
import me.cortex.voxy.client.core.vk.VkDepth;
import me.cortex.voxy.client.core.vk.VkGraphicsPipeline;
import me.cortex.voxy.client.core.vk.VkRenderTarget;
import me.cortex.voxy.client.core.vk.VkSampler;
import me.cortex.voxy.client.core.vk.VkTexture;
import me.cortex.voxy.client.core.vk.shader.VkAutoBindingShader;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GL's final blit's colour work natively ({@code voxy:lod/vk/voxy_post.frag}, a port of
 * {@code post/blit_texture_depth_cutout.frag} with HAS_FOG/HAS_FADE): fog and fade applied to
 * Voxy's colour in Voxy's own submission, into {@link #output} (cleared to transparent), which the
 * composite blends into Minecraft's frame and the judged sample reads back. Parameters as
 * {@code NormalRenderPipeline.finish} computes them ({@link #parameters}).
 */
final class McNativePost {
    static final int PUSH_SIZE = 64 + 16 * 4;
    final VkTexture output;
    final VkBuffer readback;
    private final VkAutoBindingShader shader;
    private final VkGraphicsPipeline pipeline;
    private final int width, height;
    private boolean freed;

    McNativePost(VkTexture colour, VkTexture depth, int width, int height) {
        this.width = width;
        this.height = height;
        this.output = new VkTexture(VkRenderTarget.FORMAT_COLOR, 1, width, height,
            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
            .name("voxyPost");
        this.readback = new VkBuffer((long) width * height * 4);
        this.shader = VkDepth.defines(VkShader.makeAuto().name("vk-native-post")
                .addSource(ShaderType.VERTEX, VkShaderLoader.parse("voxy:lod/vk/native_composite.vert"))
                .addSource(ShaderType.FRAGMENT, VkShaderLoader.parse("voxy:lod/vk/voxy_post.frag")))
            .compile();
        this.shader.texture(0, colour, VkSampler.nearestClamp());
        this.shader.texture(1, depth, VkSampler.nearestClamp());
        var missing = this.shader.unboundBindings();
        if (!missing.isEmpty() || this.shader.pushConstantSize() < PUSH_SIZE) {
            this.shader.free();
            this.output.free();
            this.readback.free();
            throw new IllegalStateException("the native post pass has unbound " + missing + " or "
                + this.shader.pushConstantSize() + " bytes of push constants");
        }
        this.pipeline = VkGraphicsPipeline.builder(this.shader)
            .colorFormat(VkRenderTarget.FORMAT_COLOR)
            .depthFormat(VK_FORMAT_UNDEFINED)
            .depthTest(false).depthWrite(false)
            .cullMode(VK_CULL_MODE_NONE)
            .build();
    }

    /**
     * {@code NormalRenderPipeline.finish}'s uniforms: {endParams[4], fogColour[4], fadeParams[4]},
     * and whether fog covers all Voxy rendering (GL then skips the blit: Voxy is not shown).
     *
     * @param fog {red, green, blue, alpha, environmentalStart, environmentalEnd} or null
     */
    static float[] parameters(float[] fog, boolean hasFog, boolean hasFade, float vanillaRd,
                              float sectionRenderDistance) {
        float[] p = new float[13];
        if (hasFog && fog != null) {
            float start = fog[4], end = fog[5];
            if (Math.abs(end - start) > 1) {
                float invEndFogDelta = 1f / (end - start);
                float endDistance = Math.max(vanillaRd, 20 * 16);
                endDistance *= (float) Math.sqrt(3);
                float startDelta = -start * invEndFogDelta;
                p[0] = invEndFogDelta;
                p[1] = startDelta;
                p[2] = Math.clamp(endDistance * invEndFogDelta + startDelta, 0, 1);
                p[4] = fog[0]; p[5] = fog[1]; p[6] = fog[2]; p[7] = fog[3];
            }
        }
        if (hasFade) {
            int mode = 1;   // 0 off, 1 xz, 2 xyz — GL's constant
            float rd = sectionRenderDistance * 16 * 32 - (float) Math.sqrt(mode > 1 ? 32 * 32 * 32 : 32 * 32);
            float start = Math.max(vanillaRd, rd * 0.9f);
            float end = Math.max(vanillaRd, rd);
            float scale = 1.0f / (end - start);
            p[8] = mode; p[9] = (-start) * scale; p[10] = scale;
        }
        // GL: fogCoversAllRendering = environmentalEnd < vanilla render distance
        p[12] = fog != null && fog[5] < vanillaRd ? 1 : 0;
        return p;
    }

    /**
     * After the scene and the depth resolve (Voxy's depth already shader-readable), in Voxy's
     * submission. With {@code readback} the result is also copied to {@link #readback}.
     */
    void record(VkCommandBuffer cmd, VkTexture colour, VkTexture depth, Matrix4fc invMvp,
                float[] cameraRel, float[] params, boolean readback) {
        if (this.freed) throw new IllegalStateException("the native post pass was freed");
        colour.barrier(cmd, 0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        depth.barrier(cmd, 0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        this.output.barrier(cmd, 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            var att = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                .imageView(this.output.view(0))
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            att.get(0).clearValue().color().float32(0, 0).float32(1, 0).float32(2, 0).float32(3, 0);
            var ri = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(att);
            ri.renderArea().offset().set(0, 0);
            ri.renderArea().extent().set(this.width, this.height);
            VkCmd.beginRendering(cmd, ri);
            var vp = VkViewport.calloc(1, stack)
                .x(0).y(0).width(this.width).height(this.height).minDepth(0).maxDepth(1);
            var sc = VkRect2D.calloc(1, stack);
            sc.offset().set(0, 0);
            sc.extent().set(this.width, this.height);
            vkCmdSetViewport(cmd, 0, vp);
            vkCmdSetScissor(cmd, 0, sc);
            var buf = stack.malloc(PUSH_SIZE);
            invMvp.get(0, buf);
            buf.putFloat(64, cameraRel[0]).putFloat(68, cameraRel[1]).putFloat(72, cameraRel[2]).putFloat(76, 0);
            for (int i = 0; i < 12; i++) buf.putFloat(80 + i * 4, params[i]);
            this.shader.pushBytes(0, buf);
            this.pipeline.bind(cmd);
            this.shader.bind(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS);
            this.shader.flushPushConstants(cmd);
            vkCmdDraw(cmd, 3, 1, 0, 0);
        }
        VkCmd.endRendering(cmd);
        if (!readback) return;
        this.output.barrier(cmd, 0, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        try (var stack = stackPush()) {
            var region = VkBufferImageCopy.calloc(1, stack).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(this.width, this.height, 1);
            vkCmdCopyImageToBuffer(cmd, this.output.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                this.readback.handle, region);
            var b = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(this.readback.handle).offset(0).size(this.readback.size());
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, b, null);
        }
    }

    void free() {
        if (this.freed) return;
        this.freed = true;
        this.pipeline.free();
        this.shader.free();
        this.output.free();
        this.readback.free();
    }
}
