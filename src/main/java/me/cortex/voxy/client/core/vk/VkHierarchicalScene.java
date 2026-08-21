package me.cortex.voxy.client.core.vk;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.core.rendering.ISectionWatcher;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Phase 5c-4c — <b>実データの階層トラバーサル</b>。Voxy の本質が初めて動く段。
 *
 * <h2>この段で初めて起きること</h2>
 * <ul>
 *   <li><b>遠景が粗い LoD で描かれる</b> — 5c-3b までは LoD 0 固定だった</li>
 *   <li><b>何を描くかを GPU が決める</b> — CPU が全部並べていた経路が閉じる</li>
 *   <li><b>フレーム時間に意味が出る</b> — MC の描画距離の外を描くので二重描画にならない</li>
 * </ul>
 *
 * <h2>⚠ 縮小した範囲 (承知の上)</h2>
 * <table>
 *   <tr><th>本番</th><th>ここ</th></tr>
 *   <tr><td>トラバーサルの要求に応じて非同期にメッシュ化</td>
 *       <td><b>先に全部メッシュ化して渡す</b></td></tr>
 *   <tr><td>{@code AsyncNodeManager} が二重バッファで流す</td><td><b>同期</b></td></tr>
 *   <tr><td>{@code NodeCleaner} が使われないノードを回収</td><td>回収しない</td></tr>
 * </table>
 *
 * <p><b>この段で見たいのは選択の結果と内訳の時間</b>であって、非同期の調停ではない。
 * 5c-3b でメッシュ化を同期にしたのと同じ判断である。
 *
 * <h2>⚠ 描画キューは {@code indirectLookup} そのもの</h2>
 * トラバーサルの描画キューと {@code IndirectSectionLookupBuffer} は
 * <b>同じ形</b> ({@code uint count; uint ids[]}) である [確認済 — {@code bindings.glsl}]。
 * 同じバッファを使えば<b>写す手が要らない</b>。
 *
 * <p>⚠ 密テーブルは {@code sectionCount * 7} で確保される。
 * <b>トラバーサルが選んだ数</b>でスケールするので、
 * {@code maxSections} を描画キューの容量以上にしておくこと。
 */
public final class VkHierarchicalScene {
    /** 監視状態だけを持つ最小の実装。回収しないので減ることはない。 */
    private static final class Watcher implements ISectionWatcher {
        private final Long2IntOpenHashMap watched = new Long2IntOpenHashMap();
        @Override public boolean watch(long position, int types) {
            this.watched.put(position, this.watched.get(position) | types);
            return true;
        }
        @Override public boolean unwatch(long position, int types) {
            int v = this.watched.get(position) & ~types;
            if (v == 0) this.watched.remove(position); else this.watched.put(position, v);
            return true;
        }
        @Override public int get(long position) { return this.watched.get(position); }
    }

    /**
     * <b>2 の冪へ切り上げる</b> (既に 2 の冪ならそのまま)。
     *
     * <p>⚠ {@code NodeManager} と {@code AbstractSectionGeometryManager} が
     * <b>2 の冪を要求する</b>。実際に 20000 を渡して
     * {@code "Max node count must be a power of 2"} で落ちた。
     *
     * <p>⚠ <b>既に 2 の冪なら増やさない</b> — 増やすと確保が黙って倍になる。
     */
    public static int roundUpToPowerOfTwo(int v) {
        if (v < 4) return 4;
        return Integer.highestOneBit(v - 1) << 1;
    }

    /** GPU の区間。⚠ 名前は<b>疑う先</b>に対応させる [Phase 6]。 */
    public static final String[] SPANS = {"hiz", "traversal", "table", "draw", "resolve"};

    public final VkTerrainResources res;
    private final Watcher watcher = new Watcher();
    private final BasicAsyncGeometryManager geometry;
    private final NodeManager nodes;
    private final VkModelUploadTarget modelTarget;
    private final VkRealModelBakery bakery;
    private final VkRealMesher mesher;
    private final VkNodeUploadTarget nodeTarget;
    private final VkTraversal traversal;
    private final VkHiZ hiz;
    private final VkMergedTableBuilder table;
    private final VkTerrainRenderer renderer;
    private final VkGpuTimer timer = new VkGpuTimer(SPANS);
    private final int maxSections;
    /**
     * 間接描画に渡す draw 本数。
     *
     * <p>⚠ <b>密テーブルのエントリ数 ({@code sectionCount * 7}) とは別物である。</b>
     * 統合描画では面ごとに 1 本で足り、索引バッファを超えた分だけ分割される
     * [確認済 — {@code SyntheticTerrain.maxFaceDrawCount} = {@code 7 + quads/capacity}]。
     * エントリ数を渡すと <b>{@code mergedDraw} を溢れさせる</b> — 最初そう書いた。
     */
    private final int maxDraws;
    private boolean freed;

    private int topLevelCount;
    private int meshedSections;

    /**
     * @param depthSource 深度解決の出力 (HiZ の元)
     * @param maxSections 描画キューと密テーブルの容量。<b>選ばれうるセクション数の上限</b>
     */
    public VkHierarchicalScene(WorldEngine world, VkTexture depthSource,
                               int width, int height, int requestedSections, int maxQuads,
                               int colourFormat) {
        // ⚠ {@code NodeManager} も {@code AbstractSectionGeometryManager} も
        // **2 の冪を要求する** [確認済 — どちらもコンストラクタで弾く]。
        // 呼び出し側に押し付けず、ここで切り上げる
        int maxSections = roundUpToPowerOfTwo(requestedSections);
        if (maxSections != requestedSections) {
            Logger.info("[5c-4c] rounded the section capacity " + requestedSections
                + " up to " + maxSections + " (must be a power of two)");
        }
        this.maxSections = maxSections;
        this.maxDraws = SyntheticTerrain.maxFaceDrawCount(maxQuads,
            VkQuadIndexBuffer.DEFAULT_QUAD_CAPACITY);
        this.res = new VkTerrainResources(maxSections, maxQuads, maxSections * 7, 1 << 16,
            VkQuadIndexBuffer.DEFAULT_QUAD_CAPACITY, VkTerrainResources.AtlasScale.REAL);
        this.res.useExternalAtlasContent();

        this.modelTarget = new VkModelUploadTarget(this.res);
        // ⚠ ワールドの Mapper を借りる。新しく作るとブロック id が全部別物になる [5c-3b]
        this.bakery = new VkRealModelBakery(this.modelTarget, world.getMapper());
        this.mesher = new VkRealMesher(world, this.bakery);

        this.geometry = new BasicAsyncGeometryManager(maxSections, (long) maxQuads * 8);
        this.nodes = new NodeManager(maxSections, this.geometry, this.watcher);

        this.hiz = new VkHiZ(depthSource, width, height);
        // ⚠ 描画キューに indirectLookup をそのまま渡す。cmdgen が直接読む
        this.traversal = new VkTraversal(this.hiz.texture(), maxSections, maxSections,
            (int) ((this.res.indirectLookup.size() - 4) / 4), 4096, this.res.indirectLookup);
        this.nodeTarget = new VkNodeUploadTarget(this.traversal.nodeData);

        this.table = new VkMergedTableBuilder(this.res, VkTerrainRenderer.Barriers.CONSERVATIVE);
        this.renderer = new VkTerrainRenderer(this.res, width, height,
            VkTerrainRenderer.Barriers.CONSERVATIVE, VkTerrainRenderer.Mode.MERGED,
            VkTerrainRenderer.Pass.OPAQUE, colourFormat);
    }

    /**
     * カメラ周辺を<b>先にメッシュ化して</b>木に入れる。
     *
     * <p>⚠ 本番は要求に応じて非同期に行う。ここは同期で、
     * <b>最上位から {@code depth} 段ぶん</b>を一度に入れる。
     *
     * @param topRadius 最上位ノードの半径 (セクション単位、{@code MAX_LOD_LAYER} の粒度)
     * @param depth     何段下まで入れるか。0 なら最上位だけ
     */
    public void populate(double camX, double camY, double camZ, int topRadius, int depth) {
        int top = WorldEngine.MAX_LOD_LAYER;
        int cx = VkHostViewport.sectionOf(camX) >> top;
        int cy = VkHostViewport.sectionOf(camY) >> top;
        int cz = VkHostViewport.sectionOf(camZ) >> top;

        for (int dx = -topRadius; dx <= topRadius; dx++) {
            for (int dy = -topRadius; dy <= topRadius; dy++) {
                for (int dz = -topRadius; dz <= topRadius; dz++) {
                    long pos = WorldEngine.getWorldSectionId(top, cx + dx, cy + dy, cz + dz);
                    this.nodes.insertTopLevelNode(pos);
                    this.topLevelCount++;
                }
            }
        }

        // ⚠ 粗いほうから順に入れる。細かいほうを先に入れると、
        // NodeManager が親をまだ知らない状態で子を受け取ることになる
        for (int level = top; level >= Math.max(0, top - depth); level--) {
            int r = (topRadius + 1) << (top - level);
            var built = this.mesher.meshAround(cx << (top - level), cy << (top - level),
                cz << (top - level), r, level);
            for (var b : built) {
                this.meshedSections++;
                this.nodes.processGeometryResult(b);   // ⚠ 所有権が移る。free しない
            }
        }
        this.bakery.replayBiomes();
        Logger.info("[5c-4c] populated " + this.topLevelCount + " top-level nodes (LoD " + top
            + "), meshed " + this.meshedSections + " sections down to LoD "
            + Math.max(0, top - depth));
    }

    /** ⚠ フレームの記録の<b>前</b>に呼ぶこと。ホスト側の書き込みを済ませる。 */
    public VkGeometryFlush.Result prepare(Matrix4fc mvp, int[] camSection, float[] camSubPos,
                                          float minScreenSize, int frameId, float renderDistance) {
        this.nodes.writeChanges(this.nodeTarget);
        var flushed = VkGeometryFlush.flush(this.geometry, this.res.geometry, this.res.sectionMetadata);
        this.traversal.writeUniform(mvp, camSection, camSubPos, this.hiz.packedSize(),
            minScreenSize, VkHostViewport.frustumPlanes(mvp), frameId, renderDistance);
        this.traversal.reset(this.topLevelCount);
        return flushed;
    }

    /**
     * 1 フレームぶんを記録する。区間ごとに時刻を打つ。
     *
     * <p>⚠ <b>順序は GL 版と同じ</b>: このフレームの描画は
     * <b>前フレームのテーブル</b>で行われる [{@code VkMergedTableBuilder.record} の前提]。
     */
    public void record(VkCommandBuffer cmd, VkRenderTarget target, float[] clearColour,
                       VkInteropDepth depthOut) {
        this.timer.reset(cmd);
        this.timer.mark(cmd, 0);

        this.hiz.record(cmd);
        this.timer.mark(cmd, 1);

        this.traversal.record(cmd, this.topLevelCount);
        this.timer.mark(cmd, 2);

        int drawn = this.drawnSectionCount();
        this.table.record(cmd, drawn, this.maxDraws);
        this.timer.mark(cmd, 3);

        this.renderer.record(cmd, target, this.maxDraws, clearColour);
        this.timer.mark(cmd, 4);

        if (depthOut != null) depthOut.resolve(cmd, target.depth);
        this.timer.mark(cmd, 5);
    }

    /** 深度解決を差し替えられるようにするだけの薄い口。 */
    public interface VkInteropDepth {
        void resolve(VkCommandBuffer cmd, VkTexture depth);
    }

    /** トラバーサルが選んだセクション数 (= {@code indirectLookup} の先頭)。 */
    public int drawnSectionCount() {
        return Math.min(org.lwjgl.system.MemoryUtil.memGetInt(this.res.indirectLookup.addr()),
            this.maxSections);
    }

    public VkGpuTimer timer() { return this.timer; }
    public VkTraversal traversal() { return this.traversal; }
    public NodeManager nodes() { return this.nodes; }
    public int topLevelCount() { return this.topLevelCount; }
    public int meshedSections() { return this.meshedSections; }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        this.timer.free();
        this.renderer.free();
        this.table.free();
        this.traversal.free();
        this.hiz.free();
        this.mesher.free();
        this.bakery.free();
        this.modelTarget.free();
        this.res.free();
    }
}
