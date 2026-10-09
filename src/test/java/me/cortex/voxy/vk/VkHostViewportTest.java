package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.VkHostViewport;
import me.cortex.voxy.client.core.vk.VkSceneUniform;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>MC のカメラから組み立てた MVP が、合成地形を画面に載せること。</b>
 *
 * <h2>なぜ要るのか — 5c-1d で実際に踏んだ</h2>
 * ホストの中で「橙一色 (地形が画面に無い)」になったとき、
 * <b>置き場所の問題か MVP の式の問題か</b>が切り分けられなかった。
 * MC を起動しないと分からない範囲が広すぎたためである。
 *
 * <p>{@link VkHostViewport} に計算を切り出し、ここで数値を固定する。
 * <b>MC 起動が要るのは「実際に見えるか」だけ</b>にする。
 *
 * <h2>この検査を空虚に満たす方法と、その塞ぎ方</h2>
 * <ol>
 *   <li><b>1 方向だけ試す</b> → たまたま通る。
 *       {@link #theTerrainIsOnScreenWhereverTheCameraLooks} が<b>全方位</b>で要求する</li>
 *   <li><b>「画面内」の判定が緩い</b> → 何でも通る。
 *       {@link #aFixedDirectionAnchorGoesOffScreen} が<b>落ちるべき配置で落ちること</b>を示す
 *       (これは 5c-1d の初回に実際に起きた配置である)</li>
 *   <li><b>深度を見ていない</b> → クリップされていても気付かない。
 *       深度が {@code (0,1)} の内側にあることも同時に要求する</li>
 * </ol>
 */
public class VkHostViewportTest {
    /** MC 相当の投影。逆Z・0..1・GL 規約の Y [docs/phase5c-reverse-z.md]。 */
    private static Matrix4f mcLikeProjection() {
        float[] p = VkSceneUniform.perspective(
            (float) Math.toRadians(70), 16.0f / 9.0f, 0.05f, 192.0f);
        return new Matrix4f().set(p);
    }

    /** ヨー角 {@code yaw} を向いたビュー行列 (回転のみ。MC の modelView と同じ形)。 */
    private static Matrix4f viewLookingAt(float yawRadians) {
        return new Matrix4f().rotateY(yawRadians);
    }

    private static final double DISTANCE = 96;
    private static final int[] CENTRE = {2, 0, 1};
    private static final int QUANTUM = 2;

    /**
     * <b>逆Z・-1..1 の投影</b> — Minecraft がこれだった [確認済 — 5c-1d の実機ログ]。
     * 0..1 版の深度行に対して {@code z' = 2z - w} を掛けたもの。
     */
    private static Matrix4f mcLikeProjectionMinusOneToOne() {
        var p = mcLikeProjection();
        return new Matrix4f(p)
            .m02(2 * p.m02() - p.m03())
            .m12(2 * p.m12() - p.m13())
            .m22(2 * p.m22() - p.m23())
            .m32(2 * p.m32() - p.m33());
    }

    /** データセットの中心セクションのクリップ座標 (x, y, z, w)。 */
    private static float[] clipOfCentre(Matrix4f projection, Matrix4f view,
                                        double camX, double camY, double camZ, int[] anchor) {
        float[] sub = VkHostViewport.cameraSubPos(camX, camY, camZ, anchor);
        return VkHostViewport.clipOfCentre(projection, view, sub, CENTRE);
    }

    /**
     * <b>本番と同じ順で組み立てる</b> — 置き場所を決め、投影を 0..1 に直し、投影する。
     * 対照が本番でない経路を通らないようにするため [規約 5]。
     */
    private static float[] placeAndProject(Matrix4f projection, Matrix4f view,
                                           double camX, double camY, double camZ,
                                           boolean convertDepth) {
        int[] anchor = VkHostViewport.anchorInFront(camX, camY, camZ, projection, view,
            DISTANCE, CENTRE, QUANTUM);
        float[] sub = VkHostViewport.cameraSubPos(camX, camY, camZ, anchor);
        var proj = convertDepth
            ? VkHostViewport.projectionForVulkan(projection, view, sub, CENTRE)
            : new Matrix4f(projection);
        return VkHostViewport.clipOfCentre(proj, view, sub, CENTRE);
    }

    /**
     * ⚠ <b>{@code w} を見ずに NDC だけで判定してはならない。</b>
     * カメラの後ろの点は {@code w < 0} になり、透視除算で符号が反転して
     * <b>画面内に見えてしまう</b>。5c-1d の実機ログがまさにこれだった
     * ({@code centreNDC=(0.02, 0.45, -0.9986)} なのに {@code drawn=0})。
     */
    private static void assertOnScreen(String what, float[] clip) {
        assertTrue(clip[3] > 0,
            what + ": w=" + clip[3] + " (<= 0 means the terrain is BEHIND the camera)");
        assertTrue(VkHostViewport.insideFrustum(clip),
            what + ": ndc=(" + clip[0] / clip[3] + "," + clip[1] / clip[3]
                + "," + clip[2] / clip[3] + ")");
    }

    /**
     * <b>どの方向を向いていても地形が画面に入ること。</b>
     *
     * <p>これが 5c-1d の初回に欠けていた性質である。固定の方角に置くと
     * <b>そちらを向いていなければ何も出ず、経路の不具合と区別が付かない</b>。
     */
    @Test
    void theTerrainIsOnScreenWhereverTheCameraLooks() {
        var projection = mcLikeProjection();
        double camX = 1234.7, camY = 71.3, camZ = -987.2;

        for (int deg = 0; deg < 360; deg += 15) {
            var view = viewLookingAt((float) Math.toRadians(deg));
            assertOnScreen("yaw " + deg + " deg",
                placeAndProject(projection, view, camX, camY, camZ, true));
        }
    }

    /**
     * <b>⚠⚠ ホストの深度規約 (0..1 か -1..1 か) に依存しないこと。</b>
     *
     * <p>5c-1d で<b>実際に踏んだ</b>。MC の投影は<b>逆Z・-1..1</b> で、
     * Vulkan のクリップ空間 (常に 0..1) に渡すと <b>z &lt; 0 が全部クリップされ</b>、
     * 1 画素も描かれなかった。ログの {@code centreNDC=(0.15, 0.12, -0.9992)} がそれである。
     *
     * <p>実装は<b>検算して要るときだけ変換する</b>ので、どちらの規約でも通らなければならない。
     */
    @Test
    void itDoesNotAssumeTheHostDepthRange() {
        double camX = 1234.7, camY = 71.3, camZ = -987.2;
        for (int deg = 0; deg < 360; deg += 30) {
            var view = viewLookingAt((float) Math.toRadians(deg));
            assertOnScreen("yaw " + deg + " deg (host 0..1)",
                placeAndProject(mcLikeProjection(), view, camX, camY, camZ, true));
            assertOnScreen("yaw " + deg + " deg (host -1..1)",
                placeAndProject(mcLikeProjectionMinusOneToOne(), view, camX, camY, camZ, true));
        }
    }

    /**
     * <b>対照 — 変換しなければ -1..1 のホストで落ちること。</b>
     * これが落ちなければ、上の検査は深度規約を区別していない。
     */
    @Test
    void skippingTheConversionBreaksAMinusOneToOneHost() {
        double camX = 1234.7, camY = 71.3, camZ = -987.2;
        var view = viewLookingAt(0.0f);
        float[] clip = placeAndProject(mcLikeProjectionMinusOneToOne(), view,
            camX, camY, camZ, false);
        assertFalse(VkHostViewport.insideFrustum(clip),
            "a -1..1 projection passed straight through and still landed inside the 0..1 "
                + "frustum; then the conversion is not what makes the terrain visible");
    }

    /**
     * <b>⚠⚠ ビュー行列の「前」の規約に依存しないこと。</b>
     *
     * <p>5c-1d の実機で踏んだ欠陥そのものである。実装は
     * 「前 = {@code -positiveZ}」と決め打ちしており、MC の規約と合わなかったため
     * <b>地形がカメラの真後ろに置かれ、1 画素も描かれなかった</b>。
     *
     * <p>そして<b>この検査も同じ決め打ちを共有していたので素通りした</b> —
     * 予測と実装が同じ規約を参照する型 [規約 4]。
     * ここでは<b>向きが逆のビュー行列</b>も渡し、実装が<b>自分で確かめて選ぶ</b>ことを要求する。
     * 決め打ちが残っていれば、どちらか一方で必ず落ちる。
     */
    @Test
    void itDoesNotAssumeWhichWayTheViewMatrixLooks() {
        var projection = mcLikeProjection();
        double camX = 1234.7, camY = 71.3, camZ = -987.2;

        // ⚠ **ビュー行列を回しても規約差にはならない** — positiveZ も一緒に回るので
        // 「前」の向きは変わらない (最初にこれで模そうとして、変異を捕まえられなかった)。
        // 規約差は<b>投影側の eye-Z の向き</b>にある。eye Z を反転した投影を使うと、
        // カメラが見るのは eye +Z 側になり、ワールドの「前」は +positiveZ になる
        var flippedEyeZ = new Matrix4f(projection).scale(1, 1, -1);

        for (int deg = 0; deg < 360; deg += 30) {
            var view = viewLookingAt((float) Math.toRadians(deg));

            for (var proj : new Matrix4f[]{projection, flippedEyeZ}) {
                assertOnScreen("yaw " + deg + " deg ("
                        + (proj == projection ? "eye -Z" : "eye +Z") + ")",
                    placeAndProject(proj, view, camX, camY, camZ, true));
            }
        }
    }

    /**
     * <b>対照 — 固定の方角に置くと画面から外れる。</b>
     *
     * <p>5c-1d の初回はこの配置だった (カメラのセクション + 一定のオフセット)。
     * <b>落ちるべき配置で落ちる</b>ことを示さないと、上の検査は
     * 「何を置いても通る」ものかもしれない。
     */
    @Test
    void aFixedDirectionAnchorGoesOffScreen() {
        var projection = mcLikeProjection();
        double camX = 1234.7, camY = 71.3, camZ = -987.2;

        int offScreen = 0;
        for (int deg = 0; deg < 360; deg += 15) {
            var view = viewLookingAt((float) Math.toRadians(deg));
            // 5c-1d 初回の配置: 方角に依存しない固定オフセット
            int[] anchor = VkHostViewport.align(new int[]{
                VkHostViewport.sectionOf(camX) + 2,
                VkHostViewport.sectionOf(camY),
                VkHostViewport.sectionOf(camZ) + 2,
            }, QUANTUM);
            float[] sub = VkHostViewport.cameraSubPos(camX, camY, camZ, anchor);
            var proj = VkHostViewport.projectionForVulkan(projection, view, sub, CENTRE);
            if (!VkHostViewport.insideFrustum(
                VkHostViewport.clipOfCentre(proj, view, sub, CENTRE))) offScreen++;
        }
        assertTrue(offScreen > 0,
            "a fixed-direction anchor stayed on screen for every yaw; "
                + "then the on-screen check above proves nothing");
    }

    /**
     * <b>5c-1e — 手前の組と奥の組が、本当に違う距離に置かれること。</b>
     *
     * <h2>なぜ {@code w} で比べるのか</h2>
     * {@code w} は<b>カメラからの距離そのもの</b>で、深度の規約 (逆Zか、0..1 か -1..1 か) に
     * <b>一切依存しない</b>。5c-1d で規約の思い込みを 2 回踏んだので、
     * ここは<b>規約に依存しない量</b>だけで主張する [規約 4 / 9]。
     *
     * <p>両方が画面に入ることも要求する — <b>奥が見えないのが配置のせいでは困る</b>。
     * 奥が隠れるのは<b>MC の地形のせい</b>でなければ、遮蔽の対照にならない。
     */
    @Test
    void thePairSitsAtTwoDifferentDistances() {
        // far 平面は描画距離 20 チャンク相当。奥の組より十分遠くしないと、
        // ⚠ 「奥が見えない」が<b>遮蔽ではなく切り落とし</b>になり対照が空虚になる
        var projection = new Matrix4f().set(VkSceneUniform.perspective(
            (float) Math.toRadians(70), 16.0f / 9.0f, 0.05f, 320.0f));
        double camX = 1234.7, camY = 71.3, camZ = -987.2;
        // ⚠ 量子は 1 セクション。2 にすると丸めが距離差を潰す (実際に踏んだ)
        double nearBlocks = 64, farBlocks = 160, lateral = 64;
        int quantum = 1;

        for (int deg = 0; deg < 360; deg += 30) {
            var view = viewLookingAt((float) Math.toRadians(deg));

            int[] nearAnchor = VkHostViewport.anchorInFront(camX, camY, camZ, projection, view,
                nearBlocks, CENTRE, quantum);
            double[] dir = VkHostViewport.verifiedForward(camX, camY, camZ, projection, view,
                nearBlocks, CENTRE, quantum);
            int[] farAnchor = VkHostViewport.anchorAlongDirection(camX, camY, camZ,
                dir[0], dir[1], farBlocks, lateral, CENTRE, quantum);

            float[] nearSub = VkHostViewport.cameraSubPos(camX, camY, camZ, nearAnchor);
            var proj = VkHostViewport.projectionForVulkan(projection, view, nearSub, CENTRE);

            float[] nearClip = VkHostViewport.clipOfCentre(proj, view, nearSub, CENTRE);
            float[] farClip = VkHostViewport.clipOfCentre(proj, view,
                VkHostViewport.cameraSubPos(camX, camY, camZ, farAnchor), CENTRE);

            String at = "yaw " + deg + " deg";
            assertOnScreen(at + " (near copy)", nearClip);
            assertOnScreen(at + " (far copy)", farClip);
            // w = カメラからの距離。奥の組のほうが遠いこと
            assertTrue(farClip[3] > nearClip[3] + 48,
                at + ": the far copy is not meaningfully farther away"
                    + " (near w=" + nearClip[3] + " far w=" + farClip[3] + ")");
        }
    }

    /** セクション座標は<b>負の側でも切り下げ</b>であること (境界で 1 セクションずれる)。 */
    @Test
    void sectionCoordinatesFloorTowardsNegativeInfinity() {
        assertEquals(0, VkHostViewport.sectionOf(0.0));
        assertEquals(0, VkHostViewport.sectionOf(31.9));
        assertEquals(1, VkHostViewport.sectionOf(32.0));
        assertEquals(-1, VkHostViewport.sectionOf(-0.1));
        assertEquals(-1, VkHostViewport.sectionOf(-32.0));
        assertEquals(-2, VkHostViewport.sectionOf(-32.1));
    }

    /**
     * アンカーは量子化の倍数になること。
     * <b>倍数でないと LoD レベルの違うセクションだけが相対位置を崩す</b>
     * ({@code SyntheticTerrain.writePositions} の注意)。
     */
    @Test
    void anchorsAreAlignedToTheQuantum() {
        for (int q : new int[]{1, 2, 4}) {
            for (int v = -20; v <= 20; v++) {
                int[] a = VkHostViewport.align(new int[]{v, v, v}, q);
                assertEquals(0, Math.floorMod(a[0], q),
                    "anchor " + a[0] + " is not a multiple of " + q);
                assertTrue(a[0] <= v, "align must round down");
                assertTrue(v - a[0] < q, "align must not move more than one quantum");
            }
        }
    }

    /**
     * <b>カメラ内位置は投影の平行移動と噛み合っていること。</b>
     *
     * <p>アンカーの原点そのもの (セクション座標 0) は、
     * <b>カメラから見て {@code -cameraSubPos} の位置</b>にいなければならない。
     * ここが噛み合っていないと、地形は<b>カメラ位置の大きさぶん</b>ずれる
     * (ワールド座標が大きいほど遠くへ飛ぶ、という形で現れる)。
     */
    @Test
    void theAnchorOriginSitsWhereTheSubPositionSaysItDoes() {
        var view = new Matrix4f();   // 回転なし = -Z を向く
        double camX = 5000.25, camY = 71.5, camZ = -3000.75;
        int[] anchor = VkHostViewport.anchorInFront(camX, camY, camZ, mcLikeProjection(), view,
            DISTANCE, CENTRE, QUANTUM);
        float[] sub = VkHostViewport.cameraSubPos(camX, camY, camZ, anchor);

        // アンカー原点のワールド座標 + sub = カメラのワールド座標
        assertEquals(camX, ((long) anchor[0] << 5) + sub[0], 0.01,
            "the sub-position must close the gap between the anchor and the camera in X");
        assertEquals(camY, ((long) anchor[1] << 5) + sub[1], 0.01, "... in Y");
        assertEquals(camZ, ((long) anchor[2] << 5) + sub[2], 0.01, "... in Z");
    }

    // ================= 5c-3a: Voxy 自前の投影 =================

    /** クリップ座標。{@code projection * (x,y,z,1)}。 */
    private static float[] clipOf(Matrix4f projection, float x, float y, float z) {
        var v = new org.joml.Vector4f(x, y, z, 1.0f).mul(projection);
        return new float[]{v.x, v.y, v.z, v.w};
    }

    /**
     * round-26 R26-PROJECTION-EQUIVALENCE: {@code VoxyRenderSystem.computeProjectionMat} keeps the
     * extra transforms the frame's projection carries over the raw camera projection and replaces
     * only the raw projection's depth row; its near plane depends on the vanilla distance.
     */
    @Test
    void theGlProjectionKeepsExtraTransformsAndPicksNearLikeGl() {
        var raw = mcLikeProjection();
        // no extra transform: the plain helper
        var plain = VkHostViewport.voxyProjection(raw, 16f, VkHostViewport.VOXY_FAR);
        var same = VkHostViewport.voxyProjectionGl(raw, raw, 16f, VkHostViewport.VOXY_FAR);
        for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++)
            assertEquals(plain.get(c, r), same.get(c, r), 1e-6f * Math.max(1f, Math.abs(plain.get(c, r))));
        // a bobbing-like extra transform: kept, applied before the replaced raw projection
        var extra = new Matrix4f().rotateX(0.03f).rotateZ(-0.02f).translate(0.01f, -0.02f, 0f);
        var base = new Matrix4f(raw).mul(extra);
        var gl = VkHostViewport.voxyProjectionGl(base, raw, 16f, VkHostViewport.VOXY_FAR);
        var want = new Matrix4f(plain).mul(extra);
        for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++)
            assertEquals(want.get(c, r), gl.get(c, r), 1e-5f * Math.max(1f, Math.abs(want.get(c, r))));
        // and that differs from replacing the depth row of the bobbed matrix
        var naive = VkHostViewport.voxyProjection(base, 16f, VkHostViewport.VOXY_FAR);
        float diff = 0;
        for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) diff = Math.max(diff, Math.abs(naive.get(c, r) - gl.get(c, r)));
        assertTrue(diff > 1e-6f, "the extra transform must matter");
        assertEquals(8f, VkHostViewport.voxyNear(32f, false));
        assertEquals(16f, VkHostViewport.voxyNear(48f, false));
        assertEquals(0.1f, VkHostViewport.voxyNear(128f, true));
    }

    private static Matrix4f voxyProjection() {
        return VkHostViewport.voxyProjection(mcLikeProjection(),
            VkHostViewport.VOXY_NEAR, VkHostViewport.VOXY_FAR);
    }

    /**
     * <b>変わるのは深度だけであること。</b>
     *
     * <p>これが再投影ブリットの<b>前提</b>である — 色が同じ画面位置に出るからこそ、
     * 色は素通しで深度だけを画素ごとに変換すればよい。崩れると
     * <b>色と深度が別の場所を指す</b>ことになり、遮蔽が 1 画素単位でずれる。
     *
     * <p>⚠ 主張は<b>式ではなく性質</b>である [規約 4] —
     * {@code voxyProjection} の式を再実行して比べているのではなく、
     * 「xy と w が一致し、z<b>だけ</b>が違う」ことを言っている。
     * {@code assertNotEquals} は<b>「入力をそのまま返す」実装を落とす</b>ための対照である。
     */
    @Test
    void voxysProjectionChangesTheDepthAndNothingElse() {
        var mc = mcLikeProjection();
        var voxy = voxyProjection();
        float[][] points = {
            {0, 0, -20}, {3, -2, -50}, {-40, 25, -300}, {100, 100, -5000}, {-7, 0.5f, -16}
        };
        for (float[] pt : points) {
            float[] a = clipOf(mc, pt[0], pt[1], pt[2]);
            float[] b = clipOf(voxy, pt[0], pt[1], pt[2]);
            String at = "at view point (" + pt[0] + "," + pt[1] + "," + pt[2] + ")";

            assertEquals(a[3], b[3], 1e-4 * Math.abs(a[3]) + 1e-6, at + ": w must not change");
            assertEquals(a[0] / a[3], b[0] / b[3], 1e-5, at + ": x_ndc must not change");
            assertEquals(a[1] / a[3], b[1] / b[3], 1e-5, at + ": y_ndc must not change");

            // ...そして深度は**実際に変わる**こと (恒等写像を落とす対照)
            assertNotEquals(a[2] / a[3], b[2] / b[3], 1e-4,
                at + ": the depth did not change — voxyProjection returned the host's depth row");
        }
    }

    /**
     * <b>near で 1、far で 0</b> であること (逆Z)。
     *
     * <p>期待値は<b>平面の定義そのもの</b>であって {@code voxyProjection} の式ではない [規約 4]。
     */
    @Test
    void voxysPlanesLandOnTheReverseZEndpoints() {
        var voxy = voxyProjection();

        float[] atNear = clipOf(voxy, 0, 0, -VkHostViewport.VOXY_NEAR);
        assertEquals(1.0, atNear[2] / atNear[3], 1e-4,
            "a point on the near plane must sit at NEAR (1.0 under reverse-Z)");

        float[] atFar = clipOf(voxy, 0, 0, -VkHostViewport.VOXY_FAR);
        assertEquals(0.0, atFar[2] / atFar[3], 1e-6,
            "a point on the far plane must sit at FAR (0.0 under reverse-Z)");

        // 間は単調に減る (逆Z: 遠いほど小さい)
        float prev = Float.MAX_VALUE;
        for (float d = VkHostViewport.VOXY_NEAR; d < VkHostViewport.VOXY_FAR; d *= 2) {
            float[] c = clipOf(voxy, 0, 0, -d);
            float z = c[2] / c[3];
            assertTrue(z < prev, "depth must decrease with distance under reverse-Z (at d=" + d + ")");
            prev = z;
        }
    }

    /**
     * <b>これが 5c-3a の存在理由である</b> —
     * MC の far 平面の外にあるものは、<b>MC の投影では深度範囲に入らない</b>が、
     * Voxy の投影なら入る。
     *
     * <p>⚠ 両側を見ること。「Voxy なら入る」だけでは
     * <b>「何でも入る」実装</b>と区別が付かない [規約 11]。
     */
    @Test
    void onlyVoxysProjectionReachesPastMinecraftsFarPlane() {
        var mc = mcLikeProjection();      // far = 192
        var voxy = voxyProjection();      // far = 48000

        // 手前 — **どちらの投影でも入る** (「MC は常に外」を落とす対照)
        assertTrue(VkHostViewport.insideDepth(clipOf(mc, 0, 0, -100)),
            "control: 100 blocks is inside Minecraft's own frustum");
        assertTrue(VkHostViewport.insideDepth(clipOf(voxy, 0, 0, -100)),
            "control: 100 blocks is inside Voxy's frustum too");

        // 遠景 — **Voxy だけが届く**
        assertFalse(VkHostViewport.insideDepth(clipOf(mc, 0, 0, -10000)),
            "10000 blocks must fall outside Minecraft's far plane — otherwise there is"
                + " nothing for 5c-3a to fix");
        assertTrue(VkHostViewport.insideDepth(clipOf(voxy, 0, 0, -10000)),
            "10000 blocks must be inside Voxy's frustum — that is the whole point");
    }

    /**
     * <b>far 平面の距離が読み取れること。</b>
     *
     * <p>期待値は<b>投影を作るときに渡した far そのもの</b>であって、
     * {@code farPlaneDistance} の式ではない [規約 4]。
     *
     * <p>これが要るのは、「MC の far 平面の外」を<b>描画距離に依らず</b>
     * 指定するためである。決め打ちの数字にすると、描画距離を変えたときに
     * <b>対照が黙って成立しなくなる</b>。
     */
    @Test
    void theFarPlaneDistanceCanBeReadBackFromTheProjection() {
        for (float far : new float[]{64f, 192f, 1024f, 48000f}) {
            var p = new Matrix4f().set(VkSceneUniform.perspective(
                (float) Math.toRadians(70), 16.0f / 9.0f, 0.05f, far));
            assertEquals(far, VkHostViewport.farPlaneDistance(p), far * 1e-3,
                "the far plane of a projection built with far=" + far);
        }
        // Voxy 自前の投影でも読めること (別の作り方をした行列)
        var voxy = voxyProjection();
        assertEquals(VkHostViewport.VOXY_FAR,
            VkHostViewport.farPlaneDistance(voxy), VkHostViewport.VOXY_FAR * 1e-3,
            "the far plane of Voxy's own projection");

        // ...そして near は far ではない (「常に引数を返す」実装を落とす対照)
        assertTrue(VkHostViewport.farPlaneDistance(voxy)
            > VkHostViewport.farPlaneDistance(new Matrix4f().set(VkSceneUniform.perspective(
                (float) Math.toRadians(70), 16.0f / 9.0f, 0.05f, 192f))),
            "Voxy's frustum must reach farther than a 192-block one");
    }

    /** 平面の指定が壊れていたら<b>黙って進まない</b>こと。 */
    @Test
    void voxysProjectionRejectsImpossiblePlanes() {
        var mc = mcLikeProjection();
        assertThrows(IllegalArgumentException.class,
            () -> VkHostViewport.voxyProjection(mc, 0.0f, 100.0f));
        assertThrows(IllegalArgumentException.class,
            () -> VkHostViewport.voxyProjection(mc, -1.0f, 100.0f));
        assertThrows(IllegalArgumentException.class,
            () -> VkHostViewport.voxyProjection(mc, 100.0f, 100.0f));
        assertThrows(IllegalArgumentException.class,
            () -> VkHostViewport.voxyProjection(mc, 100.0f, 50.0f));
        // 範囲内は通すこと (弾きすぎの対照)
        assertNotNull(VkHostViewport.voxyProjection(mc, 16.0f, 48000.0f));
    }
}
