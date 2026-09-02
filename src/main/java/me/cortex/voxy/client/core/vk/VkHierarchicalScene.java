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
     * <b>最上位ノード id のキュー</b> (Phase 5c-5a の修正)。
     *
     * <h2>⚠ これが無いとトラバーサルは根を 1 つも見ない</h2>
     * トラバーサルの 0 回目は {@code topNodeIds} を<b>ソースキュー</b>として読む
     * [確認済 — {@code VkDescriptorSetGroup} の variant 0]。
     * <b>誰も書かなければゼロのまま</b>なので、
     * {@code reset(topNodeCount)} が {@code n} を渡しても
     * <b>ノード id 0 を n 回訪問する</b>だけになる。
     *
     * <p>⚠ <b>落ちない。</b> id 0 がたまたま実在の最上位ノードなら
     * <b>そこから降りた分だけ絵が出る</b> — 27 個の根のうち 1 個だけで
     * 動いているのに、動いているように見える。
     *
     * <h2>入れ替え方式は GL 版と同じ</h2>
     * {@code HierarchicalOcclusionTraverser.addTLN / remTLN} をそのまま写した。
     * 削除は<b>末尾を空いた位置へ移す</b>ので、[0, count) が常に詰まっている。
     *
     * <p>⚠ 空いた末尾は<b>消さない</b> (GL 版も消していない)。
     * トラバーサルは [0, count) しか読まないので害は無いが、
     * <b>バッファを直接覗くと古い id が残って見える</b>。
     */
    public static final class TopLevelNodeQueue {
        /** 書き出し先。装置を要らなくするために切ってある。 */
        public interface Sink { void write(int index, int nodeId); }

        private final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap id2idx =
            new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        private final int[] idx2id;
        private final Sink sink;
        private int count;

        public TopLevelNodeQueue(int capacity, Sink sink) {
            this.idx2id = new int[capacity];
            this.sink = sink;
            this.id2idx.defaultReturnValue(-1);
        }

        public void add(int nodeId) {
            if (this.count >= this.idx2id.length) {
                throw new IllegalStateException("more than " + this.idx2id.length
                    + " top-level nodes; raise the traversal queue capacity");
            }
            int idx = this.count++;
            if (this.id2idx.put(nodeId, idx) != -1) {
                throw new IllegalStateException("node " + nodeId + " is already top-level");
            }
            this.idx2id[idx] = nodeId;
            this.sink.write(idx, nodeId);
        }

        public void remove(int nodeId) {
            int idx = this.id2idx.remove(nodeId);
            if (idx == -1) {
                throw new IllegalStateException("node " + nodeId + " is not top-level");
            }
            this.count--;
            // 末尾そのものなら詰めるものが無い
            if (idx == this.count) return;
            int moved = this.idx2id[this.count];
            this.idx2id[idx] = moved;
            this.id2idx.put(moved, idx);
            this.sink.write(idx, moved);
        }

        public int count() { return this.count; }

        /** ⚠ [0, {@link #count()}) だけが有効である。 */
        public int idAt(int index) {
            if (index < 0 || index >= this.count) {
                throw new IndexOutOfBoundsException(index + " of " + this.count);
            }
            return this.idx2id[index];
        }
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

    /**
     * GPU の区間。⚠ 名前は<b>疑う先</b>に対応させる [Phase 6]。
     *
     * <p>⚠ 5c-5b1 で<b>並びが参照実装に揃った</b>ので、5c-4c の内訳とは
     * <b>直接比べられない</b>: 旧 {@code draw} は {@code opaque} と {@code temporal}
     * に分かれている。
     */
    public static final String[] SPANS =
        {"opaque", "hiz", "traversal", "cull", "table", "temporal", "resolve"};

    /**
     * <b>可視バッファを誰がどう書くか</b> (Phase 5c-5a)。
     *
     * <h2>なぜモードにするのか</h2>
     * temporal に回るかどうかは <b>{@code visibilityData} の bit 31</b> だけで決まる
     * [確認済 — {@code cmdgen.comp}: {@code renderTemporally = (dat & 0x80000000u) == 0u}]。
     * そして本番でその bit を書くのは<b>カルパスの頂点シェーダだけ</b>である
     * [確認済 — {@code cull_raster.vert}: {@code wasVisibleLastFrame}]。
     *
     * <p>⚠ したがって {@link #ALL_VISIBLE} のままでは
     * <b>temporal は構造的に空になる</b> — 描画を繋いでも 1 quad も出ない。
     * <b>「繋いだが何も描かれない」を PASS と読める形になる</b> [規約 11]。
     *
     * <p>ホストが書くモードを用意するのは、<b>期待集合を予測できる段を 1 つ挟む</b>ためである
     * (5c-4b が合成の木で機構を確かめたのと同じ構造)。
     */
    public enum Visibility {
        /** 全て「前フレームも可視」。<b>temporal は空になる</b>。5c-4c までの挙動。 */
        ALL_VISIBLE,
        /** 全て「今フレーム新規可視」。<b>temporal は不透明と一致する</b>はず。 */
        NONE_VISIBLE,
        /** 偶数 id だけ新規可視。temporal は<b>真部分集合</b>になる。 */
        EVEN_NEW,
        /** 奇数 id だけ新規可視。{@link #EVEN_NEW} との<b>和が全体</b>になるはず。 */
        ODD_NEW,
        /**
         * ホストは<b>何も書かない</b>。カルパスが書く (Phase 5c-5b2)。<b>本番の経路</b>。
         *
         * <h2>⚠ カルパスはこのモードでしか走らない</h2>
         * ホストの書き込みは記録の<b>前</b>、cull は記録の<b>中</b>なので、
         * 両方走らせると<b>常に cull が勝ち</b>、ホストのモードが no-op になる。
         * それでは 5c-5a の両極の対照が<b>黙って空虚になる</b> [規約 11]。
         * したがって {@code CULL} 以外では cull を記録しない。
         */
        CULL
    }

    private Visibility visibility = Visibility.ALL_VISIBLE;

    public final VkTerrainResources res;
    private final Watcher watcher = new Watcher();
    private final BasicAsyncGeometryManager geometry;
    private final NodeManager nodes;
    private final WorldEngine world;
    private final VkModelUploadTarget modelTarget;
    private final VkRealModelBakery bakery;
    private final VkRealMesher mesher;
    private final VkNodeUploadTarget nodeTarget;
    private final VkTraversal traversal;
    private VkHiZ hiz;
    private final VkMergedTableBuilder table;
    /**
     * 遮蔽カリング (Phase 5c-5b2)。<b>可視バッファを書く唯一の本番の書き手</b>。
     *
     * <p>⚠ 画面サイズに依存しないので {@link #resize} で作り直す必要は無い。
     */
    private final VkCullPass cull;
    private VkTerrainRenderer renderer;
    /**
     * temporal パス (Phase 5c-5a)。不透明と<b>同じ頂点シェーダ・同じエントリ配列</b>で、
     * {@code MERGED_PREFIX_BINDING} に {@code temporalPrefix} を張ったもの。
     */
    private VkTerrainRenderer temporalRenderer;
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
    final int maxDraws;
    private boolean freed;

    /**
     * <b>要求した</b>最上位ノードの位置の数。
     *
     * <p>⚠ <b>実際にキューに入った数とは別物である。</b>
     * {@code insertTopLevelNode} が作るのは<b>要求</b>で、
     * ノード id が生まれるのはジオメトリが届いたときである
     * [確認済 — {@code NodeManager.finishRequest}]。
     * 2 つを 1 つの数字にすると<b>「27 個ある」と「27 個頼んだ」が区別できない</b>。
     */
    private int topLevelRequested;
    /** 実際に {@code topNodeIds} に入っている最上位ノード。<b>これがトラバーサルの入口</b>。 */
    private final TopLevelNodeQueue topNodes;
    /** GPU が写したユニフォーム。{@code {描画用 64B, トラバーサル用 64B}}。 */
    private final VkBuffer uniformEcho;
    /** ジオメトリ領域の容量 (バイト)。⚠ この段では<b>回収が無いので減らない</b>。 */
    private final long geometryCapacityBytes;
    /** 容量に届いて<b>メッシュ化を止めた</b>か。届いたら二度と再開しない (回収が無いため)。 */
    private boolean geometryExhausted;
    private int meshedSections;
    /**
     * 前フレームのトラバーサルが選んだセクション数。
     *
     * <p>⚠ <b>{@code reset()} が描画キューの先頭を 0 にする</b>ので、
     * 記録の時点で読むと必ず 0 になる。0 を {@code merged_prefix} に渡すと
     * 全 7 面が同じ位置を指し、<b>描画コマンドが全部空になる</b> — 実際にそうなった。
     *
     * <p>1 フレーム遅れは<b>設計どおり</b>である —
     * {@code VkMergedTableBuilder} は「このフレームの描画は前フレームのテーブルで行う」
     * ことを前提にしている。
     */
    private int lastDrawnSections;
    /** 既にメッシュ化を試した位置。監視集合との差分を取るのに使う。 */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet pendingMesh =
        new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /**
     * @param depthSource 深度解決の出力 (HiZ の元)
     * @param maxSections 描画キューと密テーブルの容量。<b>選ばれうるセクション数の上限</b>
     */
    public VkHierarchicalScene(WorldEngine world, VkRenderTarget target,
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
        this.world = world;

        this.geometryCapacityBytes = (long) maxQuads * 8;
        this.geometry = new BasicAsyncGeometryManager(maxSections, this.geometryCapacityBytes);
        this.nodes = new NodeManager(maxSections, this.geometry, this.watcher);

        // ⚠⚠ **HiZ は Voxy 自身の深度アタッチメントを読む** [確認済 — 上流
        // {@code NormalRenderPipeline.setup} が {@code fb.getDepthTex()} を返し、
        // {@code runPipeline} が {@code renderOpaque} の**後**に
        // {@code innerPrimaryWork(viewport, depthTexture)} を呼ぶ]。
        //
        // ⚠ interop の解決済み深度を読んではならない。あれは**再投影済み**で
        // MC の投影空間にあり、トラバーサルが使う MVP と**別の空間**である。
        // しかも 1 フレーム古い。絵は出るので気付けない型の誤りだった
        this.hiz = new VkHiZ(target.depth, width, height);
        // ⚠ 描画キューに indirectLookup をそのまま渡す。cmdgen が直接読む
        this.traversal = new VkTraversal(this.hiz.texture(), maxSections, maxSections,
            (int) ((this.res.indirectLookup.size() - 4) / 4), 4096, this.res.indirectLookup);
        this.nodeTarget = new VkNodeUploadTarget(this.traversal.nodeData);

        // ⚠ **トラバーサルの入口を繋ぐ。** GL 版は addTLN/remTLN で同じことをしている
        // [HierarchicalOcclusionTraverser:96]。繋がないと topNodeIds はゼロのままで、
        // **ノード id 0 を topLevelCount 回訪問する**だけになる
        this.topNodes = new TopLevelNodeQueue(
            (int) (this.traversal.topNodeIds.size() / 4),
            (idx, id) -> org.lwjgl.system.MemoryUtil.memPutInt(
                this.traversal.topNodeIds.addr() + (long) idx * 4L, id));
        this.nodes.setTLNCallbacks(this.topNodes::add, this.topNodes::remove);
        this.uniformEcho = new VkBuffer(ECHO_BYTES * 2,
            VkBuffer.DEFAULT_USAGE | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true)
            .name("uniformEcho");

        this.table = new VkMergedTableBuilder(this.res, VkTerrainRenderer.Barriers.CONSERVATIVE);
        this.cull = new VkCullPass(this.res, VkTerrainRenderer.Barriers.CONSERVATIVE);
        this.renderer = new VkTerrainRenderer(this.res, width, height,
            VkTerrainRenderer.Barriers.CONSERVATIVE, VkTerrainRenderer.Mode.MERGED,
            VkTerrainRenderer.Pass.OPAQUE, colourFormat);
        this.temporalRenderer = new VkTerrainRenderer(this.res, width, height,
            VkTerrainRenderer.Barriers.CONSERVATIVE, VkTerrainRenderer.Mode.MERGED,
            VkTerrainRenderer.Pass.TEMPORAL, colourFormat);
    }

    /**
     * <b>画面サイズが変わったときに、サイズ依存の資源だけを作り直す</b> (Phase 5c-5a)。
     *
     * <h2>⚠ シーンを作り直してはならない</h2>
     * 上流 Voxy はサイズ依存の資源だけを {@code resize} し、
     * <b>ワールドの状態には触らない</b> [確認済 — {@code Viewport.update} が
     * {@code depthBoundingBuffer.resize} を呼ぶだけ。{@code NodeManager} も
     * ジオメトリも画面サイズと独立である]。
     *
     * <p>作り直すと<b>メッシュ化した地形が全部捨てられ、木が組み直しになる</b>。
     * サイズが 2 値を行き来する構成では<b>それが毎フレーム起きて</b>、
     * ちらつきと「絵が戻らない」の両方になる。
     *
     * <p>⚠ <b>フレームの記録中に呼んではならない。</b> in-flight = 1 なので
     * 記録の外なら GPU はアイドルである。
     *
     * @param depthSource 新しい深度の元 (interop 画像が作り直されると別物になる)
     */
    public void resize(VkRenderTarget target, int width, int height, int colourFormat) {
        this.assertNotFreed();
        this.renderer.free();
        this.temporalRenderer.free();
        this.hiz.free();

        this.hiz = new VkHiZ(target.depth, width, height);
        // ⚠ トラバーサルは**張り替えるだけ**。ノードもキューも作り直さない
        this.traversal.rebindHiZ(this.hiz.texture());
        this.renderer = new VkTerrainRenderer(this.res, width, height,
            VkTerrainRenderer.Barriers.CONSERVATIVE, VkTerrainRenderer.Mode.MERGED,
            VkTerrainRenderer.Pass.OPAQUE, colourFormat);
        this.temporalRenderer = new VkTerrainRenderer(this.res, width, height,
            VkTerrainRenderer.Barriers.CONSERVATIVE, VkTerrainRenderer.Mode.MERGED,
            VkTerrainRenderer.Pass.TEMPORAL, colourFormat);
        Logger.info("[5c-5a] resized the size-dependent resources to " + width + "x" + height
            + " (the tree and its " + this.meshedSections + " meshed sections are kept)");
    }

    private void assertNotFreed() {
        if (this.freed) throw new IllegalStateException("VkHierarchicalScene already freed");
    }

    /** 可視バッファの書き方を選ぶ (Phase 5c-5a)。既定は 5c-4c までと同じ。 */
    public void setVisibility(Visibility mode) { this.visibility = mode; }

    public Visibility visibility() { return this.visibility; }

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
                    this.topLevelRequested++;
                }
            }
        }

        // ⚠ 粗いほうから順に入れる。細かいほうを先に入れると、
        // NodeManager が親をまだ知らない状態で子を受け取ることになる
        for (int level = top; level >= Math.max(0, top - depth); level--) {
            int r = (topRadius + 1) << (top - level);
            var built = this.mesher.meshAround(cx << (top - level), cy << (top - level),
                cz << (top - level), r, level);
            boolean stopped = false;
            for (var b : built) {
                // ⚠ ここも容量を超えうる。populate は一度に大量に入れるので**先に届く**
                if (stopped) {
                    // ⚠ 渡さなかったものは**こちらが解放する** — 止めた後に残りを
                    // 放置すると、落ちない代わりに黙って漏れる
                    b.free();
                    continue;
                }
                // ⚠ 断られた分は acceptGeometry が解放済みである。ここで free すると二重解放
                if (!this.acceptGeometry(b)) { stopped = true; continue; }
                this.meshedSections++;
            }
        }
        this.bakery.replayBiomes();
        Logger.info("[5c-4c] populated " + this.topNodes.count() + " top-level nodes of "
            + this.topLevelRequested + " requested (LoD " + top
            + "), meshed " + this.meshedSections + " sections down to LoD "
            + Math.max(0, top - depth));
        // ⚠ 0 の意味を 1 通りにする [規約 18] — 「頼んだが 1 つも届いていない」と
        // 「頼んでいない」を分ける。前者ならトラバーサルは**空回りする**
        if (this.topNodes.count() == 0 && this.topLevelRequested > 0) {
            Logger.warn("[5c-4c] ⚠ none of the " + this.topLevelRequested
                + " requested top-level nodes resolved to a node id yet;"
                + " the traversal has no entry point and will draw nothing");
        }
    }

    /**
     * <b>可視バッファをホストが書く</b> (Phase 5c-4c の縮小 / 5c-5a で両極を足した)。
     *
     * <h2>⚠ なぜ要るのか</h2>
     * {@code cmdgen} は <b>{@code visibilityData[sid] == frameId}</b> でなければ
     * そのセクションを 0 quad として扱う [確認済 — {@code cmdgen.comp}]。
     * 本番はこれを<b>カルパス</b> (ラスタ遮蔽判定) が書く。
     * 繋がないと<b>密テーブルが全部空になり、1 画素も出ない</b> — 実際にそうなった。
     *
     * <h2>bit 31 が temporal を決める</h2>
     * 下位 31 bit が {@code frameId} と一致すれば<b>描く</b>。
     * その上で <b>bit 31 が立っていなければ temporal パスへ回る</b>
     * [確認済 — {@code cmdgen.comp}: {@code renderTemporally}]。
     * {@link Visibility} はこの bit の書き方だけを変える —
     * <b>どのセクションを描くかは変えない</b>ので、
     * 不透明側の quad 数はモードによらず同じでなければならない。
     *
     * <h2>⚠ 何を捨てているか</h2>
     * これは<b>カルパスの代用ではない</b>。遮蔽で落とす仕組みを丸ごと外している。
     * <ul>
     *   <li>トラバーサルの HiZ 判定は<b>効いたまま</b> (ノード単位)</li>
     *   <li>セクション単位のラスタ遮蔽は<b>効かない</b> → <b>本番より多く描く</b></li>
     *   <li>したがって<b>描画の時間は悲観的に出る</b>。5c-5b で取り直す</li>
     * </ul>
     */
    private void writeVisibility(int frameId) {
        if (this.visibility == Visibility.CULL) return;   // カルパスが書く
        long addr = this.res.visibility.addr();
        int n = (int) (this.res.visibility.size() / 4);
        for (int i = 0; i < n; i++) {
            org.lwjgl.system.MemoryUtil.memPutInt(addr + (long) i * 4L,
                visibilityWord(this.visibility, i, frameId));
        }
    }

    /**
     * 1 セクションぶんの可視の語 (Phase 5c-5a)。<b>装置なしで検査できるように切り出してある。</b>
     *
     * <p>下位 31 bit は必ず {@code frameId} — <b>どのモードでも描く</b>。
     * 変えるのは bit 31 だけである。
     *
     * <p>⚠ {@link Visibility#EVEN_NEW} と {@link Visibility#ODD_NEW} は
     * <b>互いに素で全体を覆う</b>。この性質が
     * 「temporal の quad 数の和が全体になる」という<b>数え方に依存しない主張</b>を成立させる。
     */
    public static int visibilityWord(Visibility mode, int index, int frameId) {
        int base = frameId & 0x7fffffff;
        boolean newlyVisible = switch (mode) {
            case ALL_VISIBLE -> false;
            case NONE_VISIBLE -> true;
            case EVEN_NEW -> (index & 1) == 0;
            case ODD_NEW -> (index & 1) == 1;
            case CULL -> throw new IllegalArgumentException(
                "CULL means the host writes nothing; there is no word to compute");
        };
        return newlyVisible ? base : (base | 0x80000000);
    }

    /** ⚠ フレームの記録の<b>前</b>に呼ぶこと。ホスト側の書き込みを済ませる。 */
    public VkGeometryFlush.Result prepare(Matrix4fc mvp, int[] camSection, float[] camSubPos,
                                          float minScreenSize, int frameId, float renderDistance) {
        this.nodes.writeChanges(this.nodeTarget);
        var flushed = VkGeometryFlush.flush(this.geometry, this.res.geometry, this.res.sectionMetadata);
        this.traversal.writeUniform(mvp, camSection, camSubPos, this.hiz.packedSize(),
            minScreenSize, VkHostViewport.frustumPlanes(mvp), frameId, renderDistance);
        // ⚠ **reset の前に読む。** reset が描画キューの先頭を 0 にする
        this.lastDrawnSections = this.drawnSectionCount();
        this.traversal.reset(this.topNodes.count());
        this.writeVisibility(frameId);
        return flushed;
    }

    /**
     * 1 フレームぶんを記録する。区間ごとに時刻を打つ。
     *
     * <h2>並びは参照実装そのもの (Phase 5c-5b1)</h2>
     * <pre>
     *   renderOpaque (前フレームのテーブル)
     *     -&gt; innerPrimaryWork (HiZ + traversal)
     *     -&gt; buildDrawCalls   (テーブル生成)
     *     -&gt; renderTemporal   (今フレームのテーブル)
     * </pre>
     * [確認済 — {@code AbstractRenderPipeline.runPipeline}]。
     *
     * <p>⚠ <b>不透明が先頭にあることが HiZ の前提である。</b> 入れ替えると
     * HiZ が 1 フレーム古い深度を見て、回転で遮蔽判定がずれる。
     */
    public void record(VkCommandBuffer cmd, VkRenderTarget target, float[] clearColour,
                       VkInteropDepth depthOut) {
        this.timer.reset(cmd);
        this.timer.mark(cmd, 0);
        this.echoUniforms(cmd);

        // ⚠ **焼いたタイルをアトラスへ流す。** これを忘れると
        // `useExternalAtlasContent()` で合成の中身も止めているので
        // **アトラスが未初期化のまま**になる。落ちないし絵も出る — 一色になるだけである
        this.bakery.recordUploads(cmd);

        // ① 不透明。**前フレームのテーブル**で描き、深度を書く
        this.renderer.record(cmd, target, this.maxDraws, clearColour);
        this.timer.mark(cmd, 1);

        // ② HiZ。**①が書いた深度**から作る。順序を入れ替えると 1 フレーム古くなる
        this.hiz.record(cmd);
        this.timer.mark(cmd, 2);

        this.traversal.record(cmd, this.topNodes.count());
        this.timer.mark(cmd, 3);

        // ⚠ 参照実装の並び: prep -> cull のラスタ -> cmdgen
        // [確認済 — MDICSectionRenderer.buildDrawCalls]。
        // cull は prep が書いた間接コマンドを使うので、セクション数が GPU 側の値になる
        this.table.recordPrep(cmd);
        if (this.visibility == Visibility.CULL) {
            this.cull.recordIndirect(cmd, target, this.res.cullDraw);
        }
        // ⚠ 区間 "cull" は prep を含む (prep は 1 ディスパッチで無視できる)
        this.timer.mark(cmd, 4);

        this.table.recordAfterPrep(cmd, this.lastDrawnSections, this.maxDraws);
        this.timer.mark(cmd, 5);

        // ③ temporal。**今フレームのテーブル**で、①の取りこぼしだけを埋める。
        // ⚠ 色も深度もクリアしない — クリアすると①の絵が丸ごと消える
        this.temporalRenderer.record(cmd, target, this.maxDraws, null, null);
        this.timer.mark(cmd, 6);

        if (depthOut != null) depthOut.resolve(cmd, target.depth);
        this.timer.mark(cmd, 7);
    }

    /**
     * <b>GPU に見えているユニフォームをそのまま写して返す</b> (Phase 5c-5a の切り分け)。
     *
     * <h2>⚠ 何を分けるのか</h2>
     * ホスト側の総和が変わっているのに絵が変わらないとき、原因は 2 つある:
     * <ul>
     *   <li><b>GPU がホストの書き込みを見ていない</b> — ここに<b>古い値</b>が出る</li>
     *   <li>全部生きているが<b>結果がたまたま一定</b> — ここには<b>新しい値</b>が出る</li>
     * </ul>
     *
     * <p>⚠ <b>ホストのポインタを読み直すのでは駄目である。</b> それは書いた本人の記憶で、
     * <b>GPU が何を読んだか</b>を何も主張しない [規約 4 — 予測と実装が規約を共有する]。
     * {@code vkCmdCopyBuffer} を<b>フレームの中で</b>積み、GPU に写させる。
     */
    private void echoUniforms(VkCommandBuffer cmd) {
        if (this.uniformEcho == null) return;
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var regions = org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack);
            regions.get(0).srcOffset(0).dstOffset(0).size(ECHO_BYTES);
            org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmd, this.res.uniform.handle,
                this.uniformEcho.handle, regions);
            regions.get(0).srcOffset(0).dstOffset(ECHO_BYTES).size(ECHO_BYTES);
            org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmd, this.traversal.uniform.handle,
                this.uniformEcho.handle, regions);
        }
    }

    /** 写す長さ。MVP (mat4) だけで足りる。 */
    private static final long ECHO_BYTES = 64;

    private static long fnv(long base) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < ECHO_BYTES / 4; i++) {
            h = (h ^ (org.lwjgl.system.MemoryUtil.memGetInt(base + i * 4L) & 0xffffffffL))
                * 0x100000001b3L;
        }
        return h;
    }

    /**
     * <b>ホストが書いた MVP の総和</b> {@code {描画用, トラバーサル用}}。
     *
     * <p>⚠ {@link #echoedUniformChecksums()} と<b>同じ範囲・同じ式</b>で取る。
     * ここで式を揃えるのは意図的である — 比べたいのは<b>メモリが届いているか</b>
     * だけなので、式が違うと差が「届いていない」以外の理由でも出てしまう。
     */
    public long[] hostUniformChecksums() {
        return new long[]{fnv(this.res.uniform.addr()), fnv(this.traversal.uniform.addr())};
    }

    /**
     * <b>GPU が写した MVP の総和</b> {@code {描画用, トラバーサル用}}。
     *
     * <p>⚠ この値が<b>ホスト側の総和と食い違えば、GPU は古いメモリを読んでいる</b>。
     * 一致すれば<b>ユニフォームは届いている</b>ので、原因は選択の側にある。
     */
    public long[] echoedUniformChecksums() {
        if (this.uniformEcho == null) return new long[]{0, 0};
        return new long[]{fnv(this.uniformEcho.addr()), fnv(this.uniformEcho.addr() + ECHO_BYTES)};
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
    /**
     * ⚠ ワールドがまだ生きているか。
     *
     * <p>Voxy は<b>使われていないワールドを閉じる</b> (アイドル回収)。
     * 閉じたあとに {@code acquireIfExists} を呼ぶと
     * {@code "World is not live"} で<b>フレームの途中で落ちる</b> — 実際に落ちた。
     * シーンは古いワールドを掴んだままなので、<b>捨てて作り直す</b>のが正しい。
     */
    public boolean worldIsLive() { return this.world.isLive(); }

    /**
     * このセクションがジオメトリ領域から<b>何バイト取るか</b> (Phase 5c-5a)。
     *
     * <p>⚠ <b>空のセクションは 0 である。</b> {@code BuiltSection} は
     * {@code geometryBuffer == null} を「空」の表現に使っており
     * [確認済 — {@code BuiltSection.isEmpty}]、
     * <b>空でも木には渡さなければならない</b> — {@code childExistence} を運んでいるので、
     * 捨てると要求が満たされずトラバーサルが降りられなくなる。
     *
     * <p>⚠ <b>127 要素 (= 1016 バイト) 単位に切り上げられる</b>
     * [確認済 — {@code BasicAsyncGeometryManager.createMeta} の {@code upsized}]。
     * 切り上げを見ないと「ちょうど入る」と判断して溢れる。
     */
    public static long geometryBytesNeeded(
            me.cortex.voxy.client.core.rendering.building.BuiltSection built) {
        if (built.isEmpty()) return 0;
        return ((built.geometryBuffer.size / 8 + 127) & ~127L) * 8;
    }

    /**
     * <b>入るなら渡す。入らないなら止める</b> (Phase 5c-5a の修正)。
     *
     * <h2>⚠ なぜ要るのか</h2>
     * この段は <b>{@code NodeCleaner} を繋いでいない</b>ので、
     * ジオメトリ領域は<b>増える一方</b>である。木全体を辿るようになった今、
     * 上限に届くのは<b>時間の問題</b>であって異常ではない。
     *
     * <p>⚠ <b>上流は容量不足で例外を投げる</b>
     * [確認済 — {@code BasicAsyncGeometryManager.createMeta} の "Geometry OOM"]。
     * しかもその時点で<b>セクション id は確保済み</b>なので、
     * 投げられた後の状態は一貫していない。<b>受け取る前に決める</b>しかない。
     *
     * <h2>⚠ 止めたことは必ず言う</h2>
     * 黙って止めると<b>「描かれない」と「選ばれなかった」が区別できない</b> [規約 18]。
     * トラバーサルは要求を出し続けるので、<b>答えていないことが見えなければならない</b>。
     *
     * @return 渡したなら true。<b>false なら所有権はこちらに残る</b>ので解放済みである
     */
    private boolean acceptGeometry(me.cortex.voxy.client.core.rendering.building.BuiltSection built) {
        long used = this.geometry.getGeometryUsedBytes();
        long need = geometryBytesNeeded(built);
        if (used + need <= this.geometryCapacityBytes) {
            this.nodes.processGeometryResult(built);   // ⚠ 所有権が移る
            return true;
        }
        this.geometryExhausted = true;
        built.free();   // ⚠ 渡していないので**こちらが解放する**
        Logger.warn("[5c-4c] ⚠ the geometry arena is full ("
            + (used / 1024) + " KiB of " + (this.geometryCapacityBytes / 1024)
            + " KiB used; this section needs " + (need / 1024) + " KiB)."
            + " Meshing stops here and will NOT resume — this stage does not connect"
            + " NodeCleaner, so nothing is ever reclaimed."
            + " The traversal keeps requesting nodes that will never be answered,"
            + " so expect holes in the distance."
            + " Raise it with -Pvoxy5c3bQuads, or descend less with a bigger"
            + " -Pvoxy5c4Subdivision.");
        return false;
    }

    /** ⚠ ジオメトリ領域を使い切ってメッシュ化を止めたか。<b>絵の穴の説明になる</b>。 */
    public boolean geometryExhausted() { return this.geometryExhausted; }

    public int serviceRequests(int maxMeshesPerCall) {
        if (!this.world.isLive()) return 0;
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
        if (this.geometryExhausted) return 0;
        for (long pos : this.watcher.watched.keySet().toLongArray()) {
            if (meshed >= maxMeshesPerCall) break;
            if (!this.pendingMesh.add(pos)) continue;   // 済み
            int lvl = WorldEngine.getLevel(pos);
            var built = this.mesher.meshOne(lvl, WorldEngine.getX(pos),
                WorldEngine.getY(pos), WorldEngine.getZ(pos));
            if (built == null) continue;
            if (!this.acceptGeometry(built)) break;
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

    /**
     * <b>cmdgen が読む値そのもの</b>を先頭のエントリについて書き出す (Phase 5c-4c)。
     *
     * <p>テーブルが空になる原因は 2 つしかない:
     * <ul>
     *   <li>{@code visibilityData[sid] != frameId} → 可視の印が届いていない</li>
     *   <li>面ごとの quad 数が全部 0 → <b>メタデータが届いていない</b></li>
     * </ul>
     * どちらかを<b>読んで</b>言う。
     */
    public String describeFirstEntry() {
        int n = this.drawnSectionCount();
        if (n == 0) return "nothing selected";
        int sid = org.lwjgl.system.MemoryUtil.memGetInt(this.res.indirectLookup.addr() + 4L);
        int frameId = org.lwjgl.system.MemoryUtil.memGetInt(this.res.uniform.addr() + 76);
        int vis = org.lwjgl.system.MemoryUtil.memGetInt(
            this.res.visibility.addr() + (long) sid * 4L);
        long meta = this.res.sectionMetadata.addr() + (long) sid * 32L;
        int quadStart = org.lwjgl.system.MemoryUtil.memGetInt(meta + 12);
        var counts = new StringBuilder();
        int totalFaces = 0;
        for (int w = 0; w < 4; w++) {
            int v = org.lwjgl.system.MemoryUtil.memGetInt(meta + 16 + w * 4L);
            counts.append(v & 0xFFFF).append('/').append((v >>> 16) & 0xFFFF).append(' ');
            totalFaces += (v & 0xFFFF) + ((v >>> 16) & 0xFFFF);
        }
        return "sid=" + sid
            + " visibility=0x" + Integer.toHexString(vis)
            + " frameId=" + frameId
            + " visibleMatches=" + (((vis & 0x7fffffff) == frameId) ? "YES" : "NO")
            + " quadStart=" + quadStart
            + " counts(t/ds d/u n/s w/e)=" + counts
            + " sumOfCounts=" + totalFaces
            + (totalFaces == 0 ? "  ⚠ THE SECTION METADATA HAS NO QUADS" : "");
    }

    /**
     * <b>生成された間接描画コマンド</b>を読む (Phase 5c-4c)。
     *
     * <p>テーブルに quad があるのに画素が出ないとき、
     * <b>描画コマンドが空なのか、実行されて何も出ないのか</b>を分ける。
     * 1 コマンド = 5 uint (indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)。
     */
    public String describeDraws() {
        long addr = this.res.mergedDraw.addr();
        int slots = Math.min(this.maxDraws, (int) (this.res.mergedDraw.size() / 20));
        long totalIndices = 0;
        int nonEmpty = 0;
        var sb = new StringBuilder();
        for (int i = 0; i < slots; i++) {
            int idx = org.lwjgl.system.MemoryUtil.memGetInt(addr + (long) i * 20L);
            int inst = org.lwjgl.system.MemoryUtil.memGetInt(addr + (long) i * 20L + 4);
            if (idx != 0 && inst != 0) { nonEmpty++; totalIndices += idx; }
            if (i < 8) {
                sb.append(idx).append('x').append(inst).append(' ');
            }
        }
        return "slots=" + slots + " nonEmpty=" + nonEmpty
            + " totalIndices=" + totalIndices
            + " first8(indexCount x instanceCount)=" + sb
            + (nonEmpty == 0 ? "  ⚠ EVERY DRAW COMMAND IS EMPTY — the problem is in the table"
                             : "  [commands exist; if no pixels appear the draw itself is at fault]");
    }

    /**
     * <b>temporal のテーブルが何 quad ぶんを指しているか</b> (Phase 5c-5a)。
     *
     * <p>{@link #mergedTableTotals()} と<b>同じ形</b>で読む。
     * 両者を並べることで {@link Visibility} の両極を数字で言える:
     * <ul>
     *   <li>{@link Visibility#ALL_VISIBLE} → temporal は <b>0</b></li>
     *   <li>{@link Visibility#NONE_VISIBLE} → temporal は<b>不透明と一致</b></li>
     *   <li>{@link Visibility#EVEN_NEW} + {@link Visibility#ODD_NEW} → <b>和が不透明</b></li>
     * </ul>
     *
     * <p>⚠ エントリ数は不透明と<b>共有</b>である (temporal は quad 数だけ絞り込む)。
     * したがってエントリ数が一致することは<b>何も主張しない</b> — 見るのは総 quad 数。
     *
     * @return {@code {エントリ数, 末尾の prefix = 総 quad 数}}
     */
    public int[] temporalTableTotals() {
        long base = this.res.temporalPrefix.addr();
        int entries = org.lwjgl.system.MemoryUtil.memGetInt(base);
        int max = (int) ((this.res.temporalPrefix.size() - 4) / 4) - 1;
        if (entries < 0 || entries > max) return new int[]{entries, -1};
        int total = org.lwjgl.system.MemoryUtil.memGetInt(base + 4L + (long) entries * 4L);
        return new int[]{entries, total};
    }

    /** トラバーサルが選んだセクション数 (= {@code indirectLookup} の先頭)。 */
    public int drawnSectionCount() {
        return Math.min(org.lwjgl.system.MemoryUtil.memGetInt(this.res.indirectLookup.addr()),
            this.maxSections);
    }

    /** 前フレームの選択数 ({@code merged_prefix} に渡した値)。 */
    public int lastDrawnSections() { return this.lastDrawnSections; }

    public VkGpuTimer timer() { return this.timer; }
    public VkTraversal traversal() { return this.traversal; }
    public NodeManager nodes() { return this.nodes; }
    /** {@code topNodeIds} に実際に入っている数 = トラバーサルの入口の数。 */
    public int topLevelCount() { return this.topNodes.count(); }
    /** ⚠ 要求した位置の数。入口の数とは別物である。 */
    public int topLevelRequested() { return this.topLevelRequested; }
    public int meshedSections() { return this.meshedSections; }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        this.timer.free();
        this.cull.free();
        this.uniformEcho.free();
        this.renderer.free();
        this.temporalRenderer.free();
        this.table.free();
        this.traversal.free();
        this.hiz.free();
        this.mesher.free();
        this.bakery.free();
        this.modelTarget.free();
        this.res.free();
    }
}
