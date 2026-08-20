package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer.Barriers;
import me.cortex.voxy.client.core.vk.VkTerrainResources.AtlasScale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5c-2a — <b>アトラスを実寸にしても同じ絵が出ること。</b>
 *
 * <h2>なぜアトラスが実データ投入の最初なのか</h2>
 * <b>合成データと実データで挙動が変わることが既に分かっている唯一の入力</b>だからである
 * [確認済 — Stage 1 で UV 計算と噛み合わない不具合を踏んだ]。
 *
 * <p>シェーダの UV 計算は<b>タイル格子 256x256 を直書き</b>している
 * ({@code quads.frag} の {@code 1.0/256.0})。合成アトラスは<b>格子はそのまま</b>で
 * 面セルを 1 テクセルに縮めただけなので、実寸 (面セル 16x16) にしても
 * <b>同じ quad が同じ色で出なければならない</b>。
 *
 * <h2>この検査を空虚に満たす方法と、その塞ぎ方</h2>
 * <ol>
 *   <li><b>どちらも同じ設定で描いている</b> → 一致は自明。
 *       {@link #theTwoScalesReallyDiffer} が<b>寸法とミップ段数が実際に違うこと</b>を要求する</li>
 *   <li><b>絵が背景だけ</b> → 一致は自明。被覆画素数と色数の下限を要求する</li>
 *   <li><b>アトラスを引いていない</b> → 何を変えても一致する。
 *       色数の下限が「モデル/面ごとに違う色が出ている」ことを担保する</li>
 * </ol>
 *
 * <p>⚠ <b>面セルの継ぎ目を跨ぐ quad</b> がこの検査の主眼である
 * [docs/phase5c-plan.md §2 の 5c-2]。面セルが 1 テクセル (SMALL) と 16x16 (REAL) では
 * <b>セル内のどこを引くかが変わる</b>ので、丸めが違えばここに出る。
 */
public class VkAtlasScaleTest {
    private static final int W = 512, H = 512;
    private static final float[] CLEAR = {0.05f, 0.05f, 0.10f, 1.0f};

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

    /** {@code VkTerrainRenderTest.closeUpMvp} と同じ視点 — 7 面区分すべてが映る。 */
    private static float[] closeUpMvp() {
        return VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(60), (float) W / H, 0.1f, 2000f),
            VkSceneUniform.lookAt(new float[]{14.0f, 7.0f, 13.0f},
                new float[]{8.5f, 0.5f, 0.5f}, new float[]{0, 1, 0}));
    }

    /** 指定した倍率のアトラスで 1 枚描いて画素を返す。<b>他は何も変えない。</b> */
    private static int[] renderWith(AtlasScale scale) {
        var terrain = SyntheticTerrain.boundaryCases();
        VkTerrainResources res = null;
        VkRenderTarget rt = null;
        VkTerrainRenderer renderer = null;
        try {
            res = new VkTerrainResources(terrain.sectionCount(), terrain.totalQuads(), 4096,
                terrain.maxStateId() + 1, VkQuadIndexBuffer.DEFAULT_QUAD_CAPACITY, scale);
            int[] starts = terrain.writeGeometry(res.geometry);
            terrain.writeMetadata(res.sectionMetadata, starts);
            terrain.writePositions(res.positionScratch);
            res.fillModels(VkTerrainResources.MODEL_FLAG_SHADED);
            var draws = terrain.opaqueDrawCommands(starts, SyntheticTerrain.ORIGIN);
            SyntheticTerrain.writeDrawCommands(res.drawCall, draws, res.indexQuadCapacity);

            rt = new VkRenderTarget(W, H);
            renderer = new VkTerrainRenderer(res, W, H, Barriers.CONSERVATIVE);

            VkSceneUniform.write(res.uniform, closeUpMvp(), new int[]{0, 0, 0}, 1,
                new float[]{0, 0, 0});
            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            renderer.record(cmd, rt, draws.size(), CLEAR);
            rt.recordReadback(cmd);
            t.endFrame();
            t.waitForFrame();

            long base = rt.readbackBuffer().addr();
            int[] px = new int[W * H];
            for (int i = 0; i < px.length; i++) px[i] = MemoryUtil.memGetInt(base + (long) i * 4);
            return px;
        } finally {
            if (renderer != null) renderer.free();
            if (rt != null) rt.free();
            if (res != null) res.free();
        }
    }

    /**
     * <b>2 つの倍率が本当に違うこと。</b>
     * ここが同じなら、下の一致は「同じものを 2 回描いた」だけになる。
     */
    @Test
    void theTwoScalesReallyDiffer() {
        assertEquals(768, AtlasScale.SMALL.width());
        assertEquals(512, AtlasScale.SMALL.height());
        assertEquals(1, AtlasScale.SMALL.mipLevels);

        // 実物と同じ寸法 [確認済 — RenderResourceReuse: MODEL_TEXTURE_SIZE*3*256 x *2*256]
        assertEquals(12288, AtlasScale.REAL.width());
        assertEquals(8192, AtlasScale.REAL.height());
        // 実物と同じミップ段数 [確認済 — numberOfTrailingZeros(16)]
        assertEquals(4, AtlasScale.REAL.mipLevels);

        assertTrue(AtlasScale.REAL.bytesPerModel() > AtlasScale.SMALL.bytesPerModel() * 100,
            "the real scale must carry far more data per model");
    }

    /**
     * <b>実寸のアトラスで描いても、絵が 1 画素も変わらないこと。</b>
     *
     * <p>変わるとしたら<b>面セルの継ぎ目</b>である。SMALL は面セルが 1 テクセルなので
     * セル内のどこを引いても同じ色だが、REAL は 16x16 あるので
     * <b>丸めが違えば隣のセルを引きうる</b>。
     */
    @Test
    void theRealSizedAtlasProducesTheSamePicture() {
        int[] small = renderWith(AtlasScale.SMALL);

        // 対照: 絵が背景だけなら一致は自明である
        int clear = 0xFF000000
            | (Math.round(CLEAR[2] * 255) << 16)
            | (Math.round(CLEAR[1] * 255) << 8)
            | Math.round(CLEAR[0] * 255);
        long covered = 0;
        var colours = new java.util.HashSet<Integer>();
        for (int v : small) {
            colours.add(v);
            if ((v & 0x00FFFFFF) != (clear & 0x00FFFFFF)) covered++;
        }
        assertTrue(covered > 1000, "the picture is nearly empty: " + covered + " px covered");
        assertTrue(colours.size() >= 4,
            "only " + colours.size() + " distinct colours; the atlas may not be sampled at all");

        int[] real = renderWith(AtlasScale.REAL);

        long diff = 0;
        int firstX = -1, firstY = -1, gotSmall = 0, gotReal = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                if (small[i] == real[i]) continue;
                if (diff == 0) { firstX = x; firstY = y; gotSmall = small[i]; gotReal = real[i]; }
                diff++;
            }
        }
        assertEquals(0, diff,
            diff + " pixels differ between the small and the real-sized atlas."
                + " First at (" + firstX + "," + firstY + "): small=0x"
                + Integer.toHexString(gotSmall) + " real=0x" + Integer.toHexString(gotReal)
                + " — the UV maths does not survive the real face-cell size");
    }
}
