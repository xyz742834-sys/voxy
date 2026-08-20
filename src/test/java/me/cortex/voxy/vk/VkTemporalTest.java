package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer.Barriers;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer.Mode;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer.Pass;
import org.junit.jupiter.api.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 4d: <b>temporal パス。</b>
 *
 * <h2>何のためのパスか</h2>
 * 不透明パスは<b>前フレームに生成されたコマンド</b>で描くので、1 フレーム遅れの
 * 可視集合しか出せない [確認済 — `AbstractRenderPipeline.runPipeline` の呼び出し順]。
 * 今フレーム新たに可視になったセクションはそこに含まれないため、
 * temporal パスが<b>今フレームのテーブル</b>でそれだけを描いて埋める。
 *
 * <p>対象かどうかは {@code visibilityData} の bit 31 (cull が立てる
 * 「前フレームも可視だった」印) が<b>立っていない</b>ことで決まる
 * [確認済 — `cmdgen.comp:67` / `cull/raster.vert:55-56`]。
 *
 * <h2>検証の骨子</h2>
 * temporal テーブルは不透明テーブルと<b>同じエントリ</b>を持ち、quad 数だけが違う。
 * よって 2 つの極端な場合が強い検査になる:
 * <ul>
 *   <li>全セクションが「前フレーム不可視」→ temporal は不透明と<b>完全に同じ</b>
 *       → 絵が<b>差分ゼロ</b></li>
 *   <li>全セクションが「前フレーム可視」→ temporal は<b>空</b> → 背景だけ</li>
 * </ul>
 * 片方だけだと「常に空」「常に全部」の実装が通ってしまう。
 */
public class VkTemporalTest {
    private static final int W = 512, H = 512;
    private static final float[] CLEAR = {0.05f, 0.05f, 0.10f, 1.0f};
    private static final Path OUT = Path.of("build", "vk-test-output");
    private static final int FRAME_ID = 7;

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

    private static float[] mvp() {
        return VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(60), (float) W / H, 0.1f, 2000f),
            VkSceneUniform.lookAt(new float[]{14, 7, 13}, new float[]{8.5f, 0.5f, 0.5f},
                new float[]{0, 1, 0}));
    }

    private record Run(VkTerrainResources res, VkRenderTarget target,
                       int opaqueQuads, int temporalQuads) {}

    /**
     * テーブルを作り、指定したパスだけを描く。
     *
     * @param wasVisibleLastFrame bit 31 に入れる印。null なら全て「前フレーム不可視」
     * @param pass                描くパス。null なら描かない (テーブルだけ作る)
     */
    private static Run run(SyntheticTerrain terrain, boolean[] wasVisibleLastFrame,
                           Pass pass, Barriers barriers) {
        var res = new VkTerrainResources(terrain.sectionCount(), terrain.totalQuads(), 4096,
            terrain.maxStateId() + 1);
        var rt = new VkRenderTarget(W, H);
        VkTerrainRenderer renderer = null;
        VkMergedTableBuilder builder = null;
        try {
            int[] starts = terrain.writeGeometry(res.geometry);
            terrain.writeMetadata(res.sectionMetadata, starts);
            res.fillModels(VkTerrainResources.MODEL_FLAG_SHADED);
            terrain.writeIndirectLookup(res.indirectLookup);
            terrain.writeVisibility(res.visibility, FRAME_ID, null, wasVisibleLastFrame);

            builder = new VkMergedTableBuilder(res, barriers);
            if (pass != null) {
                renderer = new VkTerrainRenderer(res, W, H, barriers, Mode.MERGED, pass);
            }
            int maxDraws = SyntheticTerrain.maxFaceDrawCount(terrain.totalQuads(),
                res.indexQuadCapacity);
            VkSceneUniform.write(res.uniform, mvp(), SyntheticTerrain.ORIGIN, FRAME_ID,
                new float[]{0, 0, 0});

            var t = VkFrameTracker.get();
            for (int frame = 0; frame < 2; frame++) {
                var cmd = t.beginFrame();
                if (frame > 0 && renderer != null) {
                    renderer.record(cmd, rt, maxDraws, CLEAR);
                    rt.recordReadback(cmd);
                } else if (frame > 0) {
                    // 描かない場合でも読み戻し先を初期化しておく
                    rt.beginRendering(cmd, CLEAR, VkDepth.CLEAR);
                    rt.endRendering(cmd);
                    rt.recordReadback(cmd);
                }
                builder.record(cmd, terrain.sectionCount(), maxDraws);
                t.endFrame();
                t.waitForFrame();
            }

            int n = 7 * terrain.sectionCount();
            int opaque = MemoryUtil.memGetInt(res.mergedPrefix.addr() + 4L + (long) n * 4);
            int temporal = MemoryUtil.memGetInt(res.temporalPrefix.addr() + 4L + (long) n * 4);
            return new Run(res, rt, opaque, temporal);
        } catch (RuntimeException e) {
            rt.free();
            res.free();
            throw e;
        } finally {
            if (builder != null) builder.free();
            if (renderer != null) renderer.free();
        }
    }

    private static boolean[] all(int n, boolean v) {
        var a = new boolean[n];
        Arrays.fill(a, v);
        return a;
    }

    // ---------------- テーブル ----------------

    /** <b>temporal テーブルが CPU の参照とバイト一致すること。</b> */
    @Test
    void temporalTableMatchesTheCpuReference() {
        var terrain = SyntheticTerrain.boundaryCases();
        var wasVisible = all(terrain.sectionCount(), false);
        wasVisible[0] = true;      // 7 面区分すべてを持つセクションを temporal から外す
        wasVisible[3] = true;

        var r = run(terrain, wasVisible, null, Barriers.CONSERVATIVE);
        try {
            var geo = new VkBuffer(Math.max(4096,
                (long) terrain.totalQuads() * SyntheticTerrain.QUAD_SIZE));
            int[] starts;
            try { starts = terrain.writeGeometry(geo); } finally { geo.free(); }
            var expected = terrain.mergedTable(starts, SyntheticTerrain.ORIGIN, null, wasVisible);

            int n = expected.entryCount();
            assertEquals(n, MemoryUtil.memGetInt(r.res().temporalPrefix.addr()),
                "prep must publish the temporal entry count too");
            for (int i = 0; i <= n; i++) {
                assertEquals(expected.prefix()[i],
                    MemoryUtil.memGetInt(r.res().temporalPrefix.addr() + 4L + (long) i * 4),
                    "temporal prefix " + i);
            }
            for (int f = 0; f < VkTerrainRenderer.FACE_COUNT; f++) {
                var d = expected.faceDraws().get(f);
                long o = r.res().temporalDraw.addr() + (long) f * SyntheticTerrain.DRAW_COMMAND_SIZE;
                assertEquals(d.quadCount() * 6, MemoryUtil.memGetInt(o), "face " + f + " indexCount");
                assertEquals(d.quadOrdinalStart() << 2, MemoryUtil.memGetInt(o + 12),
                    "face " + f + " vertexOffset");
            }
            System.out.println("[vk] temporal table: " + r.temporalQuads() + " quads of "
                + r.opaqueQuads() + " opaque -- byte-identical to the CPU reference");
        } finally {
            r.target().free();
            r.res().free();
        }
    }

    /**
     * <b>temporal が実際に何かを含んでいること、かつ全部ではないこと。</b>
     *
     * <p>6 例目の型 (空のまま通る) と、その逆 (常に全部) の両方を塞ぐ。
     * 片方だけだと「絞り込みが効いていない」実装が素通りする。
     */
    @Test
    void temporalIsNeitherEmptyNorEverything() {
        var terrain = SyntheticTerrain.boundaryCases();
        var wasVisible = all(terrain.sectionCount(), false);
        wasVisible[0] = true;
        wasVisible[3] = true;

        var r = run(terrain, wasVisible, null, Barriers.CONSERVATIVE);
        try {
            System.out.println("[vk] temporal quads = " + r.temporalQuads()
                + ", opaque quads = " + r.opaqueQuads());
            assertTrue(r.opaqueQuads() > 0, "the opaque table must have content to compare against");
            assertTrue(r.temporalQuads() > 0,
                "temporal is empty; an implementation that never emits anything would pass "
                    + "every other check in this class");
            assertTrue(r.temporalQuads() < r.opaqueQuads(),
                "temporal (" + r.temporalQuads() + ") must be a strict subset of opaque ("
                    + r.opaqueQuads() + "); otherwise the bit-31 filter is not being applied");
        } finally {
            r.target().free();
            r.res().free();
        }
    }

    /** 全セクションが「前フレーム可視」なら temporal は空になること。 */
    @Test
    void everythingVisibleLastFrameMeansAnEmptyTemporalTable() {
        var terrain = SyntheticTerrain.boundaryCases();
        var r = run(terrain, all(terrain.sectionCount(), true), null, Barriers.CONSERVATIVE);
        try {
            assertTrue(r.opaqueQuads() > 0, "opaque must be unaffected by the temporal filter");
            assertEquals(0, r.temporalQuads(), "nothing is newly visible, so temporal must be empty");
            for (int f = 0; f < VkTerrainRenderer.FACE_COUNT; f++) {
                long o = r.res().temporalDraw.addr() + (long) f * SyntheticTerrain.DRAW_COMMAND_SIZE;
                assertEquals(0, MemoryUtil.memGetInt(o + 4),
                    "face " + f + " must be a no-op when temporal is empty");
            }
        } finally {
            r.target().free();
            r.res().free();
        }
    }

    /** 全セクションが「前フレーム不可視」なら temporal は不透明とまったく同じになること。 */
    @Test
    void nothingVisibleLastFrameMeansTemporalEqualsOpaque() {
        var terrain = SyntheticTerrain.boundaryCases();
        var r = run(terrain, null, null, Barriers.CONSERVATIVE);
        try {
            assertEquals(r.opaqueQuads(), r.temporalQuads(),
                "with nothing visible last frame, temporal must cover the whole opaque set");
            int n = 7 * terrain.sectionCount();
            for (int i = 0; i <= n; i++) {
                assertEquals(
                    MemoryUtil.memGetInt(r.res().mergedPrefix.addr() + 4L + (long) i * 4),
                    MemoryUtil.memGetInt(r.res().temporalPrefix.addr() + 4L + (long) i * 4),
                    "prefix " + i + " must match the opaque table exactly");
            }
        } finally {
            r.target().free();
            r.res().free();
        }
    }

    // ---------------- 絵 ----------------

    /**
     * <b>全セクションが新規可視なら、temporal の絵が不透明と差分ゼロになること。</b>
     *
     * <p>Stage 2b と同じ枠組み。temporal は不透明と同じエントリ配列・同じ索引解決を使うので、
     * 絞り込みが恒等になる条件では<b>ピクセル単位で一致</b>しなければならない。
     */
    @Test
    void temporalRendersIdenticallyToOpaqueWhenNothingWasVisible() throws Exception {
        var terrain = SyntheticTerrain.boundaryCases();
        var opaque = run(terrain, null, Pass.OPAQUE, Barriers.CONSERVATIVE);
        var temporal = run(terrain, null, Pass.TEMPORAL, Barriers.CONSERVATIVE);
        try {
            long covered = coverage(opaque.target());
            assertTrue(covered > 1000, "the comparison is only meaningful if something was drawn");
            long diff = VkRenderTarget.compareColor(opaque.target(), temporal.target());
            if (diff != 0) {
                VkRenderTarget.writeDiffPng(opaque.target(), temporal.target(),
                    OUT.resolve("temporal-diff.png"));
            }
            assertEquals(0, diff, "temporal drew " + diff + " pixels differently from opaque");
            temporal.target().writePng(OUT.resolve("terrain-temporal.png"), true);
            System.out.println("[vk] temporal == opaque: " + covered + " px, diff 0");
        } finally {
            opaque.target().free(); opaque.res().free();
            temporal.target().free(); temporal.res().free();
        }
    }

    /**
     * <b>対照</b>: 一部を「前フレーム可視」にすると temporal の絵が変わること。
     * 上のテストが「temporal が不透明を素通ししているだけ」でないことの裏付け。
     */
    @Test
    void markingSectionsAsAlreadyVisibleChangesTheTemporalImage() {
        var terrain = SyntheticTerrain.boundaryCases();
        var wasVisible = all(terrain.sectionCount(), false);
        wasVisible[0] = true;      // 至近の視点に映っているセクション

        var full = run(terrain, null, Pass.TEMPORAL, Barriers.CONSERVATIVE);
        var partial = run(terrain, wasVisible, Pass.TEMPORAL, Barriers.CONSERVATIVE);
        try {
            long diff = VkRenderTarget.compareColor(full.target(), partial.target());
            System.out.println("[vk] excluding one section from temporal -> " + diff + " px differ");
            assertTrue(diff > 0,
                "excluding a visible section from temporal must change the image; "
                    + "otherwise the bit-31 filter never reaches the draw");
        } finally {
            full.target().free(); full.res().free();
            partial.target().free(); partial.res().free();
        }
    }

    /** temporal が空なら絵は背景だけになること。 */
    @Test
    void anEmptyTemporalTableDrawsNothing() {
        var terrain = SyntheticTerrain.boundaryCases();
        var r = run(terrain, all(terrain.sectionCount(), true), Pass.TEMPORAL, Barriers.CONSERVATIVE);
        try {
            assertEquals(0, coverage(r.target()),
                "an empty temporal table must leave the clear colour untouched");
        } finally {
            r.target().free();
            r.res().free();
        }
    }

    /** 絞ったバリアでも同じ結果になること。 */
    @Test
    void narrowBarriersProduceTheSameTemporalResult() {
        var terrain = SyntheticTerrain.boundaryCases();
        var wasVisible = all(terrain.sectionCount(), false);
        wasVisible[3] = true;
        var a = run(terrain, wasVisible, Pass.TEMPORAL, Barriers.CONSERVATIVE);
        var b = run(terrain, wasVisible, Pass.TEMPORAL, Barriers.NARROW);
        try {
            assertEquals(a.temporalQuads(), b.temporalQuads(), "table differs");
            assertEquals(0, VkRenderTarget.compareColor(a.target(), b.target()), "image differs");
        } finally {
            a.target().free(); a.res().free();
            b.target().free(); b.res().free();
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
