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
        TRIPLE
    }

    private static final Mode MODE = resolveMode();

    /**
     * {@code voxy.5c1c} を見る。無ければ<b>5c-1b のスイッチを解釈する</b> —
     * 5c-1b の {@code full} は<b>いまの {@link Mode#PATTERN}</b> に当たるので、
     * 当時の手動確認をそのまま再現できる。
     */
    private static Mode resolveMode() {
        // 5c-3a: 配置は 3 組で固定し、**投影だけ**を切り替える (VOXY_PROJECTION)
        if (System.getProperty("voxy.5c3") != null) return Mode.TRIPLE;
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
            case "full" -> fullMeans;
            default -> throw new IllegalArgumentException("unknown " + property + "=" + value);
        };
    }

    /** 地形を描くモードか。 */
    private static boolean drawsTerrain() {
        return MODE == Mode.TERRAIN || MODE == Mode.TERRAIN_NODEPTH
            || MODE == Mode.TERRAIN_REFMVP || MODE == Mode.PAIR || MODE == Mode.TRIPLE;
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
        String v = System.getProperty("voxy.5c3");
        if (v == null) return false;
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
    private double lastBeyondDistance;
    private double lastMcFarPlane;

    /**
     * 3 つ目の組を MC の far 平面の<b>何倍の距離</b>に置くか。
     *
     * <p>⚠ 1.0 に近いと「境界にいるので見えたり見えなかったりする」になり、
     * <b>対照が揺れる</b>。はっきり外に置く。
     */
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
        if (drawsTerrain()) {
            this.writeSceneUniform(projection, modelView, cameraX, cameraY, cameraZ);
        }
        var tracker = VkFrameTracker.get();
        var cmd = tracker.beginFrame();
        switch (MODE) {
            case TERRAIN, TERRAIN_NODEPTH, TERRAIN_REFMVP, PAIR, TRIPLE -> {
                // 焼いたテクスチャを先に流す。地形描画の recordUploads より前でなければ
                // ミップの遷移とレイアウトが噛み合わない
                if (this.bakery != null) this.bakery.recordUploads(cmd);
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
        if (this.frames < 3) {
            this.logDiagnostics(mcColourTexture, mcDepthTexture, w, h);
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
                case TERRAIN, PAIR, TRIPLE -> GlInteropCompositor.forHost();
                // 診断: 深度を見ずに全面を貼る。橙一色なら地形が画面に無い
                case TERRAIN_NODEPTH, TERRAIN_REFMVP ->
                    new GlInteropCompositor(GlInteropCompositor.DepthMode.NONE);
                // まだ Voxy 自身の深度が無い段。書くと MC の深度を潰す
                default -> new GlInteropCompositor(GlInteropCompositor.DepthMode.NONE);
            };
        }
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
        SyntheticTerrain.writeDrawCommands(this.res.drawCall, draws, this.res.indexQuadCapacity);
        this.drawCount = draws.size();
        Logger.info("[5c-1d] synthetic terrain: " + this.terrain.sectionCount() + " sections, "
            + this.terrain.totalQuads() + " quads, " + this.drawCount + " draws"
            + " (same dataset as interopCompositeCheck)");
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
                Logger.info("[5c-3a] BEYOND copy anchored at section "
                    + java.util.Arrays.toString(beyondAnchor)
                    + "  (" + (int) this.lastBeyondDistance + "b out; Minecraft's far plane is at "
                    + (int) this.lastMcFarPlane + "b)"
                    + "  [this copy can only appear with Voxy's own projection]");
            }
        }
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
            Logger.info("[5c-1d]   voxy footprint: " + this.screenFootprint());
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
        if (this.bakery != null) { this.bakery.free(); this.bakery = null; }
        if (this.modelTarget != null) { this.modelTarget.free(); this.modelTarget = null; }
        if (this.depthImport != null) { this.depthImport.free(); this.depthImport = null; }
        if (this.probeReadback != null) { this.probeReadback.free(); this.probeReadback = null; }
        if (this.footprintReadback != null) { this.footprintReadback.free(); this.footprintReadback = null; }
        if (this.compositor != null) { this.compositor.free(); this.compositor = null; }
    }
}
