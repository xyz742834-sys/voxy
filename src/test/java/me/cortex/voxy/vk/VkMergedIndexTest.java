package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.shader.VkAutoBindingShader;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import org.junit.jupiter.api.*;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Stage 2a: <b>GPU 上の二分探索を全数検査する。</b>
 *
 * <h2>なぜ絵の比較だけでは足りないか</h2>
 * 「Stage 2b の絵が Stage 1 と一致した」だけだと、
 * <b>索引が偶然一致した場合と区別できない</b>。とくに隠れた quad の解決が
 * 間違っていても絵には出ない。索引を単独で、しかも全通り見る必要がある。
 *
 * <p>ここで走らせる {@code index_probe.comp} は
 * <b>頂点シェーダとまったく同じ {@code quad_index.glsl} の {@code resolveQuad}</b> を呼ぶ。
 * だから「テスト用に書いた別実装が合っていた」ではなく、
 * <b>本番のコードが合っている</b>ことの検査になる。
 *
 * <p>期待値は CPU 側の<b>線形</b>探索。別のアルゴリズムで出した答えと突き合わせる。
 */
public class VkMergedIndexTest {

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

    private static VkAutoBindingShader probeShader() {
        return VkShader.makeAuto().name("merged-index-probe")
            .addSource(ShaderType.COMPUTE, VkShaderLoader.parse("voxy:lod/vk/index_probe.comp"))
            .compile();
    }

    /** 計算パイプラインを 1 本作る (他の Vk テストと同じ組み立て)。 */
    private static long computePipeline(VkShader shader) {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var ci = org.lwjgl.vulkan.VkComputePipelineCreateInfo.calloc(1, stack)
                .sType$Default()
                .stage(shader.stageInfos(stack).get(0))
                .layout(shader.pipelineLayout());
            long[] p = new long[1];
            VkContext.check(vkCreateComputePipelines(
                VkContext.get().device, VK_NULL_HANDLE, ci, null, p), "vkCreateComputePipelines");
            return p[0];
        }
    }

    /** テーブルを GPU に載せ、全通し番号を解決させて {@code (quadIndex, drawId)} を読み戻す。 */
    private static long[][] runProbe(SyntheticTerrain terrain) {
        var res = new VkTerrainResources(terrain.sectionCount(), terrain.totalQuads(), 4096,
            terrain.maxStateId() + 1);
        int[] starts = terrain.writeGeometry(res.geometry);
        var table = terrain.mergedTable(starts, SyntheticTerrain.ORIGIN);
        SyntheticTerrain.writeMergedEntries(res.mergedEntry, table);
        SyntheticTerrain.writeMergedPrefix(res.mergedPrefix, table);

        int total = table.totalQuads();
        var results = new VkBuffer(Math.max(4096L, (long) total * 8)).fill(0xDEADBEEF);
        var shader = probeShader();
        long pipeline = computePipeline(shader);
        try {
            shader.ssbo(0, results)
                  .ssbo(VkTerrainRenderer.MERGED_ENTRY_BINDING, res.mergedEntry)
                  .ssbo(VkTerrainRenderer.MERGED_PREFIX_BINDING, res.mergedPrefix);
            shader.pushUInt(0, total);

            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            VkBarriers.memoryBarrier(cmd,
                org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_HOST_BIT,
                org.lwjgl.vulkan.VK13.VK_ACCESS_2_HOST_WRITE_BIT,
                org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            shader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
            shader.flushPushConstants(cmd);
            vkCmdDispatch(cmd, (total + 63) / 64, 1, 1);
            VkBarriers.memoryBarrier(cmd,
                org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_HOST_BIT,
                org.lwjgl.vulkan.VK13.VK_ACCESS_2_HOST_READ_BIT);
            t.endFrame();
            t.waitForFrame();

            long[][] out = new long[total][2];
            for (int i = 0; i < total; i++) {
                out[i][0] = Integer.toUnsignedLong(MemoryUtil.memGetInt(results.addr() + (long) i * 8));
                out[i][1] = Integer.toUnsignedLong(MemoryUtil.memGetInt(results.addr() + (long) i * 8 + 4));
            }
            return out;
        } finally {
            vkDestroyPipeline(VkContext.get().device, pipeline, null);
            shader.free();
            results.free();
            res.free();
        }
    }

    /** <b>全数検査</b>: GPU の二分探索が CPU の線形探索と 1 件残らず一致すること。 */
    @Test
    void gpuBinarySearchMatchesCpuLinearSearchForEveryQuad() {
        var terrain = SyntheticTerrain.boundaryCases();
        var geo = new VkBuffer(Math.max(4096, (long) terrain.totalQuads() * SyntheticTerrain.QUAD_SIZE));
        int[] starts;
        try {
            starts = terrain.writeGeometry(geo);
        } finally {
            geo.free();
        }
        var table = terrain.mergedTable(starts, SyntheticTerrain.ORIGIN);

        long[][] gpu = runProbe(terrain);
        assertEquals(table.totalQuads(), gpu.length);
        assertTrue(gpu.length > 100, "the check is only meaningful on a table with real content");

        for (int q = 0; q < gpu.length; q++) {
            int[] expected = table.resolveLinear(q);
            assertEquals(expected[0], gpu[q][0],
                "quad ordinal " + q + ": GPU resolved quad index " + gpu[q][0]
                    + " but the linear search says " + expected[0]);
            assertEquals(expected[1], gpu[q][1],
                "quad ordinal " + q + ": GPU resolved section " + gpu[q][1]
                    + " but the linear search says " + expected[1]);
        }
        System.out.println("[vk] merged index probe: " + gpu.length
            + " quad ordinals resolved, all matching the CPU reference");
    }

    /**
     * 最小データでも動くこと (エントリ 1 本 = 二分探索が 1 回も回らない経路)。
     * ループが 0 回でも正しい答えを返すかは、境界としてわざわざ見る価値がある。
     */
    @Test
    void singleEntryTableResolvesWithoutIterating() {
        var terrain = SyntheticTerrain.minimal();
        long[][] gpu = runProbe(terrain);
        assertEquals(1, gpu.length);
        assertEquals(0, gpu[0][0], "the only quad is at index 0");
        assertEquals(0, gpu[0][1], "and it belongs to section 0");
    }

    /**
     * <b>対照</b>: prefix を壊したら検査が実際に落ちること。
     *
     * <p>「全通り一致した」が意味を持つのは、不一致を検出できる場合だけである。
     */
    @Test
    void aCorruptedPrefixIsActuallyDetected() {
        var terrain = SyntheticTerrain.boundaryCases();
        var res = new VkTerrainResources(terrain.sectionCount(), terrain.totalQuads(), 4096,
            terrain.maxStateId() + 1);
        int[] starts = terrain.writeGeometry(res.geometry);
        var table = terrain.mergedTable(starts, SyntheticTerrain.ORIGIN);
        SyntheticTerrain.writeMergedEntries(res.mergedEntry, table);
        SyntheticTerrain.writeMergedPrefix(res.mergedPrefix, table);

        // ⚠ 壊す場所は **非空エントリ同士の境界** でなければならない。
        // 密なレイアウトでは prefix[1] が長さ 0 のエントリの境界になりうる。
        // そこを 1 ずらしても解決結果は 1 件も変わらず、対照が静かに無力化される
        // (実際そうなった。docs/phase4-stage1-completion.md 5.2)
        int[] p = table.prefix();
        int boundary = -1;
        for (int i = 1; i < table.entryCount(); i++) {
            if (p[i] > p[i - 1] && p[i + 1] > p[i]) { boundary = i; break; }
        }
        assertTrue(boundary > 0, "no interior boundary between two non-empty entries to corrupt");
        long slot = res.mergedPrefix.addr() + 4L + (long) boundary * 4;
        MemoryUtil.memPutInt(slot, MemoryUtil.memGetInt(slot) + 1);

        int total = table.totalQuads();
        var results = new VkBuffer(Math.max(4096L, (long) total * 8)).fill(0xDEADBEEF);
        var shader = probeShader();
        long pipeline = computePipeline(shader);
        try {
            shader.ssbo(0, results)
                  .ssbo(VkTerrainRenderer.MERGED_ENTRY_BINDING, res.mergedEntry)
                  .ssbo(VkTerrainRenderer.MERGED_PREFIX_BINDING, res.mergedPrefix);
            shader.pushUInt(0, total);

            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            VkBarriers.conservative(cmd, "probe control");
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            shader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
            shader.flushPushConstants(cmd);
            vkCmdDispatch(cmd, (total + 63) / 64, 1, 1);
            VkBarriers.conservative(cmd, "probe control");
            t.endFrame();
            t.waitForFrame();

            int mismatches = 0;
            for (int q = 0; q < total; q++) {
                int[] expected = table.resolveLinear(q);
                long got = Integer.toUnsignedLong(MemoryUtil.memGetInt(results.addr() + (long) q * 8));
                if (got != expected[0]) mismatches++;
            }
            assertTrue(mismatches > 0,
                "corrupting the prefix table must change at least one resolution; "
                    + "if it does not, the exhaustive check proves nothing");
            System.out.println("[vk] corrupted prefix -> " + mismatches + " resolutions differ");
        } finally {
            vkDestroyPipeline(VkContext.get().device, pipeline, null);
            shader.free();
            results.free();
            res.free();
        }
    }
}
