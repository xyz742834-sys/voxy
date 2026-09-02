package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.SyntheticTerrain;
import me.cortex.voxy.client.core.vk.VkCullPass;
import me.cortex.voxy.client.core.vk.VkFrameTracker;
import me.cortex.voxy.client.core.vk.VkMergedTableBuilder;
import me.cortex.voxy.client.core.vk.VkQuadIndexBuffer;
import me.cortex.voxy.client.core.vk.VkRenderTarget;
import me.cortex.voxy.client.core.vk.VkSampler;
import me.cortex.voxy.client.core.vk.VkSceneUniform;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer;
import me.cortex.voxy.client.core.vk.VkTerrainResources;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5c-5b2: <b>cull の間接描画コマンドを prep が書くこと</b>。
 *
 * <h2>なぜ間接にしたのか</h2>
 * 階層トラバーサルではセクション数を<b>GPU が決める</b>ので、ホストは
 * <b>前フレームの数しか知らない</b>。今フレームのほうが多いと
 * <b>末尾のセクションが可視の印を貰えず、cmdgen が 0 quad 扱いにして消える</b> —
 * つまり<b>点滅する</b>。GL 版も同じ理由で prep が
 * {@code cullDrawIndirectCommand} を書いている [確認済 — {@code lod/gl46/prep.comp}]。
 *
 * <h2>⚠ この検査が塞ぐ空虚な満たし方</h2>
 * <ul>
 *   <li>prep が<b>何も書かない</b> → {@code instanceCount} が 0 のまま →
 *       間接描画は no-op → <b>1 つもカリングされない</b>。
 *       落ちないし、絵は「遠景が多い」だけになる</li>
 *   <li>定数を書く → {@link #theInstanceCountFollowsTheSectionCount} が落とす</li>
 * </ul>
 */
public class VkCullIndirectTest {
    private static final int W = 160, H = 120;
    private static final int FRAME_ID = 11;

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

    /** prep だけを走らせ、書かれた 5 uint を返す。 */
    private static int[] cullCommandAfterPrep(SyntheticTerrain terrain) {
        var res = new VkTerrainResources(terrain.sectionCount(), terrain.totalQuads(), 4096,
            terrain.maxStateId() + 1);
        VkMergedTableBuilder builder = null;
        try {
            int[] starts = terrain.writeGeometry(res.geometry);
            terrain.writeMetadata(res.sectionMetadata, starts);
            res.fillModels(VkTerrainResources.MODEL_FLAG_SHADED);
            terrain.writeIndirectLookup(res.indirectLookup);
            terrain.writeVisibility(res.visibility, FRAME_ID, null);
            VkSceneUniform.write(res.uniform, new float[16], SyntheticTerrain.ORIGIN, FRAME_ID,
                new float[]{0, 0, 0});

            builder = new VkMergedTableBuilder(res, VkTerrainRenderer.Barriers.CONSERVATIVE);
            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            builder.recordPrep(cmd);
            t.endFrame();
            t.waitForFrame();

            var out = new int[5];
            for (int i = 0; i < 5; i++) {
                out[i] = MemoryUtil.memGetInt(res.cullDraw.addr() + (long) i * 4L);
            }
            return out;
        } finally {
            if (builder != null) builder.free();
            res.free();
        }
    }

    /** <b>prep が立方体の間接描画コマンドを書くこと。</b> */
    @Test
    void prepWritesTheCullDrawCommand() {
        var terrain = SyntheticTerrain.boundaryCases();
        int[] c = cullCommandAfterPrep(terrain);
        assertEquals(VkCullPass.CUBE_INDEX_COUNT, c[0],
            "indexCount must be the cube's index count");
        assertEquals(terrain.sectionCount(), c[1],
            "instanceCount must be the section count the GPU sees;"
                + " 0 here means the cull pass is a silent no-op");
        assertEquals(0, c[2],
            "firstIndex must be 0 — Vulkan has its own cube index buffer,"
                + " it is not a slice of a shared one like the GL path");
        assertEquals(0, c[3], "vertexOffset must be 0");
        assertEquals(0, c[4],
            "baseInstance MUST be 0 — the vertex shader uses gl_InstanceIndex"
                + " directly as the indirectLookup index");
    }

    /**
     * ⚠ <b>対照</b>: セクション数が変われば {@code instanceCount} も変わること。
     *
     * <p>1 つの構成だけを見ると<b>定数を書く実装</b>が通る。
     */
    @Test
    void theInstanceCountFollowsTheSectionCount() {
        var small = SyntheticTerrain.boundaryCases();
        var big = SyntheticTerrain.boundaryCases().repeated(3);
        assertNotEquals(small.sectionCount(), big.sectionCount(),
            "the two terrains must differ, or this control asserts nothing");
        assertEquals(small.sectionCount(), cullCommandAfterPrep(small)[1]);
        assertEquals(big.sectionCount(), cullCommandAfterPrep(big)[1]);
    }

    /**
     * <b>間接の cull が、正確な数を渡した直接の cull と同じ可視集合を作ること。</b>
     *
     * <p>合成データではホストが正確な数を知っているので、<b>両者は一致しなければならない</b>。
     * ずれるなら間接コマンドの中身が違う。
     */
    @Test
    void theIndirectCullMatchesTheDirectCullWhenTheCountIsExact() {
        var terrain = SyntheticTerrain.boundaryCases();
        int[] direct = visibilityAfterCull(terrain, false);
        int[] indirect = visibilityAfterCull(terrain, true);
        assertArrayEquals(direct, indirect,
            "the indirect cull must reproduce the direct cull exactly when the host"
                + " count is correct; a difference means prep wrote the wrong command");
        // ⚠ 対照: 何かが実際に落ちていること。全可視なら一致は何も主張しない
        int culled = 0;
        for (int v : direct) if ((v & 0x7fffffff) != FRAME_ID + 2) culled++;
        assertTrue(culled > 0,
            "nothing was culled, so 'both agree' holds for the wrong reason");
    }

    /** 3 フレーム走らせて最終フレームの可視状態を返す。 */
    private static int[] visibilityAfterCull(SyntheticTerrain terrain, boolean indirect) {
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

            var barriers = VkTerrainRenderer.Barriers.CONSERVATIVE;
            renderer = new VkTerrainRenderer(res, W, H, barriers, VkTerrainRenderer.Mode.MERGED);
            builder = new VkMergedTableBuilder(res, barriers);
            cull = new VkCullPass(res, barriers);
            int maxDraws = SyntheticTerrain.maxFaceDrawCount(terrain.totalQuads(),
                res.indexQuadCapacity);
            var t = VkFrameTracker.get();

            for (int frame = 0; frame < 3; frame++) {
                int frameId = FRAME_ID + frame;
                VkSceneUniform.write(res.uniform, mvp(), SyntheticTerrain.ORIGIN, frameId,
                    new float[]{0, 0, 0});
                if (frame == 0) terrain.writeVisibility(res.visibility, frameId, null);

                var cmd = t.beginFrame();
                if (frame == 0) {
                    builder.record(cmd, terrain.sectionCount(), maxDraws);
                    me.cortex.voxy.client.core.vk.VkBarriers.conservative(cmd, "seed");
                    renderer.record(cmd, rt, maxDraws, new float[]{0, 0, 0, 1});
                } else {
                    renderer.record(cmd, rt, maxDraws, new float[]{0, 0, 0, 1});
                    builder.recordPrep(cmd);
                    if (indirect) {
                        cull.recordIndirect(cmd, rt, res.cullDraw);
                    } else {
                        cull.record(cmd, rt, terrain.sectionCount());
                    }
                    builder.recordAfterPrep(cmd, terrain.sectionCount(), maxDraws);
                }
                t.endFrame();
                t.waitForFrame();
            }

            var vis = new int[terrain.sectionCount()];
            for (int i = 0; i < vis.length; i++) {
                vis[i] = MemoryUtil.memGetInt(res.visibility.addr() + (long) i * 4);
            }
            return vis;
        } finally {
            if (cull != null) cull.free();
            if (builder != null) builder.free();
            if (renderer != null) renderer.free();
            rt.free();
            res.free();
        }
    }

    private static float[] mvp() {
        return VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(60), (float) W / H, 0.1f, 2000f),
            VkSceneUniform.lookAt(new float[]{14, 7, 13}, new float[]{8.5f, 0.5f, 0.5f},
                new float[]{0, 1, 0}));
    }
}
