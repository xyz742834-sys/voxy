package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.shader.VkAutoBindingShader;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Stage 1 の地形レンダラ。<b>{@code quads3.vert} + {@code quads.frag} を無改造で
 * Vulkan 上に載せ、オフスクリーンに 1 枚描く</b>だけのもの。
 *
 * <h2>スコープ (縮小版) — 何を含み、何を含まないか</h2>
 * <table>
 *   <tr><th>含む</th><th>含まない</th></tr>
 *   <tr>
 *     <td>不透明地形パス 1 本 (MDI 相当の間接描画)</td>
 *     <td>{@code buildDrawCalls} の 5 段 (prep / cull / cmdgen / prefixsum / translucentGen)</td>
 *   </tr>
 *   <tr><td>合成データからの CPU 側コマンド生成</td><td>temporal パス / 半透明パス</td></tr>
 *   <tr><td>descriptor 8 本のバインド</td><td>実ワールドのジオメトリとアトラス</td></tr>
 * </table>
 *
 * <p>5 段を式から除いたのは意図的である。Stage 2b で比較したいのは
 * <b>{@code quads3.vert} の索引解決と cmdgen の出力形式</b>であり、
 * cull / traversal を残すと差分の原因がそこまで広がる
 * (docs/phase4-proposal.md の Stage 1 縮小スコープ)。
 *
 * <h2>⚠ ここで「正しい」と言えるのは自己整合性まで</h2>
 * 入力は合成データなので、<b>「GL 版と同じ絵か」は一切検証していない</b>。
 * 検証しているのは「同じ入力から決定的に同じ絵が出るか」「索引をずらすと絵が変わるか」で、
 * これは Stage 2b の draw 統合を判定するのに必要十分である
 * (docs/phase4-proposal.md 7.1)。
 */
public class VkTerrainRenderer {

    /**
     * バリアの張り方。<b>両者が同じ絵を出すことがバリア設計の検証になる。</b>
     *
     * <p>手順は「まず {@link #CONSERVATIVE} で絵を出す → 系列表を見ながら
     * {@link #NARROW} に絞る → 差が出た箇所を記録する」。
     * <b>同期バリデーションがこの環境で機能しない</b>ため
     * (docs/phase3-completion.md 3.1)、
     * 「絞っても絵が変わらない」ことしか観測できない点に注意すること。
     * これは<b>安全の証明ではない</b> — 詳細は docs/phase4-stage1-completion.md。
     */
    public enum Barriers {
        /** {@code ALL_COMMANDS} の全書き。まず動かすためのもの。 */
        CONSERVATIVE,
        /** アクセス系列表 (docs/phase4-buffer-hazards.md) から導いた最小のもの。 */
        NARROW
    }

    /**
     * 索引の解決方法。<b>これ以外は何も変えない</b> — 同じ入力から
     * <b>ピクセル単位で同じ絵</b>が出ることが Stage 2b の合格条件である
     * (docs/phase4-proposal.md 4.1)。
     */
    public enum Mode {
        /**
         * Stage 1: (セクション, 面) ごとに 1 draw。
         * {@code gl_VertexIndex>>2} が quad 番号そのもの、セクションは {@code gl_BaseInstance}。
         * 使うのは無改造の {@code lod/gl46/quads3.vert}。
         */
        PER_SECTION("voxy:lod/gl46/quads3.vert"),
        /**
         * Stage 2b: 面方向別に 7 draws。
         * {@code gl_VertexIndex>>2} をグローバルな quad 通し番号とみなし、
         * prefix sum を二分探索して (quad, セクション) を復元する。
         * 使うのは Vulkan 専用の {@code lod/vk/quads3.vert}。
         */
        MERGED("voxy:lod/vk/quads3.vert");

        public final String vertexShader;
        Mode(String vertexShader) { this.vertexShader = vertexShader; }
    }

    /** 統合テーブルの binding 番号。0..7 は地形パイプラインが使用済み [確認済 — phase2-binding-audit 8.2]。 */
    public static final int MERGED_ENTRY_BINDING = 10;
    public static final int MERGED_PREFIX_BINDING = 11;

    /** 面区分の数 (6 方向 + 両面)。分割が起きると実際の draw 本数はこれより増える。 */
    public static final int FACE_COUNT = 7;

    /**
     * どのパスを描くか。
     *
     * <p>頂点シェーダは<b>どちらも同じ</b> ({@code lod/vk/quads3.vert})。
     * binding 10/11 に不透明のテーブルを張るか半透明のテーブルを張るかだけが違う。
     * Stage 2b で差分ゼロを確認した索引解決をそのまま使い回せる。
     */
    public enum Pass {
        /** 不透明。面方向別の draw。前フレームのテーブルで描く。 */
        OPAQUE,
        /**
         * temporal。<b>今フレーム新たに可視になった</b>セクションだけを描く。
         * 不透明パスが 1 フレーム遅れの可視集合を描くので、その取りこぼしを埋める
         * [確認済 — `AbstractRenderPipeline.runPipeline` の呼び出し順]。
         *
         * <p>使うのは不透明と同じ頂点シェーダ・同じエントリ配列で、
         * prefix と DrawCommand だけが temporal 用のものになる。
         */
        TEMPORAL,
        /**
         * 半透明。距離バケットごとの draw、ブレンド有効。
         * GL 側 {@code renderTranslucent} に対応する
         * ({@code TRANSLUCENT} define + {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA})。
         */
        TRANSLUCENT
    }

    /** 深度境界テクスチャの書式。GL 側は {@code GL_DEPTH_COMPONENT24} [確認済 — `DepthFramebuffer`]。 */
    public static final int DEPTH_BOUND_FORMAT = VK_FORMAT_D32_SFLOAT;

    private final Mode mode;
    private final Pass pass;
    private final VkTerrainResources res;
    private final VkAutoBindingShader shader;
    private final VkGraphicsPipeline pipeline;
    /**
     * {@code depthTex} (binding 2)。フラグメントシェーダが
     * {@code texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r} と比べて手前なら discard する。
     * 本番では前段が書いた深度境界だが、Stage 1 では
     * <b>{@link VkDepth#BOUND_NEUTRAL} で埋めて「誰も落とさない」</b>状態にする
     * (逆Zなので比較は {@code gl_FragCoord.z > bound}、境界が NEAR なら常に偽)。
     */
    private final VkTexture depthBound;
    /** パイプラインが宣言したカラーフォーマット。描画先と食い違うと未定義動作になる。 */
    private final int colorFormat;
    private final Barriers barriers;
    private boolean uploaded;
    private boolean freed;
    /**
     * 深度境界の値 (Phase 6 第二項目の測定用)。
     *
     * <p>本番の境界は<b>画素ごとに違う</b> — 上流は
     * {@code BoundRenderer} がバニラの読み込み済みチャンクの AABB の裏面を描いて作る
     * [確認済 — {@code VoxyRenderSystem:301}]。
     *
     * <p>⚠ ここで定数を入れられるようにしたのは<b>払い戻しを測るため</b>であって、
     * 境界の代用ではない。<b>「近景の断片を落としたら {@code opaque} がどれだけ下がるか」
     * の上限を知る</b>のが目的である。
     */
    private float depthBoundValue = VkDepth.BOUND_NEUTRAL;
    private boolean depthBoundDirty;

    /**
     * @param res      バインドするバッファ・テクスチャ一式
     * @param width    描画先の幅。{@code depthTex} を同寸で作るために要る
     *                 ({@code texelFetch} が {@code gl_FragCoord} をそのまま使うため)
     * @param height   描画先の高さ
     */
    public VkTerrainRenderer(VkTerrainResources res, int width, int height, Barriers barriers) {
        this(res, width, height, barriers, Mode.PER_SECTION);
    }

    public VkTerrainRenderer(VkTerrainResources res, int width, int height,
                             Barriers barriers, Mode mode) {
        this(res, width, height, barriers, mode, Pass.OPAQUE);
    }

    public VkTerrainRenderer(VkTerrainResources res, int width, int height,
                             Barriers barriers, Mode mode, Pass pass) {
        this(res, width, height, barriers, mode, pass, VkRenderTarget.FORMAT_COLOR);
    }

    /**
     * @param colorFormat 描画先カラーのフォーマット。
     *                    <b>interop に直接描くときは {@code VK_FORMAT_B8G8R8A8_UNORM}</b>
     *                    (IOSurface が受ける色は 'BGRA' だけ [確認済 — Phase 0 §8.2])。
     *                    シェーダから見た挙動は変わらない — フォーマットが
     *                    メモリ上の並びを吸収するので、書く値も読める値も同じになる。
     */
    public VkTerrainRenderer(VkTerrainResources res, int width, int height,
                             Barriers barriers, Mode mode, Pass pass, int colorFormat) {
        this.res = res;
        this.barriers = barriers;
        this.mode = mode;
        this.pass = pass;
        this.colorFormat = colorFormat;
        if (pass != Pass.OPAQUE && mode != Mode.MERGED) {
            throw new IllegalArgumentException(pass + " only exists in MERGED mode");
        }

        var builder = VkDepth.defines(VkShader.makeAuto().name("vk-terrain-" + mode.name().toLowerCase()))
            // 方向性ティント。#ifdef ガードが無いので未定義だとコンパイルできない
            // [確認済 — docs/phase2-glsl-compat.md 4]。本番は ClientLevel.cardinalLighting() 由来
            .define("NO_SHADE_FACE_TINT", "1.0")
            .define("UP_FACE_TINT", "1.0")
            .define("DOWN_FACE_TINT", "0.5")
            .define("Z_AXIS_FACE_TINT", "0.8")
            .define("X_AXIS_FACE_TINT", "0.6")
            // 半透明バリアント。quads.frag のアルファ判定が変わる [確認済 — #ifdef TRANSLUCENT]
            .defineIf("TRANSLUCENT", pass == Pass.TRANSLUCENT);
        if (mode == Mode.MERGED) {
            builder.define("MERGED_ENTRY_BINDING", MERGED_ENTRY_BINDING)
                   .define("MERGED_PREFIX_BINDING", MERGED_PREFIX_BINDING);
        }
        this.shader = builder
            .addSource(ShaderType.VERTEX, VkShaderLoader.parse(mode.vertexShader))
            .addSource(ShaderType.FRAGMENT, VkShaderLoader.parse("voxy:lod/gl46/quads.frag"))
            .compile();

        this.pipeline = VkGraphicsPipeline.builder(this.shader)
            .colorFormat(colorFormat)
            // GL 側は renderTerrain で glDisable(GL_CULL_FACE) している [確認済 — MDIC:200]。
            // 面の向きは quad の face で決まっており winding では決まらないため、
            // カリングを有効にすると半分が消える
            .cullMode(VK_CULL_MODE_NONE)
            .depthTest(true).depthWrite(true)
            // properties.closerEqualDepthCompare() の逆Z 側 = GL_GEQUAL [VkDepth]
            .depthCompare(VkDepth.COMPARE_OP)
            // GL 側 renderTranslucent の glBlendFuncSeparate と同じ組み合わせ
            // [確認済 — MDIC:245]。深度書き込みは GL 側も切っていないのでそのまま
            .alphaBlend(pass == Pass.TRANSLUCENT)
            .build();

        this.depthBound = new VkTexture(DEPTH_BOUND_FORMAT, 1, width, height,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT).name("depthBound");

        this.bindDescriptors();
    }

    /**
     * descriptor を張る。番号は {@code docs/phase2-binding-audit.md} 8.2 の表そのまま。
     *
     * <p>1 回だけ呼ぶ。以降 {@code rebuild} が立たないので、フレーム中の
     * in-flight 更新検出 ({@code VkAutoBindingShader.assertSafeToMutate}) には掛からない。
     */
    private void bindDescriptors() {
        this.shader
            .ubo(0, this.res.uniform, 0, VkSceneUniform.SIZE)
            .ssbo(1, this.res.geometry)
            .texture(2, this.depthBound, VkSampler.nearestClamp())
            .ssbo(3, this.res.model)
            .ssbo(4, this.res.modelColour)
            .ssbo(5, this.res.positionScratch)
            // ライトマップは GL 側 LINEAR だが、合成ライトマップは一様なので差が出ない
            .texture(6, this.res.lightmap, VkSampler.linearClamp())
            // ⚠ アトラスは NEAREST でなければならない。合成アトラスは
            // (modelId, face) 1 組につき 1 テクセルしか無いので、線形補間すると
            // 隣の組の色と混ざって「どの索引が引かれたか」が読めなくなる
            .texture(7, this.res.atlas, VkSampler.nearestClamp());

        if (this.mode == Mode.MERGED) {
            // ★ 同じ頂点シェーダに、パスに応じて別のテーブルを張る
            this.shader
                .ssbo(MERGED_ENTRY_BINDING, switch (this.pass) {
                    case TRANSLUCENT -> this.res.translucentEntry;
                    // temporal は不透明とエントリ配列を共有する
                    case OPAQUE, TEMPORAL -> this.res.mergedEntry;
                })
                .ssbo(MERGED_PREFIX_BINDING, switch (this.pass) {
                    case TRANSLUCENT -> this.res.translucentPrefix;
                    case TEMPORAL -> this.res.temporalPrefix;
                    case OPAQUE -> this.res.mergedPrefix;
                });
        }

        var missing = this.shader.unboundBindings();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("terrain shader has unbound descriptors: " + missing);
        }
    }

    // ---------------- recording ----------------

    /**
     * 初回だけ必要な転送を積む。アトラス/ライトマップの本体転送と深度境界のクリア。
     * {@link #record} が自動で呼ぶので通常は直接呼ばなくてよい。
     */
    public void recordUploads(VkCommandBuffer cmd) {
        if (this.uploaded) return;
        this.uploaded = true;

        this.res.recordAtlasUpload(cmd);

        // 深度境界を中立値で埋める = 誰も落とさない [VkDepth.BOUND_NEUTRAL]。
        // ⚠ 逆Zでは NEAR (1.0)。非逆Zの 0.0 のままにすると全部落ちる
        this.fillDepthBound(cmd, this.depthBoundValue);
    }

    /** 深度境界を定数で埋める。 */
    private void fillDepthBound(VkCommandBuffer cmd, float value) {
        this.depthBound.barrier(cmd, 0, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            var v = VkClearDepthStencilValue.calloc(stack).depth(value).stencil(0);
            var range = VkImageSubresourceRange.calloc(1, stack)
                .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            vkCmdClearDepthStencilImage(cmd, this.depthBound.image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, v, range);
        }
        this.depthBound.barrier(cmd, 0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    /**
     * 1 フレームぶんの地形描画を記録する。読み戻しは呼び出し側で
     * {@link VkRenderTarget#recordReadback} を呼ぶこと。
     *
     * @param drawCount   間接バッファに入っているコマンド数。
     *                    {@link Mode#MERGED} では面の分割ぶんを含む
     *                    ({@code SyntheticTerrain.faceDrawCount} / {@code maxFaceDrawCount})
     * @param clearColour null ならクリアしない
     */
    public void record(VkCommandBuffer cmd, VkRenderTarget target, int drawCount, float[] clearColour) {
        this.record(cmd, target, drawCount, clearColour, VkDepth.CLEAR);
    }

    /**
     * 深度のクリア値まで指定して記録する (Phase 5c-5a)。
     *
     * <h2>⚠ 同じ的に 2 本目以降を描くときは <b>{@code null}</b> でなければならない</h2>
     * temporal / 半透明は<b>不透明が描いた上に重ねる</b>パスである。
     * ここでクリアすると<b>不透明の絵と深度が丸ごと消え</b>、
     * 最後に描いたパスだけが残る。
     *
     * <p>⚠ <b>落ちない型である。</b> temporal は不透明の部分集合なので
     * 「絵が薄くなった」ようにしか見えず、半透明は<b>水だけの画面</b>になる。
     * どちらもバリデーションは何も言わない。
     *
     * @param clearDepth null ならクリアしない ({@code LOAD})。
     *                   不透明パスだけが {@link VkDepth#CLEAR} を渡す
     */
    /**
     * 深度境界を定数で埋める (Phase 6 第二項目の測定用)。
     *
     * @param value 逆Zの深度。{@link VkDepth#BOUND_NEUTRAL} なら<b>誰も落とさない</b>。
     *              小さいほど<b>手前の断片が多く落ちる</b>
     */
    public void setDepthBound(float value) {
        if (this.depthBoundValue != value) {
            this.depthBoundValue = value;
            this.depthBoundDirty = true;
        }
    }

    public float depthBoundValue() { return this.depthBoundValue; }

    public void record(VkCommandBuffer cmd, VkRenderTarget target, int drawCount,
                       float[] clearColour, Float clearDepth) {
        this.assertNotFreed();
        // フォーマットの食い違いは絵が微妙に狂う形で出る (R と B が入れ替わる等)。
        // バリデーションが無い実行でも確実に捕まえたいのでここで落とす
        if (target.colorFormat != this.colorFormat) {
            throw new IllegalArgumentException("target colour format 0x"
                + Integer.toHexString(target.colorFormat)
                + " does not match the pipeline's 0x" + Integer.toHexString(this.colorFormat));
        }
        this.recordUploads(cmd);
        if (this.depthBoundDirty) {
            this.depthBoundDirty = false;
            this.fillDepthBound(cmd, this.depthBoundValue);
        }
        this.hostWriteBarrier(cmd);

        target.beginRendering(cmd, clearColour, clearDepth);
        this.pipeline.bind(cmd);
        this.shader.bind(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS);
        vkCmdBindIndexBuffer(cmd, this.res.index.buffer.handle, 0, VK_INDEX_TYPE_UINT32);

        if (this.mode == Mode.MERGED) {
            // quad 数 0 の面も 1 本出す。instanceCount 0 の間接描画は仕様上の no-op であり、
            // 面の有無で発行数を変えるより単純。面が T を超えると分割ぶん本数が増える
            if (drawCount > 0) {
                var indirect = switch (this.pass) {
                    case TRANSLUCENT -> this.res.translucentDraw;
                    case TEMPORAL -> this.res.temporalDraw;
                    case OPAQUE -> this.res.mergedDraw;
                };
                vkCmdDrawIndexedIndirect(cmd, indirect.handle, 0,
                    drawCount, SyntheticTerrain.DRAW_COMMAND_SIZE);
            }
        } else if (drawCount > 0) {
            // GL 側は glMultiDrawElementsIndirectCountARB で「件数も GPU から」読むが、
            // Stage 1 はコマンドを CPU で作るので件数も CPU で分かる。
            // VK_KHR_draw_indirect_count への依存をここで持ち込まずに済む
            vkCmdDrawIndexedIndirect(cmd, this.res.drawCall.handle, 0,
                drawCount, SyntheticTerrain.DRAW_COMMAND_SIZE);
        }
        target.endRendering(cmd);
    }

    /**
     * ホストが書いた内容を GPU の読み手に見せるバリア。
     *
     * <h2>これが要る/要らない の理由 [確認済 — 仕様ベース]</h2>
     * Vulkan は <b>{@code vkQueueSubmit} より前に行われたホスト書き込みを
     * 暗黙に可視化する</b>と定めている。ユニファイドメモリで
     * {@code HOST_COHERENT} を使い、in-flight = 1 で記録中は GPU がアイドルな
     * 本構成では、実のところ <b>{@link Barriers#NARROW} の側すら省ける</b>。
     *
     * <p>それでも明示的に置いているのは、この保証が
     * 「submit 前に書いた」ことに依存しており、<b>アップロードを
     * {@code VkUploadStream} 経由や別スレッドに移した瞬間に崩れる</b>ためである。
     * 繰り返し踏んでいる誤りの型 (CPU 書き込みと GPU コマンドに順序が付かない) が
     * まさにここなので、依存関係をコードに残す。
     */
    private void hostWriteBarrier(VkCommandBuffer cmd) {
        if (this.barriers == Barriers.CONSERVATIVE) {
            VkBarriers.conservative(cmd, "stage1: host writes -> terrain draw");
            return;
        }
        VkBarriers.memoryBarrier(cmd,
            VK_PIPELINE_STAGE_2_HOST_BIT, VK_ACCESS_2_HOST_WRITE_BIT,
            // 間接コマンド (drawCall)、SSBO (geometry / model / modelColour / positionScratch)、
            // UBO (uniform) の 3 系統が読み手
            VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT
                | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT
                | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
            VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_UNIFORM_READ_BIT);
    }

    // ---------------- accessors ----------------

    public VkAutoBindingShader shader() { return this.shader; }
    public VkTexture depthBound() { return this.depthBound; }
    public Barriers barrierMode() { return this.barriers; }
    public Mode mode() { return this.mode; }
    public Pass pass() { return this.pass; }

    private void assertNotFreed() {
        if (this.freed) throw new IllegalStateException("VkTerrainRenderer already freed");
    }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        this.depthBound.free();
        this.pipeline.free();
        this.shader.free();
    }
}
