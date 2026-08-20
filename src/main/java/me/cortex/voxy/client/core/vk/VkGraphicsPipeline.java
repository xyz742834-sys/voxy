package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.vk.shader.VkShader;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * グラフィクスパイプライン。{@code dynamic_rendering} 前提なので
 * {@code VkRenderPass} を要求せず、アタッチメントのフォーマットだけを受け取る。
 *
 * <h2>頂点入力が空である理由</h2>
 * Voxy は <b>VAO を使わない</b>。頂点データは {@code gl_VertexIndex} をキーに
 * SSBO から引く (vertex pulling) [確認済 — `quads3.vert`、
 * GL 側も {@code GlVertexArray.STATIC_VAO} という空の VAO を使っている]。
 * したがって {@code VkPipelineVertexInputStateCreateInfo} は空でよく、
 * 頂点属性の記述がまるごと不要になる。
 *
 * <p>これは Vulkan 移植で<b>有利に働く数少ない点</b>の 1 つ
 * (docs/vulkan-port-feasibility.md の「頂点入力レイアウト」)。
 */
public class VkGraphicsPipeline {
    public final long handle;
    private final VkShader shader;
    private boolean freed;

    private VkGraphicsPipeline(long handle, VkShader shader) {
        this.handle = handle;
        this.shader = shader;
    }

    public VkShader shader() { return this.shader; }

    public void bind(VkCommandBuffer cmd) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.handle);
    }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        vkDestroyPipeline(VkContext.get().device, this.handle, null);
    }

    public static Builder builder(VkShader shader) { return new Builder(shader); }

    public static class Builder {
        private final VkShader shader;
        private int colorFormat = VkRenderTarget.FORMAT_COLOR;
        private int depthFormat = VkRenderTarget.FORMAT_DEPTH;
        private int topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        private int cullMode = VK_CULL_MODE_NONE;
        private boolean depthTest = true;
        private boolean depthWrite = true;
        private int depthCompare = VkDepth.COMPARE_OP;
        private boolean blend = false;
        private boolean colorAttachment = true;

        Builder(VkShader shader) { this.shader = shader; }

        public Builder colorFormat(int f) { this.colorFormat = f; return this; }
        public Builder depthFormat(int f) { this.depthFormat = f; return this; }
        public Builder topology(int t) { this.topology = t; return this; }
        public Builder cullMode(int m) { this.cullMode = m; return this; }
        public Builder depthTest(boolean b) { this.depthTest = b; return this; }
        public Builder depthWrite(boolean b) { this.depthWrite = b; return this; }
        public Builder depthCompare(int op) { this.depthCompare = op; return this; }

        /** GL 側の {@code glBlendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA)} 相当。 */
        public Builder alphaBlend(boolean b) { this.blend = b; return this; }

        /**
         * カラーアタッチメント無しの深度専用パス。遮蔽カリングが使う
         * (深度テストだけして SSBO に書く。カラーは一切触らない)。
         */
        public Builder depthOnly() { this.colorAttachment = false; return this; }

        public VkGraphicsPipeline build() {
            var ctx = VkContext.get();
            try (MemoryStack stack = stackPush()) {
                // 頂点入力なし (vertex pulling)
                var vi = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();

                var ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(this.topology).primitiveRestartEnable(false);

                // ビューポート/シザーは動的。ターゲットのサイズに追従させるため
                var vpState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .viewportCount(1).scissorCount(1);

                var rs = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(this.cullMode)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .depthBiasEnable(false)
                    .lineWidth(1.0f);

                var ms = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
                    .sampleShadingEnable(false);

                var ds = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                    .depthTestEnable(this.depthTest)
                    .depthWriteEnable(this.depthWrite)
                    .depthCompareOp(this.depthCompare)
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(false);

                var cbaCount = this.colorAttachment ? 1 : 0;
                var cba = VkPipelineColorBlendAttachmentState.calloc(Math.max(cbaCount, 1), stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                                  | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(this.blend);
                if (this.blend) {
                    cba.get(0)
                        .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .alphaBlendOp(VK_BLEND_OP_ADD);
                }
                var cb = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                    .logicOpEnable(false);
                if (this.colorAttachment) cb.pAttachments(cba);

                var dyn = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

                // dynamic_rendering: RenderPass の代わりにフォーマットを渡す
                var rendering = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                    .depthAttachmentFormat(this.depthFormat);
                if (this.colorAttachment) rendering.pColorAttachmentFormats(stack.ints(this.colorFormat));

                var ci = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
                    .pNext(rendering.address())
                    .pStages(this.shader.stageInfos(stack))
                    .pVertexInputState(vi)
                    .pInputAssemblyState(ia)
                    .pViewportState(vpState)
                    .pRasterizationState(rs)
                    .pMultisampleState(ms)
                    .pDepthStencilState(ds)
                    .pColorBlendState(cb)
                    .pDynamicState(dyn)
                    .layout(this.shader.pipelineLayout())
                    .renderPass(VK_NULL_HANDLE);   // dynamic_rendering では未使用

                long[] p = new long[1];
                VkContext.check(
                    vkCreateGraphicsPipelines(ctx.device, VK_NULL_HANDLE, ci, null, p),
                    "vkCreateGraphicsPipelines");
                return new VkGraphicsPipeline(p[0], this.shader);
            }
        }
    }
}
