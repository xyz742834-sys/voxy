package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.interop.VkInteropImage;
import me.cortex.voxy.client.core.vk.shader.VkAutoBindingShader;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;

/**
 * Vulkan の深度アタッチメント (D32_SFLOAT) を <b>interop の R32F 画像</b>へ写すパス。
 *
 * <h2>なぜコピーで済まないか [確認済 — 仕様ベース]</h2>
 * <ul>
 *   <li>IOSurface に depth aspect の面は作れない。使えるのは 'BGRA' と 'r00f' だけ
 *       [確認済 — Phase 0 §8.2]。つまり深度は<b>色として運ぶ</b>しかない</li>
 *   <li>{@code vkCmdCopyImage} は「片方が深度/ステンシル形式なら両方が同一形式」を要求する
 *       (VUID-vkCmdCopyImage-srcImage-01557)。D32_SFLOAT → R32_SFLOAT は通らない</li>
 * </ul>
 *
 * <p>結果としてフルスクリーンパスが 1 枚要る。これは GL 側 {@code initDepthStencil} が
 * d32 → d24s8 でやっていた「フォーマット不一致のためのフルスクリーンコピー」の裏返しで、
 * 提案書 §3.1 が「そのまま interop の仕事になる」と書いていたものである。
 *
 * <h2>⚠ 出力先のレイアウト</h2>
 * 書き終えたら {@code GENERAL} に置いて GL に渡す。
 * <b>{@code UNDEFINED} から遷移させてはならない</b> — と言っても、ここは Vulkan が
 * 全面を書く側なので初回の破棄は無害である。危険なのは GL が書いた画像を Vulkan が読む側
 * [Phase 5a §6.1]。{@link VkTexture} がレイアウトを追跡しているので、
 * 2 フレーム目以降は {@code GENERAL} からの遷移になる。
 */
public class VkDepthResolve {
    private final VkAutoBindingShader shader;
    private final VkGraphicsPipeline pipeline;
    private final int width, height;
    private boolean freed;

    /**
     * @param srcDepth 読む深度。{@code VK_IMAGE_USAGE_SAMPLED_BIT} が要る
     *                 ({@link VkRenderTarget} の深度は既に持っている)
     */
    public VkDepthResolve(VkTexture srcDepth, int width, int height) {
        this.width = width;
        this.height = height;

        this.shader = VkShader.makeAuto().name("vk-depth-resolve")
            .addSource(ShaderType.VERTEX, VkShaderLoader.parse("voxy:lod/vk/depth_resolve.vert"))
            .addSource(ShaderType.FRAGMENT, VkShaderLoader.parse("voxy:lod/vk/depth_resolve.frag"))
            .compile();

        this.shader.texture(0, srcDepth, VkSampler.nearestClamp());
        var missing = this.shader.unboundBindings();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("depth resolve has unbound descriptors: " + missing);
        }

        this.pipeline = VkGraphicsPipeline.builder(this.shader)
            .colorFormat(VkInteropImage.Kind.DEPTH_R32F.vkFormat)
            // 深度アタッチメント無しのパス。宣言も UNDEFINED にしないと
            // VkRenderingInfo と食い違う
            .depthFormat(VK_FORMAT_UNDEFINED)
            .depthTest(false).depthWrite(false)
            .cullMode(VK_CULL_MODE_NONE)
            .build();
    }

    /**
     * 解決を記録する。呼び出し後、{@code dst} は {@code GENERAL} にあり GL から読める。
     *
     * @param srcDepth 直前の描画が書いた深度。{@link VkRenderTarget#depth} を渡す
     */
    public void record(VkCommandBuffer cmd, VkTexture srcDepth, VkInteropImage dst) {
        if (this.freed) throw new IllegalStateException("VkDepthResolve was freed");
        if (dst.width != this.width || dst.height != this.height) {
            throw new IllegalArgumentException("destination is " + dst.width + "x" + dst.height
                + " but the pass was built for " + this.width + "x" + this.height);
        }

        // 地形描画が書いた深度を、フラグメントシェーダから読める状態にする。
        // src は LATE_FRAGMENT_TESTS (深度書き込みが完了する段)
        srcDepth.barrier(cmd, 0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);

        // 書き先。前フレームで GL が読んだかもしれないので、src は ALL_COMMANDS で
        // 実行依存だけ張る (WAR。可視化は不要)
        dst.texture().barrier(cmd, 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

        try (MemoryStack stack = stackPush()) {
            var att = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                .imageView(dst.texture().view(0))
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                // 全面を書くので LOAD は要らない
                .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);

            var ri = VkRenderingInfo.calloc(stack).sType$Default()
                .layerCount(1).pColorAttachments(att);
            ri.renderArea().offset().set(0, 0);
            ri.renderArea().extent().set(this.width, this.height);
            vkCmdBeginRendering(cmd, ri);

            var vp = VkViewport.calloc(1, stack)
                .x(0).y(0).width(this.width).height(this.height).minDepth(0).maxDepth(1);
            var sc = VkRect2D.calloc(1, stack);
            sc.offset().set(0, 0);
            sc.extent().set(this.width, this.height);
            vkCmdSetViewport(cmd, 0, vp);
            vkCmdSetScissor(cmd, 0, sc);

            this.pipeline.bind(cmd);
            this.shader.bind(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS);
            vkCmdDraw(cmd, 3, 1, 0, 0);
        }
        vkCmdEndRendering(cmd);

        // GL に渡す。GL 側との順序は Vulkan のバリアでは表現できず、
        // CPU 側の同期でしか担保できない [Phase 5a §4]
        dst.texture().barrier(cmd, 0, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
    }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        this.pipeline.free();
        this.shader.free();
    }
}
