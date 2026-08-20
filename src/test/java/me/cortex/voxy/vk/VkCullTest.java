package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.SyntheticTerrain.Face;
import me.cortex.voxy.client.core.vk.SyntheticTerrain.Section;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer.Barriers;
import org.junit.jupiter.api.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 4b: <b>遮蔽カリング。</b>
 *
 * <h2>判定基準が他のステージと違う</h2>
 * cull の正しさは「同じ絵が出ること」ではない。可視セクションの集合が変わるのが目的だから、
 * Stage 2b との差分ゼロは成功条件にできない。<b>2 方向から見る</b>:
 *
 * <table>
 *   <tr><th>向き</th><th>何を防ぐか</th><th>検査</th></tr>
 *   <tr><td>見えているものを落としていない</td><td>過剰カリング</td>
 *       <td>cull あり/なしで<b>絵が変わらない</b></td></tr>
 *   <tr><td>見えないものは落ちている</td><td>カリングが効いていない</td>
 *       <td>遮蔽したセクションが実際に不可視になる</td></tr>
 * </table>
 *
 * 片方だけでは成立しない。前者だけなら「何もカリングしない」実装が通り、
 * 後者だけなら「全部落とす」実装が通る。
 *
 * <h2>遮蔽関係は合成データで意図的に作る</h2>
 * 32x32 の壁 (SOUTH 面 1024 枚) を手前に置き、その真後ろに小さな AABB のセクションを置く。
 * 壁が完全に覆うので、後ろのセクションは<b>絵に一切寄与しない</b>。
 */
public class VkCullTest {
    private static final int W = 384, H = 384;
    private static final float[] CLEAR = {0.05f, 0.05f, 0.10f, 1.0f};
    private static final Path OUT = Path.of("build", "vk-test-output");
    private static final int FRAME_ID = 5;

    /** 壁のセクション添字 / 隠れるセクションの添字。 */
    private static final int WALL = 0, HIDDEN = 1;

    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
        VkFrameTracker.init();
    }

    @AfterAll
    static void teardown() {
        if (VkFrameTracker.isRecordingFrame()) VkFrameTracker.get().endFrame();
        VkFrameTracker.get().waitIdle();
        VkSampler.shutdown();
        VkQuadIndexBuffer.shutdown();
        VkCullPass.shutdown();
        VkFrameTracker.shutdown();
    }

    // ---------------- scenes ----------------

    /**
     * 壁 + その真後ろに隠れるセクション。
     *
     * <p>壁は (0,0,0) の SOUTH 1024 枚 = px 0..31, py 0..31 の格子で z=1 の面。
     * 隠れる側は (0,0,-1) にあり、AABB を 10..18 に絞ってある —
     * <b>cull は AABB の箱を描くので、箱が壁からはみ出すと可視と判定される</b>
     * (箱はさらに ±1 拡張される)。
     */
    private static SyntheticTerrain occludedScene() {
        return new SyntheticTerrain()
            .add(new Section(0, 0, 0, 0).face(Face.SOUTH, 1024))
            .add(new Section(0, 0, -1, 0).face(Face.SOUTH, 16).aabb(10, 8));
    }

    /**
     * 同じ 2 セクションだが、隠れる側を横にずらして遮蔽を外したもの (対照)。
     *
     * <p>ずらし幅は<b>画面内に残る範囲</b>でなければならない。
     * 遠くへ飛ばすと視錐台の外に出て、遮蔽ではなく<b>フレームアウトで不可視</b>になり
     * 対照として機能しない (実際に一度そうなった)。
     *
     * <p>この深度 (z≈-18、距離 63) で壁が覆う範囲はワールド x で約 -7..39。
     * セクション (1,0,-1) の AABB は x 41..51 に来るので、
     * 壁の影から外れつつ画面内 (NDC 0.69..0.96) に収まる。
     */
    private static SyntheticTerrain unoccludedScene() {
        return new SyntheticTerrain()
            .add(new Section(0, 0, 0, 0).face(Face.SOUTH, 1024))
            .add(new Section(1, 0, -1, 0).face(Face.SOUTH, 16).aabb(10, 8));
    }

    /** 壁を正面から見る。壁は世界座標で x 0..32, y 0..32, z=1。 */
    private static float[] mvp() {
        return VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(60), (float) W / H, 0.5f, 500f),
            VkSceneUniform.lookAt(new float[]{16, 16, 45}, new float[]{16, 16, 0},
                new float[]{0, 1, 0}));
    }

    private record Run(VkRenderTarget target, int[] visibility, int quadsInTable) {}

    /**
     * 3 フレーム走らせて、最後のフレームの絵と可視状態を返す。
     *
     * <pre>
     * frame 0 (F0)  全可視でテーブル生成 -> 描画 (深度が埋まる)
     * frame 1 (F1)  描画 -> cull (深度テスト) -> テーブル生成 (カリング反映)
     * frame 2 (F2)  描画 (カリング済みテーブル) -> 読み戻し
     * </pre>
     *
     * <h2>⚠ frameId は毎フレーム進めなければならない</h2>
     * cull は<b>通ったセクションに書き込むだけで、落としたセクションを消さない</b>
     * [確認済 — cull/raster.frag]。落ちたことは「値が今の frameId と一致しない」で表す。
     * frameId を固定にすると<b>前のフレームで可視だった記録がそのまま残り、
     * 何もカリングされていないように見える</b> (実際にそう見えて 1 度はまった)。
     *
     * @param withCull false なら cull を走らせない。毎フレーム全可視に戻す
     */
    private static Run run(SyntheticTerrain terrain, boolean withCull, Barriers barriers) {
        var res = new VkTerrainResources(terrain.sectionCount(), terrain.totalQuads(), 4096,
            terrain.maxStateId() + 1);
        var rt = new VkRenderTarget(W, H);
        VkTerrainRenderer renderer = null;
        VkMergedTableBuilder builder = null;
        VkCullPass cull = null;
        try {
            int[] starts = terrain.writeGeometry(res.geometry);
            terrain.writeMetadata(res.sectionMetadata, starts);
            res.fillModels(VkTerrainResources.MODEL_FLAG_SHADED);
            terrain.writeIndirectLookup(res.indirectLookup);

            renderer = new VkTerrainRenderer(res, W, H, barriers, VkTerrainRenderer.Mode.MERGED);
            builder = new VkMergedTableBuilder(res, barriers);
            if (withCull) cull = new VkCullPass(res, barriers);

            int maxDraws = SyntheticTerrain.maxFaceDrawCount(terrain.totalQuads(),
                res.indexQuadCapacity);
            var t = VkFrameTracker.get();

            for (int frame = 0; frame < 3; frame++) {
                int frameId = FRAME_ID + frame;
                VkSceneUniform.write(res.uniform, mvp(), SyntheticTerrain.ORIGIN, frameId,
                    new float[]{0, 0, 0});
                // frame 0 は種として全可視にする。cull 無しの経路は毎フレーム戻す
                if (frame == 0 || !withCull) {
                    terrain.writeVisibility(res.visibility, frameId, null);
                }

                var cmd = t.beginFrame();
                if (frame == 0) {
                    // 深度を埋めるために、まず全可視のテーブルを作ってから描く
                    builder.record(cmd, terrain.sectionCount(), maxDraws);
                    VkBarriers.conservative(cmd, "test: seed table -> prime depth");
                    renderer.record(cmd, rt, maxDraws, CLEAR);
                } else {
                    renderer.record(cmd, rt, maxDraws, CLEAR);
                    if (cull != null) cull.record(cmd, rt, terrain.sectionCount());
                    builder.record(cmd, terrain.sectionCount(), maxDraws);
                }
                if (frame == 2) rt.recordReadback(cmd);
                t.endFrame();
                t.waitForFrame();
            }

            int[] vis = new int[terrain.sectionCount()];
            for (int i = 0; i < vis.length; i++) {
                vis[i] = MemoryUtil.memGetInt(res.visibility.addr() + (long) i * 4);
            }
            int n = 7 * terrain.sectionCount();
            int quads = MemoryUtil.memGetInt(res.mergedPrefix.addr() + 4L + (long) n * 4);
            return new Run(rt, vis, quads);
        } finally {
            if (cull != null) cull.free();
            if (builder != null) builder.free();
            if (renderer != null) renderer.free();
            res.free();
        }
    }

    /** 最後にカリングが走ったフレーム (frame 2) の frameId と一致するか。 */
    private static boolean visible(int[] vis, int section) {
        return (vis[section] & 0x7fffffff) == FRAME_ID + 2;
    }

    // ---------------- 向き 1: 見えているものを落としていない ----------------

    /**
     * <b>cull あり/なしで絵が変わらないこと。</b>
     * 落ちたのは遮蔽されていたセクションだけなので、絵に寄与しない。
     * 1 ピクセルでも変われば<b>見えているものを落とした</b>ことになる。
     */
    @Test
    void cullingDoesNotChangeWhatIsVisible() throws Exception {
        var without = run(occludedScene(), false, Barriers.CONSERVATIVE);
        var with = run(occludedScene(), true, Barriers.CONSERVATIVE);
        try {
            long diff = VkRenderTarget.compareColor(without.target(), with.target());
            if (diff != 0) {
                VkRenderTarget.writeDiffPng(without.target(), with.target(),
                    OUT.resolve("cull-diff.png"));
                with.target().writePng(OUT.resolve("cull-with.png"), true);
                without.target().writePng(OUT.resolve("cull-without.png"), true);
            }
            assertEquals(0, diff, "culling removed " + diff + " visible pixels; "
                + "see build/vk-test-output/cull-diff.png");
            with.target().writePng(OUT.resolve("cull-scene.png"), true);
        } finally {
            without.target().free();
            with.target().free();
        }
    }

    /** その比較が意味を持つよう、そもそも絵が描かれていること。 */
    @Test
    void theOccluderIsActuallyDrawn() {
        var r = run(occludedScene(), true, Barriers.CONSERVATIVE);
        try {
            long covered = coverage(r.target());
            System.out.println("[vk] cull scene: " + covered + " px covered");
            assertTrue(covered > 20000,
                "the 32x32 wall should fill much of the frame, got " + covered + " px");
        } finally {
            r.target().free();
        }
    }

    // ---------------- 向き 2: 見えないものは落ちている ----------------

    /** <b>遮蔽されたセクションが不可視と記録されること。</b> */
    @Test
    void theOccludedSectionIsCulled() {
        var r = run(occludedScene(), true, Barriers.CONSERVATIVE);
        try {
            assertTrue(visible(r.visibility(), WALL),
                "the wall is in front of everything and must stay visible");
            assertFalse(visible(r.visibility(), HIDDEN),
                "the section behind the wall must be culled; "
                    + "visibility = 0x" + Integer.toHexString(r.visibility()[HIDDEN]));
        } finally {
            r.target().free();
        }
    }

    /**
     * <b>対照</b>: 遮蔽を外すと同じセクションが可視に戻ること。
     *
     * <p>これが無いと「そのセクションは何をしても常に不可視」でも
     * 上のテストが通ってしまう。
     */
    @Test
    void movingTheSectionOutFromBehindTheWallMakesItVisibleAgain() {
        var occluded = run(occludedScene(), true, Barriers.CONSERVATIVE);
        var open = run(unoccludedScene(), true, Barriers.CONSERVATIVE);
        try {
            assertFalse(visible(occluded.visibility(), HIDDEN), "behind the wall -> culled");
            assertTrue(visible(open.visibility(), HIDDEN),
                "moved out from behind the wall -> must be visible again");
        } finally {
            occluded.target().free();
            open.target().free();
        }
    }

    /** カリングでテーブルの quad 数が実際に減ること (段をまたいで効いている証拠)。 */
    @Test
    void cullingReducesTheGeneratedTable() {
        var without = run(occludedScene(), false, Barriers.CONSERVATIVE);
        var with = run(occludedScene(), true, Barriers.CONSERVATIVE);
        try {
            System.out.println("[vk] table quads: no cull=" + without.quadsInTable()
                + ", with cull=" + with.quadsInTable());
            assertTrue(with.quadsInTable() < without.quadsInTable(),
                "culling must shrink the table (" + with.quadsInTable()
                    + " vs " + without.quadsInTable() + ")");
            // 落ちたのは隠れたセクションの 16 枚ちょうど
            assertEquals(16, without.quadsInTable() - with.quadsInTable(),
                "exactly the hidden section's quads should disappear");
        } finally {
            without.target().free();
            with.target().free();
        }
    }

    // ---------------- 前提の常設検査 ----------------

    /**
     * <b>{@code gl_InstanceIndex} への置換が成り立つ前提の検査。</b>
     *
     * <p>GL の {@code gl_InstanceID} は baseInstance を含まないが Vulkan の
     * {@code gl_InstanceIndex} は含む。置換してよいのは baseInstance が 0 のときだけ
     * (docs/phase2-glsl-compat.md 3.1)。{@code VkCullPass} が 0 を渡していることを
     * ソースで押さえる — ここが変わると<b>静かにセクションがずれる</b>。
     */
    @Test
    void cullDrawUsesZeroBaseInstance() throws Exception {
        String src = java.nio.file.Files.readString(
            Path.of("src/main/java/me/cortex/voxy/client/core/vk/VkCullPass.java"));
        assertTrue(src.contains("vkCmdDrawIndexed(cmd, CUBE_INDEX_COUNT, sectionCount, 0, 0, 0)"),
            "the cull draw must pass firstInstance = 0; gl_InstanceIndex is used directly "
                + "as the indirectLookup index");
    }

    /** 遮蔽シーンが本当に遮蔽関係になっていること (可視位置の規約も守れていること)。 */
    @Test
    void theSceneActuallyHasAnOcclusionRelationship() {
        var t = occludedScene();
        // 規約: quad が別々の可視位置を占めること
        var prints = t.footprints();
        assertEquals(prints.size(), new java.util.HashSet<>(prints).size(),
            "the occlusion scene must satisfy the distinct-footprint convention");

        // 壁は 32x32 を埋めること (1024 = 32*32 で px 0..31, py 0..31 に並ぶ)
        assertEquals(1024, t.sections().get(WALL).faceCounts[Face.SOUTH.bit]);
        // 隠れる側は壁より奥 (z が小さい)
        assertTrue(t.sections().get(HIDDEN).z < t.sections().get(WALL).z,
            "the hidden section must sit behind the wall");
    }

    /** 絞ったバリアでも同じ結果になること。 */
    @Test
    void narrowBarriersGiveTheSameCullResult() {
        var conservative = run(occludedScene(), true, Barriers.CONSERVATIVE);
        var narrow = run(occludedScene(), true, Barriers.NARROW);
        try {
            assertArrayEquals(conservative.visibility(), narrow.visibility(),
                "narrowing the barriers changed the visibility result");
            assertEquals(0, VkRenderTarget.compareColor(conservative.target(), narrow.target()),
                "narrowing the barriers changed the image");
        } finally {
            conservative.target().free();
            narrow.target().free();
        }
    }

    private static long coverage(VkRenderTarget rt) {
        int clear = (Math.round(CLEAR[2] * 255) << 16)
            | (Math.round(CLEAR[1] * 255) << 8) | Math.round(CLEAR[0] * 255);
        long base = rt.readbackBuffer().addr();
        long n = 0;
        for (long i = 0; i < (long) rt.width * rt.height; i++) {
            if ((MemoryUtil.memGetInt(base + i * 4) & 0x00FFFFFF) != (clear & 0x00FFFFFF)) n++;
        }
        return n;
    }
}
