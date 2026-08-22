package me.cortex.voxy.client.core.vk.interop;

import me.cortex.voxy.client.core.vk.SyntheticTerrain;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkDepthResolve;
import me.cortex.voxy.client.core.vk.VkDepthVisualise;
import me.cortex.voxy.client.core.vk.VkFrameTracker;
import me.cortex.voxy.client.core.vk.VkRenderTarget;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer;
import me.cortex.voxy.client.core.vk.VkTerrainResources;
import me.cortex.voxy.client.core.vk.VkSceneUniform;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;
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
 * Phase 5c-1b / 5c-1c — <b>Vulkan と Minecraft の間で絵が正しい向きで往復するか</b>を確かめる探り。
 *
 * <p>地形は描かない。5c-1d で本物の地形描画に置き換わる。
 *
 * <h2>5c-1b (色経路) — 完了</h2>
 * Vulkan が<b>非対称な模様</b>を interop 色に描き、GL が合成する。
 * <b>単色フルスクリーンでは上下反転を検出できない</b>ので、最初から
 * 上下と左右が区別できる模様を使う [docs/phase5c1b-completion.md]。
 * {@link Mode#PATTERN} で今も再現できる。
 *
 * <h2>5c-1c (深度経路) — この段</h2>
 * <pre>
 * MC の深度 ──(GL 取り込み)──→ IOSurface(R32F) ──(Vulkan 可視化)──→ IOSurface(BGRA) ──(GL 合成)──→ 画面
 * </pre>
 *
 * <b>色とは別経路の向き検査である。</b>片方の反転が他方を打ち消せない
 * [docs/phase5c-plan.md §9]。画面には
 *
 * <ul>
 *   <li><b>空 (深度がクリア値のまま) = マゼンタ</b> — 物理的に画面の<b>上</b>に出るはず</li>
 *   <li><b>近い地形 = 明るいグレー</b> — 画面の<b>下</b>に出るはず</li>
 *   <li><b>緑の四角 = 画面の左上</b> — 5c-1b から引き継いだ<b>色経路</b>の向きの目印</li>
 * </ul>
 *
 * が同時に出る。<b>3 つの独立した事実が 1 枚に収まる</b>ので、
 * 基準スクリーンショットはこの状態で撮る。
 *
 * <h2>⚠ この探りが単独で証明できないこと</h2>
 * パターンの生成と機械的な検証は<b>同じ規約を参照している</b>ので、
 * 規約が反転していれば<b>両方が反転して再び一致する</b> [規約 4]。
 * 機械的に言えるのは<b>行の対応が保たれること</b>までで、
 * <b>行 N が画面の上か下か</b>は物理的な事実なので目視でしか言えない。
 *
 * <p>深度経路では<b>空がその外部基準になる</b> —
 * 「空は画面の上にある」は Voxy の規約に一切依存しない。
 * さらに<b>見上げれば全面マゼンタ・見下ろせば全面グレー</b>という
 * 2 視点の対照を撮ることで「たまたまそう見えた」を排除する。
 */
public final class VkInteropProbe {
    /** 背景 (濃い青)。RGBA 0..1。{@link Mode#PATTERN} のみ。 */
    private static final float[] BACKGROUND = {0.05f, 0.05f, 0.25f, 1.0f};
    /** 上 1/4 の帯 (赤)。{@link Mode#PATTERN} のみ。 */
    private static final float[] BAND = {0.85f, 0.05f, 0.05f, 1.0f};
    /** 左上の四角 (緑)。<b>5c-1c でも残す</b> — 色経路の向きの常設基準。 */
    private static final float[] CORNER = {0.05f, 0.85f, 0.10f, 1.0f};

    /**
     * 切り分け用のモード。{@code -Pvoxy5c1c=off|state|pattern|solid|full} で切り替える。
     *
     * <p><b>1 変数ずつ切り分けるための仕掛け</b>である。5c-1b では
     * 4 手で原因に到達した [docs/phase5c1b-completion.md §13]。
     */
    public enum Mode {
        /** 何もしない。5c-1a と同じ状態に戻す。 */
        OFF,
        /** FBO/ビューポートの控え・復帰だけ行い、<b>描かない</b>。 */
        STATE,
        /**
         * <b>5c-1b の非対称パターン</b>を描く。深度には一切触らない。
         * 色経路だけが健在かを確かめる<b>既知の正解</b>として使う。
         */
        PATTERN,
        /**
         * 合成は行うが<b>テクスチャを読まずマゼンタ一色を出す</b>。診断専用。
         * 画面がマゼンタになれば描画は届いている = 原因はテクスチャの読み側。
         */
        SOLID,
        /**
         * <b>5c-1c</b> — MC の深度を Vulkan に渡し、色に変換して返す。
         * 深度経路が生きているかの診断としてこの先も使える。
         */
        DEPTH,
        /**
         * <b>5c-1d</b> — Phase 4 の合成地形を実際に描いて合成する (既定)。
         * <b>ここで初めて Voxy 自身の深度を MC の深度バッファへ書く。</b>
         */
        TERRAIN,
        /**
         * 地形を描くが<b>深度を一切見ずに全面を貼る</b>。診断専用。
         *
         * <p><b>「絵が出ない」を 1 手で割る。</b>
         * この段には失敗しうる場所が 3 つあり、通常のモードでは区別が付かない:
         *
         * <ol>
         *   <li>Vulkan が地形を描けていない</li>
         *   <li>描けているが<b>画面の外</b>にいる (置き場所・投影)</li>
         *   <li>描けて画面内にいるが<b>深度テストで落ちている</b></li>
         * </ol>
         *
         * このモードは深度を無視するので、<b>橙一色なら 1 か 2、地形が見えれば 3</b> と分かる。
         *
         * <p>⚠ <b>MC の絵を全部覆う。</b> 診断のときだけ使うこと。
         */
        TERRAIN_NODEPTH,
        /**
         * 地形を<b>オフスクリーン検査と同じ既知の MVP</b> で描き、深度を見ずに貼る。診断専用。
         *
         * <p>{@link #TERRAIN_NODEPTH} が「橙一色」だったときに、
         * <b>置き場所・カメラの向きの問題</b>と<b>描画そのものの問題</b>を割る。
         * MC のカメラを一切使わないので、<b>どこを向いていても同じ絵</b>が出るはずである。
         *
         * <ul>
         *   <li><b>地形が見える</b> → Vulkan 側は健在。原因は<b>MC 由来の MVP か置き場所</b></li>
         *   <li><b>橙のまま</b> → 原因は<b>もっと手前</b> (資源・描画コマンド・アップロード)</li>
         * </ul>
         *
         * <p>出るべき絵は {@code build/vk-test-output/5b-interop-composite.png} と同じ構図である。
         */
        TERRAIN_REFMVP,
        /**
         * <b>5c-1e</b> — 同じ合成地形を<b>手前と奥の 2 か所</b>に置く (既定)。
         *
         * <p>遮蔽の対照は<b>片側だけでは成立しない</b>。奥が隠れるのを見ても
         * 「全部隠れている」のか「奥だけ」なのか区別できない。
         * <b>手前が出ていて奥が隠れている</b>ことを同時に見て初めて、
         * 深度テストが<b>効いている</b>と言える。
         */
        PAIR,
        /** 5c-3a: 3 組。<b>3 つ目は MC の far 平面の外</b>に置く。 */
        TRIPLE,
        /** 5c-4c: <b>実データの階層トラバーサル</b>。何を描くかを GPU が選ぶ。 */
        HIERARCHICAL
    }

    private static final Mode MODE = resolveMode();

    /**
     * {@code voxy.5c1c} を見る。無ければ<b>5c-1b のスイッチを解釈する</b> —
     * 5c-1b の {@code full} は<b>いまの {@link Mode#PATTERN}</b> に当たるので、
     * 当時の手動確認をそのまま再現できる。
     */
    private static Mode resolveMode() {
        // ⚠ **5c-4c を先に見る。** 逆にすると `-Pvoxy5c3=off -Pvoxy5c4=on` が
        // TRIPLE になり、**投影を切り替えるつもりでモードごと変わる**。
        // 実際にその対照コマンドを出して空振りした
        if (System.getProperty("voxy.5c4") != null) return Mode.HIERARCHICAL;
        // 5c-3a: 配置は 3 組で固定し、**投影だけ**を切り替える (VOXY_PROJECTION)
        if (System.getProperty("voxy.5c3") != null) return Mode.TRIPLE;
        // 5c-3b: 実ジオメトリ。組の複製は要らない (地形はワールドが決める)
        if (System.getProperty("voxy.5c3b") != null) return Mode.TERRAIN;
        String e = System.getProperty("voxy.5c1e");
        if (e != null) return parse("voxy.5c1e", e, Mode.PAIR);
        String v = System.getProperty("voxy.5c1d");
        if (v != null) return parse("voxy.5c1d", v, Mode.TERRAIN);
        // 5c-1c のスイッチ: その "full" は今の DEPTH に当たる
        String c = System.getProperty("voxy.5c1c");
        if (c != null) return parse("voxy.5c1c", c, Mode.DEPTH);
        // 5c-1b のスイッチ: その "full" は今の PATTERN に当たる
        String b = System.getProperty("voxy.5c1b");
        if (b != null) return parse("voxy.5c1b", b, Mode.PATTERN);
        return Mode.PAIR;
    }

    /** {@code full} はスイッチごとに<b>その段の全経路</b>を指す。当時の確認を再現できる。 */
    private static Mode parse(String property, String value, Mode fullMeans) {
        String v = value.toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "off" -> Mode.OFF;
            case "state" -> Mode.STATE;
            case "pattern" -> Mode.PATTERN;
            case "solid" -> Mode.SOLID;
            case "depth" -> Mode.DEPTH;
            case "terrain" -> Mode.TERRAIN;
            case "terrain-nodepth", "nodepth" -> Mode.TERRAIN_NODEPTH;
            case "terrain-refmvp", "refmvp" -> Mode.TERRAIN_REFMVP;
            case "pair" -> Mode.PAIR;
            case "triple" -> Mode.TRIPLE;
            case "hierarchical", "hier" -> Mode.HIERARCHICAL;
            case "full" -> fullMeans;
            default -> throw new IllegalArgumentException("unknown " + property + "=" + value);
        };
    }

    /** 地形を描くモードか。 */
    private static boolean drawsTerrain() {
        return MODE == Mode.TERRAIN || MODE == Mode.TERRAIN_NODEPTH
            || MODE == Mode.TERRAIN_REFMVP || MODE == Mode.PAIR || MODE == Mode.TRIPLE
            || MODE == Mode.HIERARCHICAL;
    }

    /** 何組の地形を置くか。1 = 単体、2 = 5c-1e の手前/奥、3 = 5c-3a (+ far 平面の外)。 */
    private static int copies() {
        return switch (MODE) {
            case PAIR -> 2;
            case TRIPLE -> 3;
            default -> 1;
        };
    }

    /** 複数組を置くモードか。 */
    private static boolean drawsPair() { return copies() > 1; }

    /**
     * <b>Voxy 自前の投影で描き、深度を MC の空間へ再投影するか</b> [Phase 5c-3a]。
     *
     * <p>⚠ <b>配置 ({@link Mode#TRIPLE}) とは分けてある。</b>
     * 同じ配置のまま投影だけを切り替えられなければ、
     * 「3 つ目が見えないのは投影のせいか置き場所のせいか」が分からない [規約 3]。
     */
    private static final boolean VOXY_PROJECTION = resolveVoxyProjection();

    private static boolean resolveVoxyProjection() {
        // ⚠ 階層モード専用の切り替え。`voxy.5c3` はモード選択も兼ねているので、
        // **投影だけを変えたいときに使えない**
        String own = System.getProperty("voxy.5c4.projection");
        if (own != null) return Boolean.parseBoolean(own);
        String v = System.getProperty("voxy.5c3");
        // ⚠ 階層トラバーサルは**自前の投影が前提**である。
        // MC の投影のままでは MC の far 平面の外へ届かず、
        // **5c-3a で作ったものが効かない**。既定で入れる
        //
        // ⚠⚠ ここは **MODE が先に初期化されていること**に依存する (静的初期化は宣言順)。
        // 並べ替えると MODE が null になり、この判定が黙って false に倒れて
        // **自前の投影が無効になる** — 絵は出るので気付かない。だから明示的に落とす
        if (MODE == null) {
            throw new IllegalStateException("MODE must be initialised before VOXY_PROJECTION;"
                + " the field order in this class was changed and the projection would have"
                + " silently fallen back to Minecraft's");
        }
        if (v == null) return MODE == Mode.HIERARCHICAL;
        return switch (v.toLowerCase(java.util.Locale.ROOT)) {
            case "off" -> false;              // 対照: MC の投影のまま 3 組置く
            case "on", "full" -> true;        // 本命: 自前の投影 + 深度再投影
            default -> throw new IllegalArgumentException("unknown voxy.5c3=" + v
                + " (expected 'off' or 'on')");
        };
    }

    private static VkInteropProbe INSTANCE;

    public static VkInteropProbe get() {
        if (INSTANCE == null) INSTANCE = new VkInteropProbe();
        return INSTANCE;
    }

    public static void shutdown() {
        if (INSTANCE != null) { INSTANCE.free(); INSTANCE = null; }
    }

    private VkInteropImage colour;
    private VkInteropImage depth;
    private GlInteropCompositor compositor;
    private GlDepthImport depthImport;
    private VkDepthVisualise visualise;
    private GlScratchFramebuffer scratchFbo;
    private int width, height;

    // ---- 5c-1d: 合成地形 ----
    /** 合成地形のデータ。{@code interopCompositeCheck} と<b>同じデータセット</b>。 */
    private SyntheticTerrain terrain;
    private VkTerrainResources res;
    private VkTerrainRenderer renderer;
    private VkRenderTarget rt;
    private VkDepthResolve resolve;
    private int drawCount;
    /** 直前に使ったアンカー。変わったときだけ位置を書き直す。 */
    private int[] lastAnchor;

    // ---- 5c-2b: 実データのモデル ----
    /**
     * <b>実際のブロックを焼いて使うか</b> ({@code -Pvoxy5c2=real})。
     *
     * <p>⚠ ワールドに入っていないと成立しない — {@code ModelFactory} が
     * {@code Minecraft.getInstance().level} を参照する。
     */
    private static final boolean REAL_MODELS =
        "real".equalsIgnoreCase(System.getProperty("voxy.5c2", "synthetic"));

    /**
     * 焼くブロック。<b>見た目がはっきり違うものを選ぶ</b> —
     * 似た色ばかりだと「索引がずれたら絵に出る」性質が失われる (規約 1 の実データ版)。
     * 焼けたタイルが実際に区別できるかは {@code VkRealModelBakery} が突き合わせる。
     */
    private static java.util.List<net.minecraft.world.level.block.state.BlockState> realBlocks() {
        // ⚠ **静的フィールドにしてはならない。** net.minecraft.world.level.block.Blocks に
        // 触れると Minecraft のブートストラップが要り、このクラスを読み込むだけで
        // **オフスクリーンの検査が落ちる** (実際に interopCompositeCheck を
        // ExceptionInInitializerError で落とした)。
        // 5c-1a §7 の「GL 専用の静的初期化」と同じ型が、MC のクラスで出たもの
        return java.util.List.of(
            net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
            net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(),
            net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState(),
            net.minecraft.world.level.block.Blocks.SAND.defaultBlockState(),
            net.minecraft.world.level.block.Blocks.BRICKS.defaultBlockState(),
            net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(),
            net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState(),
            net.minecraft.world.level.block.Blocks.NETHERRACK.defaultBlockState());
    }

    private me.cortex.voxy.client.core.vk.VkModelUploadTarget modelTarget;
    private me.cortex.voxy.client.core.vk.VkRealModelBakery bakery;
    /** 5c-1e: 奥の組のアンカー (診断用)。 */
    private int[] lastFarAnchor;
    /** 5c-1e: 奥のアンカーを決めたときのカメラ位置 (診断用)。 */
    private double[] farAnchorCam;
    /** 5c-3a: MC の far 平面の外に置いた組 (診断用)。 */
    private int[] lastBeyondAnchor;
    /**
     * 5c-3a: 外の組が<b>いま画面に入る位置にあるか</b>。
     *
     * <p>⚠ これが無いと {@code drawn=0} が
     * <b>「描けていない」と「そちらを向いていない」のどちらか分からない</b>。
     * 対照の答えが 0 か否かなので、0 の意味が 2 通りあると何も言えない。
     */
    private Boolean beyondOnScreen;
    private double lastBeyondDistance;
    private double lastMcFarPlane;

    /**
     * <b>どの組だけを描くか</b> (-1 = 全部) [Phase 5c-3a]。
     *
     * <h2>⚠ なぜ要るのか — 3 組は見分けが付かない</h2>
     * 3 組は同じ地形なので、画面のどの塊がどれか<b>目では区別できない</b>。
     * 「外の組が出たか」を人に判断させると答えが得られない [実際に得られなかった]。
     *
     * <p>さらに、全部まとめて描くと<b>画素数の比較も成立しない</b> —
     * ウィンドウの大きさやカメラ位置が変われば総数は当然変わる
     * (off で 113111/1639680、on で 15050/10608960 という比較にならない数字が出た)。
     *
     * <p><b>1 組だけ描けば、ログの「drawn=」が 0 か否かで決まる。</b>
     * 画面にも 1 つしか出ないので、目で見ても迷わない。
     */
    private static final int ONLY_COPY = resolveOnlyCopy();

    private static int resolveOnlyCopy() {
        String v = System.getProperty("voxy.5c3.only");
        if (v == null) return -1;
        return switch (v.toLowerCase(java.util.Locale.ROOT)) {
            case "all" -> -1;
            case "near" -> 0;
            case "mid", "far" -> 1;
            case "beyond" -> 2;
            default -> throw new IllegalArgumentException("unknown voxy.5c3.only=" + v
                + " (expected all|near|mid|beyond)");
        };
    }

    private static String copyName(int i) {
        return switch (i) { case 0 -> "near"; case 1 -> "mid"; case 2 -> "beyond"; default -> "all"; };
    }

    /**
     * 3 つ目の組を MC の far 平面の<b>何倍の距離</b>に置くか。
     *
     * <p>⚠ 1.0 に近いと「境界にいるので見えたり見えなかったりする」になり、
     * <b>対照が揺れる</b>。はっきり外に置く。
     */
    /**
     * <b>実ジオメトリを使うか</b> [Phase 5c-3b]。
     *
     * <p>⚠ <b>モデルは合成色のままにする</b> (5c-3c で実色にする)。
     * 実ジオメトリでは位置が実データになるので<b>規約 1 の「各 quad が別々の可視位置を
     * 占める」が保証から要求に変わる</b>。だが色を合成のままにしておけば
     * 「索引がずれたら色が変わる」性質は保たれるので、<b>この段では表面化しない</b>
     * [docs/phase5c3-plan.md 3.1]。<b>1 変数ずつ</b>。
     */
    private static final int REAL_GEOMETRY_RADIUS = resolveRealGeometry();

    private static int resolveRealGeometry() {
        String v = System.getProperty("voxy.5c3b");
        if (v == null) return -1;
        if (v.equalsIgnoreCase("off")) return -1;
        int r = v.equalsIgnoreCase("on") || v.equalsIgnoreCase("full") ? 4 : Integer.parseInt(v);
        if (r < 0 || r > 16) {
            throw new IllegalArgumentException("voxy.5c3b radius " + r + " is out of range (0..16)");
        }
        return r;
    }

    private static boolean usesRealGeometry() { return REAL_GEOMETRY_RADIUS >= 0; }

    private me.cortex.voxy.client.core.vk.VkRealMesher mesher;
    private me.cortex.voxy.client.core.vk.VkHierarchicalScene scene;

    /** 5c-4c: 最上位ノードの半径 ({@code MAX_LOD_LAYER} の粒度)。 */
    private static final int HIER_TOP_RADIUS =
        Integer.parseInt(System.getProperty("voxy.5c4.radius", "1"));
    /** 5c-4c: 最上位から何段下までメッシュ化するか。 */
    private static final int HIER_DEPTH =
        Integer.parseInt(System.getProperty("voxy.5c4.depth", "2"));
    /**
     * 5c-4c: 降下の閾値 (画面面積の比)。
     *
     * <p>本番は {@code subDivisionSize^2 / (width*height)} [確認済 —
     * {@code HierarchicalOcclusionTraverser.uploadUniform}]。
     * ここは<b>画素数で指定させて同じ式で割る</b>。
     */
    private static final double HIER_SUBDIVISION_PX =
        Double.parseDouble(System.getProperty("voxy.5c4.subdivision", "128"));
    /**
     * 5c-4c: 1 フレームでメッシュ化する上限。
     *
     * <p>⚠ 上限が無いと、要求が一気に来たフレームで<b>数秒止まる</b>。
     * 本番は専用スレッドが非同期に行う。
     */
    private static final int HIER_MESHES_PER_FRAME =
        Integer.parseInt(System.getProperty("voxy.5c4.meshesPerFrame", "16"));

    /**
     * 可視の書き方 (Phase 5c-5a)。{@code -Pvoxy5c5=all|none|even|odd|cycle}。
     *
     * <p>⚠ <b>これが temporal の中身を決める唯一のもの</b>である。
     * 既定の {@code all} では temporal は<b>構造的に空</b>になるので、
     * temporal の描画が繋がっているかどうかを<b>既定のまま確かめることはできない</b>
     * [規約 11]。
     */
    private static final String TEMPORAL_MODE =
        System.getProperty("voxy.5c5", "all").toLowerCase();

    /** {@code cycle} のとき 1 モードを保つフレーム数。系が落ち着くのを待つ [規約 23]。 */
    private static final int TEMPORAL_CYCLE_FRAMES =
        Integer.parseInt(System.getProperty("voxy.5c5.frames", "30"));

    /** {@code cycle} が回す順。<b>4 つ揃って初めて分割の恒等式が言える</b>。 */
    private static final me.cortex.voxy.client.core.vk.VkHierarchicalScene.Visibility[]
        TEMPORAL_CYCLE = {
            me.cortex.voxy.client.core.vk.VkHierarchicalScene.Visibility.ALL_VISIBLE,
            me.cortex.voxy.client.core.vk.VkHierarchicalScene.Visibility.NONE_VISIBLE,
            me.cortex.voxy.client.core.vk.VkHierarchicalScene.Visibility.EVEN_NEW,
            me.cortex.voxy.client.core.vk.VkHierarchicalScene.Visibility.ODD_NEW,
        };


    /** 5c-4c: 描画距離 (ブロック)。負なら無制限。 */
    private static final double HIER_RENDER_DISTANCE =
        Double.parseDouble(System.getProperty("voxy.5c4.distance", "-1"));
    private double lastCameraX, lastCameraY, lastCameraZ;
    private boolean warnedNoWorld;
    /**
     * モードごとの直近の観測。{@code {drawn, opaqueQuads, temporalQuads}}。
     *
     * <p>⚠ <b>モードをまたいで比べられるのは drawn が同じときだけ</b>である。
     * トラバーサルの選択が動いていたら、quad 数の差が可視の書き方によるものか
     * 選択が変わったせいかを<b>区別できない</b> [規約 22]。
     */
    private final java.util.EnumMap<
        me.cortex.voxy.client.core.vk.VkHierarchicalScene.Visibility, int[]> temporalSamples =
        new java.util.EnumMap<>(me.cortex.voxy.client.core.vk.VkHierarchicalScene.Visibility.class);

    /** 実ジオメトリのジオメトリバッファ容量 (quad)。足りなければ半径を下げる。 */
    private static final int REAL_GEOMETRY_MAX_QUADS =
        Integer.parseInt(System.getProperty("voxy.5c3b.quads", "2000000"));

    /**
     * 実モデルのテクスチャを使うか。<b>既定は使う</b>。
     *
     * <h2>⚠ 合成色を既定にしようとして、目で読めないと分かった</h2>
     * 合成アトラスの色は <b>{@code R = モデル id, G = 0, B = 面*40+15}</b> である
     * [確認済 — {@code fillSyntheticAtlas}]。**G が常に 0** なので、
     * 実データの 200 個ほどのモデルでは<b>青紫の斑にしかならない</b> (実機で確認)。
     * あの配色は<b>オフスクリーンの検査が {@code atlasTexel} と突き合わせるため</b>のもので、
     * 人の目で読むためのものではない。
     *
     * <h2>規約 1 はどう担保されるか</h2>
     * 実テクスチャでも<b>「索引がずれたら絵が変わる」は保たれる</b> —
     * ただしそれは<b>焼けたタイルが互いに区別できる場合に限る</b>。
     * だから {@code VkRealModelBakery.assertTilesAreDistinguishable} を
     * <b>実ジオメトリの経路でも走らせる</b> [5c-2b §6.3 の実データ版]。
     */
    private static final boolean REAL_GEOMETRY_COLOURS =
        Boolean.parseBoolean(System.getProperty("voxy.5c3b.realColours", "true"));

    /**
     * <b>MC の深度を無視して Voxy の絵だけを見る</b>か [Phase 5c-3b の診断]。
     *
     * <p>⚠ 5c-3b は<b>カメラの周り半径 4</b> を LoD 0 で描く。
     * そこは <b>MC 自身も描いている</b>ので、同じ面が同じ深度で争って<b>ちらつく</b>。
     * これは欠陥ではなく<b>この段の設定の当然の帰結</b>である
     * (本番の Voxy は MC の描画距離の<b>外</b>しか描かない)。
     *
     * <p>Voxy 側だけを見たいときに使う。
     */
    private static final boolean REAL_GEOMETRY_ONLY =
        Boolean.parseBoolean(System.getProperty("voxy.5c3b.only", "false"));

    /** 焼いたタイルを GPU へ流すか (合成アトラスと<b>二重に流さない</b>ため)。 */
    private boolean uploadBakedTiles = true;
    private java.util.List<me.cortex.voxy.client.core.rendering.building.BuiltSection> meshedSections;
    private int[] lastMeshedCameraSection;

    /** 合成地形が横に占めるブロック数 (見かけの大きさの見積もり用)。 */
    private static final double TERRAIN_SPAN_BLOCKS = 192.0;

    private static final double BEYOND_FAR_PLANE_FACTOR =
        Double.parseDouble(System.getProperty("voxy.5c3.factor", "2.0"));

    /** 合成地形の中心をカメラから何ブロック前に置くか。 */
    private static final double TERRAIN_DISTANCE_BLOCKS =
        Double.parseDouble(System.getProperty("voxy.5c1e.near", "64"));

    /**
     * 奥の組までの距離。<b>MC の地形が間に入りうる遠さ</b>にする。
     *
     * <p>⚠ 遠すぎると MC の far 平面で切られる。切られた場合はログの
     * {@code farCentre} の深度が {@code (0,1)} の外に出るので分かる。
     */
    private static final double PAIR_FAR_BLOCKS =
        Double.parseDouble(System.getProperty("voxy.5c1e.far", "160"));

    /**
     * 奥の組を横にずらす量。<b>手前の組が奥の組を隠さないようにする</b> —
     * 重ねると「奥が見えないのは MC のせいか手前の組のせいか」が区別できない。
     */
    /**
     * 5c-1e のアンカーの量子。<b>1 セクション</b>。
     *
     * <p>{@code 1 << maxLevel} (= 2) にすると丸めが最大 32 ブロック動き、
     * <b>手前と奥の距離差を潰してしまう</b> (実際に踏んだ: 32/144 のつもりが 82/106 になった)。
     *
     * <p>代わりに LoD 1 のセクションが 1 つだけ最大 1 セクションずれるが、
     * <b>この段で見たいのは前後関係</b>であって配置の精度ではない。
     */
    private static final int PAIR_QUANTUM = 1;

    private static final double PAIR_LATERAL_BLOCKS =
        Double.parseDouble(System.getProperty("voxy.5c1e.lateral", "64"));

    /**
     * データセットの中心 (セクション単位)。アンカーからこのぶん引くと中心が正面に来る。
     *
     * <p>{@code boundaryCases()} のセクションは x∈[0,5]、z∈[-3,5] に散っているので
     * その中ほどを取る。Y は 0 — <b>地面をまたがせて、一部が MC の地形に隠れる状況</b>を
     * 作るためである (5c-1e の前哨戦)。
     */
    private static final int[] TERRAIN_CENTRE_SECTIONS = {2, 0, 1};

    /**
     * 合成地形の背景 (どの quad も覆わない画素)。<b>橙</b>。
     *
     * <p>本来これは合成で {@code discard} されるので<b>画面に出てはならない</b>。
     * 出たら「描いていない画素を捨てる」条件が効いていないということで、
     * <b>目立つ色にしてあるので一目で分かる</b>。
     */
    private static final float[] TERRAIN_CLEAR = {0.9f, 0.45f, 0.05f, 1.0f};

    /** 診断用: 合成を何回呼んだか。 */
    private long frames;
    /** 診断用: IOSurface を読み戻すための小さなバッファ。 */
    private VkBuffer probeReadback;
    /** 診断用: 地形が画面に落ちた量を数えるための全面バッファ。 */
    private VkBuffer footprintReadback;
    /** 読み戻す領域の一辺。 */
    private static final int PROBE = 4;
    /** 読み戻す箇所の数 (絵の上端寄り / 中央 / 下端寄り)。 */
    private static final int PROBE_SPOTS = 3;

    private VkInteropProbe() {}

    /**
     * 画面サイズに合わせて資源を作り直す。
     *
     * <p><b>解放順は {@link VkInteropImage#free} が持っている</b> (view/image → GL テクスチャ →
     * IOSurface)。直前のフレームは既に合成まで終わっているので {@code glFinish} は要らない。
     */
    private void resizeIfNeeded(int w, int h) {
        if (this.colour != null && this.width == w && this.height == h) return;

        this.freeSizeDependent();
        this.width = w;
        this.height = h;
        this.colour = new VkInteropImage(w, h, VkInteropImage.Kind.COLOR_BGRA8,
            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        // ⚠ 深度は<b>段によって書き手が入れ替わる</b>:
        //   5c-1c (DEPTH)   GL が書き   Vulkan が読む  -> SAMPLED が要る
        //   5c-1d (TERRAIN) Vulkan が書き GL が読む     -> COLOR_ATTACHMENT が要る
        // 両方を立てておく。usage が足りないと生成時ではなく**使った瞬間**に壊れる
        this.depth = new VkInteropImage(w, h, VkInteropImage.Kind.DEPTH_R32F,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        // GL テクスチャは GL スレッドで作る (ここは Render thread)
        this.colour.glTexture();
        this.depth.glTexture();

        // ⚠ GL が書く前に UNDEFINED -> GENERAL を済ませる。
        // これを怠ると最初の 1 フレームだけ GL の書き込みが捨てられる [Phase 5a §6.1]
        this.depth.primeLayout();

        this.visualise = new VkDepthVisualise(this.depth.texture(), w, h,
            VkInteropImage.Kind.COLOR_BGRA8.vkFormat);

        if (MODE == Mode.HIERARCHICAL) {
            // ⚠ シーンは**サイズ依存として作り直す**。HiZ と描画器が画面サイズを持つので、
            // 分けて持つより単純である。メッシュ化をやり直すぶん遅いが、リサイズは稀
            if (this.scene != null) { this.scene.free(); this.scene = null; }
            this.rt = new VkRenderTarget(w, h, this.colour.texture(),
                VkInteropImage.Kind.COLOR_BGRA8.vkFormat);
            this.resolve = new VkDepthResolve(this.rt.depth, w, h, VOXY_PROJECTION);
            this.buildHierarchicalScene(w, h);
            return;
        }
        if (drawsTerrain()) {
            this.ensureScene();
            // 描き先は **interop の色画像**。5b の ioRt と同じ組み方である
            this.rt = new VkRenderTarget(w, h, this.colour.texture(),
                VkInteropImage.Kind.COLOR_BGRA8.vkFormat);
            this.renderer = new VkTerrainRenderer(this.res, w, h,
                VkTerrainRenderer.Barriers.CONSERVATIVE,
                // ⚠ PER_SECTION は **オフスクリーンの基準と同じ経路**である。
                // MERGED (本番の統合描画) は cmdgen/HiZ が要るので 5c-4 で入れる
                VkTerrainRenderer.Mode.PER_SECTION, VkTerrainRenderer.Pass.OPAQUE,
                VkInteropImage.Kind.COLOR_BGRA8.vkFormat);
            // 5c-3a: 自前の投影で描くなら、深度を MC の空間へ写し直す必要がある
            this.resolve = new VkDepthResolve(this.rt.depth, w, h, VOXY_PROJECTION);
            this.lastAnchor = null;   // サイズが変わったら位置も書き直す
        }

        Logger.info("[5c-1d] interop images (re)created at " + w + "x" + h
            + "; green square at x=[0," + cornerEndCol(w) + ") y=[" + cornerStartRow(h) + "," + h + ")"
            + "   [rows are framebuffer rows; row 0 is the BOTTOM of the picture under the GL"
            + " convention, so the square is at the TOP-LEFT of the screen]");
    }

    /**
     * 1 フレーム分を Vulkan で描き、現在束縛されている GL フレームバッファへ合成する。
     *
     * <p><b>合成は深度を書かない。</b> この段で運んでいるのは
     * <b>MC の深度を Vulkan へ渡す向き</b>だけで、Voxy 自身の深度はまだ無い。
     * 書くと {@code GL_ALWAYS} で MC の深度バッファを一様に潰す
     * [docs/phase5c1b-completion.md §4]。
     *
     * @param mcColourTexture MC のカラーテクスチャ (合成先)
     * @param mcDepthTexture  MC の深度テクスチャ (D32F。Vulkan へ渡す元)
     */
    public void composite(int mcColourTexture, int mcDepthTexture, int w, int h,
                          org.joml.Matrix4fc projection, org.joml.Matrix4fc modelView,
                          double cameraX, double cameraY, double cameraZ) {
        if (MODE == Mode.OFF) return;
        if (w <= 0 || h <= 0) return;
        // ⚠ ensureScene はカメラを引数で受け取らないが、実ジオメトリでは
        // **どこをメッシュ化するか**にカメラが要る
        this.lastCameraX = cameraX; this.lastCameraY = cameraY; this.lastCameraZ = cameraZ;

        // ⚠ `target.width/height` はレンダーターゲットの論理サイズで、
        // テクスチャの実寸と食い違いうる (Retina のバッキングスケール等)。
        // 食い違ったまま texelFetch すると **範囲外読み = 未定義 (実際は黒)** になる
        int prevTex2d = org.lwjgl.opengl.GL11C.glGetInteger(
            org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D);
        org.lwjgl.opengl.GL11C.glBindTexture(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, mcColourTexture);
        int texW = org.lwjgl.opengl.GL11C.glGetTexLevelParameteri(
            org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_WIDTH);
        int texH = org.lwjgl.opengl.GL11C.glGetTexLevelParameteri(
            org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_HEIGHT);
        // 深度も同じ実寸であることを確かめる。違えば深度の texelFetch が範囲外を読む
        org.lwjgl.opengl.GL11C.glBindTexture(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, mcDepthTexture);
        int depthW = org.lwjgl.opengl.GL11C.glGetTexLevelParameteri(
            org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_WIDTH);
        int depthH = org.lwjgl.opengl.GL11C.glGetTexLevelParameteri(
            org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_HEIGHT);
        org.lwjgl.opengl.GL11C.glBindTexture(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, prevTex2d);

        if (texW > 0 && texH > 0 && (texW != w || texH != h)) {
            if (this.frames < 3) {
                Logger.warn("[5c-1d] target says " + w + "x" + h + " but MC's colour texture is "
                    + texW + "x" + texH + " — using the texture size (compositing into it)");
            }
            w = texW;
            h = texH;
        }
        if (depthW > 0 && depthH > 0 && (depthW != w || depthH != h)) {
            // ここが食い違うと 5c-1c は成立しない。黙って進むと「深度が化けている」
            // という形でしか現れず、原因が遠くなる
            Logger.error("[5c-1d] MC's depth texture is " + depthW + "x" + depthH
                + " but the colour texture is " + w + "x" + h
                + " — skipping the depth path this frame");
            return;
        }

        VkFrameTracker.init();   // 冪等 (INSTANCE が null のときだけ作る)
        this.resizeIfNeeded(w, h);
        this.ensureGlObjects();

        // ---- 1. GL: MC の深度を interop の R32F へ写す (5c-1c のみ) ----
        if (MODE == Mode.DEPTH) {
            this.depthImport.record(mcDepthTexture, this.depth);
            // ---- 2. GL -> Vulkan。GL の書き込み順序はバリアで表現できない ----
            GlVkSync.waitForGl();
        }

        // ---- 3. Vulkan ----
        if (MODE == Mode.HIERARCHICAL) {
            // ⚠ ワールドが閉じられていたら**シーンごと捨てる**。
            // 掴んだままだと acquireIfExists が投げてフレームの途中で落ちる
            if (this.scene != null && !this.scene.worldIsLive()) {
                Logger.info("[5c-4c] the world engine was closed (idle world reclaim);"
                    + " dropping the scene and rebuilding");
                this.scene.free();
                this.scene = null;
            }
            if (this.scene == null) this.buildHierarchicalScene(w, h);
            if (this.scene != null) {
                // ⚠ **prepare より前**に決める。writeVisibility は prepare の中で走る
                this.scene.setVisibility(this.visibilityForThisFrame());
                this.writeHierarchicalUniform(projection, modelView, cameraX, cameraY, cameraZ);
            }
        } else if (drawsTerrain()) {
            this.writeSceneUniform(projection, modelView, cameraX, cameraY, cameraZ);
        }
        var tracker = VkFrameTracker.get();
        var cmd = tracker.beginFrame();
        switch (MODE) {
            case HIERARCHICAL -> {
                if (this.scene != null) {
                    this.scene.record(cmd, this.rt, TERRAIN_CLEAR,
                        (c, d) -> this.resolve.record(c, d, this.depth));
                    this.colour.toGeneral(cmd,
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                        VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
                }
            }
            case TERRAIN, TERRAIN_NODEPTH, TERRAIN_REFMVP, PAIR, TRIPLE -> {
                // 焼いたテクスチャを先に流す。地形描画の recordUploads より前でなければ
                // ミップの遷移とレイアウトが噛み合わない
                if (this.bakery != null && this.uploadBakedTiles) this.bakery.recordUploads(cmd);
                // 合成地形を描き、その深度を interop の R32F へ解決する。
                // **色と深度の両方**が GL に渡る (5c-1c までは色だけだった)
                this.renderer.record(cmd, this.rt, this.drawCount, TERRAIN_CLEAR);
                this.resolve.record(cmd, this.rt.depth, this.depth);
                this.colour.toGeneral(cmd,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
            }
            case DEPTH -> this.visualise.record(cmd, this.depth.texture(), this.colour.texture(),
                // 緑の四角だけ重ねる。同じレンダリングの中なので順序が保証される
                () -> recordCornerMarker(cmd, this.width, this.height));
            default -> recordTestPattern(cmd, this.colour, this.width, this.height);
        }
        tracker.endFrame();
        // Vulkan -> GL の同期。Phase 0 が 0.315 ms と測った待ち
        tracker.waitForFrame();

        // --- 診断 (最初の数フレームだけ) ---
        //
        // ⚠ 5c-3a では**周期的にも出す**。答えが「drawn= の数字」なので、
        // プレイヤーが向きを変えたあとにも読めなければ確認できない。
        // 最初の 3 フレームだけだと、たまたまそちらを向いていなかった場合と
        // 「描けていない」場合が**区別できない**
        if (this.frames < 3 || (MODE == Mode.TRIPLE && this.frames % 300 == 0)) {
            this.logDiagnostics(mcColourTexture, mcDepthTexture, w, h);
        }
        // ⚠ トラバーサルの要求に答える。**フレームの完了後**でなければ
        // 要求キューの中身が確定していない
        if (MODE == Mode.HIERARCHICAL && this.scene != null) {
            this.scene.serviceRequests(HIER_MESHES_PER_FRAME);
        }
        // ⚠ 5c-4c は**毎フレーム値が変わる**ので周期的に出す。
        // 初回だけだと「まだメッシュ化が終わっていない状態」の数字を見てしまう
        if (MODE == Mode.HIERARCHICAL && this.scene != null
                && (this.frames == 2 || this.frames % 120 == 0)) {
            this.logHierarchical();
            // ⚠ **画素数を出す。** 「drawn=216」は*選ばれた*数であって
            // *画面に出た*数ではない。目視で判断させると 5c-3a と同じ空振りになる
            Logger.info("[5c-4c]   voxy footprint: " + this.screenFootprint());
        }
        // ⚠ cycle はモードの**最後のフレーム**でだけ採る。切り替えた直後は
        // まだ前のモードのテーブルが残っている [規約 23]
        if (MODE == Mode.HIERARCHICAL && this.scene != null && "cycle".equals(TEMPORAL_MODE)
                && (this.frames % TEMPORAL_CYCLE_FRAMES) == TEMPORAL_CYCLE_FRAMES - 1) {
            this.sampleTemporal();
        }
        this.frames++;

        // ---- 4. GL: 合成 ----
        // ⚠ 何もしないと既定フレームバッファ (drawFbo=0) に描いてしまい、
        // MC が後からメインターゲットを転送する際に消える [GlScratchFramebuffer の javadoc]
        // ⚠ **深度も付ける。** 付けないと深度テストが成立せず、
        // 「MC の深度は無傷なのに Voxy が何にも隠されない」状態になる
        // [GlScratchFramebuffer.bindWithColourAndDepth の javadoc]
        int previousFbo = this.scratchFbo.bindWithColourAndDepth(
            org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, mcColourTexture, mcDepthTexture);
        int[] previousViewport = new int[4];
        org.lwjgl.opengl.GL11C.glGetIntegerv(org.lwjgl.opengl.GL11C.GL_VIEWPORT, previousViewport);
        org.lwjgl.opengl.GL11C.glViewport(0, 0, w, h);

        // ブレンド・シザー・深度・プログラム・VAO・テクスチャ・サンプラは
        // **合成側が自分で控えて自分で戻す** [GlInteropCompositor.composite の javadoc]。
        // ここで GlStateManager を使って戻そうとすると、
        // キャッシュが「既にその値」と判断して GL を呼ばず、復帰が no-op になる
        if (MODE != Mode.STATE) {
            this.compositor.composite(this.colour.glTexture(), this.depth.glTexture());
        }

        org.lwjgl.opengl.GL11C.glViewport(previousViewport[0], previousViewport[1],
            previousViewport[2], previousViewport[3]);
        this.scratchFbo.restore(previousFbo);
    }

    private void ensureGlObjects() {
        if (this.scratchFbo == null) this.scratchFbo = new GlScratchFramebuffer();
        if (this.depthImport == null) this.depthImport = new GlDepthImport();
        if (this.compositor == null) {
            // GlInteropCompositor は GL 4.1 の範囲で書かれている
            // (FullscreenBlit は glCreateVertexArrays = GL 4.5 なので使えない)
            this.compositor = switch (MODE) {
                case SOLID -> new GlInteropCompositor(
                    GlInteropCompositor.Defect.CONSTANT_COLOUR, GlInteropCompositor.DepthMode.NONE);
                // ⚠ 5c-1d で初めて深度を書く。**無条件の上書きではない** —
                // 描いていない画素は捨て、残りは MC の深度と比較する
                // 5c-3b: MC も同じ場所を描いているので、深度を見ると当然ちらつく。
                // Voxy 側だけを見たいときは深度を見ない
                // ⚠ **HIERARCHICAL をここに入れ忘れると default に落ちて DepthMode.NONE になる。**
                // 深度を見ずに全面を貼るので、**クリア色が MC の画面を丸ごと覆う**
                // (5c-1d の「橙一色」と同じ症状・同じ場所)
                case TERRAIN, PAIR, TRIPLE, HIERARCHICAL -> (usesRealGeometry() && REAL_GEOMETRY_ONLY)
                    ? new GlInteropCompositor(GlInteropCompositor.DepthMode.NONE)
                    : GlInteropCompositor.forHost();
                // 診断: 深度を見ずに全面を貼る。橙一色なら地形が画面に無い
                case TERRAIN_NODEPTH, TERRAIN_REFMVP ->
                    new GlInteropCompositor(GlInteropCompositor.DepthMode.NONE);
                // まだ Voxy 自身の深度が無い段。書くと MC の深度を潰す
                default -> new GlInteropCompositor(GlInteropCompositor.DepthMode.NONE);
            };
        }
    }

    // ---------------- 5c-4c: 実データの階層トラバーサル ----------------

    /**
     * 実データのシーンを作ってメッシュ化する。
     *
     * <p>⚠ ワールドがまだ無ければ<b>作らずに戻る</b>。次のフレームでやり直す。
     */
    private void buildHierarchicalScene(int w, int h) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return;
        var world = me.cortex.voxy.commonImpl.WorldIdentifier.ofEngineNullable(level);
        if (world == null) {
            if (!this.warnedNoWorld) {
                this.warnedNoWorld = true;
                Logger.warn("[5c-4c] no Voxy world engine yet — retrying each frame");
            }
            return;
        }
        // ⚠ HiZ の元は **前フレームの解決済み深度**。本番は MC の深度も混ざるが、
        // まずは Voxy 自身の遮蔽だけにする [1 変数ずつ]
        var built = new me.cortex.voxy.client.core.vk.VkHierarchicalScene(
            world, this.depth.texture(), w, h,
            Integer.parseInt(System.getProperty("voxy.5c4.sections", "20000")),
            REAL_GEOMETRY_MAX_QUADS, VkInteropImage.Kind.COLOR_BGRA8.vkFormat);
        try {
            built.populate(this.lastCameraX, this.lastCameraY, this.lastCameraZ,
                HIER_TOP_RADIUS, HIER_DEPTH);
        } catch (RuntimeException e) {
            built.free();
            throw e;
        }
        if (built.meshedSections() == 0) {
            // ⚠ ワールドがまだその LoD を持っていない。作り直せるように捨てる
            Logger.warn("[5c-4c] ⚠ nothing meshed at LoD " + me.cortex.voxy.common.world.WorldEngine.MAX_LOD_LAYER
                + ".." + Math.max(0, me.cortex.voxy.common.world.WorldEngine.MAX_LOD_LAYER - HIER_DEPTH)
                + " — Voxy only has what the player has loaded. Fly around, or lower"
                + " -Pvoxy5c4Depth so it reaches a level that exists");
            built.free();
            return;
        }
        this.scene = built;
    }

    /**
     * 階層トラバーサル用のユニフォーム。<b>描画とトラバーサルで同じ MVP を使う</b> —
     * 違うと画面上の大きさが食い違い、<b>降下の判定が描画とずれる</b>。
     */
    private void writeHierarchicalUniform(org.joml.Matrix4fc projection,
                                          org.joml.Matrix4fc modelView,
                                          double cameraX, double cameraY, double cameraZ) {
        int[] anchor = {
            me.cortex.voxy.client.core.vk.VkHostViewport.sectionOf(cameraX),
            me.cortex.voxy.client.core.vk.VkHostViewport.sectionOf(cameraY),
            me.cortex.voxy.client.core.vk.VkHostViewport.sectionOf(cameraZ)};
        float[] sub = me.cortex.voxy.client.core.vk.VkHostViewport.cameraSubPos(
            cameraX, cameraY, cameraZ, anchor);

        var mcProjection = me.cortex.voxy.client.core.vk.VkHostViewport.projectionForVulkan(
            projection, modelView, sub, new int[]{0, 0, 0});
        var vkProjection = VOXY_PROJECTION
            ? me.cortex.voxy.client.core.vk.VkHostViewport.voxyProjection(mcProjection,
                me.cortex.voxy.client.core.vk.VkHostViewport.VOXY_NEAR,
                me.cortex.voxy.client.core.vk.VkHostViewport.VOXY_FAR)
            : mcProjection;
        float[] m = me.cortex.voxy.client.core.vk.VkHostViewport.mvp(vkProjection, modelView, sub);

        VkSceneUniform.write(this.scene.res.uniform, m, anchor,
            (int) (this.frames & 0x7fffffff), sub);

        if (VOXY_PROJECTION) {
            float[] mcM = me.cortex.voxy.client.core.vk.VkHostViewport.mvp(
                mcProjection, modelView, sub);
            this.resolve.setReprojection(
                new org.joml.Matrix4f().set(m).invert(),
                new org.joml.Matrix4f().set(mcM));
        }

        float minSSS = (float) ((HIER_SUBDIVISION_PX * HIER_SUBDIVISION_PX)
            / ((double) this.width * this.height));
        float renderDistance = HIER_RENDER_DISTANCE < 0 ? -1.0f
            : (float) (HIER_RENDER_DISTANCE * HIER_RENDER_DISTANCE);
        this.scene.prepare(new org.joml.Matrix4f().set(m), anchor, sub,
            minSSS, (int) (this.frames & 0x7fffffff), renderDistance);
    }

    // ---------------- 5c-3b: 実ジオメトリ ----------------

    /**
     * ワールドの実データをメッシュ化して置く (Phase 5c-3b)。
     *
     * <h2>⚠ ワールドがまだ無いことがある</h2>
     * Voxy は<b>プレイヤーが読み込んだセクションしか持たない</b>。
     * 何も取れなければ資源を作らずに戻り、<b>次のフレームでやり直す</b> —
     * ここで諦めると「一度失敗したら永久に出ない」になる。
     */
    private void ensureRealScene() {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return;
        var world = me.cortex.voxy.commonImpl.WorldIdentifier.ofEngineNullable(level);
        if (world == null) {
            if (!this.warnedNoWorld) {
                this.warnedNoWorld = true;
                Logger.warn("[5c-3b] no Voxy world engine for this level yet — retrying each frame");
            }
            return;
        }

        int r = REAL_GEOMETRY_RADIUS;
        int side = 2 * r + 1;
        int maxSections = side * side * side;

        var res = new VkTerrainResources(maxSections, REAL_GEOMETRY_MAX_QUADS,
            maxSections * 7, 1 << 16,
            me.cortex.voxy.client.core.vk.VkQuadIndexBuffer.DEFAULT_QUAD_CAPACITY,
            VkTerrainResources.AtlasScale.REAL);
        var target = new me.cortex.voxy.client.core.vk.VkModelUploadTarget(res);
        // ⚠ **ワールドの Mapper を借りる。** 新しく作ると、セクションの中身の
        // ブロック id / バイオーム id が全部別のものを指す
        var bakery = new me.cortex.voxy.client.core.vk.VkRealModelBakery(target, world.getMapper());
        var mesher = new me.cortex.voxy.client.core.vk.VkRealMesher(world, bakery);

        int cx = me.cortex.voxy.client.core.vk.VkHostViewport.sectionOf(this.lastCameraX);
        int cy = me.cortex.voxy.client.core.vk.VkHostViewport.sectionOf(this.lastCameraY);
        int cz = me.cortex.voxy.client.core.vk.VkHostViewport.sectionOf(this.lastCameraZ);
        var built = mesher.meshAround(cx, cy, cz, r);

        if (built.isEmpty()) {
            // まだ何も無い。**捨ててやり直す** (res を残すと二度と作り直されない)
            mesher.free(); bakery.free(); target.free(); res.free();
            return;
        }

        bakery.replayBiomes();
        var uploaded = me.cortex.voxy.client.core.vk.VkRealSectionUpload.upload(built, res);

        // ⚠ **アトラスの出どころは 1 つに決める。**
        // 両方流すと毎フレーム上書きし合い、実タイルと合成タイルが混ざる
        // (実機で「青紫の斑に緑の葉が混じる」形で出た)
        this.uploadBakedTiles = REAL_GEOMETRY_COLOURS;
        if (REAL_GEOMETRY_COLOURS) {
            res.useExternalAtlasContent();
            // 規約 1 の実データ版。⚠ **ここで結論を言わない** —
            // 「全部区別できる」は事実より強い主張になる (57 組は実際にタイルを共有していて、
            // 無害なだけである)。内訳を知っているのは bakery のほうなので、
            // 報告もあちらに任せる [規約 20]
            bakery.reportIndistinguishableStagedTiles();
        }

        this.res = res;
        this.modelTarget = target;
        this.bakery = bakery;
        this.mesher = mesher;
        this.meshedSections = built;
        this.drawCount = uploaded.drawCount();
        this.lastMeshedCameraSection = new int[]{cx, cy, cz};

        Logger.info("[5c-3b] uploaded " + uploaded.sectionCount() + " sections, "
            + uploaded.totalQuads() + " quads, " + uploaded.drawCount() + " draws"
            + "  (colours=" + (REAL_GEOMETRY_COLOURS ? "REAL textures" : "synthetic, by model id")
            + ")");

        // --- 不変条件。**絵を見る前に**言えることを言う ---
        var problems = me.cortex.voxy.client.core.vk.VkGeometryInvariants.checkSections(
            uploaded.geometry(), res.maxModels, res.indexQuadCapacity);
        if (problems.isEmpty()) {
            Logger.info("[5c-3b] the real geometry satisfies every invariant across "
                + uploaded.sectionCount() + " sections"
                + " (bucket order, stateId range, no duplicate quads, fits one draw)");
        } else {
            for (String p : problems) Logger.error("[5c-3b] ⚠ invariant broken: " + p);
        }
        this.reportGreedyMerging(uploaded);
    }

    /**
     * <b>貪欲メッシュが効いているか</b>を数字で言う (Phase 5c-3b)。
     *
     * <p>⚠ 合成地形は<b>全 quad が 1x1</b> だった。実データでも全部 1x1 なら
     * <b>併合が働いていない</b> — これは落ちないし絵も出るので、見に行かないと分からない
     * [規約 19 を書いたときの副産物]。
     */
    private void reportGreedyMerging(
            me.cortex.voxy.client.core.vk.VkRealSectionUpload.Uploaded uploaded) {
        long merged = 0, total = 0, area = 0;
        for (var sg : uploaded.geometry()) {
            for (int i = 0; i < sg.quadCount(); i++) {
                long q = org.lwjgl.system.MemoryUtil.memGetLong(sg.quadAddress() + (long) i * 8L);
                // ⚠ ここだけ大きさのビットを読む。**quad_format.glsl の literal**
                int sx = (int) ((q >>> 3) & 0xFL) + 1;
                int sy = (int) ((q >>> 7) & 0xFL) + 1;
                area += (long) sx * sy;
                if (sx > 1 || sy > 1) merged++;
                total++;
            }
        }
        Logger.info("[5c-3b] greedy merging: " + merged + " of " + total
            + " quads cover more than one block (" + area + " block faces in "
            + total + " quads)"
            + (merged == 0 ? "  ⚠ NOTHING merged — the mesher is emitting 1x1 quads only" : ""));
    }

    // ---------------- 5c-1d: 合成地形 ----------------

    /**
     * 合成地形の資源を作る (画面サイズに依存しないぶん)。
     *
     * <p><b>{@code interopCompositeCheck} と同じ組み立て</b>である —
     * 違う組み方をすると「オフスクリーンと同じ絵が出るか」を比べる意味が無くなる。
     */
    private void ensureScene() {
        if (this.res != null) return;
        if (usesRealGeometry()) { this.ensureRealScene(); return; }
        // 5c-1e は同じ地形を 2 組持つ。前半 = 手前、後半 = 奥
        this.terrain = SyntheticTerrain.boundaryCases().repeated(copies());
        // ⚠ 実データのモデル id は mapper が採番するので、合成の maxStateId とは無関係。
        // 少なめに取ると **範囲外読み**になる (バリデーションは捕まえない)
        int models = REAL_MODELS ? 4096 : this.terrain.maxStateId() + 1;
        this.res = new VkTerrainResources(this.terrain.sectionCount(), this.terrain.totalQuads(),
            4096, models,
            me.cortex.voxy.client.core.vk.VkQuadIndexBuffer.DEFAULT_QUAD_CAPACITY,
            REAL_MODELS ? VkTerrainResources.AtlasScale.REAL : VkTerrainResources.AtlasScale.SMALL);
        int[] starts;
        if (REAL_MODELS) {
            // ⚠ ModelBakerySubsystem を通さない (ModelStore = GL 4.5 DSA を作ってしまう)
            this.modelTarget = new me.cortex.voxy.client.core.vk.VkModelUploadTarget(this.res);
            this.bakery = new me.cortex.voxy.client.core.vk.VkRealModelBakery(this.modelTarget);
            // ⚠ 合成地形はバイオーム id に**セクション番号**を書く [SyntheticTerrain.writeRun] ので、
            // セクション数だけバイオームが要る。足りないと色表の外を読む
            int[] ids = this.bakery.bake(realBlocks(), this.terrain.sectionCount());
            this.res.useExternalAtlasContent();
            // 合成の stateId を焼けたモデル id へ写す。
            // ⚠ 単射ではないので、**モデルのずれは絵に出なくなる** (位置のずれは出る)
            starts = this.terrain.writeGeometry(this.res.geometry,
                raw -> ids[Math.floorMod(raw, ids.length)]);
            this.terrain.writeMetadata(this.res.sectionMetadata, starts);
            // モデル属性はベイカーが書いた。fillModels は**呼ばない** (上書きになる)
        } else {
            starts = this.terrain.writeGeometry(this.res.geometry);
            this.terrain.writeMetadata(this.res.sectionMetadata, starts);
            this.res.fillModels(VkTerrainResources.MODEL_FLAG_SHADED);
        }
        // 面方向マスクは **相対配置** で決まる。アンカーで平行移動しても変わらないので
        // ORIGIN のまま 1 度だけ作ってよい
        var draws = this.terrain.opaqueDrawCommands(starts, SyntheticTerrain.ORIGIN);
        if (ONLY_COPY >= 0) {
            // ⚠ 1 組だけ描く。**どの塊がどれか**を目で当てさせないため
            int per = Math.max(1, this.terrain.sectionCount() / copies());
            int want = Math.min(ONLY_COPY, copies() - 1);
            int before = draws.size();
            draws = draws.stream()
                .filter(d -> Math.min(d.drawId() / per, copies() - 1) == want)
                .toList();
            Logger.info("[5c-3a] drawing ONLY the '" + copyName(want) + "' copy: "
                + draws.size() + " of " + before + " draws"
                + "  [\"voxy footprint: drawn=\" in the log is now that copy alone]");
            if (draws.isEmpty()) {
                throw new IllegalStateException("filtering to the '" + copyName(want)
                    + "' copy left no draws — the section-to-copy mapping is wrong");
            }
        }
        SyntheticTerrain.writeDrawCommands(this.res.drawCall, draws, this.res.indexQuadCapacity);
        this.drawCount = draws.size();
        Logger.info("[5c-3a] projection=" + (VOXY_PROJECTION ? "VOXY (near=16 far=48000, depth reprojected)"
            : "MINECRAFT (control)") + "  copies=" + copies()
            + "  drawing=" + copyName(ONLY_COPY));
        Logger.info("[5c-1d] synthetic terrain: " + this.terrain.sectionCount() + " sections, "
            + this.terrain.totalQuads() + " quads, " + this.drawCount + " draws"
            + " (same dataset as interopCompositeCheck)");
    }

    /**
     * 実ジオメトリ用のユニフォーム (Phase 5c-3b)。
     *
     * <h2>合成地形との違い</h2>
     * <ul>
     *   <li>アンカーは<b>カメラのセクション</b>。本番と同じである
     *       (合成地形だけが「カメラの前に置く」ために別のアンカーを使っていた)</li>
     *   <li>位置は<b>アップロード時に絶対座標で書き終えている</b>。毎フレーム書き直さない</li>
     * </ul>
     */
    private void writeRealSceneUniform(org.joml.Matrix4fc projection, org.joml.Matrix4fc modelView,
                                       double cameraX, double cameraY, double cameraZ) {
        int[] anchor = {
            me.cortex.voxy.client.core.vk.VkHostViewport.sectionOf(cameraX),
            me.cortex.voxy.client.core.vk.VkHostViewport.sectionOf(cameraY),
            me.cortex.voxy.client.core.vk.VkHostViewport.sectionOf(cameraZ)};
        float[] sub = me.cortex.voxy.client.core.vk.VkHostViewport.cameraSubPos(
            cameraX, cameraY, cameraZ, anchor);

        var mcProjection = me.cortex.voxy.client.core.vk.VkHostViewport.projectionForVulkan(
            projection, modelView, sub, new int[]{0, 0, 0});
        var vkProjection = VOXY_PROJECTION
            ? me.cortex.voxy.client.core.vk.VkHostViewport.voxyProjection(mcProjection,
                me.cortex.voxy.client.core.vk.VkHostViewport.VOXY_NEAR,
                me.cortex.voxy.client.core.vk.VkHostViewport.VOXY_FAR)
            : mcProjection;
        float[] m = me.cortex.voxy.client.core.vk.VkHostViewport.mvp(vkProjection, modelView, sub);
        VkSceneUniform.write(this.res.uniform, m, anchor,
            (int) (this.frames & 0x7fffffff), sub);

        if (VOXY_PROJECTION) {
            float[] mcM = me.cortex.voxy.client.core.vk.VkHostViewport.mvp(
                mcProjection, modelView, sub);
            this.resolve.setReprojection(
                new org.joml.Matrix4f().set(m).invert(),
                new org.joml.Matrix4f().set(mcM));
        }

        if (this.frames < 3 || this.frames % 300 == 0) {
            Logger.info("[5c-3b] camera section " + java.util.Arrays.toString(anchor)
                + "  meshed around " + java.util.Arrays.toString(this.lastMeshedCameraSection)
                + "  draws=" + this.drawCount
                + "  [the mesh does NOT follow the camera in 5c-3b; walk back if nothing shows]");
        }
    }

    /**
     * MC のカメラから <b>MVP・アンカー・カメラ内位置</b>を作ってユニフォームに書く。
     *
     * <h2>本番の GL 経路と同じ組み立て [確認済 — {@code MDICSectionRenderer.uploadUniformBuffer}]</h2>
     * <pre>
     * MVP           = projection * modelView, その後 -(カメラ - アンカー*32) だけ平行移動
     * baseSectionPos = アンカー (セクション座標)
     * cameraSubPos   = カメラ - アンカー*32
     * </pre>
     *
     * <p>本番は<b>アンカー = カメラのセクション</b>である。ここだけ違うのは、
     * 合成地形を<b>プレイヤーの近くに置きたい</b>からで
     * ({@link #TERRAIN_OFFSET_SECTIONS})、
     * <b>式そのものは同じ</b>である (本番は平行移動が「セクション内の端数」に縮退する)。
     *
     * <p>⚠ <b>アンカーは {@code 1 << maxLevel} の倍数に揃える</b> —
     * {@link SyntheticTerrain#writePositions} の注意を参照。
     *
     * <h2>⚠ 投影は MC のものをそのまま使う</h2>
     * Voxy の GL 経路は near=16 / far=48000 の<b>自前の投影</b>で描き、
     * 書き戻すときに {@code transformBlitDepth} で<b>深度を MC の空間へ再投影</b>している。
     * 5c-1d は<b>再投影を持ち込まない</b> — MC の投影で描けば深度は最初から MC と同じ空間にあり、
     * 合成はそのまま比較できる。<b>この段で増える未知を 1 つに保つ</b>ためである。
     *
     * <p>これは<b>遠景の LoD には足りない</b> (MC の far 平面で切られる)。
     * 自前の投影 + 深度の再投影が要るのは 5c-2 以降である
     * [docs/phase5c1d-completion.md]。
     */
    private void writeSceneUniform(org.joml.Matrix4fc projection, org.joml.Matrix4fc modelView,
                                   double cameraX, double cameraY, double cameraZ) {
        if (MODE == Mode.TERRAIN_REFMVP) {
            this.writeReferenceSceneUniform();
            return;
        }
        if (usesRealGeometry()) {
            this.writeRealSceneUniform(projection, modelView, cameraX, cameraY, cameraZ);
            return;
        }
        // ⚠ **カメラの正面**に置く。固定の方角に置いていた 5c-1d の初回は、
        // プレイヤーがそちらを向いていなかったために「橙一色」になり、
        // 「経路が壊れている」と区別が付かなかった [docs/phase5c1d-completion.md §7]
        int[] anchor = me.cortex.voxy.client.core.vk.VkHostViewport.anchorInFront(
            cameraX, cameraY, cameraZ, projection, modelView, TERRAIN_DISTANCE_BLOCKS,
            TERRAIN_CENTRE_SECTIONS, 1 << this.terrain.maxLevel());

        // アンカーが変わったときだけ位置を書き直す (プレイヤーが動くと追従)
        if (this.lastAnchor == null || !java.util.Arrays.equals(this.lastAnchor, anchor)) {
            if (drawsPair()) {
                this.writePairPositions(anchor, cameraX, cameraY, cameraZ, projection, modelView);
            } else {
                this.terrain.writePositions(this.res.positionScratch, anchor);
            }
            this.lastAnchor = anchor;
            if (this.frames < 3 || this.frames % 600 == 0) {
                Logger.info("[5c-1e] near copy anchored at section "
                    + java.util.Arrays.toString(anchor) + "; " + this.worldBoundsOf(anchor));
            }
        }

        float[] sub = me.cortex.voxy.client.core.vk.VkHostViewport.cameraSubPos(
            cameraX, cameraY, cameraZ, anchor);
        // ⚠ MC の投影は逆Z・**-1..1**。Vulkan のクリップ空間は常に 0..1 なので、
        // そのまま渡すと z < 0 の断片が全部クリップされる (5c-1d で 1 画素も出なかった原因)。
        // 規約を決め打ちせず、検算して要るときだけ変換する [規約 9]
        var mcProjection = me.cortex.voxy.client.core.vk.VkHostViewport.projectionForVulkan(
            projection, modelView, sub, TERRAIN_CENTRE_SECTIONS);

        // 5c-3a: 自前の投影で描くと MC の far 平面の外まで届く。
        // ⚠ その代わり深度は MC の空間に無いので、**書き戻す前に写し直す**必要がある
        var vkProjection = VOXY_PROJECTION
            ? me.cortex.voxy.client.core.vk.VkHostViewport.voxyProjection(mcProjection,
                me.cortex.voxy.client.core.vk.VkHostViewport.VOXY_NEAR,
                me.cortex.voxy.client.core.vk.VkHostViewport.VOXY_FAR)
            : mcProjection;
        float[] m = me.cortex.voxy.client.core.vk.VkHostViewport.mvp(vkProjection, modelView, sub);

        VkSceneUniform.write(this.res.uniform, m, anchor,
            (int) (this.frames & 0x7fffffff), sub);

        if (copies() >= 3 && this.lastBeyondAnchor != null) {
            float[] beyondSub = me.cortex.voxy.client.core.vk.VkHostViewport.cameraSubPos(
                cameraX, cameraY, cameraZ, this.lastBeyondAnchor);
            this.beyondOnScreen = me.cortex.voxy.client.core.vk.VkHostViewport.insideFrustum(
                me.cortex.voxy.client.core.vk.VkHostViewport.clipOfCentre(
                    vkProjection, modelView, beyondSub, TERRAIN_CENTRE_SECTIONS));
        }

        if (VOXY_PROJECTION) {
            // ⚠ 写し先の MVP は**同じ sub** で組むこと。再投影が経由する
            // 「カメラ相対ワールド座標」が両者で同じ空間でなければならない
            float[] mcM = me.cortex.voxy.client.core.vk.VkHostViewport.mvp(
                mcProjection, modelView, sub);
            this.resolve.setReprojection(
                new org.joml.Matrix4f().set(m).invert(),
                new org.joml.Matrix4f().set(mcM));
        }

        if (this.frames < 3) {
            this.logViewportMath(modelView, m, anchor, sub);
            if (me.cortex.voxy.client.core.vk.VkHostViewport.depthStillOutOfRange(
                    vkProjection, modelView, sub, TERRAIN_CENTRE_SECTIONS)) {
                Logger.warn("[5c-1d] the depth of the terrain centre is STILL outside (0,1)"
                    + " after the zero-to-one conversion — the host uses a depth convention"
                    + " this code does not know about");
            }
            if (VOXY_PROJECTION && this.lastBeyondAnchor != null) {
                float[] beyondSub = me.cortex.voxy.client.core.vk.VkHostViewport.cameraSubPos(
                    cameraX, cameraY, cameraZ, this.lastBeyondAnchor);
                boolean outOfMc = me.cortex.voxy.client.core.vk.VkHostViewport.depthStillOutOfRange(
                    mcProjection, modelView, beyondSub, TERRAIN_CENTRE_SECTIONS);
                boolean outOfVoxy = me.cortex.voxy.client.core.vk.VkHostViewport.depthStillOutOfRange(
                    vkProjection, modelView, beyondSub, TERRAIN_CENTRE_SECTIONS);
                Logger.info("[5c-3a] BEYOND copy: outside Minecraft's frustum=" + outOfMc
                    + " (must be true, else the control is vacuous),"
                    + " outside Voxy's frustum=" + outOfVoxy
                    + " (must be false, else it cannot be drawn at all)");
            }
            // ⚠ 5c-1e: 奥の組が MC の far 平面の外にいると、
            // 「奥が見えない」のが**遮蔽ではなく切り落とし**になり対照が空虚になる
            if (drawsPair() && this.lastFarAnchor != null) {
                float[] farSub = me.cortex.voxy.client.core.vk.VkHostViewport.cameraSubPos(
                    cameraX, cameraY, cameraZ, this.lastFarAnchor);
                if (me.cortex.voxy.client.core.vk.VkHostViewport.depthStillOutOfRange(
                        vkProjection, modelView, farSub, TERRAIN_CENTRE_SECTIONS)) {
                    Logger.warn("[5c-1e] the FAR copy is outside the frustum (probably beyond"
                        + " Minecraft's far plane at this render distance). It would be hidden"
                        + " by CLIPPING, not by occlusion — the control is vacuous."
                        + " Lower it with -Pvoxy5c1eFar=<blocks> or raise the render distance");
                }
            }
        }
    }

    /**
     * <b>オフスクリーン検査と同じ視点</b>でユニフォームを書く (診断専用)。
     *
     * <p>MC のカメラを一切使わない。{@code InteropCompositeCheck.closeUpMvp} と同じ値なので、
     * <b>出るべき絵が既に分かっている</b> — それが対照になる。
     *
     * <p>アンカーは {@link SyntheticTerrain#ORIGIN} で、位置もずらさない。
     * つまり<b>オフスクリーンと完全に同じ入力</b>である。
     */
    private void writeReferenceSceneUniform() {
        if (this.lastAnchor == null) {
            this.terrain.writePositions(this.res.positionScratch, null);
            this.lastAnchor = SyntheticTerrain.ORIGIN;
            Logger.info("[5c-1d] REFMVP: drawing with the offscreen check's viewpoint;"
                + " the picture should match build/vk-test-output/5b-interop-composite.png");
        }
        float[] mvp = VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(60),
                (float) this.width / this.height, 0.1f, 2000f),
            VkSceneUniform.lookAt(new float[]{14.0f, 7.0f, 13.0f},
                new float[]{8.5f, 0.5f, 0.5f}, new float[]{0, 1, 0}));
        VkSceneUniform.write(this.res.uniform, mvp, SyntheticTerrain.ORIGIN,
            (int) (this.frames & 0x7fffffff), new float[]{0, 0, 0});
    }

    /**
     * <b>行列の効き方を CPU 側で 1 行にする。</b>
     *
     * <p>「地形が見えない」ときに<b>行列が悪いのか置き場所が悪いのか</b>を
     * 目視では割れない。ここでデータセットの中心を<b>同じ MVP で CPU 投影</b>し、
     * NDC を出す。
     *
     * <ul>
     *   <li><b>|ndc.xy| &lt;= 1</b> → 中心は画面内。見えないなら深度か大きさの問題</li>
     *   <li><b>|ndc.xy| が桁で大きい</b> → 行列が違う。とくに<b>カメラ座標の大きさに比例</b>して
     *       いれば、{@code modelView} が既にカメラの平行移動を含んでいる疑い
     *       (こちらの平行移動と二重になる)</li>
     * </ul>
     *
     * <p>{@code modelView} の平行移動成分も出す。<b>0 でなければ</b>
     * 「カメラ相対の回転だけ」という前提が崩れている。
     */
    private void logViewportMath(org.joml.Matrix4fc modelView, float[] mvp,
                                 int[] anchor, float[] sub) {
        var fwd = me.cortex.voxy.client.core.vk.VkHostViewport.forward(
            modelView, new org.joml.Vector3f());
        // データセットの中心セクション -> アンカー原点のブロック座標
        float[] ndc = VkSceneUniform.project(mvp,
            TERRAIN_CENTRE_SECTIONS[0] << 5,
            TERRAIN_CENTRE_SECTIONS[1] << 5,
            TERRAIN_CENTRE_SECTIONS[2] << 5);
        Logger.info("[5c-1d]   viewport math:"
            + " modelViewTranslation=(" + modelView.m30() + "," + modelView.m31()
            + "," + modelView.m32() + ")"
            + "  [must be ~0; otherwise it already contains the camera translation]"
            + " forward=(" + fwd.x + "," + fwd.y + "," + fwd.z + ")"
            + " anchor=" + java.util.Arrays.toString(anchor)
            + " sub=(" + sub[0] + "," + sub[1] + "," + sub[2] + ")"
            + " centreNDC=(" + ndc[0] + "," + ndc[1] + "," + ndc[2] + ")"
            + "  [|xy|<=1 means on screen; z in (0,1) means inside the frustum]");
    }

    /**
     * 5c-1e — <b>手前の組と奥の組</b>に別々のアンカーを与えて位置を書く。
     *
     * <p>奥の組の方向は<b>手前のアンカーから逆算する</b> —
     * {@link me.cortex.voxy.client.core.vk.VkHostViewport#anchorInFront} が
     * 投影で確かめて選んだ向きをそのまま使うので、<b>決め打ちを増やさない</b> [規約 9]。
     *
     * <p>ユニフォームの {@code baseSectionPos} は<b>手前のアンカー</b>である。
     * 位置は絶対セクション座標で書くので、1 つの原点から両方を表せる。
     */
    private void writePairPositions(int[] nearAnchor, double camX, double camY, double camZ,
                                    org.joml.Matrix4fc projection, org.joml.Matrix4fc modelView) {
        // ⚠ アンカーから逆算しない — 量子化のせいで方向が数十度ずれる
        // [VkHostViewport.verifiedForward の注意]
        double[] dir = me.cortex.voxy.client.core.vk.VkHostViewport.verifiedForward(
            camX, camY, camZ, projection, modelView, TERRAIN_DISTANCE_BLOCKS,
            TERRAIN_CENTRE_SECTIONS, PAIR_QUANTUM);
        int[] farAnchor = me.cortex.voxy.client.core.vk.VkHostViewport.anchorAlongDirection(
            camX, camY, camZ, dir[0], dir[1], PAIR_FAR_BLOCKS, PAIR_LATERAL_BLOCKS,
            TERRAIN_CENTRE_SECTIONS, PAIR_QUANTUM);

        // 5c-3a: 3 つ目は **MC の far 平面の外**に置く。距離は描画距離から導く —
        // 決め打ちにすると描画距離を変えたときに対照が黙って成立しなくなる
        int[] beyondAnchor = null;
        if (copies() >= 3) {
            var mc01 = me.cortex.voxy.client.core.vk.VkHostViewport.projectionForVulkan(
                projection, modelView,
                me.cortex.voxy.client.core.vk.VkHostViewport.cameraSubPos(camX, camY, camZ, nearAnchor),
                TERRAIN_CENTRE_SECTIONS);
            double mcFar = me.cortex.voxy.client.core.vk.VkHostViewport.farPlaneDistance(mc01);
            this.lastMcFarPlane = mcFar;
            double beyond = Math.min(mcFar * BEYOND_FAR_PLANE_FACTOR,
                me.cortex.voxy.client.core.vk.VkHostViewport.VOXY_FAR * 0.5);
            beyondAnchor = me.cortex.voxy.client.core.vk.VkHostViewport.anchorAlongDirection(
                camX, camY, camZ, dir[0], dir[1], beyond, -PAIR_LATERAL_BLOCKS,
                TERRAIN_CENTRE_SECTIONS, PAIR_QUANTUM);
            this.lastBeyondDistance = beyond;
        }

        int n = this.terrain.sectionCount();
        int per = n / copies();
        int[][] anchors = new int[n][];
        for (int i = 0; i < n; i++) {
            int copy = Math.min(i / per, copies() - 1);
            anchors[i] = copy == 0 ? nearAnchor : (copy == 1 ? farAnchor : beyondAnchor);
        }
        this.terrain.writePositionsPerSection(this.res.positionScratch, anchors);

        this.lastBeyondAnchor = beyondAnchor;
        this.lastFarAnchor = farAnchor;
        this.farAnchorCam = new double[]{camX, camY, camZ};
        if (this.frames < 3 || this.frames % 600 == 0) {
            Logger.info("[5c-1e] far copy anchored at section "
                + java.util.Arrays.toString(farAnchor)
                + "  (near=" + (int) TERRAIN_DISTANCE_BLOCKS + "b far=" + (int) PAIR_FAR_BLOCKS
                + "b lateral=" + (int) PAIR_LATERAL_BLOCKS + "b)"
                + "  [the FAR copy is the one that must be hidden by Minecraft's terrain]");
            if (beyondAnchor != null) {
                // ⚠ **どれくらいの大きさに見えるはずか**を先に言う。
                // 「見えなかった」が「壊れている」なのか「小さすぎる」なのかを、
                // 実際に見る前に切り分けられるようにするため
                // (実際に 4092b 先で 12 画素になり、目視できなかった)。
                // ndc 幅 = m00 * (幅ブロック / 距離)、画面比はその半分
                double spanBlocks = TERRAIN_SPAN_BLOCKS;
                double px = projection.m00() * (spanBlocks / this.lastBeyondDistance)
                    * 0.5 * this.width;
                Logger.info("[5c-3a] BEYOND copy anchored at section "
                    + java.util.Arrays.toString(beyondAnchor)
                    + "  (" + (int) this.lastBeyondDistance + "b out; Minecraft's far plane is at "
                    + (int) this.lastMcFarPlane + "b)"
                    + "  [this copy can only appear with Voxy's own projection."
                    + " It should span about " + Math.round(px) + " px of "
                    + this.width + "; the synthetic terrain is sparse, so expect far fewer"
                    + " drawn pixels than that."
                    + (px < 200 ? " ⚠ TOO SMALL TO SEE — lower the render distance to bring"
                        + " Minecraft's far plane closer, then this copy comes closer too." : "")
                    + "]");
            }
        }
    }

    // ---------------- 5c-5a: temporal の実データ ----------------

    /** 短縮名。{@code VkHierarchicalScene.Visibility} を毎回書かないためだけのもの。 */
    private static me.cortex.voxy.client.core.vk.VkHierarchicalScene.Visibility vis(String name) {
        return me.cortex.voxy.client.core.vk.VkHierarchicalScene.Visibility.valueOf(name);
    }

    /**
     * このフレームの可視の書き方 (Phase 5c-5a)。
     *
     * <p>{@code cycle} は {@link #TEMPORAL_CYCLE} を {@link #TEMPORAL_CYCLE_FRAMES}
     * フレームずつ回す。<b>4 つ揃って初めて分割の恒等式が言える</b>ので、
     * 途中で止めると何も主張できない。
     */
    private me.cortex.voxy.client.core.vk.VkHierarchicalScene.Visibility visibilityForThisFrame() {
        return switch (TEMPORAL_MODE) {
            case "none" -> vis("NONE_VISIBLE");
            case "even" -> vis("EVEN_NEW");
            case "odd" -> vis("ODD_NEW");
            case "cull" -> vis("CULL");
            case "cycle" -> TEMPORAL_CYCLE[(int)
                ((this.frames / TEMPORAL_CYCLE_FRAMES) % TEMPORAL_CYCLE.length)];
            default -> vis("ALL_VISIBLE");
        };
    }

    /**
     * <b>4 つのモードの観測を突き合わせる</b> (Phase 5c-5a)。
     *
     * <h2>何を主張しているのか</h2>
     * <table>
     *   <tr><th>主張</th><th>これが落とす実装</th></tr>
     *   <tr><td>{@code ALL_VISIBLE} で temporal = <b>0</b></td>
     *       <td>「常に不透明と同じものを描く」</td></tr>
     *   <tr><td>{@code NONE_VISIBLE} で temporal = <b>不透明</b></td>
     *       <td>「temporal は常に空」</td></tr>
     *   <tr><td><b>{@code EVEN} + {@code ODD} = {@code NONE}</b></td>
     *       <td>上の 2 つを<b>同時に</b>落とす</td></tr>
     * </table>
     *
     * <p>⚠⚠ <b>分割の恒等式は quad 数の数え方に依存しない。</b>
     * 面ごとの quad 数の式をこちらで書き直すと
     * <b>予測と実装が同じ規約を共有する</b> [規約 4]。
     * 偶数 id と奇数 id は<b>互いに素で全体を覆う</b>ので、
     * 和が全体になることは<b>フィルタだけを主張している</b>。
     *
     * <p>⚠ <b>不透明側が動かないことも要求する。</b> 可視の書き方は bit 31 しか変えないので、
     * 不透明の quad 数がモードで変わったら<b>フィルタが不透明側に漏れている</b>。
     */
    private void sampleTemporal() {
        var mode = this.scene.visibility();
        int[] opaque = this.scene.mergedTableTotals();
        int[] temporal = this.scene.temporalTableTotals();
        int drawn = this.scene.drawnSectionCount();
        this.temporalSamples.put(mode, new int[]{drawn, opaque[1], temporal[1]});
        Logger.info("[5c-5a] " + mode + ": drawn=" + drawn
            + " opaqueQuads=" + opaque[1] + " temporalQuads=" + temporal[1]);

        if (this.temporalSamples.size() < TEMPORAL_CYCLE.length) return;

        var all = this.temporalSamples.get(vis("ALL_VISIBLE"));
        var none = this.temporalSamples.get(vis("NONE_VISIBLE"));
        var even = this.temporalSamples.get(vis("EVEN_NEW"));
        var odd = this.temporalSamples.get(vis("ODD_NEW"));

        // ⚠ 選択が動いていたら比べない。**数字が違う理由が 2 通りある状態で
        // 結論を出さない** [規約 22]
        if (all[0] != none[0] || all[0] != even[0] || all[0] != odd[0]) {
            Logger.info("[5c-5a] the drawn set moved between modes ("
                + all[0] + "/" + none[0] + "/" + even[0] + "/" + odd[0]
                + ") — stand still and let it settle before reading the identity");
            return;
        }
        // ⚠ drawn=0 なら全部 0 で恒等式が**空虚に成立**する [規約 18]
        if (all[1] <= 0) {
            Logger.warn("[5c-5a] ⚠ the opaque table is empty, so every claim below holds"
                + " vacuously — nothing is being asserted");
            return;
        }

        boolean opaqueStable = all[1] == none[1] && all[1] == even[1] && all[1] == odd[1];
        boolean emptyWhenAllVisible = all[2] == 0;
        boolean fullWhenNoneVisible = none[2] == all[1];
        boolean partitions = (long) even[2] + odd[2] == none[2];

        Logger.info("[5c-5a] ---- temporal on real data ----");
        Logger.info("[5c-5a]   opaque unchanged across modes: " + verdict(opaqueStable)
            + "  (" + all[1] + "/" + none[1] + "/" + even[1] + "/" + odd[1] + ")");
        Logger.info("[5c-5a]   ALL_VISIBLE  -> temporal empty:      " + verdict(emptyWhenAllVisible)
            + "  (" + all[2] + ")");
        Logger.info("[5c-5a]   NONE_VISIBLE -> temporal == opaque:  " + verdict(fullWhenNoneVisible)
            + "  (" + none[2] + " of " + all[1] + ")");
        Logger.info("[5c-5a]   EVEN + ODD   == NONE:                " + verdict(partitions)
            + "  (" + even[2] + " + " + odd[2] + " = " + ((long) even[2] + odd[2])
            + " vs " + none[2] + ")");
        // ⚠ **真部分集合であること**も言う。even が 0 でも全体でも
        // 恒等式は成立しうる (0 + 全体 = 全体)
        boolean strict = even[2] > 0 && odd[2] > 0 && even[2] < none[2] && odd[2] < none[2];
        Logger.info("[5c-5a]   EVEN and ODD are both strict subsets: " + verdict(strict)
            + (strict ? "" : "  ⚠ one side is empty or everything — the identity above"
                + " would hold even if the filter did nothing"));
        this.temporalSamples.clear();
    }

    private static String verdict(boolean ok) { return ok ? "PASS" : "⚠ FAIL"; }

    /**
     * 実データのトラバーサルの内訳を出す (Phase 5c-4c)。
     *
     * <h2>⚠ この段の数字は「遅くて正常」である</h2>
     * まだ最適化していない — 保守的なバリア、密テーブル、旧経路が残っている。
     * <b>合計ではなく内訳</b>を出すのは、Phase 6 で<b>何を疑うか</b>を決めるためである。
     */
    private void logHierarchical() {
        var tr = this.scene.traversal();
        Logger.info("[5c-4c] drawn=" + this.scene.drawnSectionCount()
            + " of " + this.scene.meshedSections() + " meshed"
            + " (top-level nodes " + this.scene.topLevelCount() + ")"
            + "  requests=" + tr.requestCount()
            + "  dropped: pushes=" + tr.droppedNodePushes()
            + " reads=" + tr.droppedNodeReads()
            + (tr.droppedNodePushes() + tr.droppedNodeReads() > 0
                ? "  ⚠ THE QUEUE OVERFLOWED — raise -Pvoxy5c4Sections" : "")
            // ⚠ drawn=0 の意味を 1 通りにする [規約 18]。木は populate した位置に
            // 固定されているので、離れれば何も選ばれないのが**正常**である
            + (this.scene.drawnSectionCount() == 0
                ? "  [drawn=0: the tree is anchored where it was populated and does NOT"
                  + " follow the camera — walk back, or restart to re-populate here]" : ""));
        // ⚠ 描画キューの中身が正しい id かどうか。
        //
        // ⚠⚠ **「drawn > meshed だからおかしい」は誤りだった。**
        // meshedSections はこちらが数えている値であって、ジオメトリ管理器が持つ
        // セクション数ではない。**権威のないカウンタを不変条件の根拠にしない**
        int[] invalid = this.scene.countInvalidRenderIds();
        if (invalid[0] > 0) {
            Logger.error("[5c-4c] ⚠ " + invalid[0] + " of " + invalid[1]
                + " render-queue entries are not valid section ids");
        }
        // ⚠ 画素が 0 のとき、原因が**テーブルの側**か**描画の側**かを分ける
        int[] table = this.scene.mergedTableTotals();
        if (table[1] > 0) {
            // ⚠ テーブルに quad があるのに画素が 0 — **コマンドが空か、実行して出ないか**
            Logger.info("[5c-4c] draws: " + this.scene.describeDraws());
        }
        if (table[1] == 0 && this.scene.drawnSectionCount() > 0) {
            // ⚠ 空の原因は「可視の印」か「メタデータ」しかない。**読んで**決める
            Logger.info("[5c-4c] first entry: " + this.scene.describeFirstEntry());
        }
        Logger.info("[5c-4c] merged table: entries=" + table[0] + " totalQuads=" + table[1]
            + " (sectionCount passed to prefix=" + this.scene.lastDrawnSections() + ")"
            + (table[1] == 0 ? "  ⚠ THE TABLE IS EMPTY — the problem is at or before cmdgen"
               : table[1] < 0 ? "  ⚠ entry count is out of range" : ""));
        // 5c-5a: temporal は不透明と**エントリ配列を共有**するので、
        // 見るのは総 quad 数だけである。エントリ数の一致は何も主張しない
        int[] temporal = this.scene.temporalTableTotals();
        Logger.info("[5c-5a] visibility=" + this.scene.visibility()
            + "  temporal table: totalQuads=" + temporal[1] + " of " + table[1] + " opaque"
            + (this.scene.visibility()
                    == me.cortex.voxy.client.core.vk.VkHierarchicalScene.Visibility.ALL_VISIBLE
                ? "  [ALL_VISIBLE: 0 is expected. temporal cannot be exercised in this mode —"
                  + " use -Pvoxy5c5=cycle]" : ""));
        Logger.info("[5c-4c] GPU " + this.scene.timer().describe());
    }

    /**
     * 合成地形が<b>ワールドのどこを占めるか</b>。
     *
     * <p>「絵が出ない」の切り分けで最初に要る情報である —
     * <b>プレイヤーがそこを見ていなければ、経路が正しくても何も出ない。</b>
     * 座標を出しておけば {@code /tp} で確かめられる。
     */
    private String worldBoundsOf(int[] anchor) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var sec : this.terrain.sections()) {
            int size = 32 << sec.level;
            int bx = ((sec.x << sec.level) + anchor[0]) << 5;
            int by = ((sec.y << sec.level) + anchor[1]) << 5;
            int bz = ((sec.z << sec.level) + anchor[2]) << 5;
            minX = Math.min(minX, bx); maxX = Math.max(maxX, bx + size);
            minY = Math.min(minY, by); maxY = Math.max(maxY, by + size);
            minZ = Math.min(minZ, bz); maxZ = Math.max(maxZ, bz + size);
        }
        return "world blocks x=[" + minX + "," + maxX + ") y=[" + minY + "," + maxY
            + ") z=[" + minZ + "," + maxZ + ")";
    }

    // ---------------- 診断 ----------------

    /**
     * <b>1 行で経路上の複数の事実を出す。</b>
     *
     * <p>5c-1b では「FBO・ビューポート・ブレンド/シザー・IOSurface の中身」を
     * 1 行にまとめたことで、<b>4 往復するはずの切り分けが 1 回で済んだ</b>
     * [docs/phase5c1b-completion.md §8.5]。5c-1c では経路が 1 段長いので、
     * <b>深度が interop に届いているか</b>を最初から出す。
     */
    private void logDiagnostics(int mcColourTexture, int mcDepthTexture, int w, int h) {
        int drawFbo = org.lwjgl.opengl.GL11C.glGetInteger(
            org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] vp = new int[4];
        org.lwjgl.opengl.GL11C.glGetIntegerv(org.lwjgl.opengl.GL11C.GL_VIEWPORT, vp);
        Logger.info("[5c-1d] frame " + this.frames
            + " mode=" + MODE
            + " mcColourTex=" + mcColourTexture + " mcDepthTex=" + mcDepthTexture
            + " target=" + w + "x" + h
            + " drawFbo=" + drawFbo
            + " viewport=[" + vp[0] + "," + vp[1] + "," + vp[2] + "," + vp[3] + "]"
            + " blend=" + org.lwjgl.opengl.GL11C.glIsEnabled(org.lwjgl.opengl.GL11C.GL_BLEND)
            + " scissor=" + org.lwjgl.opengl.GL11C.glIsEnabled(org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST)
            + " depthTest=" + org.lwjgl.opengl.GL11C.glIsEnabled(org.lwjgl.opengl.GL11C.GL_DEPTH_TEST));
        if (MODE == Mode.DEPTH || drawsTerrain()) {
            Logger.info("[5c-1d]   depth in interop: " + this.readProbe(this.depth)
                + "   [reverse-Z: 1.0 = nearest, 0.0 = FAR = "
                + (MODE == Mode.TERRAIN ? "no Voxy geometry (discarded by the composite)" : "sky")
                + "]");
        }
        if (drawsTerrain()) {
            String where = "";
            if (MODE == Mode.TRIPLE && this.beyondOnScreen != null) {
                // ⚠ drawn=0 の意味を確定させる。「向いていない」と「描けていない」は別物
                where = "   [the '" + copyName(ONLY_COPY) + "' copy; the BEYOND copy's centre is "
                    + (this.beyondOnScreen ? "ON screen — drawn=0 here means it was NOT drawn"
                        : "OFF screen — drawn=0 here says nothing, turn to face it")
                    + "]";
            }
            Logger.info("[5c-1d]   voxy footprint: " + this.screenFootprint() + where);
        }
        Logger.info("[5c-1d]   colour in interop: " + this.readProbe(this.colour)
            + "   [BGRA bytes; sky is magenta = B and R high, G low]");
    }

    /**
     * interop 画像の 3 箇所を読み戻す — <b>絵の上端寄り / 中央 / 下端寄り</b>。
     *
     * <p><b>最も強い切り分け</b>である。経路の前半 (ここまで) と後半 (GL 合成) の
     * どちらが悪いかで、見る場所がまったく変わる
     * [docs/phase5c1b-completion.md §8.1 — この 1 行が調査の 9 割を決めた]。
     *
     * <p>上端と下端を両方読むのは、<b>値が一様でないこと</b>も同時に見るためである。
     * 一様な絵は上下反転に無反応で、何も主張できない。
     */
    private String readProbe(VkInteropImage image) {
        boolean isDepth = image.kind == VkInteropImage.Kind.DEPTH_R32F;
        int stride = PROBE * PROBE * 4;
        if (this.probeReadback == null) {
            this.probeReadback = new VkBuffer((long) stride * PROBE_SPOTS,
                VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        }
        var tracker = VkFrameTracker.get();
        var cmd = tracker.beginFrame();
        // GENERAL 据え置きで転送読みに可視化する。
        // ⚠ src のアクセスは **直前に誰がどう書いたか** で決まる。画像の種類ではない —
        // 深度 interop は 5c-1c では GL が書き (Vulkan からは書き込みが見えない)、
        // 5c-1d では Vulkan が **カラーアタッチメントとして** 書く
        int srcAccess = (isDepth && MODE == Mode.DEPTH)
            ? 0                                          // 書いたのは GL。CPU 同期が担保する
            : VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;      // 書いたのは Vulkan
        image.toGeneral(cmd,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, srcAccess,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        try (MemoryStack stack = stackPush()) {
            var regions = VkBufferImageCopy.calloc(PROBE_SPOTS, stack);
            int cx = (this.width - PROBE) / 2;
            // GL 規約: 行 0 は絵の下端。したがって「絵の上端寄り」は行が大きい側
            int[][] at = {
                {cx, this.height - PROBE},              // 絵の上端寄り (空が来るはず)
                {cx, (this.height - PROBE) / 2},        // 中央
                {cx, 0},                                // 絵の下端寄り (近い地形が来るはず)
            };
            for (int i = 0; i < PROBE_SPOTS; i++) {
                var r = regions.get(i);
                r.bufferOffset((long) i * stride).bufferRowLength(0).bufferImageHeight(0);
                r.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
                r.imageOffset().set(at[i][0], at[i][1], 0);
                r.imageExtent().set(PROBE, PROBE, 1);
            }
            vkCmdCopyImageToBuffer(cmd, image.imageHandle(), VK_IMAGE_LAYOUT_GENERAL,
                this.probeReadback.handle, regions);
        }
        tracker.endFrame();
        tracker.waitForFrame();

        long a = this.probeReadback.addr();
        String[] label = {"top-of-picture", "middle", "bottom-of-picture"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PROBE_SPOTS; i++) {
            if (i > 0) sb.append(' ');
            sb.append(label[i]).append('=');
            sb.append(isDepth
                ? Float.toString(MemoryUtil.memGetFloat(a + (long) i * stride))
                : texelString(a + (long) i * stride));
        }
        return sb.toString();
    }

    /**
     * <b>Voxy の地形が画面のどこに、どれだけ落ちたか。</b>
     *
     * <p>「絵が出ない」ときに<b>最初に知りたいのはこれ</b>である —
     * 描けていないのか、画面の外なのか、深度で落ちたのか。
     * interop の深度を全面を読み戻し、<b>クリア値でないテクセル</b>を数える。
     *
     * <ul>
     *   <li><b>drawn=0</b> → 地形が画面に落ちていない (置き場所・投影・カメラの向き)</li>
     *   <li><b>drawn&gt;0 なのに画面で見えない</b> → 合成の深度テストで落ちている</li>
     * </ul>
     *
     * <p>bbox は framebuffer 座標 (GL 規約なので行 0 は<b>絵の下端</b>)。
     */
    private String screenFootprint() {
        int texels = this.width * this.height;
        long need = (long) texels * 4;
        // ⚠ **画面サイズに依存する資源である。** リサイズで大きくなったときに
        // 作り直さないと、小さいバッファを大きい範囲で読んで<b>範囲外アクセスで落ちる</b>。
        // 実際に 1708x960 -> 4112x2580 のリサイズで JVM ごと落とした
        // [docs/phase5c1e-completion.md の失敗例 18]。
        // 解放漏れに備えて<b>ここでも大きさを確かめる</b> — 二重の防御
        if (this.footprintReadback != null && this.footprintReadback.size() < need) {
            this.footprintReadback.free();
            this.footprintReadback = null;
        }
        if (this.footprintReadback == null) {
            this.footprintReadback = new VkBuffer(need, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        }
        var tracker = VkFrameTracker.get();
        var cmd = tracker.beginFrame();
        this.depth.toGeneral(cmd,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        try (MemoryStack stack = stackPush()) {
            var region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(this.width, this.height, 1);
            vkCmdCopyImageToBuffer(cmd, this.depth.imageHandle(), VK_IMAGE_LAYOUT_GENERAL,
                this.footprintReadback.handle, region);
        }
        tracker.endFrame();
        tracker.waitForFrame();

        long base = this.footprintReadback.addr();
        long drawn = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        float nearest = me.cortex.voxy.client.core.vk.VkDepth.FAR;
        for (int y = 0; y < this.height; y++) {
            for (int x = 0; x < this.width; x++) {
                float d = MemoryUtil.memGetFloat(base + ((long) y * this.width + x) * 4);
                if (d == me.cortex.voxy.client.core.vk.VkDepth.CLEAR) continue;
                drawn++;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
                if (d > nearest) nearest = d;   // 逆Z: 大きいほど手前
            }
        }
        if (drawn == 0) {
            return "drawn=0 of " + texels + "  <-- the terrain is NOT on screen"
                + " (placement / projection / where the camera looks)";
        }
        return "drawn=" + drawn + " of " + texels
            + " bbox=[" + minX + "," + minY + " .. " + maxX + "," + maxY + "]"
            + " nearestDepth=" + nearest
            + "  [framebuffer rows; row 0 is the BOTTOM of the picture]";
    }

    private static String texelString(long addr) {
        return "[" + (MemoryUtil.memGetByte(addr) & 0xFF)
            + "," + (MemoryUtil.memGetByte(addr + 1) & 0xFF)
            + "," + (MemoryUtil.memGetByte(addr + 2) & 0xFF)
            + "," + (MemoryUtil.memGetByte(addr + 3) & 0xFF) + "]";
    }

    // ---------------- パターン (5c-1b から引き継ぎ) ----------------

    /** 赤い帯が占める framebuffer の行 [開始, 終了)。{@link Mode#PATTERN} のみ。 */
    public static int bandStartRow(int h) { return h * 3 / 4; }
    /** 緑の四角が占める framebuffer の行 [開始, 終了) と列 [0, {@link #cornerEndCol})。 */
    public static int cornerStartRow(int h) { return h * 7 / 8; }
    public static int cornerEndCol(int w) { return w / 8; }

    public static int bandColourRgba() { return rgba(BAND); }
    public static int backgroundColourRgba() { return rgba(BACKGROUND); }
    public static int cornerColourRgba() { return rgba(CORNER); }

    /** RGBA8 のリトルエンディアン int ({@code glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE)} と同じ並び)。 */
    private static int rgba(float[] c) {
        return (Math.round(c[0] * 255))
             | (Math.round(c[1] * 255) << 8)
             | (Math.round(c[2] * 255) << 16)
             | (Math.round(c[3] * 255) << 24);
    }

    /**
     * 5c-1b の非対称パターンを {@code vkCmdClearAttachments} だけで置く (シェーダ不要)。
     *
     * <p><b>オフスクリーンの自動検査からも呼ぶ</b> —
     * MC 上で見るものと同じ模様でなければ、目視と機械的検査が別物を見ることになる。
     */
    public static void recordTestPattern(VkCommandBuffer cmd, VkInteropImage colour,
                                         int width, int height) {
        colour.texture().barrier(cmd, 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

        try (MemoryStack stack = stackPush()) {
            var att = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                .imageView(colour.texture().view(0))
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            att.get(0).clearValue().color()
                .float32(0, BACKGROUND[0]).float32(1, BACKGROUND[1])
                .float32(2, BACKGROUND[2]).float32(3, BACKGROUND[3]);

            var ri = VkRenderingInfo.calloc(stack).sType$Default()
                .layerCount(1).pColorAttachments(att);
            ri.renderArea().offset().set(0, 0);
            ri.renderArea().extent().set(width, height);
            vkCmdBeginRendering(cmd, ri);

            var vp = VkViewport.calloc(1, stack)
                .x(0).y(0).width(width).height(height).minDepth(0).maxDepth(1);
            var sc = VkRect2D.calloc(1, stack);
            sc.offset().set(0, 0);
            sc.extent().set(width, height);
            vkCmdSetViewport(cmd, 0, vp);
            vkCmdSetScissor(cmd, 0, sc);

            // GL 規約: 0 行目が絵の下端なので、画面の「上」は y が大きい側
            clearRect(cmd, stack, BAND, 0, bandStartRow(height), width, height - bandStartRow(height));
            recordCornerMarker(cmd, width, height);

            vkCmdEndRendering(cmd);
        }

        // GL に渡す
        colour.toGeneral(cmd,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
    }

    /**
     * 緑の四角だけを置く。<b>レンダリングが開いている間に呼ぶこと。</b>
     *
     * <p>5c-1c ではこれが<b>色経路の向きの唯一の目印</b>になる。
     * 深度可視化の上に重なるので、<b>四角が右下に出たら色経路の向きが狂った</b>と
     * 即座に分かる。
     */
    public static void recordCornerMarker(VkCommandBuffer cmd, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            clearRect(cmd, stack, CORNER, 0, cornerStartRow(height),
                cornerEndCol(width), height - cornerStartRow(height));
        }
    }

    private static void clearRect(VkCommandBuffer cmd, MemoryStack stack, float[] rgba,
                                  int x, int y, int w, int h) {
        var clear = VkClearAttachment.calloc(1, stack)
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(0);
        clear.get(0).clearValue().color()
            .float32(0, rgba[0]).float32(1, rgba[1]).float32(2, rgba[2]).float32(3, rgba[3]);

        var rect = VkClearRect.calloc(1, stack).baseArrayLayer(0).layerCount(1);
        rect.get(0).rect().offset().set(x, y);
        rect.get(0).rect().extent().set(w, h);

        vkCmdClearAttachments(cmd, clear, rect);
    }

    // ---------------- 解放 ----------------

    /**
     * <b>画面サイズに依存する資源を全て解放する。</b>
     *
     * <p>⚠ <b>サイズに依存するものは 1 つ残らずここに入れること。</b>
     * 入れ忘れると<b>リサイズしたときにだけ</b>壊れるので、
     * 起動直後の確認では気づかない [失敗例 18 — 診断用の読み戻しバッファを入れ忘れ、
     * リサイズで JVM ごと落とした]。
     */
    private void freeSizeDependent() {
        if (this.footprintReadback != null) {
            this.footprintReadback.free();
            this.footprintReadback = null;
        }
        if (this.resolve != null) { this.resolve.free(); this.resolve = null; }
        if (this.renderer != null) { this.renderer.free(); this.renderer = null; }
        if (this.rt != null) { this.rt.free(); this.rt = null; }
        if (this.visualise != null) { this.visualise.free(); this.visualise = null; }
        if (this.colour != null) { this.colour.free(); this.colour = null; }
        if (this.depth != null) { this.depth.free(); this.depth = null; }
    }

    private void free() {
        this.freeSizeDependent();
        if (this.res != null) { this.res.free(); this.res = null; }
        if (this.scratchFbo != null) { this.scratchFbo.free(); this.scratchFbo = null; }
        if (this.scene != null) { this.scene.free(); this.scene = null; }
        if (this.mesher != null) { this.mesher.free(); this.mesher = null; }
        if (this.meshedSections != null) {
            for (var b : this.meshedSections) b.free();
            this.meshedSections = null;
        }
        if (this.bakery != null) { this.bakery.free(); this.bakery = null; }
        if (this.modelTarget != null) { this.modelTarget.free(); this.modelTarget = null; }
        if (this.depthImport != null) { this.depthImport.free(); this.depthImport = null; }
        if (this.probeReadback != null) { this.probeReadback.free(); this.probeReadback = null; }
        if (this.footprintReadback != null) { this.footprintReadback.free(); this.footprintReadback = null; }
        if (this.compositor != null) { this.compositor.free(); this.compositor = null; }
    }
}
