package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.shader.VkAutoBindingShader;
import me.cortex.voxy.client.core.vk.shader.VkDescriptorSetGroup;
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

/**
 * Phase 5c-4a — <b>階層 Z バッファ (HiZ)</b> をミップ連鎖として作る。
 *
 * <h2>何のためのものか</h2>
 * トラバーサルが<b>ノードの遮蔽</b>を判定するのに使う
 * [{@code screenspace.glsl} の {@code isCulledByHiz}]。
 * ノードの画面上の大きさからミップレベルを選び、その数テクセルだけ読んで
 * 「このノードは全部隠れているか」を決める。
 *
 * <h2>縮約は逆Zの min [確認済 — {@code hiz/blit.fsh}]</h2>
 * {@code REDUCTION} は逆Zで {@code min} = <b>4 テクセルのうち最も奥</b>。
 * <b>最も奥の遮蔽物より更に奥のものだけ落とす</b>ので、保守的な側に倒れる。
 *
 * <p>⚠ {@code blit.fsh} の「穴埋め」分岐は<b>どちらの深度規約でも no-op である</b>
 * [確認済 — 導出は docs/phase5c4-plan.md 2.4]。
 * FAR は既に縮約を支配するので、置き換えても結果が変わらない。
 * <b>参照仕様なので直さないが、効いている前提にしない。</b>
 *
 * <h2>GL 版との違い</h2>
 * <table>
 *   <tr><th></th><th>GL</th><th>Vulkan (ここ)</th></tr>
 *   <tr><td>格納</td><td>{@code GL_DEPTH24_STENCIL8} + {@code gl_FragDepth}</td>
 *       <td><b>{@code R32_SFLOAT} の色</b></td></tr>
 *   <tr><td>読み側を 1 レベルに絞る</td><td>{@code GL_TEXTURE_BASE_LEVEL/MAX_LEVEL}</td>
 *       <td><b>レベルごとのビュー</b></td></tr>
 * </table>
 *
 * <p>traversal は {@code sampler2D} の {@code .r} を {@code texelFetch} するだけなので
 * <b>色でも深度でも等価</b>である [確認済 — {@code screenspace.glsl}]。
 * ⚠ 精度は<b>むしろ上がる</b> (24bit 固定小数 → float)。逆Zでは奥ほど 0 に近いので、
 * float のほうが遠方に細かい。<b>GL 版と数値が一致するとは限らない</b>が、
 * この環境では GL 版を走らせられないので比較はもともとできない。
 *
 * <h2>大きさ [確認済 — {@code HiZBuffer.buildMipChain}]</h2>
 * <b>2 の冪へ切り下げる</b> ({@code Integer.highestOneBit})。
 * レベル数は {@code ceil(log2(max(w,h)))} なので、いちばん小さいレベルは 1x1 ではない。
 */
public final class VkHiZ {
    public static final int FORMAT = VK_FORMAT_R32_SFLOAT;

    private final VkAutoBindingShader shader;
    private final VkGraphicsPipeline pipeline;
    private final VkTexture source;
    private final VkTexture texture;
    private final VkDescriptorSetGroup sets;
    private final int width, height, levels;
    private boolean freed;

    /** HiZ の一辺 (2 の冪へ切り下げ)。 */
    public static int hizExtent(int v) { return Integer.highestOneBit(Math.max(1, v)); }

    /** レベル数。{@code ceil(log2(max(w,h)))} [確認済 — GL 版と同じ]。 */
    public static int hizLevels(int width, int height) {
        int m = Math.max(hizExtent(width), hizExtent(height));
        return Math.max(1, (int) Math.ceil(Math.log(m) / Math.log(2)));
    }

    /**
     * @param source 元の深度。<b>{@code R32_SFLOAT} で 1 レベル</b>
     *               ({@code VkDepthResolve} の出力、または検査が用意したもの)
     */
    public VkHiZ(VkTexture source, int width, int height) {
        this.source = source;
        this.width = hizExtent(width);
        this.height = hizExtent(height);
        this.levels = hizLevels(width, height);

        this.texture = new VkTexture(FORMAT, this.levels, this.width, this.height,
            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT).name("hiz");

        // ⚠ 断片シェーダは **GL 版のものをそのまま使う**。参照仕様を書き直さない
        this.shader = VkDepth.defines(VkShader.makeAuto().name("vk-hiz")
                .define("OUTPUT_COLOUR")
                .addSource(ShaderType.VERTEX, VkShaderLoader.parse("voxy:lod/vk/hiz_blit.vert"))
                .addSource(ShaderType.FRAGMENT, VkShaderLoader.parse("voxy:hiz/blit.fsh")))
            .compile();

        // レベルごとに読み先が違う。レベル 0 は元の深度、レベル i は自分の i-1
        this.sets = new VkDescriptorSetGroup(this.shader, this.levels);
        for (int i = 0; i < this.levels; i++) {
            if (i == 0) {
                this.sets.variantTexture(0, 0, source, VkSampler.nearestClamp());
            } else {
                this.sets.variantTextureLevel(i, 0, this.texture, i - 1, VkSampler.nearestClamp());
            }
        }
        var missing = this.sets.unbound(0);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("HiZ has unbound descriptors: " + missing);
        }
        this.sets.update();

        this.pipeline = VkGraphicsPipeline.builder(this.shader)
            .colorFormat(FORMAT)
            .depthFormat(VK_FORMAT_UNDEFINED)
            .depthTest(false).depthWrite(false)
            .cullMode(VK_CULL_MODE_NONE)
            .build();
    }

    /**
     * ミップ連鎖を作る記録を積む。
     *
     * <p>⚠ <b>レベルごとにバリアが要る。</b> レベル i を書く前に i-1 を
     * {@code COLOR_ATTACHMENT} から {@code SHADER_READ_ONLY} へ移し、
     * <b>書き込みが読み出しから見えるようにする</b>。
     * これを落とすと「前フレームの中身を読む」形で静かに間違える。
     */
    public void record(VkCommandBuffer cmd) {
        if (this.freed) throw new IllegalStateException("VkHiZ was freed");

        // 元の深度を読める状態に
        this.source.barrier(cmd, 0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);

        int w = this.width, h = this.height;
        for (int level = 0; level < this.levels; level++) {
            if (level > 0) {
                this.texture.barrier(cmd, level - 1, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            }
            this.texture.barrier(cmd, level, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            try (MemoryStack stack = stackPush()) {
                var att = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                    .imageView(this.texture.view(level))
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)   // 全面を書く
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                var ri = VkRenderingInfo.calloc(stack).sType$Default()
                    .layerCount(1).pColorAttachments(att);
                ri.renderArea().offset().set(0, 0);
                ri.renderArea().extent().set(w, h);
                VkCmd.beginRendering(cmd, ri);

                var vp = VkViewport.calloc(1, stack)
                    .x(0).y(0).width(w).height(h).minDepth(0).maxDepth(1);
                var sc = VkRect2D.calloc(1, stack);
                sc.offset().set(0, 0);
                sc.extent().set(w, h);
                vkCmdSetViewport(cmd, 0, vp);
                vkCmdSetScissor(cmd, 0, sc);

                this.pipeline.bind(cmd);
                this.sets.bind(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, level);
                vkCmdDraw(cmd, 3, 1, 0, 0);
            }
            VkCmd.endRendering(cmd);

            w = Math.max(w / 2, 1);
            h = Math.max(h / 2, 1);
        }

        // 最後のレベルも読める状態にして返す (traversal が全レベルを引く)
        this.texture.barrier(cmd, this.levels - 1, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT);
    }

    public VkTexture texture() { return this.texture; }
    public int width()  { return this.width; }
    public int height() { return this.height; }
    public int levels() { return this.levels; }

    /** レベル {@code l} の幅 / 高さ。 */
    public int widthOf(int l)  { return Math.max(this.width  >> l, 1); }
    public int heightOf(int l) { return Math.max(this.height >> l, 1); }

    /**
     * {@code screenspace.glsl} の {@code packedHizSize} と同じ詰め方
     * ({@code w<<16 | h})。
     */
    public int packedSize() { return (this.width << 16) | (this.height & 0xFFFF); }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        this.sets.free();
        this.pipeline.free();
        this.shader.free();
        this.texture.free();
    }
}
