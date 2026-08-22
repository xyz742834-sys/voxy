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
    /** 既にメッシュ化を試した位置。監視集合との差分を取るのに使う。 */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet pendingMesh =
        new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

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

        // ⚠ **焼いたタイルをアトラスへ流す。** これを忘れると
        // `useExternalAtlasContent()` で合成の中身も止めているので
        // **アトラスが未初期化のまま**になる。落ちないし絵も出る — 一色になるだけである
        this.bakery.recordUploads(cmd);

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

    /**
     * <b>トラバーサルの要求に答える</b> (Phase 5c-4c)。フレームの完了後に呼ぶこと。
     *
     * <h2>⚠ これが無いと木は深くならない</h2>
     * トラバーサルは「降りたいが子がいない」ノードを要求キューに積む
     * [{@code traversal_dev.comp} の {@code addRequest}]。
     * 誰も答えなければ<b>最上位ノードを描き続ける</b> —
     * 実際に {@code drawn=27} (= 最上位の数) のまま動かなかった。
     *
     * <p>⚠ 要求は<b>1 度しか積まれない</b> ({@code markRequested} が印を付ける)。
     * 「要求数が 0 だから要求していない」ではない — <b>既に答え待ちかもしれない</b>。
     *
     * <h2>位置の詰め方</h2>
     * 要求キューに入るのは {@code getRawPos(node)} = <b>GPU の詰め方</b> (px, py) である。
     * {@code NodeManager} が要るのは<b>ワールドのキー</b>。
     * 両者は<b>ワードを入れ替えたもの</b>なので、組み直すだけでよい
     * [確認済 — {@code VkRealPositionTest.theWorldKeyIsPackPositionWithItsWordsSwapped}]。
     *
     * @param maxMeshesPerCall 1 回でメッシュ化する上限。<b>止めないため</b>に要る
     * @return 新しくメッシュ化した数
     */
    public int serviceRequests(int maxMeshesPerCall) {
        int count = Math.min(org.lwjgl.system.MemoryUtil.memGetInt(this.traversal.request.addr()),
            4096);
        for (int i = 0; i < count; i++) {
            long e = this.traversal.request.addr() + 8L + (long) i * 8L;
            int px = org.lwjgl.system.MemoryUtil.memGetInt(e);
            int py = org.lwjgl.system.MemoryUtil.memGetInt(e + 4);
            // ⚠ ワールドのキーは px が上位ワード
            long key = (Integer.toUnsignedLong(px) << 32) | Integer.toUnsignedLong(py);
            try {
                this.nodes.processRequest(key);
            } catch (RuntimeException ex) {
                Logger.warn("[5c-4c] request for " + WorldEngine.pprintPos(key)
                    + " was rejected: " + ex);
            }
        }

        // ⚠ NodeManager が新しく監視し始めた位置には、まだジオメトリが無い。
        // **監視集合との差分**が「メッシュ化すべきもの」である
        int meshed = 0;
        for (long pos : this.watcher.watched.keySet().toLongArray()) {
            if (meshed >= maxMeshesPerCall) break;
            if (!this.pendingMesh.add(pos)) continue;   // 済み
            int lvl = WorldEngine.getLevel(pos);
            var built = this.mesher.meshOne(lvl, WorldEngine.getX(pos),
                WorldEngine.getY(pos), WorldEngine.getZ(pos));
            if (built == null) continue;
            this.nodes.processGeometryResult(built);
            this.meshedSections++;
            meshed++;
        }
        return meshed;
    }

    /**
     * <b>描画キューに入った不正な id を数える</b> (Phase 5c-4c)。
     *
     * <h2>⚠ 上流は「葉ノードは必ずメッシュを持つ」を前提にしている</h2>
     * {@code traversal_dev.comp} の自己描画の枝は <b>{@code hasMesh} を見ていない</b> —
     * コメントに「is error state if it doesnt have one since all leaf nodes
     * should have a mesh」とある。
     *
     * <p><b>その前提をこちらの組み立てが破る。</b> 最上位ノードを先に作ってから
     * メッシュを流し込むので、<b>メッシュ未着のノードが存在する</b>。
     * それが {@code NULL_MESH} のまま描画キューへ入り、
     * cmdgen が<b>範囲外のセクションメタデータ</b>を読む。
     *
     * <p>⚠ 落ちない。バリデーションも捕まえない。<b>絵が出ないだけ</b>である。
     *
     * @return {@code {不正な id の数, 全体}}
     */
    public int[] countInvalidRenderIds() {
        int n = this.drawnSectionCount();
        int bad = 0;
        for (int i = 0; i < n; i++) {
            int id = org.lwjgl.system.MemoryUtil.memGetInt(
                this.res.indirectLookup.addr() + 4L + (long) i * 4L);
            // NULL_MESH / EMPTY_MESH / 範囲外
            if (id < 0 || id >= this.maxSections
                || id == VkNodeTree.NULL_MESH || id == VkNodeTree.EMPTY_MESH) {
                bad++;
            }
        }
        return new int[]{bad, n};
    }

    /**
     * <b>密テーブルが実際に何 quad ぶんを指しているか</b> (Phase 5c-4c)。
     *
     * <p>描画が空になったとき、原因が<b>テーブルの側</b>か<b>描画の側</b>かを分ける。
     * <ul>
     *   <li>合計が 0 → cmdgen までで空になっている (テーブルの側)</li>
     *   <li>合計 &gt; 0 なのに画素が 0 → <b>描画が落としている</b></li>
     * </ul>
     *
     * @return {@code {エントリ数, 末尾の prefix = 総 quad 数}}
     */
    public int[] mergedTableTotals() {
        long base = this.res.mergedPrefix.addr();
        int entries = org.lwjgl.system.MemoryUtil.memGetInt(base);
        int max = (int) ((this.res.mergedPrefix.size() - 4) / 4) - 1;
        if (entries < 0 || entries > max) return new int[]{entries, -1};
        int total = org.lwjgl.system.MemoryUtil.memGetInt(base + 4L + (long) entries * 4L);
        return new int[]{entries, total};
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
