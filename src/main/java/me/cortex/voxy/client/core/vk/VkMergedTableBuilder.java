package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.shader.VkAutoBindingShader;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Stage 3: <b>統合描画テーブルを GPU 側で作る。</b>
 *
 * <p>Stage 1/2 で CPU に置いていた {@code cmdgen} 相当を GPU に戻したもの。
 * GL 側 {@code MDICSectionRenderer.buildDrawCalls} の 5 段のうち
 * <b>prep / cmdgen / prefix sum</b> に対応する。
 *
 * <h2>段の対応</h2>
 * <table>
 *   <tr><th>GL の段</th><th>ここ</th><th>備考</th></tr>
 *   <tr><td>prep</td><td>{@link #prep}</td><td>ディスパッチサイズとエントリ数</td></tr>
 *   <tr><td>cull (ラスタ)</td><td><b>未実装</b></td><td>可視判定は呼び出し側が {@code visibility} に書く</td></tr>
 *   <tr><td>cmdgen</td><td>{@link #cmdgen}</td><td>DrawCommand ではなくテーブルのエントリを書く</td></tr>
 *   <tr><td>prefixsum</td><td>{@link #prefix}</td><td>走査 + 面ごとの 7 DrawCommand</td></tr>
 *   <tr><td>translucentGen</td><td><b>未実装</b></td><td>半透明描画そのものが Stage 4</td></tr>
 * </table>
 *
 * <h2>期待される出力</h2>
 * <b>CPU 版 {@code SyntheticTerrain.mergedTable} とバイト単位で一致する。</b>
 * テーブルが密で atomic を使わないため、GPU 側も決定的になる
 * (詳細は {@code cmdgen.comp} のコメント)。
 */
public class VkMergedTableBuilder {
    /** {@code cmdgen} の間接ディスパッチサイズ。 */
    public static final int MERGED_DISPATCH_BINDING = 12;
    /** 面ごとの 7 DrawCommand。 */
    public static final int MERGED_DRAW_BINDING = 13;
    // ---- Stage 4a: 半透明 ----
    public static final int TRANSLUCENT_BUCKET_BINDING = 14;
    public static final int TRANSLUCENT_LIST_BINDING = 15;
    public static final int TRANSLUCENT_ENTRY_BINDING = 16;
    public static final int TRANSLUCENT_PREFIX_BINDING = 17;
    public static final int TRANSLUCENT_DRAW_BINDING = 18;
    public static final int TRANSLUCENT_STATS_BINDING = 19;
    // ---- Stage 4d: temporal ----
    public static final int TEMPORAL_PREFIX_BINDING = 20;
    public static final int TEMPORAL_DRAW_BINDING = 21;
    /** cull のラスタパスの間接描画コマンド。<b>prep だけが書く</b> (Phase 5c-5b2)。 */
    public static final int CULL_DRAW_BINDING = 22;

    private final VkTerrainResources res;
    private final VkTerrainRenderer.Barriers barriers;

    private final VkAutoBindingShader prepShader;
    private final VkAutoBindingShader cmdgenShader;
    private final VkAutoBindingShader prefixShader;
    private final VkAutoBindingShader temporalPrefixShader;
    private final long temporalPrefixPipeline;
    private final VkAutoBindingShader bucketScanShader;
    private final VkAutoBindingShader translucentGenShader;
    private final VkAutoBindingShader translucentPrefixShader;
    private final long prepPipeline;
    private final long cmdgenPipeline;
    private final long prefixPipeline;
    private final long bucketScanPipeline;
    private final long translucentGenPipeline;
    private final long translucentPrefixPipeline;
    private boolean freed;

    public VkMergedTableBuilder(VkTerrainResources res, VkTerrainRenderer.Barriers barriers) {
        this.res = res;
        this.barriers = barriers;

        this.prepShader = VkShader.makeAuto().name("vk-merged-prep")
            .define("MERGED_PREFIX_BINDING", VkTerrainRenderer.MERGED_PREFIX_BINDING)
            .define("MERGED_DISPATCH_BINDING", MERGED_DISPATCH_BINDING)
            .define("CULL_DRAW_BINDING", CULL_DRAW_BINDING)
            .apply(VkMergedTableBuilder::translucentDefines)
            .addSource(ShaderType.COMPUTE, VkShaderLoader.parse("voxy:lod/vk/prep.comp"))
            .compile();

        this.cmdgenShader = VkShader.makeAuto().name("vk-merged-cmdgen")
            .define("MERGED_ENTRY_BINDING", VkTerrainRenderer.MERGED_ENTRY_BINDING)
            .define("MERGED_PREFIX_BINDING", VkTerrainRenderer.MERGED_PREFIX_BINDING)
            .apply(VkMergedTableBuilder::translucentDefines)
            .addSource(ShaderType.COMPUTE, VkShaderLoader.parse("voxy:lod/vk/cmdgen.comp"))
            .compile();

        // temporal は **不透明とまったく同じシェーダ** を binding だけ差し替えて使う。
        // ソースが 1 本なので、走査と draw 生成の規則がずれようがない
        this.temporalPrefixShader = VkShader.makeAuto().name("vk-temporal-prefix")
            .define("MERGED_PREFIX_BINDING", TEMPORAL_PREFIX_BINDING)
            .define("MERGED_DRAW_BINDING", TEMPORAL_DRAW_BINDING)
            .addSource(ShaderType.COMPUTE, VkShaderLoader.parse("voxy:lod/vk/merged_prefix.comp"))
            .compile();

        // 距離バケット (1024 要素) の走査は既存シェーダをそのまま流用できる。
        // ちょうど 1024 要素を 1 ディスパッチで処理する作りで、
        // これはもともと半透明の距離バケット専用に書かれたものである [確認済]
        this.bucketScanShader = VkShader.makeAuto().name("vk-translucent-bucket-scan")
            .define("IO_BUFFER", TRANSLUCENT_BUCKET_BINDING)
            .addSource(ShaderType.COMPUTE, VkShaderLoader.parse("voxy:util/prefixsum/inital3.comp"))
            .compile();

        this.translucentGenShader = VkShader.makeAuto().name("vk-translucent-gen")
            .apply(VkMergedTableBuilder::translucentDefines)
            .addSource(ShaderType.COMPUTE, VkShaderLoader.parse("voxy:lod/vk/translucent_gen.comp"))
            .compile();

        this.translucentPrefixShader = VkShader.makeAuto().name("vk-translucent-prefix")
            .apply(VkMergedTableBuilder::translucentDefines)
            .addSource(ShaderType.COMPUTE, VkShaderLoader.parse("voxy:lod/vk/translucent_prefix.comp"))
            .compile();

        this.prefixShader = VkShader.makeAuto().name("vk-merged-prefix")
            .define("MERGED_PREFIX_BINDING", VkTerrainRenderer.MERGED_PREFIX_BINDING)
            .define("MERGED_DRAW_BINDING", MERGED_DRAW_BINDING)
            .addSource(ShaderType.COMPUTE, VkShaderLoader.parse("voxy:lod/vk/merged_prefix.comp"))
            .compile();

        this.prepPipeline = computePipeline(this.prepShader);
        this.cmdgenPipeline = computePipeline(this.cmdgenShader);
        this.prefixPipeline = computePipeline(this.prefixShader);
        this.temporalPrefixPipeline = computePipeline(this.temporalPrefixShader);
        this.bucketScanPipeline = computePipeline(this.bucketScanShader);
        this.translucentGenPipeline = computePipeline(this.translucentGenShader);
        this.translucentPrefixPipeline = computePipeline(this.translucentPrefixShader);

        this.bindDescriptors();
    }

    /** prep / cmdgen が共通で要る binding の define (半透明 + temporal)。 */
    private static void translucentDefines(VkShader.Builder<VkAutoBindingShader> b) {
        b.define("TEMPORAL_PREFIX_BINDING", TEMPORAL_PREFIX_BINDING)
         .define("TEMPORAL_DRAW_BINDING", TEMPORAL_DRAW_BINDING)
         .define("TRANSLUCENT_BUCKETS", VkTerrainResources.TRANSLUCENT_BUCKETS)
         .define("TRANSLUCENT_BUCKET_BINDING", TRANSLUCENT_BUCKET_BINDING)
         .define("TRANSLUCENT_LIST_BINDING", TRANSLUCENT_LIST_BINDING)
         .define("TRANSLUCENT_ENTRY_BINDING", TRANSLUCENT_ENTRY_BINDING)
         .define("TRANSLUCENT_PREFIX_BINDING", TRANSLUCENT_PREFIX_BINDING)
         .define("TRANSLUCENT_DRAW_BINDING", TRANSLUCENT_DRAW_BINDING)
         .define("TRANSLUCENT_STATS_BINDING", TRANSLUCENT_STATS_BINDING);
    }

    private static long computePipeline(VkShader shader) {
        try (MemoryStack stack = stackPush()) {
            var ci = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                .stage(shader.stageInfos(stack).get(0))
                .layout(shader.pipelineLayout());
            long[] p = new long[1];
            VkContext.check(vkCreateComputePipelines(
                VkContext.get().device, VK_NULL_HANDLE, ci, null, p), "vkCreateComputePipelines");
            return p[0];
        }
    }

    /**
     * シェーダがその binding を宣言しているときだけ SSBO を張る。
     *
     * <p>{@code bindings.glsl} は {@code SceneUniform} を無条件に宣言するので、
     * 使っていないシェーダでは SPIR-V 最適化で消える。消えたかどうかで
     * バインドの要否が変わるため、宣言の有無を見てから張る。
     */
    private void ssboIfDeclared(VkAutoBindingShader shader, int binding, VkBuffer buffer) {
        if (shader.bindingAt(binding) != null) shader.ssbo(binding, buffer);
    }

    private void uboIfDeclared(VkAutoBindingShader shader, int binding, VkBuffer buffer, long range) {
        if (shader.bindingAt(binding) != null) shader.ubo(binding, buffer, 0, range);
    }

    private void bindDescriptors() {
        // prep
        uboIfDeclared(this.prepShader, 0, this.res.uniform, VkSceneUniform.SIZE);
        ssboIfDeclared(this.prepShader, 5, this.res.indirectLookup);
        ssboIfDeclared(this.prepShader, VkTerrainRenderer.MERGED_PREFIX_BINDING, this.res.mergedPrefix);
        ssboIfDeclared(this.prepShader, MERGED_DISPATCH_BINDING, this.res.mergedDispatch);
        ssboIfDeclared(this.prepShader, CULL_DRAW_BINDING, this.res.cullDraw);
        bindTranslucent(this.prepShader);
        bindTemporal(this.prepShader);
        requireFullyBound(this.prepShader);

        // cmdgen
        uboIfDeclared(this.cmdgenShader, 0, this.res.uniform, VkSceneUniform.SIZE);
        ssboIfDeclared(this.cmdgenShader, 3, this.res.sectionMetadata);
        ssboIfDeclared(this.cmdgenShader, 4, this.res.visibility);
        ssboIfDeclared(this.cmdgenShader, 5, this.res.indirectLookup);
        ssboIfDeclared(this.cmdgenShader, 6, this.res.positionScratch);
        ssboIfDeclared(this.cmdgenShader, VkTerrainRenderer.MERGED_ENTRY_BINDING, this.res.mergedEntry);
        ssboIfDeclared(this.cmdgenShader, VkTerrainRenderer.MERGED_PREFIX_BINDING, this.res.mergedPrefix);
        bindTranslucent(this.cmdgenShader);
        bindTemporal(this.cmdgenShader);
        requireFullyBound(this.cmdgenShader);

        // prefix
        ssboIfDeclared(this.prefixShader, VkTerrainRenderer.MERGED_PREFIX_BINDING, this.res.mergedPrefix);
        ssboIfDeclared(this.prefixShader, MERGED_DRAW_BINDING, this.res.mergedDraw);
        requireFullyBound(this.prefixShader);

        // temporal (不透明と同じシェーダ、binding だけ違う)
        bindTemporal(this.temporalPrefixShader);
        requireFullyBound(this.temporalPrefixShader);

        // 半透明
        ssboIfDeclared(this.bucketScanShader, TRANSLUCENT_BUCKET_BINDING, this.res.translucentBucket);
        requireFullyBound(this.bucketScanShader);

        uboIfDeclared(this.translucentGenShader, 0, this.res.uniform, VkSceneUniform.SIZE);
        ssboIfDeclared(this.translucentGenShader, 3, this.res.sectionMetadata);
        ssboIfDeclared(this.translucentGenShader, 5, this.res.indirectLookup);
        bindTranslucent(this.translucentGenShader);
        requireFullyBound(this.translucentGenShader);

        bindTranslucent(this.translucentPrefixShader);
        requireFullyBound(this.translucentPrefixShader);
    }

    private void bindTemporal(VkAutoBindingShader shader) {
        ssboIfDeclared(shader, TEMPORAL_PREFIX_BINDING, this.res.temporalPrefix);
        ssboIfDeclared(shader, TEMPORAL_DRAW_BINDING, this.res.temporalDraw);
    }

    private void bindTranslucent(VkAutoBindingShader shader) {
        ssboIfDeclared(shader, TRANSLUCENT_BUCKET_BINDING, this.res.translucentBucket);
        ssboIfDeclared(shader, TRANSLUCENT_LIST_BINDING, this.res.translucentList);
        ssboIfDeclared(shader, TRANSLUCENT_ENTRY_BINDING, this.res.translucentEntry);
        ssboIfDeclared(shader, TRANSLUCENT_PREFIX_BINDING, this.res.translucentPrefix);
        ssboIfDeclared(shader, TRANSLUCENT_DRAW_BINDING, this.res.translucentDraw);
        ssboIfDeclared(shader, TRANSLUCENT_STATS_BINDING, this.res.translucentStats);
    }

    private static void requireFullyBound(VkAutoBindingShader shader) {
        var missing = shader.unboundBindings();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("shader '" + shader.debugName()
                + "' has unbound descriptors: " + missing);
        }
    }

    // ---------------- recording ----------------

    /**
     * テーブル生成の 3 段を記録する。
     *
     * <p><b>呼び出し順の前提</b>: GL 側と同じく、このフレームの描画
     * ({@code renderOpaque}) は<b>これより前</b>に、前フレームのテーブルで行われる。
     * その順序が {@code docs/phase4-buffer-hazards.md} の WAR を生む。
     *
     * @param sectionCount {@code indirectLookup} に入っているセクション数。
     *                     prefix シェーダが面の区間を切り出すのに要る
     * @param maxDraws     間接描画に渡す draw 本数。面が T を超えると分割されるので
     *                     {@code SyntheticTerrain.maxFaceDrawCount} の上限を渡す。
     *                     余ったスロットは {@code instanceCount = 0} で埋まる
     */
    public void record(VkCommandBuffer cmd, int sectionCount, int maxDraws) {
        this.recordPrep(cmd);
        this.recordAfterPrep(cmd, sectionCount, maxDraws);
    }

    /**
     * <b>prep だけ</b>を記録する (Phase 5c-5b2)。
     *
     * <p>参照実装は <b>prep と cmdgen の間に cull のラスタパス</b>を挟む
     * [確認済 — {@code MDICSectionRenderer.buildDrawCalls}]。
     * cull はそこで<b>prep が書いた間接描画コマンド</b>を使うので、
     * セクション数が GPU 側の値になる。
     */
    public void recordPrep(VkCommandBuffer cmd) {
        this.assertNotFreed();

        // ① ホスト書き込み (metadata / visibility / lookup / uniform) -> prep と cmdgen の読み。
        //    さらに **前フレームの描画がこれらのバッファを読んだこと** との WAR も
        //    ここで断ち切る。詳細は narrowBarrier のコメント
        this.beforeTableBuild(cmd);

        // ② prep: ディスパッチサイズとエントリ数、そして cull の間接描画コマンドを書く
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.prepPipeline);
        this.prepShader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
        vkCmdDispatch(cmd, 1, 1, 1);

        // ③ prep が書いた内容を **間接ディスパッチ / 間接描画の読み** と
        //    cmdgen の SSBO 読みへ。
        //    docs/phase4-buffer-hazards.md 10.2 の訂正 #4: 直後が間接読みなので
        //    P1 (SHADER_STORAGE のみ) では足りず INDIRECT_COMMAND_READ が要る
        this.prepToCmdgen(cmd);
    }

    /** prep の<b>後</b>を記録する。cull を挟むならこの前に積む。 */
    public void recordAfterPrep(VkCommandBuffer cmd, int sectionCount, int maxDraws) {
        this.assertNotFreed();

        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.cmdgenPipeline);
        this.cmdgenShader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
        vkCmdDispatchIndirect(cmd, this.res.mergedDispatch.handle, 0);

        // ④ cmdgen が書いた quad 数を prefix が読む (RAW)。同じ配列を in-place で書き換える
        this.cmdgenToPrefix(cmd);

        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.prefixPipeline);
        this.prefixShader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
        this.prefixShader.pushUInt(0, sectionCount);
        this.prefixShader.pushUInt(4, this.res.indexQuadCapacity);
        this.prefixShader.pushUInt(8, maxDraws);
        this.prefixShader.flushPushConstants(cmd);
        vkCmdDispatch(cmd, 1, 1, 1);

        // ⑤ temporal: 不透明と同じ走査を、絞り込んだ quad 数の配列に対して行う
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.temporalPrefixPipeline);
        this.temporalPrefixShader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
        this.temporalPrefixShader.pushUInt(0, sectionCount);
        this.temporalPrefixShader.pushUInt(4, this.res.indexQuadCapacity);
        this.temporalPrefixShader.pushUInt(8, maxDraws);
        this.temporalPrefixShader.flushPushConstants(cmd);
        vkCmdDispatch(cmd, 1, 1, 1);

        // ⑥ 半透明: バケット走査 -> 並べ替え -> quad 走査 + バケットごとの draw
        //    ⚠ 段の間はすべて RAW。バケット配列は 3 段が続けて触る
        this.cmdgenToPrefix(cmd);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.bucketScanPipeline);
        this.bucketScanShader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
        vkCmdDispatch(cmd, 1, 1, 1);

        this.cmdgenToPrefix(cmd);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.translucentGenPipeline);
        this.translucentGenShader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
        vkCmdDispatch(cmd, (sectionCount + 127) / 128, 1, 1);

        this.cmdgenToPrefix(cmd);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.translucentPrefixPipeline);
        this.translucentPrefixShader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
        this.translucentPrefixShader.pushUInt(0, this.res.indexQuadCapacity);
        this.translucentPrefixShader.flushPushConstants(cmd);
        vkCmdDispatch(cmd, 1, 1, 1);

        // ⑦ 生成した DrawCommand とテーブルを描画が読む
        this.prefixToDraw(cmd);
    }

    // ---------------- barriers ----------------

    /**
     * ホスト書き込み → テーブル生成、および<b>前フレームの描画 → テーブル生成の WAR</b>。
     *
     * <h2>WAR がここに現れる理由 [確認済 — docs/phase4-buffer-hazards.md 10.1]</h2>
     * 描画はこのフレームの<b>前</b>に走り、前フレームのテーブルを読む。
     * そのあとで cmdgen が同じバッファを上書きする。したがって
     *
     * <pre>
     *   描画の頂点シェーダ SSBO 読み (mergedEntry / mergedPrefix / positionScratch)
     *   描画の間接コマンド読み       (mergedDraw)
     *        ↓ ここで断ち切らないと上書きが先行しうる
     *   cmdgen / prefix の書き込み
     * </pre>
     *
     * <b>GL にはこれに対応するバリアが無い</b> (実装の順序保証に委ねていた)。
     * WAR は実行依存だけで足りるので dstAccess に書き込みだけを立て、
     * srcAccess は 0 でよい — が、ここでは読み手が確実に終わってから書くことを
     * 明示するため srcStage に読み手のステージを列挙する。
     */
    private void beforeTableBuild(VkCommandBuffer cmd) {
        if (this.barriers == VkTerrainRenderer.Barriers.CONSERVATIVE) {
            VkBarriers.conservative(cmd, "stage3: host writes + previous draw -> table build");
            return;
        }
        VkBarriers.memoryBarrier(cmd,
            // 読み手: 前フレームの描画 (間接コマンド + 頂点シェーダ) と ホスト書き込み
            VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT
                | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT
                | VK_PIPELINE_STAGE_2_HOST_BIT,
            VK_ACCESS_2_HOST_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT
                | VK_ACCESS_2_UNIFORM_READ_BIT);
    }

    /** prep の書き込み → 間接ディスパッチの読み + cmdgen の SSBO 読み。 */
    private void prepToCmdgen(VkCommandBuffer cmd) {
        if (this.barriers == VkTerrainRenderer.Barriers.CONSERVATIVE) {
            VkBarriers.conservative(cmd, "stage3: prep -> cmdgen");
            return;
        }
        VkBarriers.computeToIndirect(cmd, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT);
    }

    /** cmdgen の書き込み → prefix の読み書き。純粋な compute → compute。 */
    private void cmdgenToPrefix(VkCommandBuffer cmd) {
        if (this.barriers == VkTerrainRenderer.Barriers.CONSERVATIVE) {
            VkBarriers.conservative(cmd, "stage3: cmdgen -> prefix");
            return;
        }
        VkBarriers.computeToCompute(cmd);
    }

    /** prefix の書き込み → 間接描画の読み + 頂点シェーダの SSBO 読み。 */
    private void prefixToDraw(VkCommandBuffer cmd) {
        if (this.barriers == VkTerrainRenderer.Barriers.CONSERVATIVE) {
            VkBarriers.conservative(cmd, "stage3: prefix -> draw");
            return;
        }
        VkBarriers.computeToIndirect(cmd, VkBarriers.INDIRECT_DRAW_CONSUMERS);
    }

    // ---------------- lifecycle ----------------

    private void assertNotFreed() {
        if (this.freed) throw new IllegalStateException("VkMergedTableBuilder already freed");
    }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        var dev = VkContext.get().device;
        vkDestroyPipeline(dev, this.prepPipeline, null);
        vkDestroyPipeline(dev, this.cmdgenPipeline, null);
        vkDestroyPipeline(dev, this.prefixPipeline, null);
        vkDestroyPipeline(dev, this.temporalPrefixPipeline, null);
        vkDestroyPipeline(dev, this.bucketScanPipeline, null);
        vkDestroyPipeline(dev, this.translucentGenPipeline, null);
        vkDestroyPipeline(dev, this.translucentPrefixPipeline, null);
        this.prepShader.free();
        this.cmdgenShader.free();
        this.prefixShader.free();
        this.temporalPrefixShader.free();
        this.bucketScanShader.free();
        this.translucentGenShader.free();
        this.translucentPrefixShader.free();
    }
}
