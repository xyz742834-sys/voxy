package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer.Barriers;
import org.junit.jupiter.api.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 3: <b>統合テーブルを GPU 側で作り、CPU 版と突き合わせる。</b>
 *
 * <p>Stage 1/2 で CPU に置いていた {@code cmdgen} 相当を GPU に戻した。
 * 期待値は「CPU 版とバイト単位で一致」— テーブルが密で atomic を使わないため、
 * GPU 側も決定的になる。
 *
 * <h2>2 段の検証</h2>
 * <ol>
 *   <li><b>テーブルそのもの</b>: entries / prefix / 7 DrawCommand をバイト比較</li>
 *   <li><b>絵</b>: GPU 生成テーブルで描いた結果が Stage 2b (CPU 生成) と差分ゼロ</li>
 * </ol>
 * 1 だけだと「テーブルは合っているが描画に届いていない」を見逃し、
 * 2 だけだと「見えない部分の誤り」を見逃す。
 */
public class VkGpuTableTest {
    private static final int W = 512, H = 512;
    private static final float[] CLEAR = {0.05f, 0.05f, 0.10f, 1.0f};
    private static final Path OUT = Path.of("build", "vk-test-output");
    private static final int FRAME_ID = 1;

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
        VkFrameTracker.shutdown();
    }

    // ---------------- fixture ----------------

    private record Scene(SyntheticTerrain terrain, VkTerrainResources res,
                         SyntheticTerrain.MergedTable cpuTable) implements AutoCloseable {
        @Override public void close() { this.res.free(); }
    }

    /**
     * GPU がテーブルを作るのに必要な入力だけを用意する。
     * <b>テーブル本体 (entries / prefix / draw) は書かない</b> — GPU が作るものだから。
     */
    private static Scene build(SyntheticTerrain t, boolean[] visible) {
        var res = new VkTerrainResources(t.sectionCount(), t.totalQuads(), 4096, t.maxStateId() + 1);
        int[] starts = t.writeGeometry(res.geometry);
        t.writeMetadata(res.sectionMetadata, starts);
        res.fillModels(VkTerrainResources.MODEL_FLAG_SHADED);
        t.writeIndirectLookup(res.indirectLookup);
        t.writeVisibility(res.visibility, FRAME_ID, visible);

        // GPU が書くべき領域を毒で埋める。書き忘れがあれば比較で必ず落ちる
        res.mergedEntry.fill(0xDEADBEEF);
        res.mergedPrefix.fill(0xDEADBEEF);
        res.mergedDraw.fill(0xDEADBEEF);
        res.positionScratch.fill(0xDEADBEEF);

        var cpuTable = t.mergedTable(starts, SyntheticTerrain.ORIGIN, visible);
        return new Scene(t, res, cpuTable);
    }

    private static float[] closeUpMvp() {
        return VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(60), (float) W / H, 0.1f, 2000f),
            VkSceneUniform.lookAt(new float[]{14, 7, 13}, new float[]{8.5f, 0.5f, 0.5f},
                new float[]{0, 1, 0}));
    }

    /**
     * 間接描画に渡す draw 本数。面が T を超えると分割されるので上限を使う。
     * <b>CPU 側が実際の quad 数を知らなくても出せる値</b>である必要がある
     * (本番ではジオメトリバッファの使用量から出す)。
     */
    private static int maxDraws(Scene scene) {
        return SyntheticTerrain.maxFaceDrawCount(
            scene.terrain().totalQuads(), scene.res().indexQuadCapacity);
    }

    private static void writeUniform(Scene scene, float[] mvp) {
        VkSceneUniform.write(scene.res().uniform, mvp,
            SyntheticTerrain.ORIGIN, FRAME_ID, new float[]{0, 0, 0});
    }

    /** テーブル生成だけを 1 フレーム走らせる。 */
    private static void buildTable(VkMergedTableBuilder builder, Scene scene) {
        writeUniform(scene, closeUpMvp());
        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        builder.record(cmd, scene.terrain().sectionCount(), maxDraws(scene));
        t.endFrame();
        t.waitForFrame();
    }

    @Test
    void currentGpuCountsWinAcrossGrowthShrinkAndDispatchBoundaries() {
        var terrain = new SyntheticTerrain();
        for (int i = 0; i < 140; i++) {
            terrain.add(new SyntheticTerrain.Section(i % 10, 0, i / 10, 0)
                .face(SyntheticTerrain.Face.DOUBLE_SIDED, 2).translucent(1));
        }
        try (var scene = build(terrain, null)) {
            var builder = new VkMergedTableBuilder(scene.res(), Barriers.CONSERVATIVE);
            try {
                writeUniform(scene, closeUpMvp());
                int previous = 0;
                for (int count : new int[]{0, 1, 128, 129, 140, 7, 0, 140}) {
                    MemoryUtil.memPutInt(scene.res().indirectLookup.addr(), count);
                    // All opaque quads are newly visible, so temporal must match opaque.
                    terrain.writeVisibility(scene.res().visibility, FRAME_ID, null, new boolean[140]);
                    var tracker = VkFrameTracker.get();
                    var cmd = tracker.beginFrame();
                    builder.record(cmd, previous, maxDraws(scene));
                    tracker.endFrame();
                    tracker.waitForFrame();
                    assertEquals(count * 7, MemoryUtil.memGetInt(scene.res().mergedPrefix.addr()));
                    assertEquals(count * 2, MemoryUtil.memGetInt(scene.res().mergedPrefix.addr() + 4L + count * 7L * 4));
                    assertEquals(count * 2, MemoryUtil.memGetInt(scene.res().temporalPrefix.addr() + 4L + count * 7L * 4));
                    int opaque = 0, temporal = 0;
                    for (int slot = 0; slot < maxDraws(scene); slot++) {
                        opaque += MemoryUtil.memGetInt(scene.res().mergedDraw.addr() + slot * 20L);
                        temporal += MemoryUtil.memGetInt(scene.res().temporalDraw.addr() + slot * 20L);
                    }
                    assertEquals(count * 12, opaque, "opaque commands must use frame's GPU count, previous=" + previous);
                    assertEquals(opaque, temporal, "temporal commands must share the current count");
                    assertEquals(count, MemoryUtil.memGetInt(scene.res().translucentPrefix.addr()), "translucent dispatch must cover growth");
                    assertEquals(count, MemoryUtil.memGetInt(scene.res().translucentPrefix.addr() + 4L + count * 4L));
                    previous = count;
                }
            } finally { builder.free(); }
        }
    }

    // ---------------- 1: テーブルのバイト比較 ----------------

    /**
     * <b>GPU が作ったテーブルが CPU 版とバイト単位で一致すること。</b>
     *
     * <p>これが Stage 3 の核心。絵を出す前にテーブルの正しさが確定する。
     */
    @Test
    void gpuTableMatchesTheCpuReferenceByteForByte() {
        try (var scene = build(SyntheticTerrain.boundaryCases(), null)) {
            var builder = new VkMergedTableBuilder(scene.res(), Barriers.CONSERVATIVE);
            try {
                buildTable(builder, scene);
                assertTableMatches(scene);
                System.out.println("[vk] gpu table: " + scene.cpuTable().entryCount() + " slots, "
                    + scene.cpuTable().nonEmptyEntryCount() + " non-empty, "
                    + scene.cpuTable().totalQuads() + " quads -- byte-identical to the CPU reference");
            } finally {
                builder.free();
            }
        }
    }

    private static void assertTableMatches(Scene scene) {
        var table = scene.cpuTable();
        var res = scene.res();
        int n = table.entryCount();

        assertEquals(n, MemoryUtil.memGetInt(res.mergedPrefix.addr()),
            "prep must publish the entry count (7 * sectionCount)");

        for (int i = 0; i < n; i++) {
            long o = res.mergedEntry.addr() + (long) i * SyntheticTerrain.MERGED_ENTRY_SIZE;
            assertEquals(table.entries().get(i).quadStart(), MemoryUtil.memGetInt(o),
                "entry " + i + " quadStart");
            assertEquals(table.entries().get(i).drawId(), MemoryUtil.memGetInt(o + 4),
                "entry " + i + " drawId");
        }
        for (int i = 0; i <= n; i++) {
            assertEquals(table.prefix()[i],
                MemoryUtil.memGetInt(res.mergedPrefix.addr() + 4L + (long) i * 4),
                "prefix " + i + " (entry " + (i < n ? "start" : "sentinel") + ")");
        }

        // 面ごとの 7 DrawCommand
        for (int f = 0; f < VkTerrainRenderer.FACE_COUNT; f++) {
            var d = table.faceDraws().get(f);
            long o = res.mergedDraw.addr() + (long) f * SyntheticTerrain.DRAW_COMMAND_SIZE;
            assertEquals(d.quadCount() * 6, MemoryUtil.memGetInt(o), "face " + f + " indexCount");
            assertEquals(d.quadCount() == 0 ? 0 : 1, MemoryUtil.memGetInt(o + 4),
                "face " + f + " instanceCount");
            assertEquals(0, MemoryUtil.memGetInt(o + 8), "face " + f + " firstIndex");
            assertEquals(d.quadOrdinalStart() << 2, MemoryUtil.memGetInt(o + 12),
                "face " + f + " vertexOffset");
            assertEquals(0, MemoryUtil.memGetInt(o + 16), "face " + f + " firstInstance");
        }

        // positionBuffer は cmdgen が書く。CPU 版と同じ内容になること
        var expectedPositions = new VkBuffer(Math.max(4096L, (long) scene.terrain().sectionCount() * 8));
        try {
            scene.terrain().writePositions(expectedPositions);
            for (int i = 0; i < scene.terrain().sectionCount(); i++) {
                assertEquals(MemoryUtil.memGetLong(expectedPositions.addr() + (long) i * 8),
                    MemoryUtil.memGetLong(res.positionScratch.addr() + (long) i * 8),
                    "positionBuffer[" + i + "]");
            }
        } finally {
            expectedPositions.free();
        }
    }

    /**
     * <b>対照</b>: 毒で埋めた領域が本当に上書きされていること。
     * 「一致した」が「そもそも書いていない」でないことを確かめる。
     */
    @Test
    void gpuActuallyWritesEveryPartOfTheTable() {
        try (var scene = build(SyntheticTerrain.boundaryCases(), null)) {
            var builder = new VkMergedTableBuilder(scene.res(), Barriers.CONSERVATIVE);
            try {
                buildTable(builder, scene);
                int n = scene.cpuTable().entryCount();
                for (int i = 0; i < n; i++) {
                    long o = scene.res().mergedEntry.addr() + (long) i * 8;
                    assertNotEquals(0xDEADBEEF, MemoryUtil.memGetInt(o),
                        "entry " + i + " was never written by the GPU");
                }
                for (int i = 0; i <= n; i++) {
                    assertNotEquals(0xDEADBEEF,
                        MemoryUtil.memGetInt(scene.res().mergedPrefix.addr() + 4L + (long) i * 4),
                        "prefix " + i + " was never written by the GPU");
                }
                for (int f = 0; f < 7; f++) {
                    assertNotEquals(0xDEADBEEF,
                        MemoryUtil.memGetInt(scene.res().mergedDraw.addr() + (long) f * 20),
                        "face draw " + f + " was never written by the GPU");
                }
            } finally {
                builder.free();
            }
        }
    }

    /** 同じ入力から何度作っても同じテーブルになること (atomic を使っていないことの帰結)。 */
    @Test
    void gpuTableIsDeterministic() {
        try (var scene = build(SyntheticTerrain.boundaryCases(), null)) {
            var builder = new VkMergedTableBuilder(scene.res(), Barriers.CONSERVATIVE);
            try {
                buildTable(builder, scene);
                byte[] first = snapshot(scene.res());
                for (int i = 0; i < 3; i++) {
                    scene.res().mergedEntry.fill(0xDEADBEEF);
                    scene.res().mergedPrefix.fill(0xDEADBEEF);
                    scene.res().mergedDraw.fill(0xDEADBEEF);
                    buildTable(builder, scene);
                    assertArrayEquals(first, snapshot(scene.res()),
                        "table build " + i + " differs from the first; "
                            + "a nondeterministic table would make byte comparison meaningless");
                }
            } finally {
                builder.free();
            }
        }
    }

    private static byte[] snapshot(VkTerrainResources res) {
        int n = (int) (res.mergedEntry.size() + res.mergedPrefix.size() + res.mergedDraw.size());
        byte[] out = new byte[n];
        int k = 0;
        for (var b : new VkBuffer[]{res.mergedEntry, res.mergedPrefix, res.mergedDraw}) {
            for (long i = 0; i < b.size(); i++) out[k++] = MemoryUtil.memGetByte(b.addr() + i);
        }
        return out;
    }

    // ---------------- 2: 可視判定 ----------------

    /**
     * <b>{@code visibilityData} が効いていること。</b>
     * cull ラスタパスは未実装だが、cmdgen 側の可視判定は本番と同じ条件で動く。
     */
    @Test
    void invisibleSectionsContributeNothing() {
        var terrain = SyntheticTerrain.boundaryCases();
        var visible = new boolean[terrain.sectionCount()];
        Arrays.fill(visible, true);
        visible[0] = false;    // 7 面区分すべてを持つセクションを落とす

        try (var scene = build(terrain, visible)) {
            var builder = new VkMergedTableBuilder(scene.res(), Barriers.CONSERVATIVE);
            try {
                buildTable(builder, scene);
                assertTableMatches(scene);

                // 落としたセクションのスロットはすべて長さ 0
                int n = terrain.sectionCount();
                for (int f = 0; f < 7; f++) {
                    int slot = f * n;      // section 0
                    long a = scene.res().mergedPrefix.addr() + 4L;
                    assertEquals(MemoryUtil.memGetInt(a + (long) slot * 4),
                        MemoryUtil.memGetInt(a + (long) (slot + 1) * 4),
                        "face " + f + " of the invisible section must stay empty");
                }
            } finally {
                builder.free();
            }
        }
    }

    /**
     * <b>対照</b>: 可視判定を切り替えると総 quad 数が実際に変わること。
     * 変わらなければ上のテストは何も検査していない。
     */
    @Test
    void visibilityActuallyChangesTheTable() {
        var terrain = SyntheticTerrain.boundaryCases();
        var allVisible = new boolean[terrain.sectionCount()];
        Arrays.fill(allVisible, true);
        var oneHidden = allVisible.clone();
        oneHidden[0] = false;

        int totalAll, totalHidden;
        try (var scene = build(terrain, allVisible)) {
            var builder = new VkMergedTableBuilder(scene.res(), Barriers.CONSERVATIVE);
            try {
                buildTable(builder, scene);
                totalAll = gpuTotalQuads(scene);
            } finally { builder.free(); }
        }
        try (var scene = build(terrain, oneHidden)) {
            var builder = new VkMergedTableBuilder(scene.res(), Barriers.CONSERVATIVE);
            try {
                buildTable(builder, scene);
                totalHidden = gpuTotalQuads(scene);
            } finally { builder.free(); }
        }
        System.out.println("[vk] visibility: all=" + totalAll + " quads, one hidden=" + totalHidden);
        assertTrue(totalHidden < totalAll,
            "hiding a section must reduce the quad count (" + totalHidden + " vs " + totalAll + ")");
    }

    private static int gpuTotalQuads(Scene scene) {
        int n = scene.cpuTable().entryCount();
        return MemoryUtil.memGetInt(scene.res().mergedPrefix.addr() + 4L + (long) n * 4);
    }

    // ---------------- 3: 絵 ----------------

    /**
     * <b>GPU 生成テーブルで描いた絵が、CPU 生成 (Stage 2b) と差分ゼロであること。</b>
     *
     * <p>テーブルのバイト一致だけだと「テーブルは合っているが描画に届いていない」
     * ケースを見逃す。実際に描いて突き合わせる。
     */
    @Test
    void gpuBuiltTableRendersIdenticallyToTheCpuBuiltOne() throws Exception {
        var terrain = SyntheticTerrain.boundaryCases();
        var a = new VkRenderTarget(W, H);
        var b = new VkRenderTarget(W, H);
        try {
            // CPU 生成 (Stage 2b)
            try (var scene = build(terrain, null)) {
                int[] starts = terrain.writeGeometry(scene.res().geometry);
                terrain.writePositions(scene.res().positionScratch);
                var table = terrain.mergedTable(starts, SyntheticTerrain.ORIGIN);
                SyntheticTerrain.writeMergedEntries(scene.res().mergedEntry, table);
                SyntheticTerrain.writeMergedPrefix(scene.res().mergedPrefix, table);
                SyntheticTerrain.writeFaceDraws(scene.res().mergedDraw, table,
                    scene.res().indexQuadCapacity);
                renderWith(scene, a, null);
            }
            // GPU 生成 (Stage 3)
            try (var scene = build(terrain, null)) {
                var builder = new VkMergedTableBuilder(scene.res(), Barriers.CONSERVATIVE);
                try {
                    renderWith(scene, b, builder);
                } finally {
                    builder.free();
                }
            }

            long diff = VkRenderTarget.compareColor(a, b);
            if (diff != 0) VkRenderTarget.writeDiffPng(a, b, OUT.resolve("gpu-table-diff.png"));
            b.writePng(OUT.resolve("terrain-gpu-table.png"), true);
            assertEquals(0, diff, "the GPU-built table rendered " + diff + " pixels differently");
        } finally {
            a.free();
            b.free();
        }
    }

    /**
     * テーブルを作って (builder が非 null なら) 描く。
     *
     * <p><b>順序が要点</b>: GL 側と同じく描画を<b>テーブル生成より前</b>に置く。
     * 前フレームのテーブルで描き、そのあと今フレームのテーブルを作る形にすると
     * {@code docs/phase4-buffer-hazards.md} の WAR がそのまま現れる。
     * ここでは 2 フレーム回して、2 フレーム目の描画が
     * 1 フレーム目に GPU が作ったテーブルを読むようにしている。
     */
    private static void renderWith(Scene scene, VkRenderTarget rt, VkMergedTableBuilder builder)
            throws Exception {
        var renderer = new VkTerrainRenderer(scene.res(), W, H,
            Barriers.CONSERVATIVE, VkTerrainRenderer.Mode.MERGED);
        try {
            writeUniform(scene, closeUpMvp());
            var t = VkFrameTracker.get();
            int frames = builder == null ? 1 : 2;
            for (int i = 0; i < frames; i++) {
                var cmd = t.beginFrame();
                // ① 描画 (前フレームのテーブルを読む) -> ② テーブル生成、の順
                if (i > 0 || builder == null) {
                    renderer.record(cmd, rt, maxDraws(scene), CLEAR);
                    rt.recordReadback(cmd);
                }
                if (builder != null) builder.record(cmd, scene.terrain().sectionCount(), maxDraws(scene));
                t.endFrame();
                t.waitForFrame();
            }
        } finally {
            renderer.free();
        }
    }

    /**
     * <b>絞ったバリアでも同じ結果になること。</b>
     *
     * <p>Stage 3 で初めて「前フレームの描画 → 今フレームの生成」の WAR が実在する。
     * ⚠ 同期バリデーションが機能しないため、観測できるのは
     * 「絞っても壊れなかった」までである (docs/phase4-stage1-completion.md 4.2)。
     */
    @Test
    void narrowBarriersProduceTheSameTableAndImage() throws Exception {
        var terrain = SyntheticTerrain.boundaryCases();
        var a = new VkRenderTarget(W, H);
        var b = new VkRenderTarget(W, H);
        try {
            byte[] conservativeTable;
            try (var scene = build(terrain, null)) {
                var builder = new VkMergedTableBuilder(scene.res(), Barriers.CONSERVATIVE);
                try {
                    renderWith(scene, a, builder);
                    conservativeTable = snapshot(scene.res());
                } finally { builder.free(); }
            }
            try (var scene = build(terrain, null)) {
                var builder = new VkMergedTableBuilder(scene.res(), Barriers.NARROW);
                try {
                    renderWith(scene, b, builder);
                    assertArrayEquals(conservativeTable, snapshot(scene.res()),
                        "narrowing the barriers changed the generated table");
                } finally { builder.free(); }
            }
            assertEquals(0, VkRenderTarget.compareColor(a, b),
                "narrowing the barriers changed the image");
        } finally {
            a.free();
            b.free();
        }
    }
}
