package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer.Barriers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;

/**
 * <b>深度の約束事が逆Zで一貫していること。</b>
 *
 * <h2>なぜこのテストが要るのか — Phase 4 のテストは深度規約に無反応だった</h2>
 * 非逆Z → 逆Zに切り替えたとき、<b>既存の 168 件は 1 件も落ちなかった</b>。
 * これは不具合ではなく<b>逆Zの性質</b>である — 投影・クリア・比較を揃えて反転すれば
 * <b>同じ絵が出る</b>。変わるのは深度値の分布 (遠方の分解能) だけである。
 *
 * <p>つまり Phase 4 のテストは<b>深度の約束事を一度も検証していなかった</b>。
 * 色だけを見ていたためで、これは 5b の上下反転と同じ型の穴である
 * — <b>端点 (色) の一致は途中 (深度) の正しさを保証しない</b>
 * [docs/phase5b-composite.md §6]。
 *
 * <h2>この検査を空虚に満たす方法と、その塞ぎ方</h2>
 * <ol>
 *   <li><b>定数どうしを見比べるだけ</b> → 実装が定数を読んでいなければ意味が無い。
 *       {@link #theProjectionMapsNearToOneAndFarToZero} が<b>行列に値を通し</b>、
 *       {@link #backgroundIsFarAndGeometryIsCloser} が
 *       <b>実際にコンパイルされたシェーダの挙動</b>を見る</li>
 *   <li><b>絵が出ることしか見ない</b> → 深度規約に無反応 (上記)。
 *       {@link #backgroundIsFarAndGeometryIsCloser} と
 *       {@link #movingTheCameraCloserIncreasesTheClosestDepth} が
 *       <b>深度値そのもの</b>を見る</li>
 *   <li><b>投影だけ / 比較だけが合っている</b> → 組み合わせで壊れる。
 *       クリアと比較が食い違えば<b>何も描かれない</b>ので、
 *       被覆画素数の下限を同時に要求する</li>
 * </ol>
 */
public class VkDepthConventionTest {
    private static final int W = 256, H = 256;
    private static final float[] CLEAR_COLOUR = {0.05f, 0.05f, 0.10f, 1.0f};

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

    // ---------------- 定数の一貫性 (GPU 不要) ----------------

    /**
     * {@link VkDepth} の定数が逆Zとして矛盾していないこと。
     * <b>ここを緩めると他の全ての検査の意味が変わる</b>ので、値を直接固定する。
     */
    @Test
    void constantsDescribeReverseZ() {
        assertEquals(1.0f, VkDepth.NEAR, "reverse-Z: the near plane maps to 1.0");
        assertEquals(0.0f, VkDepth.FAR, "reverse-Z: the far plane maps to 0.0");
        assertEquals(VkDepth.FAR, VkDepth.CLEAR,
            "clearing depth must mean 'nothing here' = furthest away");
        assertEquals(VK_COMPARE_OP_GREATER_OR_EQUAL, VkDepth.COMPARE_OP,
            "closer-or-equal in reverse-Z is GREATER_OR_EQUAL");
        assertEquals(VkDepth.NEAR, VkDepth.BOUND_NEUTRAL,
            "the depth bound must reject nothing; in reverse-Z that is the maximum");
    }

    /**
     * <b>投影行列が実際に near → 1.0、far → 0.0 に写すこと。</b>
     *
     * <p>定数だけ直して行列を直し忘れる、が最も起きやすい形なので、
     * <b>行列に値を通して</b>確かめる。
     */
    @Test
    void theProjectionMapsNearToOneAndFarToZero() {
        float near = 0.1f, far = 2000f;
        float[] m = VkSceneUniform.perspective((float) Math.toRadians(60), 1.0f, near, far);

        assertEquals(VkDepth.NEAR, ndcZ(m, -near), 1e-4f,
            "a point on the near plane must land on NEAR");
        assertEquals(VkDepth.FAR, ndcZ(m, -far), 1e-4f,
            "a point on the far plane must land on FAR");

        // 単調性: カメラから遠ざかるほど深度は小さくなる (逆Z)
        float a = ndcZ(m, -1.0f), b = ndcZ(m, -10.0f), c = ndcZ(m, -100.0f);
        assertTrue(a > b && b > c,
            "in reverse-Z depth must decrease with distance, got " + a + " > " + b + " > " + c);
    }

    /** 視空間 z (負値) をクリップ→NDC に通した深度。列優先 mat4。 */
    private static float ndcZ(float[] m, float viewZ) {
        float clipZ = m[10] * viewZ + m[14];   // m[14] は w 成分の列の z 要素
        float clipW = m[11] * viewZ;
        return clipZ / clipW;
    }

    // ---------------- 実際に描いて深度を見る ----------------

    private record Scene(SyntheticTerrain terrain, VkTerrainResources res, int drawCount)
            implements AutoCloseable {
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
        return new Scene(t, res, draws.size());
    }

    /** 原点セクションを距離 {@code dist} から見る。 */
    private static float[] mvpAt(float dist) {
        float[] eye = {8.5f + dist * 0.6f, dist * 0.35f, 0.5f + dist * 0.7f};
        return VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(60), (float) W / H, 0.1f, 2000f),
            VkSceneUniform.lookAt(eye, new float[]{8.5f, 0.5f, 0.5f}, new float[]{0, 1, 0}));
    }

    private static void renderOnce(VkTerrainRenderer renderer, VkRenderTarget rt,
                                   Scene scene, float[] mvp) {
        VkSceneUniform.write(scene.res().uniform, mvp, SyntheticTerrain.ORIGIN, 1,
            new float[]{0, 0, 0});
        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        renderer.record(cmd, rt, scene.drawCount(), CLEAR_COLOUR);
        rt.recordReadback(cmd);
        rt.recordDepthReadback(cmd);
        t.endFrame();
        t.waitForFrame();
    }

    /**
     * <b>背景はクリア値 (最も奥) のまま、地形はそれより手前にあること。</b>
     *
     * <h2>この 1 本が同時に押さえている 3 つの食い違い</h2>
     * <ol>
     *   <li><b>クリア値と比較演算子の食い違い</b> → 深度テストが全部落として
     *       <b>何も描かれない</b>。被覆画素数の下限で捕まる</li>
     *   <li><b>シェーダの {@code USE_REVERSE_Z} 定義漏れ</b> →
     *       {@code depthutils.glsl} が非逆Z分岐を採り、{@code quads.frag} の
     *       {@code DEPTH_SCALAR_COMPARE(z, bound)} が {@code (z < bound)} になる。
     *       {@link VkDepth#BOUND_NEUTRAL} は 1.0 なのでほぼ全ての断片が discard され、
     *       やはり<b>何も描かれない</b>。同じ下限で捕まる</li>
     *   <li><b>深度境界の中立値の取り違え</b> → 上と同じ経路で捕まる</li>
     * </ol>
     *
     * <p>つまり<b>被覆画素数の下限が、Java 側とシェーダ側の規約一致を見張っている</b>。
     * テキストで define の有無を調べるより強い (実際にコンパイルされたものを見ているため)。
     */
    @Test
    void backgroundIsFarAndGeometryIsCloser() {
        try (var scene = build()) {
            var renderer = new VkTerrainRenderer(scene.res(), W, H, Barriers.CONSERVATIVE);
            var rt = new VkRenderTarget(W, H);
            try {
                renderOnce(renderer, rt, scene, mvpAt(12.0f));

                long covered = rt.depthCoverage();
                assertTrue(covered > 200,
                    "geometry must write depth; got " + covered + " texels. "
                        + "If this is 0 the clear value and the compare op disagree");

                float closest = rt.closestDepth();
                assertTrue(closest > VkDepth.CLEAR,
                    "the closest drawn texel must be nearer than the clear value; got " + closest);
                assertTrue(closest <= VkDepth.NEAR,
                    "depth must stay within [0,1]; got " + closest);

                // 背景がクリア値のまま残っていること (全面が塗られてはいない)
                assertEquals(VkDepth.CLEAR, rt.depthAt(0, 0),
                    "the corner should be background, still at the clear value");
            } finally {
                rt.free();
                renderer.free();
            }
        }
    }

    /**
     * <b>カメラを近づけると「最も手前の深度」が大きくなること。</b>
     *
     * <p>これが逆Zの向きそのものである。投影の {@code m[10]}/{@code m[14]} を
     * 非逆Zのまま残すと<b>この不等号だけが反転する</b> — 絵は同じままなので、
     * ここを見ない限り気づけない。
     */
    @Test
    void movingTheCameraCloserIncreasesTheClosestDepth() {
        try (var scene = build()) {
            var renderer = new VkTerrainRenderer(scene.res(), W, H, Barriers.CONSERVATIVE);
            var near = new VkRenderTarget(W, H);
            var far = new VkRenderTarget(W, H);
            try {
                renderOnce(renderer, near, scene, mvpAt(6.0f));
                float dNear = near.closestDepth();
                renderOnce(renderer, far, scene, mvpAt(40.0f));
                float dFar = far.closestDepth();

                assertTrue(near.depthCoverage() > 100 && far.depthCoverage() > 100,
                    "both views must actually draw something (near=" + near.depthCoverage()
                        + " far=" + far.depthCoverage() + ")");
                assertTrue(dNear > dFar,
                    "reverse-Z: the closer view must produce a larger depth value, got near="
                        + dNear + " far=" + dFar);
            } finally {
                near.free();
                far.free();
                renderer.free();
            }
        }
    }
}
