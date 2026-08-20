package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer.Barriers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>描いたものが framebuffer の正しい位置に出ること</b> — 特に <b>Y の向き</b>。
 *
 * <h2>なぜ要るのか — 既存のテストは Y の向きに無反応</h2>
 * Vulkan 規約 (Y 反転あり) から GL 規約 (反転なし) に切り替えたとき、
 * <b>既存の 173 件は 1 件も落ちなかった</b>。逆Zのときと同じ理由である —
 * Phase 4 のテストは<b>描いた結果どうしを比べている</b>ので、
 * 全体が一様に反転しても差が出ない。
 *
 * <p>5b で踏んだ上下反転も同じ穴だった。<b>2 度あることなので常設で塞ぐ。</b>
 *
 * <h2>やり方 — CPU で位置を予測して突き合わせる</h2>
 * Stage 1 の {@code singleQuadLandsWhereAndHowPredicted} と同じ手である。
 * <b>ジオメトリの世界座標を CPU で投影して行を予測し、実際に塗られた行と比べる。</b>
 * 予測は {@link VkSceneUniform#project} が行うので、
 * <b>実装 (GPU) と予測 (CPU) が独立</b>している。
 *
 * <h2>この検査を空虚に満たす方法と、その塞ぎ方</h2>
 * <ol>
 *   <li><b>画面全体が塗られている</b> → どの行を見ても当たる。
 *       被覆率の上限を要求し、<b>鏡像の行が背景であること</b>も要求する</li>
 *   <li><b>ジオメトリが画面中央にある</b> → 上下反転しても同じ行になる。
 *       {@link #discriminatingViewpoint} が<b>予測行と鏡像行が十分離れていること</b>を
 *       先に確かめる。離れていなければ視点の選び方が悪い</li>
 *   <li><b>⚠ 予測と実装が同じ行列を共有している</b> → <b>行列の Y 符号を反転しても
 *       予測も一緒に反転して打ち消し合う</b>。
 *       <b>これは実際に踏んだ</b> — 対照として {@code m[5]} を {@code -f} に戻したところ、
 *       {@link #geometryLandsOnThePredictedRow} は<b>通ってしまった</b>
 *       (落ちたのは {@link #discriminatingViewpoint} だけ)。
 *       塞ぎ方は<b>行列に依存しない物理的な事実</b>を別に主張すること —
 *       「ジオメトリより上を見れば、ジオメトリは画面の下半分に出る」。
 *       {@link #discriminatingViewpoint} が予測側で、
 *       {@link #geometryLandsOnThePredictedRow} が実測側でこれを言う</li>
 * </ol>
 *
 * <h2>この 2 本の役割分担</h2>
 * <table>
 *   <tr><th></th><th>捕まえるもの</th></tr>
 *   <tr><td>{@link #discriminatingViewpoint}</td>
 *       <td><b>行列の Y 規約</b>。行列だけを見る (GPU 不要)</td></tr>
 *   <tr><td>{@link #geometryLandsOnThePredictedRow}</td>
 *       <td><b>行列とラスタライザの食い違い</b> (viewport / scissor / 読み戻しの行順)
 *           <b>および</b>実測が下半分に来ること</td></tr>
 * </table>
 */
public class VkOrientationTest {
    private static final int W = 256, H = 256;
    private static final float[] CLEAR = {0.05f, 0.05f, 0.10f, 1.0f};

    /** 合成地形が実際に居る場所。原点セクションの quad 帯 [確認済 — SyntheticTerrain]。 */
    private static final float[] GEOMETRY = {8.5f, 0.5f, 0.5f};

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

    /**
     * <b>ジオメトリを画面の中央から外した視点。</b>
     * 注視点をジオメトリより上に置くので、ジオメトリは<b>画面の下寄り</b>に出る。
     * 中央に置くと上下反転しても同じ行になり、検査が空虚になる。
     */
    private static float[] mvp() {
        return VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(60), (float) W / H, 0.1f, 2000f),
            VkSceneUniform.lookAt(new float[]{14.0f, 7.0f, 13.0f},
                new float[]{8.5f, 6.0f, 0.5f},      // ジオメトリ (y=0.5) より上を見る
                new float[]{0, 1, 0}));
    }

    /**
     * NDC の y を framebuffer の行に写す。
     * <b>GL 規約なので NDC y = -1 が 0 行目 (絵の下端) である</b>
     * [{@link VkSceneUniform#perspective}]。
     */
    private static int rowOf(float ndcY) {
        return Math.round((ndcY * 0.5f + 0.5f) * (H - 1));
    }

    // ---------------- 前提の確認 ----------------

    /**
     * <b>視点が判定に使えること。</b> 予測行とその鏡像が十分離れていなければ、
     * 上下反転を検出できない。<b>本題より先にこれを確かめる。</b>
     */
    @Test
    void discriminatingViewpoint() {
        float[] ndc = VkSceneUniform.project(mvp(), GEOMETRY[0], GEOMETRY[1], GEOMETRY[2]);
        int row = rowOf(ndc[1]);
        int mirrored = H - 1 - row;

        assertTrue(row >= 0 && row < H,
            "the geometry must project inside the frame; got row " + row);
        assertTrue(Math.abs(row - mirrored) > H / 4,
            "the viewpoint must put the geometry away from the vertical centre, "
                + "otherwise a flip is undetectable. row=" + row + " mirrored=" + mirrored);
        assertTrue(row < H / 2,
            "looking above the geometry must place it in the lower half under the GL "
                + "convention (NDC y=-1 is row 0); got row " + row);
    }

    // ---------------- 本題 ----------------

    private record Scene(VkTerrainResources res, int drawCount) implements AutoCloseable {
        @Override public void close() { this.res.free(); }
    }

    private static Scene build() {
        var t = SyntheticTerrain.boundaryCases();
        var res = new VkTerrainResources(t.sectionCount(), t.totalQuads(), 4096, t.maxStateId() + 1);
        int[] starts = t.writeGeometry(res.geometry);
        t.writeMetadata(res.sectionMetadata, starts);
        t.writePositions(res.positionScratch);
        res.fillModels(VkTerrainResources.MODEL_FLAG_SHADED);
        var draws = t.opaqueDrawCommands(starts, SyntheticTerrain.ORIGIN);
        SyntheticTerrain.writeDrawCommands(res.drawCall, draws, res.indexQuadCapacity);
        return new Scene(res, draws.size());
    }

    /** 背景でない画素の行の重心と、その個数。 */
    private record Coverage(double centroidRow, long count) {}

    private static Coverage coverage(VkRenderTarget rt) {
        int clear = 0xFF000000
            | (Math.round(CLEAR[2] * 255) << 16)
            | (Math.round(CLEAR[1] * 255) << 8)
            | Math.round(CLEAR[0] * 255);
        long base = rt.readbackBuffer().addr();
        long count = 0;
        double rowSum = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int v = MemoryUtil.memGetInt(base + ((long) y * W + x) * 4L);
                // アルファは面/LoD の符号化なので比較から外す
                if ((v & 0x00FFFFFF) != (clear & 0x00FFFFFF)) {
                    count++;
                    rowSum += y;
                }
            }
        }
        return new Coverage(count == 0 ? -1 : rowSum / count, count);
    }

    /**
     * <b>塗られた画素の重心が、CPU で予測した行の近くに来ること。</b>
     *
     * <p>Y の向きが逆なら重心は<b>鏡像の位置</b>に来るので、
     * 予測との差は画面の半分近くになり必ず落ちる。
     */
    @Test
    void geometryLandsOnThePredictedRow() {
        try (var scene = build()) {
            var renderer = new VkTerrainRenderer(scene.res(), W, H, Barriers.CONSERVATIVE);
            var rt = new VkRenderTarget(W, H);
            try {
                VkSceneUniform.write(scene.res().uniform, mvp(), SyntheticTerrain.ORIGIN, 1,
                    new float[]{0, 0, 0});
                var t = VkFrameTracker.get();
                var cmd = t.beginFrame();
                renderer.record(cmd, rt, scene.drawCount(), CLEAR);
                rt.recordReadback(cmd);
                t.endFrame();
                t.waitForFrame();

                var cov = coverage(rt);
                assertTrue(cov.count() > 200,
                    "the geometry must actually be drawn; got " + cov.count() + " px");
                // 画面全体が塗られていたらどの行でも当たってしまう
                assertTrue(cov.count() < (long) W * H / 2,
                    "the frame must not be mostly covered, or the row check is vacuous; got "
                        + cov.count() + " of " + (W * H) + " px");

                float[] ndc = VkSceneUniform.project(mvp(), GEOMETRY[0], GEOMETRY[1], GEOMETRY[2]);
                int predicted = rowOf(ndc[1]);

                // ★ 行列に依存しない主張。
                // 「ジオメトリより上を見れば、ジオメトリは画面の下半分に出る」は
                // 投影行列の Y 符号とは無関係に成り立つ物理的な事実である。
                // これが無いと、行列を反転したとき予測も一緒に反転して打ち消し合う
                // (実際に対照でそうなった。クラス javadoc 参照)
                assertTrue(cov.centroidRow() < H / 2.0,
                    "the camera looks above the geometry, so the drawn pixels must sit in the "
                        + "lower half of the framebuffer (row 0 is the bottom under the GL "
                        + "convention); centroid row = " + cov.centroidRow());

                double err = Math.abs(cov.centroidRow() - predicted);
                double mirroredErr = Math.abs(cov.centroidRow() - (H - 1 - predicted));

                assertTrue(err < H / 6.0,
                    "covered centroid row " + cov.centroidRow() + " is far from the predicted "
                        + predicted + " (error " + err + "). If the error is close to "
                        + mirroredErr + " the image is vertically flipped");
                assertTrue(err < mirroredErr,
                    "the centroid is closer to the mirrored row than to the predicted one: "
                        + "the Y convention is inverted somewhere");
            } finally {
                rt.free();
                renderer.free();
            }
        }
    }
}
