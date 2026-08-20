package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer.Barriers;
import org.junit.jupiter.api.*;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 4c: <b>32bit 共有インデックスバッファと面の分割。</b>
 *
 * <h2>なぜ分割が要るか</h2>
 * 統合すると 1 draw に 1 面の全 quad が入る。インデックスバッファが面全体を
 * 覆おうとすると 1 quad あたり 24 バイト要り、ジオメトリ (8 バイト/quad) の 3 倍、
 * 上限規模で 12GiB になる [確認済 — docs/phase4-stage3-completion.md 7.1]。
 * そこで T = 1M quad (24MiB) に固定し、超えたら {@code baseVertex} を進めて分割する。
 *
 * <h2>合成データでは分割が起きないので T を小さくして試す</h2>
 * 実データ用の T = 1M に対し合成データは 189 quad しかない。
 * <b>T を小さくしなければ分割経路は 1 行も実行されない</b>ので、
 * ここでは T を明示的に絞って踏ませる。
 *
 * <h2>分割しても頂点シェーダは変わらない</h2>
 * {@code gl_VertexIndex = baseVertex + インデックス値} で {@code baseVertex} は 4 の倍数。
 * よって {@code >>2} はグローバル quad 通し番号、{@code &3} は corner のまま。
 * <b>この不変性そのものを絵の一致で確かめる</b>のがこのテストの主眼である。
 */
public class VkIndexSplitTest {
    private static final int W = 512, H = 512;
    private static final float[] CLEAR = {0.05f, 0.05f, 0.10f, 1.0f};
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

    private static float[] mvp() {
        return VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(60), (float) W / H, 0.1f, 2000f),
            VkSceneUniform.lookAt(new float[]{14, 7, 13}, new float[]{8.5f, 0.5f, 0.5f},
                new float[]{0, 1, 0}));
    }

    /** 既定の T では 24MiB のバッファが 1 本だけ作られ、使い回されること。 */
    @Test
    void theSharedBufferIsCreatedOncePerCapacity() {
        var a = VkQuadIndexBuffer.of(4096);
        var b = VkQuadIndexBuffer.of(4096);
        assertSame(a, b, "the same capacity must reuse the same buffer");
        assertEquals((long) 4096 * 6 * 4, a.buffer.size(), "32bit: 24 bytes per quad");
    }

    /** インデックスの並びが GL 側と同一であること (provoking vertex が corner 1)。 */
    @Test
    void indicesMatchTheGlLayout() {
        var idx = VkQuadIndexBuffer.of(1024);
        long a = idx.buffer.addr();
        assertArrayEquals(new int[]{1, 2, 0, 1, 3, 2}, ints(a, 6), "quad 0");
        assertArrayEquals(new int[]{13, 14, 12, 13, 15, 14}, ints(a + 3 * 24L, 6), "quad 3");
        int last = 1023, base = last * 4;
        assertArrayEquals(new int[]{base + 1, base + 2, base, base + 1, base + 3, base + 2},
            ints(a + (long) last * 24, 6), "last quad");
        // 32bit なので 16bit の上限を超えられること
        var big = VkQuadIndexBuffer.of(20000);
        int q = 19999, bv = q * 4;
        assertTrue(bv + 3 > 0xFFFF, "this quad's indices exceed what 16 bits could hold");
        assertArrayEquals(new int[]{bv + 1, bv + 2, bv, bv + 1, bv + 3, bv + 2},
            ints(big.buffer.addr() + (long) q * 24, 6),
            "32-bit indices must keep counting past 65535");
    }

    private static int[] ints(long addr, int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = MemoryUtil.memGetInt(addr + i * 4L);
        return out;
    }

    /**
     * <b>T を変えても絵が変わらないこと。</b>
     *
     * <p>分割は {@code baseVertex} を進めるだけで、頂点シェーダの索引解決には
     * 影響しないはず [推測] — それを実地で確かめる。
     * 差が出るなら分割の切れ目で quad が抜けているか二重に描かれている。
     */
    @Test
    void splittingDoesNotChangeTheImage() {
        var terrain = SyntheticTerrain.boundaryCases();
        // 189 quad に対し T=4 なら最大の面 (UP 103 枚) が 26 本に割れる
        int[] capacities = {1 << 20, 64, 8, 4, 1};
        VkRenderTarget reference = null;
        try {
            for (int t : capacities) {
                var rt = renderWith(terrain, t);
                try {
                    if (reference == null) {
                        reference = rt;
                        continue;
                    }
                    assertEquals(0, VkRenderTarget.compareColor(reference, rt),
                        "T=" + t + " produced a different image");
                } finally {
                    if (rt != reference) rt.free();
                }
            }
        } finally {
            if (reference != null) reference.free();
        }
    }

    /** 小さい T で実際に分割が起きていること (上のテストが素通りしていない対照)。 */
    @Test
    void smallCapacitiesActuallySplit() {
        var terrain = SyntheticTerrain.boundaryCases();
        var geo = new VkBuffer(Math.max(4096, (long) terrain.totalQuads() * SyntheticTerrain.QUAD_SIZE));
        int[] starts;
        try { starts = terrain.writeGeometry(geo); } finally { geo.free(); }
        var table = terrain.mergedTable(starts, SyntheticTerrain.ORIGIN);

        int big = SyntheticTerrain.faceDrawCount(table, 1 << 20);
        int small = SyntheticTerrain.faceDrawCount(table, 4);
        System.out.println("[vk] face draws: T=1M -> " + big + ", T=4 -> " + small);
        assertEquals(VkTerrainRenderer.FACE_COUNT, big, "no split at the production T");
        assertTrue(small > big * 4,
            "T=4 must split heavily (" + small + " draws); otherwise splittingDoesNotChangeTheImage "
                + "never exercises the split path");
    }

    /**
     * <b>GPU 側の分割が CPU 側と一致すること。</b>
     * {@code merged_prefix.comp} が出す DrawCommand を CPU の参照と突き合わせる。
     */
    @Test
    void gpuSplitMatchesTheCpuSplit() {
        var terrain = SyntheticTerrain.boundaryCases();
        int t = 8;
        var res = new VkTerrainResources(terrain.sectionCount(), terrain.totalQuads(), 4096,
            terrain.maxStateId() + 1, t);
        try {
            int[] starts = terrain.writeGeometry(res.geometry);
            terrain.writeMetadata(res.sectionMetadata, starts);
            terrain.writeIndirectLookup(res.indirectLookup);
            terrain.writeVisibility(res.visibility, FRAME_ID, null);
            res.mergedDraw.fill(0xDEADBEEF);
            var table = terrain.mergedTable(starts, SyntheticTerrain.ORIGIN);

            int maxDraws = SyntheticTerrain.maxFaceDrawCount(terrain.totalQuads(), t);
            int actual = SyntheticTerrain.faceDrawCount(table, t);
            assertTrue(maxDraws >= actual);

            var expected = new VkBuffer((long) maxDraws * SyntheticTerrain.DRAW_COMMAND_SIZE + 64).zero();
            var builder = new VkMergedTableBuilder(res, Barriers.CONSERVATIVE);
            try {
                SyntheticTerrain.writeFaceDraws(expected, table, t);

                VkSceneUniform.write(res.uniform, mvp(), SyntheticTerrain.ORIGIN, FRAME_ID,
                    new float[]{0, 0, 0});
                var tracker = VkFrameTracker.get();
                var cmd = tracker.beginFrame();
                builder.record(cmd, terrain.sectionCount(), maxDraws);
                tracker.endFrame();
                tracker.waitForFrame();

                for (int i = 0; i < actual; i++) {
                    long e = expected.addr() + (long) i * SyntheticTerrain.DRAW_COMMAND_SIZE;
                    long g = res.mergedDraw.addr() + (long) i * SyntheticTerrain.DRAW_COMMAND_SIZE;
                    for (int w = 0; w < 5; w++) {
                        assertEquals(MemoryUtil.memGetInt(e + w * 4L), MemoryUtil.memGetInt(g + w * 4L),
                            "draw " + i + " word " + w + " (0=indexCount 1=instances 2=firstIndex "
                                + "3=baseVertex 4=firstInstance)");
                    }
                }
                // 上限まで余ったスロットは no-op で埋まっていること
                for (int i = actual; i < maxDraws; i++) {
                    long g = res.mergedDraw.addr() + (long) i * SyntheticTerrain.DRAW_COMMAND_SIZE;
                    assertEquals(0, MemoryUtil.memGetInt(g + 4), "padding slot " + i
                        + " must have instanceCount 0");
                }
                System.out.println("[vk] gpu split at T=" + t + ": " + actual + " draws (max "
                    + maxDraws + "), all matching the CPU reference");
            } finally {
                builder.free();
                expected.free();
            }
        } finally {
            res.free();
        }
    }

    /** GPU 生成テーブル + 分割でも絵が変わらないこと (経路をまたいだ確認)。 */
    @Test
    void gpuBuiltSplitRendersIdentically() {
        var terrain = SyntheticTerrain.boundaryCases();
        var a = renderGpuBuilt(terrain, 1 << 20);
        var b = renderGpuBuilt(terrain, 4);
        try {
            assertEquals(0, VkRenderTarget.compareColor(a, b),
                "the GPU-built split changed the image");
        } finally {
            a.free();
            b.free();
        }
    }

    // ---------------- helpers ----------------

    /** CPU 生成テーブル + 指定 T で 1 枚描く。 */
    private static VkRenderTarget renderWith(SyntheticTerrain terrain, int quadCapacity) {
        var res = new VkTerrainResources(terrain.sectionCount(), terrain.totalQuads(), 4096,
            terrain.maxStateId() + 1, quadCapacity);
        var rt = new VkRenderTarget(W, H);
        VkTerrainRenderer renderer = null;
        try {
            int[] starts = terrain.writeGeometry(res.geometry);
            terrain.writeMetadata(res.sectionMetadata, starts);
            terrain.writePositions(res.positionScratch);
            res.fillModels(VkTerrainResources.MODEL_FLAG_SHADED);
            var table = terrain.mergedTable(starts, SyntheticTerrain.ORIGIN);
            SyntheticTerrain.writeMergedEntries(res.mergedEntry, table);
            SyntheticTerrain.writeMergedPrefix(res.mergedPrefix, table);
            int draws = SyntheticTerrain.writeFaceDraws(res.mergedDraw, table, quadCapacity);

            renderer = new VkTerrainRenderer(res, W, H, Barriers.CONSERVATIVE,
                VkTerrainRenderer.Mode.MERGED);
            VkSceneUniform.write(res.uniform, mvp(), SyntheticTerrain.ORIGIN, FRAME_ID,
                new float[]{0, 0, 0});
            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            renderer.record(cmd, rt, draws, CLEAR);
            rt.recordReadback(cmd);
            t.endFrame();
            t.waitForFrame();
            return rt;
        } finally {
            if (renderer != null) renderer.free();
            res.free();
        }
    }

    /** GPU 生成テーブル + 指定 T で 1 枚描く。 */
    private static VkRenderTarget renderGpuBuilt(SyntheticTerrain terrain, int quadCapacity) {
        var res = new VkTerrainResources(terrain.sectionCount(), terrain.totalQuads(), 4096,
            terrain.maxStateId() + 1, quadCapacity);
        var rt = new VkRenderTarget(W, H);
        VkTerrainRenderer renderer = null;
        VkMergedTableBuilder builder = null;
        try {
            int[] starts = terrain.writeGeometry(res.geometry);
            terrain.writeMetadata(res.sectionMetadata, starts);
            res.fillModels(VkTerrainResources.MODEL_FLAG_SHADED);
            terrain.writeIndirectLookup(res.indirectLookup);
            terrain.writeVisibility(res.visibility, FRAME_ID, null);

            renderer = new VkTerrainRenderer(res, W, H, Barriers.CONSERVATIVE,
                VkTerrainRenderer.Mode.MERGED);
            builder = new VkMergedTableBuilder(res, Barriers.CONSERVATIVE);
            int maxDraws = SyntheticTerrain.maxFaceDrawCount(terrain.totalQuads(), quadCapacity);

            VkSceneUniform.write(res.uniform, mvp(), SyntheticTerrain.ORIGIN, FRAME_ID,
                new float[]{0, 0, 0});
            var t = VkFrameTracker.get();
            for (int i = 0; i < 2; i++) {
                var cmd = t.beginFrame();
                if (i > 0) {
                    renderer.record(cmd, rt, maxDraws, CLEAR);
                    rt.recordReadback(cmd);
                }
                builder.record(cmd, terrain.sectionCount(), maxDraws);
                t.endFrame();
                t.waitForFrame();
            }
            return rt;
        } finally {
            if (builder != null) builder.free();
            if (renderer != null) renderer.free();
            res.free();
        }
    }
}
