package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.shader.VkAutoBindingShader;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * <b>The vanilla depth bound, natively</b>: the Vulkan port of GL's {@code BoundRenderer} with its
 * {@code StreamedBoundStore}. Each frame it draws the boxes of Minecraft's built, visible chunk
 * sections — depth only, the farther depth kept over a NEAR clear — into a depth-bound texture
 * that Voxy's terrain shader samples ({@code lod/gl46/quads.frag} binding 2): Voxy fragments
 * nearer than the bound are discarded, so Voxy draws only beyond vanilla's loaded terrain.
 *
 * <p>The section positions are a host-visible buffer rewritten each frame by the caller, after
 * Voxy's previous submission completed (the buffer is read by that submission only).
 */
public final class VkBoundRenderer {
    /** Sections the buffer holds; more are dropped and counted ({@link #dropped}). */
    public static final int CAPACITY = 1 << 17;
    private static final int PUSH_SIZE = 64 + 16 + 16;

    private final VkAutoBindingShader shader;
    private final VkGraphicsPipeline pipeline;
    private final VkBuffer sections;
    private final int width, height;
    private int count;
    private long dropped;
    private boolean freed;

    public VkBoundRenderer(int width, int height) {
        this.width = width;
        this.height = height;
        this.sections = new VkBuffer((long) CAPACITY * 16);
        this.shader = VkDepth.defines(VkShader.makeAuto().name("vk-vanilla-bound")
                .addSource(ShaderType.VERTEX, VkShaderLoader.parse("voxy:lod/vk/bound.vert"))
                .addSource(ShaderType.FRAGMENT, VkShaderLoader.parse("voxy:lod/vk/bound.frag")))
            .compile();
        this.shader.ssbo(1, this.sections);
        var missing = this.shader.unboundBindings();
        if (!missing.isEmpty()) {
            this.shader.free();
            this.sections.free();
            throw new IllegalStateException("the bound shader has unbound descriptors: " + missing);
        }
        if (this.shader.pushConstantSize() < PUSH_SIZE) {
            this.shader.free();
            this.sections.free();
            throw new IllegalStateException("the bound shader has " + this.shader.pushConstantSize()
                + " bytes of push constants, not " + PUSH_SIZE);
        }
        this.pipeline = VkGraphicsPipeline.builder(this.shader)
            .depthOnly()
            .depthFormat(VkTerrainRenderer.DEPTH_BOUND_FORMAT)
            .depthTest(true).depthWrite(true)
            // GL: properties.furtherDepthCompare() — reverse Z keeps the smaller (farther) depth
            .depthCompare(VK_COMPARE_OP_LESS)
            .cullMode(VK_CULL_MODE_NONE)
            .build();
    }

    /**
     * Replace the section list (chunk-section coordinates, 3 ints each). Host side; call only
     * after Voxy's previous submission has completed.
     */
    public void setSections(int[] xyz, int sectionCount) {
        int n = Math.min(sectionCount, CAPACITY);
        this.dropped += sectionCount - n;
        long addr = this.sections.addr();
        for (int i = 0; i < n; i++) {
            long p = addr + (long) i * 16;
            MemoryUtil.memPutInt(p, xyz[i * 3]);
            MemoryUtil.memPutInt(p + 4, xyz[i * 3 + 1]);
            MemoryUtil.memPutInt(p + 8, xyz[i * 3 + 2]);
            MemoryUtil.memPutInt(p + 12, 0);
        }
        this.count = n;
    }

    public int count() { return this.count; }
    public long dropped() { return this.dropped; }

    /**
     * Clear {@code bound} to NEAR (nothing discarded) and draw the boxes into it; afterwards it is
     * shader-readable for the terrain passes.
     *
     * @param mvp                 Voxy's MVP, input relative to the anchor origin
     * @param anchorBlock         anchor section * 32 (blocks)
     * @param cameraRel           camera - anchorBlock
     * @param renderDistanceBlocks Minecraft's effective render distance in blocks
     */
    public void record(VkCommandBuffer cmd, VkTexture bound, Matrix4fc mvp, int[] anchorBlock,
                       float[] cameraRel, float renderDistanceBlocks) {
        if (this.freed) throw new IllegalStateException("VkBoundRenderer was freed");
        bound.barrier(cmd, 0, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
            VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
            VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            var att = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                .imageView(bound.view(0))
                .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            att.clearValue().depthStencil().depth(VkDepth.BOUND_NEUTRAL).stencil(0);
            var ri = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pDepthAttachment(att);
            ri.renderArea().offset().set(0, 0);
            ri.renderArea().extent().set(this.width, this.height);
            VkCmd.beginRendering(cmd, ri);
            if (this.count > 0) {
                var vp = VkViewport.calloc(1, stack)
                    .x(0).y(0).width(this.width).height(this.height).minDepth(0).maxDepth(1);
                var sc = VkRect2D.calloc(1, stack);
                sc.offset().set(0, 0);
                sc.extent().set(this.width, this.height);
                vkCmdSetViewport(cmd, 0, vp);
                vkCmdSetScissor(cmd, 0, sc);
                var buf = stack.malloc(PUSH_SIZE);
                mvp.get(0, buf);
                buf.putInt(64, anchorBlock[0]).putInt(68, anchorBlock[1]).putInt(72, anchorBlock[2]).putInt(76, 0);
                buf.putFloat(80, cameraRel[0]).putFloat(84, cameraRel[1]).putFloat(88, cameraRel[2])
                    .putFloat(92, renderDistanceBlocks);
                this.shader.pushBytes(0, buf);
                this.pipeline.bind(cmd);
                this.shader.bind(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS);
                this.shader.flushPushConstants(cmd);
                vkCmdDraw(cmd, 36, this.count, 0, 0);
            }
        }
        VkCmd.endRendering(cmd);
        bound.barrier(cmd, 0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        this.pipeline.free();
        this.shader.free();
        this.sections.free();
    }
}
