package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer.Barriers;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer.Mode;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer.Pass;
import org.junit.jupiter.api.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 4a: <b>半透明パス。</b> 距離バケット順に並べ、バケットごとに 1 draw。
 *
 * <h2>⚠ バケット内の順序は非決定的で、それが正しい</h2>
 * スロットは {@code atomicAdd} で取るので実行ごとに並びが変わる。
 * GL 版も同じで [確認済 — `buildtranslucents.comp`]、
 * バケット内の順序に意味を持たせないのが上流の設計判断である。
 *
 * <p>したがって検証は<b>集合として</b>行う:
 * <ul>
 *   <li>各セクションが正しいバケットに、ちょうど 1 回だけ入る</li>
 *   <li>バケットは遠い順に並ぶ</li>
 *   <li>絵は決定的 — ただしそれは<b>半透明 quad が画面上で重ならない</b>から。
 *       この前提自体を常設検査する ({@link #translucentQuadsDoNotOverlapOnScreen})</li>
 * </ul>
 */
public class VkTranslucentTest {
    private static final int W = 512, H = 512;
    private static final float[] CLEAR = {0.05f, 0.05f, 0.10f, 1.0f};
    private static final Path OUT = Path.of("build", "vk-test-output");
    private static final int FRAME_ID = 3;
    private static final int BUCKETS = VkTerrainResources.TRANSLUCENT_BUCKETS;

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

    /** セクション 0..3 を斜め上から見る。ワールドでは x 0..100 あたりに並ぶ。 */
    private static float[] mvp() {
        return VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(60), (float) W / H, 0.5f, 2000f),
            VkSceneUniform.lookAt(new float[]{64, 48, 78}, new float[]{64, 16, 4},
                new float[]{0, 1, 0}));
    }

    // ---------------- 規約: 半透明 quad は画面上で重ならないこと ----------------

    /**
     * <b>【規約 2】半透明 quad は画面上で重ならないこと。</b>
     *
     * <p>ブレンドは<b>順序依存</b>で、バケット内の描画順は非決定的である。
     * 重なった半透明 quad があると<b>同じ入力でも絵が実行ごとに変わり</b>、
     * 「差分ゼロ」の比較が成立しなくなる。
     *
     * <p>1 本目の規約 ({@code everyQuadOccupiesADistinctVisiblePosition}) が
     * ワールド空間の重なりを禁じるのに対し、こちらは<b>投影後の重なり</b>を見る。
     * ブレンドで問題になるのは同じ画素に 2 枚落ちることだからで、
     * ワールドで離れていても視線方向に並べば重なる。
     *
     * <p>手順ではなくテストにしてある。半透明を持つデータセットを足したら
     * {@code translucentDatasets()} に登録すること。
     */
    @Test
    void translucentQuadsDoNotOverlapOnScreen() {
        for (var named : translucentDatasets()) {
            var terrain = named.getValue();
            var boxes = translucentScreenBoxes(terrain, mvp());
            assertFalse(boxes.isEmpty(), "'" + named.getKey() + "' has no translucent quads");

            // ⚠ 画面外の quad は自動的に「重ならない」ので、
            // ここを確かめないと **検査していない quad があるのに通ってしまう**
            // (実際に 1 枚が画面外のまま通っていた)
            for (int i = 0; i < boxes.size(); i++) {
                var b = boxes.get(i);
                assertTrue(b[2] > 0 && b[0] < W && b[3] > 0 && b[1] < H,
                    "'" + named.getKey() + "': translucent quad " + i + " is off screen ("
                        + java.util.Arrays.toString(b) + "); an off-screen quad trivially "
                        + "satisfies the non-overlap rule, so it would not be checked at all");
            }
            for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    assertFalse(overlaps(boxes.get(i), boxes.get(j)),
                        "'" + named.getKey() + "': translucent quads " + i + " and " + j
                            + " overlap on screen (" + java.util.Arrays.toString(boxes.get(i))
                            + " vs " + java.util.Arrays.toString(boxes.get(j))
                            + "). Blending is order dependent and the within-bucket order is "
                            + "nondeterministic, so the reference image would stop being stable.");
                }
            }
            System.out.println("[vk] " + named.getKey() + ": " + boxes.size()
                + " translucent quads, no screen overlap");
        }
    }

    /** その検査が重なりを実際に捕まえること (対照)。 */
    @Test
    void overlapDetectionActuallyWorks() {
        // 同じ位置に 2 セクションを置けば、半透明 quad はぴたり重なる
        var t = new SyntheticTerrain()
            .add(new SyntheticTerrain.Section(0, 0, 0, 0).translucent(2))
            .add(new SyntheticTerrain.Section(0, 0, 0, 0).translucent(2));
        var boxes = translucentScreenBoxes(t, mvp());
        assertEquals(4, boxes.size());
        boolean found = false;
        for (int i = 0; i < boxes.size() && !found; i++) {
            for (int j = i + 1; j < boxes.size() && !found; j++) {
                if (overlaps(boxes.get(i), boxes.get(j))) found = true;
            }
        }
        assertTrue(found, "the overlap check must detect deliberately stacked quads; "
            + "otherwise the invariant above proves nothing");
    }

    private static List<java.util.Map.Entry<String, SyntheticTerrain>> translucentDatasets() {
        return List.of(
            java.util.Map.entry("translucentCases", SyntheticTerrain.translucentCases()));
    }

    /** 半透明 quad の画面上の外接矩形。 */
    private static List<double[]> translucentScreenBoxes(SyntheticTerrain t, float[] mvp) {
        var prints = t.footprints();
        var mask = t.translucentMask();
        var out = new ArrayList<double[]>();
        for (int i = 0; i < prints.size(); i++) {
            if (!mask[i]) continue;
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (var c : prints.get(i).corners()) {
                float[] ndc = VkSceneUniform.project(mvp, (float) c[0], (float) c[1], (float) c[2]);
                double px = (ndc[0] * 0.5 + 0.5) * W;
                double py = (ndc[1] * 0.5 + 0.5) * H;
                minX = Math.min(minX, px); maxX = Math.max(maxX, px);
                minY = Math.min(minY, py); maxY = Math.max(maxY, py);
            }
            out.add(new double[]{minX, minY, maxX, maxY});
        }
        return out;
    }

    private static boolean overlaps(double[] a, double[] b) {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    // ---------------- GPU テーブルの検証 ----------------

    private record Run(VkTerrainResources res, VkRenderTarget target, int slotCount) {}

    /** テーブル生成 + 不透明描画 + 半透明描画を 2 フレーム回す。 */
    private static Run run(SyntheticTerrain terrain, Barriers barriers, boolean drawTranslucent) {
        var res = new VkTerrainResources(terrain.sectionCount(), terrain.totalQuads(), 4096,
            terrain.maxStateId() + 1);
        var rt = new VkRenderTarget(W, H);
        VkTerrainRenderer opaque = null, translucent = null;
        VkMergedTableBuilder builder = null;
        try {
            int[] starts = terrain.writeGeometry(res.geometry);
            terrain.writeMetadata(res.sectionMetadata, starts);
            res.fillModels(VkTerrainResources.MODEL_FLAG_SHADED);
            terrain.writeIndirectLookup(res.indirectLookup);
            terrain.writeVisibility(res.visibility, FRAME_ID, null);

            opaque = new VkTerrainRenderer(res, W, H, barriers, Mode.MERGED, Pass.OPAQUE);
            translucent = new VkTerrainRenderer(res, W, H, barriers, Mode.MERGED, Pass.TRANSLUCENT);
            builder = new VkMergedTableBuilder(res, barriers);

            int maxDraws = SyntheticTerrain.maxFaceDrawCount(terrain.totalQuads(),
                res.indexQuadCapacity);
            VkSceneUniform.write(res.uniform, mvp(), SyntheticTerrain.ORIGIN, FRAME_ID,
                new float[]{0, 0, 0});

            var t = VkFrameTracker.get();
            for (int frame = 0; frame < 2; frame++) {
                var cmd = t.beginFrame();
                if (frame > 0) {
                    // GL 側と同じ順: 不透明 -> (テーブル生成) -> 半透明
                    opaque.record(cmd, rt, maxDraws, CLEAR);
                    if (drawTranslucent) {
                        translucent.record(cmd, rt, BUCKETS, null);
                    }
                    rt.recordReadback(cmd);
                }
                builder.record(cmd, terrain.sectionCount(), maxDraws);
                t.endFrame();
                t.waitForFrame();
            }
            int slots = MemoryUtil.memGetInt(res.translucentPrefix.addr());
            return new Run(res, rt, slots);
        } catch (RuntimeException e) {
            rt.free();
            res.free();
            throw e;
        } finally {
            if (builder != null) builder.free();
            if (opaque != null) opaque.free();
            if (translucent != null) translucent.free();
        }
    }

    /** CPU 側の期待値: セクション -> 距離バケット。 */
    private static int bucketOf(SyntheticTerrain.Section s) {
        int dist = (Math.abs(s.x) + Math.abs(s.y) + Math.abs(s.z)) << s.level;
        return (BUCKETS - 1) - Math.min(dist, BUCKETS - 1);
    }

    /**
     * <b>各セクションが正しいバケットに、ちょうど 1 回だけ入ること。</b>
     * バケット内の順序は問わない (非決定的なので)。
     */
    @Test
    void everySectionLandsInItsDistanceBucketExactlyOnce() {
        var terrain = SyntheticTerrain.translucentCases();
        var r = run(terrain, Barriers.CONSERVATIVE, true);
        try {
            var res = r.res();
            int expectedSlots = (int) terrain.sections().stream()
                .filter(s -> s.translucentCount > 0).count();
            assertEquals(expectedSlots, r.slotCount(), "every translucent section needs a slot");

            // bucketEnd[b] はバケット b の終端スロット (translucent_gen が atomicAdd で進めた後)
            var bucketEnd = new int[BUCKETS];
            for (int b = 0; b < BUCKETS; b++) {
                bucketEnd[b] = MemoryUtil.memGetInt(res.translucentBucket.addr() + (long) b * 4);
            }
            for (int b = 1; b < BUCKETS; b++) {
                assertTrue(bucketEnd[b] >= bucketEnd[b - 1],
                    "bucket ends must not run backwards at " + b);
            }
            assertEquals(expectedSlots, bucketEnd[BUCKETS - 1], "buckets must cover every slot");

            // スロット -> drawId を読み、バケットごとの集合を作る
            var perBucket = new java.util.HashMap<Integer, java.util.Set<Integer>>();
            for (int b = 0; b < BUCKETS; b++) {
                int from = b == 0 ? 0 : bucketEnd[b - 1];
                for (int slot = from; slot < bucketEnd[b]; slot++) {
                    int drawId = MemoryUtil.memGetInt(res.translucentEntry.addr() + (long) slot * 8 + 4);
                    perBucket.computeIfAbsent(b, k -> new HashSet<>()).add(drawId);
                }
            }

            var seen = new HashSet<Integer>();
            for (int si = 0; si < terrain.sectionCount(); si++) {
                var s = terrain.sections().get(si);
                if (s.translucentCount == 0) continue;
                int b = bucketOf(s);
                assertTrue(perBucket.getOrDefault(b, java.util.Set.of()).contains(si),
                    "section " + si + " should be in bucket " + b + "; buckets = " + perBucket);
                assertTrue(seen.add(si), "section " + si + " appeared twice");
            }
            System.out.println("[vk] translucent buckets: " + perBucket);
        } finally {
            r.target().free();
            r.res().free();
        }
    }

    /** 遠いバケットほど先に描かれること (baseVertex が昇順)。 */
    @Test
    void bucketsAreOrderedFarToNear() {
        var terrain = SyntheticTerrain.translucentCases();
        var r = run(terrain, Barriers.CONSERVATIVE, true);
        try {
            var res = r.res();
            int lastEnd = -1;
            var nonEmpty = new ArrayList<Integer>();
            for (int b = 0; b < BUCKETS; b++) {
                long o = res.translucentDraw.addr() + (long) b * SyntheticTerrain.DRAW_COMMAND_SIZE;
                int indexCount = MemoryUtil.memGetInt(o);
                int baseVertex = MemoryUtil.memGetInt(o + 12);
                if (indexCount == 0) continue;
                nonEmpty.add(b);
                assertTrue(baseVertex > lastEnd, "bucket " + b
                    + " must start after the previous one (baseVertex " + baseVertex
                    + " vs " + lastEnd + ")");
                lastEnd = baseVertex;
            }
            long distinct = terrain.sections().stream().filter(s -> s.translucentCount > 0)
                .map(VkTranslucentTest::bucketOf).distinct().count();
            assertEquals(distinct, nonEmpty.size(),
                "expected one draw per distinct distance bucket, got " + nonEmpty);
            assertTrue(distinct < terrain.sections().stream()
                    .filter(s -> s.translucentCount > 0).count(),
                "the dataset must put two sections in one bucket, otherwise the "
                    + "within-bucket ordering path is never exercised");
            // 遠い = バケット番号が小さい。番号順に描かれるので遠いものが先
            assertEquals(nonEmpty.stream().sorted().toList(), nonEmpty,
                "buckets must be emitted in increasing index order (= far to near)");
        } finally {
            r.target().free();
            r.res().free();
        }
    }

    /** バケットが共有インデックスバッファを超えていないこと (超えたら切り詰められる)。 */
    @Test
    void noBucketExceedsTheIndexBuffer() {
        var terrain = SyntheticTerrain.translucentCases();
        var r = run(terrain, Barriers.CONSERVATIVE, true);
        try {
            int maxQuads = MemoryUtil.memGetInt(r.res().translucentStats.addr());
            int clamped = MemoryUtil.memGetInt(r.res().translucentStats.addr() + 4);
            System.out.println("[vk] translucent: max bucket = " + maxQuads + " quads, clamped = " + clamped);
            assertTrue(maxQuads > 0, "the diagnostic must actually be written");
            assertEquals(0, clamped, "a bucket exceeded T and was truncated; "
                + "the per-bucket draw would otherwise read past the shared index buffer");
        } finally {
            r.target().free();
            r.res().free();
        }
    }

    // ---------------- 絵 ----------------

    /**
     * <b>半透明 quad が、予測した画素に描かれること。</b>
     *
     * <p>「何ピクセル増えたか」ではなく<b>どこが増えたか</b>を見る。
     * 1x1 の quad は 4 セクションを収める視点では数画素にしかならず、
     * 枚数の閾値では「たまたま増えた」と区別が付かない。
     * CPU で射影した中心の画素が、半透明あり/なしで変わることを 1 枚ずつ確かめる。
     */
    @Test
    void translucentQuadsAppearWherePredicted() throws Exception {
        var terrain = SyntheticTerrain.translucentCases();
        var without = run(terrain, Barriers.CONSERVATIVE, false);
        var with = run(terrain, Barriers.CONSERVATIVE, true);
        try {
            long diff = VkRenderTarget.compareColor(without.target(), with.target());
            System.out.println("[vk] translucent pass changes " + diff + " px");
            assertTrue(diff > 0, "the translucent pass drew nothing at all");

            var centres = translucentScreenCentres(terrain, mvp());
            assertEquals(4, centres.size(), "one translucent quad per section");
            for (int i = 0; i < centres.size(); i++) {
                int px = (int) Math.round(centres.get(i)[0]);
                int py = (int) Math.round(centres.get(i)[1]);
                assertTrue(px >= 0 && px < W && py >= 0 && py < H,
                    "translucent quad " + i + " projects off screen at (" + px + "," + py + ")");
                var before = without.target().pixelAt(px, py);
                var after = with.target().pixelAt(px, py);
                assertFalse(java.util.Arrays.equals(before, after),
                    "translucent quad " + i + " at (" + px + "," + py + ") did not change the image: "
                        + java.util.Arrays.toString(before) + " -> " + java.util.Arrays.toString(after));
            }
            with.target().writePng(OUT.resolve("terrain-translucent.png"), true);
        } finally {
            without.target().free(); without.res().free();
            with.target().free(); with.res().free();
        }
    }

    /** 半透明 quad の画面上の中心。 */
    private static List<double[]> translucentScreenCentres(SyntheticTerrain t, float[] mvp) {
        var prints = t.footprints();
        var mask = t.translucentMask();
        var out = new ArrayList<double[]>();
        for (int i = 0; i < prints.size(); i++) {
            if (!mask[i]) continue;
            var c = prints.get(i).corners();
            double wx = 0, wy = 0, wz = 0;
            for (var p : c) { wx += p[0]; wy += p[1]; wz += p[2]; }
            float[] ndc = VkSceneUniform.project(mvp, (float) (wx / 4), (float) (wy / 4), (float) (wz / 4));
            out.add(new double[]{(ndc[0] * 0.5 + 0.5) * W, (ndc[1] * 0.5 + 0.5) * H});
        }
        return out;
    }

    /**
     * <b>同じ入力から何度描いても同じ絵になること。</b>
     *
     * <p>バケット内の順序は非決定的なので、これが成り立つのは
     * <b>半透明 quad が画面上で重なっていないから</b>である
     * ({@link #translucentQuadsDoNotOverlapOnScreen} が守っている)。
     * 重なりが入ると<b>このテストが不安定になる</b> — 落ちたらまず規約を疑うこと。
     */
    @Test
    void repeatedTranslucentRendersAreIdentical() {
        var terrain = SyntheticTerrain.translucentCases();
        var runs = new ArrayList<Run>();
        try {
            for (int i = 0; i < 4; i++) runs.add(run(terrain, Barriers.CONSERVATIVE, true));
            for (int i = 1; i < runs.size(); i++) {
                assertEquals(0, VkRenderTarget.compareColor(runs.get(0).target(), runs.get(i).target()),
                    "translucent render " + i + " differs from the first");
            }
        } finally {
            for (var r : runs) { r.target().free(); r.res().free(); }
        }
    }

    /** 絞ったバリアでも同じ結果になること。 */
    @Test
    void narrowBarriersProduceTheSameTranslucentImage() {
        var terrain = SyntheticTerrain.translucentCases();
        var a = run(terrain, Barriers.CONSERVATIVE, true);
        var b = run(terrain, Barriers.NARROW, true);
        try {
            assertEquals(0, VkRenderTarget.compareColor(a.target(), b.target()),
                "narrowing the barriers changed the translucent image");
        } finally {
            a.target().free(); a.res().free();
            b.target().free(); b.res().free();
        }
    }

    /** 半透明を持たないデータでも壊れないこと (スロット 0 の経路)。 */
    @Test
    void aSceneWithNoTranslucentSectionsIsHandled() {
        var terrain = SyntheticTerrain.minimal();
        var r = run(terrain, Barriers.CONSERVATIVE, true);
        try {
            assertEquals(0, r.slotCount(), "no translucent sections -> no slots");
            for (int b = 0; b < BUCKETS; b++) {
                long o = r.res().translucentDraw.addr() + (long) b * SyntheticTerrain.DRAW_COMMAND_SIZE;
                assertEquals(0, MemoryUtil.memGetInt(o + 4),
                    "bucket " + b + " must be a no-op when there is nothing translucent");
            }
        } finally {
            r.target().free();
            r.res().free();
        }
    }
}
