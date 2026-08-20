package me.cortex.voxy.client.core.vk;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * <b>Minecraft のカメラから Voxy のシーンユニフォームを組み立てる計算</b> (Phase 5c-1d)。
 *
 * <h2>なぜ独立したクラスにするか</h2>
 * この計算は<b>ホストの中でしか走らない</b>のに、間違えても
 * 「画面に何も出ない」という<b>区別の付きにくい形</b>でしか現れない。
 * 実際 5c-1d で「橙一色 (地形が画面に無い)」を踏み、
 * <b>置き場所の問題か式の問題か</b>が切り分けられなかった。
 *
 * <p>ここに切り出しておけば<b>素の JVM で数値を確かめられる</b>
 * ({@code VkHostViewportTest})。MC を起動しないと分からない範囲を狭めるのが目的である。
 *
 * <h2>組み立て [確認済 — {@code MDICSectionRenderer.uploadUniformBuffer}]</h2>
 * <pre>
 * MVP            = projection * modelView * translate(-(カメラ - アンカー*32))
 * baseSectionPos = アンカー
 * cameraSubPos   = カメラ - アンカー*32
 * </pre>
 *
 * <p>頂点は {@code quad_util.glsl} で
 * {@code (getLoDPosition(sPos) << lodLevel) - baseSectionPos} を {@code <<5} した
 * <b>アンカー原点のブロック座標</b>になる。MC の {@code modelView} は
 * <b>カメラ相対</b>なので、その差 (カメラ - アンカー*32) を平行移動で消す。
 */
public final class VkHostViewport {
    private VkHostViewport() {}

    /** 1 セクション = 32 ブロック。{@code Viewport.update} の {@code >>5} と同じ。 */
    public static final int SECTION_SHIFT = 5;
    public static final int SECTION_BLOCKS = 1 << SECTION_SHIFT;

    /** ワールド座標 → セクション座標。負の側でも切り下げになるよう算術シフトを使う。 */
    public static int sectionOf(double world) {
        return (int) Math.floor(world) >> SECTION_SHIFT;
    }

    /**
     * カメラが<b>向いている方向</b>の単位ベクトル (ワールド空間)。
     *
     * <p>ビュー行列は世界をカメラ空間へ写す。カメラは -Z を向いているので、
     * ワールド空間の視線は<b>ビュー行列の +Z 軸の逆</b>である。
     */
    public static Vector3f forward(Matrix4fc modelView, Vector3f dest) {
        modelView.positiveZ(dest);
        return dest.negate();
    }

    /**
     * 合成地形を置くアンカー (セクション座標)。<b>カメラの正面</b>に取る。
     *
     * <h2>なぜ正面なのか</h2>
     * 固定のワールド位置に置くと<b>プレイヤーがそちらを向いていなければ何も見えず</b>、
     * 「経路が壊れている」と区別が付かない。5c-1d で実際にこれを踏んだ。
     * <b>常に視界に入る場所</b>に置けば、見えないこと自体が不具合の証拠になる。
     *
     * <h2>⚠⚠ 視線の向きを<b>推測しない</b> — 投影して確かめる</h2>
     * ビュー行列のどちらが「前」かは<b>規約</b>であり、ホストによって違いうる。
     * 実際 5c-1d で <b>{@code -positiveZ} を前と決め打ちして外した</b> —
     * 地形はカメラの真後ろに置かれ、画面には 1 画素も出なかった。
     *
     * <p>さらに悪いことに、<b>それを検査した JUnit も同じ決め打ちを共有していた</b>ので
     * 素通りした (予測と実装が同じ規約を参照する型 [規約 4])。
     *
     * <p>したがってここでは<b>両方の向きを試し、実際に投影して視錐台に入るほうを選ぶ</b>。
     * 判定に使うのは<b>投影という外部の事実</b>だけで、向きの規約に依存しない。
     *
     * <p>Y はカメラのセクションのまま — <b>地面をまたがせて、
     * 一部が MC の地形に隠れる状況</b>を作るためである (5c-1e の前哨戦)。
     *
     * @param projection     MC の投影行列。<b>候補を検算するのに要る</b>
     * @param distanceBlocks カメラから地形の中心までの距離 (ブロック)
     * @param centreSections データセットの中心をアンカーからどれだけずらすか
     * @param alignQuantum   {@code 1 << maxLevel}。アンカーはこの倍数に丸める
     */
    public static int[] anchorInFront(double cameraX, double cameraY, double cameraZ,
                                      Matrix4fc projection, Matrix4fc modelView,
                                      double distanceBlocks,
                                      int[] centreSections, int alignQuantum) {
        Vector3f axis = new Vector3f();
        modelView.positiveZ(axis);

        double[] dir = verifiedForward(cameraX, cameraY, cameraZ, projection, modelView,
            distanceBlocks, centreSections, alignQuantum);
        return anchorAlong(cameraX, cameraY, cameraZ, dir[0], dir[1],
            distanceBlocks, centreSections, alignQuantum);
    }

    /**
     * <b>カメラの前を向く水平方向</b>を、投影で確かめて返す。
     *
     * <p>向きの規約 (どちらが「前」か) はホストによって違う。5c-1d で
     * {@code -positiveZ} と決め打ちして外したので、<b>両方試して確かめる</b> [規約 9]。
     *
     * <p>⚠ 判定は <b>{@code w > 0} だけ</b>。深度の範囲を混ぜると、
     * 投影の深度規約が違うときに<b>どちらの向きも落ちて選べなくなる</b> [規約 10]。
     *
     * <p>⚠ <b>アンカーから逆算してはならない。</b> アンカーは量子化されているので、
     * 近い距離では<b>方向が最大数十度ずれる</b>。5c-1e で奥の組を置くときに実際に踏んだ
     * (横に 96 ブロックのつもりが 200 ブロック以上ずれた)。
     *
     * @return 正規化した水平方向 {@code (dx, dz)}
     */
    public static double[] verifiedForward(double cameraX, double cameraY, double cameraZ,
                                           Matrix4fc projection, Matrix4fc modelView,
                                           double probeDistanceBlocks,
                                           int[] centreSections, int alignQuantum) {
        Vector3f axis = new Vector3f();
        modelView.positiveZ(axis);
        double[] fallback = null;
        for (float sign : new float[]{-1.0f, 1.0f}) {
            double dx = axis.x * sign, dz = axis.z * sign;
            double len = Math.sqrt(dx * dx + dz * dz);
            double[] dir = len > 1e-4 ? new double[]{dx / len, dz / len} : new double[]{0, 1};
            if (fallback == null) fallback = dir;
            int[] candidate = anchorAlong(cameraX, cameraY, cameraZ, dir[0], dir[1],
                probeDistanceBlocks, centreSections, alignQuantum);
            float[] sub = cameraSubPos(cameraX, cameraY, cameraZ, candidate);
            if (isInFront(clipOfCentre(projection, modelView, sub, centreSections))) return dir;
        }
        // どちらも前に来ない (真上や真下を向いている等)
        return fallback;
    }

    /**
     * 指定した水平方向へ {@code distanceBlocks}、その直交方向へ {@code lateralBlocks}
     * ずらした先のアンカー (Phase 5c-1e)。
     *
     * <p><b>横にずらすのは、手前の組が奥の組を隠さないようにするため</b>である。
     * 同じ視線上に重ねると、奥が見えないのが<b>MC の地形のせいか手前の組のせいか</b>
     * 区別できなくなる。
     *
     * <p>⚠ {@code dirX/dirZ} は {@link #verifiedForward} の結果を使うこと。
     * <b>アンカーから逆算した方向は量子化のぶんずれている</b>。
     */
    public static int[] anchorAlongDirection(double cameraX, double cameraY, double cameraZ,
                                             double dirX, double dirZ,
                                             double distanceBlocks, double lateralBlocks,
                                             int[] centreSections, int alignQuantum) {
        double rx = dirZ, rz = -dirX;   // 直交方向
        int[] anchor = {
            sectionOf(cameraX + dirX * distanceBlocks + rx * lateralBlocks) - centreSections[0],
            sectionOf(cameraY) - centreSections[1],
            sectionOf(cameraZ + dirZ * distanceBlocks + rz * lateralBlocks) - centreSections[2],
        };
        return alignNearest(anchor, alignQuantum);
    }

    /** 水平方向 {@code (dx, dz)} へ {@code distanceBlocks} 進んだ先のアンカー。 */
    private static int[] anchorAlong(double cameraX, double cameraY, double cameraZ,
                                     double dirX, double dirZ, double distanceBlocks,
                                     int[] centreSections, int alignQuantum) {
        // Y は使わない。見上げ / 見下ろしで地形が空や地中へ飛ばないようにする
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        double dx = len > 1e-4 ? (dirX / len) * distanceBlocks : 0;
        double dz = len > 1e-4 ? (dirZ / len) * distanceBlocks : distanceBlocks;

        int[] anchor = {
            sectionOf(cameraX + dx) - centreSections[0],
            sectionOf(cameraY) - centreSections[1],
            sectionOf(cameraZ + dz) - centreSections[2],
        };
        // ⚠ **最近傍に丸める**。切り下げだと最大 quantum セクションずれ、
        // それが距離と同程度になると<b>方角によって視界から外れる</b>
        // (実際に踏んだ: 距離 64 ブロック・量子 64 ブロックでヨー 45° のとき画面外)
        return alignNearest(anchor, alignQuantum);
    }

    /** データセット中心のクリップ座標 {@code (x, y, z, w)}。 */
    public static float[] clipOfCentre(Matrix4fc projection, Matrix4fc modelView,
                                       float[] cameraSubPos, int[] centreSections) {
        float[] m = mvp(projection, modelView, cameraSubPos);
        float x = centreSections[0] << SECTION_SHIFT;
        float y = centreSections[1] << SECTION_SHIFT;
        float z = centreSections[2] << SECTION_SHIFT;
        return new float[]{
            m[0] * x + m[4] * y + m[8]  * z + m[12],
            m[1] * x + m[5] * y + m[9]  * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14],
            m[3] * x + m[7] * y + m[11] * z + m[15],
        };
    }

    /**
     * <b>カメラの前にあるか</b> ({@code w > 0})。
     *
     * <p>向きを選ぶときはこれだけを見る。<b>深度の範囲は別の問題</b>なので
     * 混ぜてはならない — 混ぜると規約が違うときに<b>どちらの向きも選べなくなる</b>。
     */
    public static boolean isInFront(float[] clip) {
        return clip[3] > 0;
    }

    /**
     * <b>投影の深度が 0..1 になるようにする</b> (Vulkan のクリップ空間は常に 0..1)。
     *
     * <h2>⚠ ホストの深度規約を推測しない [規約 9]</h2>
     * Minecraft の投影行列は<b>逆Z・-1..1</b> だった [確認済 — 5c-1d の実機ログ。
     * 前方 120 ブロックの点が {@code z/w = -0.999}]。これをそのまま Vulkan に渡すと
     * <b>z &lt; 0 の断片が全部クリップされ、1 画素も描かれない</b>。
     *
     * <p>GL 側 {@code VoxyRenderSystem.computeProjectionMat} が m22/m32 を
     * 書き換えているのは同じ理由である。
     *
     * <p>ここでは<b>変換した版としない版の両方を検算し、
     * 実際に深度が {@code (0,1)} に入るほうを返す</b>。
     * ホストが将来 0..1 で渡してきても壊れない。
     *
     * @param sub    {@link #cameraSubPos} の結果
     * @param centre 検算に使う点 (カメラの前にあることが分かっているデータセットの中心)
     * @return Vulkan に渡してよい投影行列
     */
    public static Matrix4f projectionForVulkan(Matrix4fc projection, Matrix4fc modelView,
                                               float[] sub, int[] centre) {
        if (insideDepth(clipOfCentre(projection, modelView, sub, centre))) {
            return new Matrix4f(projection);   // ホストが既に 0..1 で渡してきている
        }
        return halveDepthRange(projection);
    }

    /**
     * 変換後も深度が {@code (0,1)} に入らなかったか。
     * <b>入らなければ、こちらの想定にない規約である</b> — 黙って進まず記録する。
     */
    public static boolean depthStillOutOfRange(Matrix4fc chosen, Matrix4fc modelView,
                                               float[] sub, int[] centre) {
        return !insideDepth(clipOfCentre(chosen, modelView, sub, centre));
    }

    /** Voxy の自前投影の near 平面 (ブロック)。 [確認済 — VoxyRenderSystem.computeProjectionMat] */
    public static final float VOXY_NEAR = 16.0f;

    /** Voxy の自前投影の far 平面 (ブロック)。{@code 16*3000}。 */
    public static final float VOXY_FAR = 16.0f * 3000.0f;

    /**
     * <b>Voxy 自前の投影</b> — MC の投影の<b>深度の行だけ</b>を差し替える (Phase 5c-3a)。
     *
     * <h2>⚠ 変わるのは深度だけである</h2>
     * {@code VoxyRenderSystem.computeProjectionMat} は MC の投影行列の
     * <b>m22 / m32 だけ</b>を書き換える [確認済]。行 0・1・3 は触らない。したがって
     * <b>{@code x_ndc} と {@code y_ndc} は MC のものと完全に一致する</b> —
     * 色は同じ画面位置に出るので、<b>色は素通し、深度だけを画素ごとに変換すればよい</b>。
     * これが再投影ブリットが成立する根拠である。
     *
     * <h2>逆Z・0..1 の式</h2>
     * 逆Zでは near と far を入れ替える [確認済 — {@code computeProjectionMat} の
     * {@code if (properties.isReverseZ())}]。0..1 側の式は:
     * <pre>
     * m22 = far'/(near'-far')          near' = far, far' = near (入れ替え後)
     * m32 = far'*near'/(near'-far')
     * z_ndc = -m22 + m32/d
     * </pre>
     * {@code d = near} で 1 (NEAR)、{@code d = far} で 0 (FAR) になる。
     *
     * <p>⚠ <b>{@code halveDepthRange} はこの経路では要らない</b> —
     * MC の投影が {@code -1..1} なのは深度の行がそうだからで、
     * ここではその行を<b>自分で 0..1 の式で書く</b>。
     *
     * @param mcProjection MC が渡してきた投影行列。<b>深度の行以外はそのまま使う</b>
     * @param near         手前の平面 (ブロック)。{@link #VOXY_NEAR}
     * @param far          奥の平面 (ブロック)。{@link #VOXY_FAR}
     */
    public static Matrix4f voxyProjection(Matrix4fc mcProjection, float near, float far) {
        if (!(near > 0) || !(far > near)) {
            throw new IllegalArgumentException("need 0 < near < far, got near=" + near + " far=" + far);
        }
        // 逆Z: near と far を入れ替える
        float n = far, f = near;
        return new Matrix4f(mcProjection)
            .m22(f / (n - f))
            .m32(f * n / (n - f));
    }

    /**
     * <b>投影の far 平面までの距離</b> (ブロック)。
     *
     * <p>逆Z・0..1 の投影では、深度は {@code z_ndc = -m22 + m32/d} なので
     * {@code z_ndc = 0} (= FAR) になる距離は <b>{@code m32/m22}</b> である。
     *
     * <p>⚠ <b>0..1 に直した行列を渡すこと</b> ({@link #projectionForVulkan} の結果)。
     * MC が渡してくる {@code -1..1} のままでは値が違う。
     *
     * <p>これを使うと「MC の far 平面の外」を<b>描画距離に依らず</b>指定できる。
     * 決め打ちの数字にすると、描画距離を変えたときに<b>対照が黙って成立しなくなる</b>。
     *
     * @return far 平面までの距離。無限遠投影なら {@link Double#POSITIVE_INFINITY}
     */
    public static double farPlaneDistance(Matrix4fc zeroToOneReverseZ) {
        float m22 = zeroToOneReverseZ.m22();
        float m32 = zeroToOneReverseZ.m32();
        if (Math.abs(m22) < 1e-12f) return Double.POSITIVE_INFINITY;
        return Math.abs((double) m32 / m22);
    }

    /**
     * {@code -1..1} の深度を {@code 0..1} に写す。
     * クリップ空間で {@code z' = (z + w) / 2} なので、<b>行 2 に行 3 を足して半分</b>にする。
     */
    public static Matrix4f halveDepthRange(Matrix4fc p) {
        return new Matrix4f(p)
            .m02((p.m02() + p.m03()) * 0.5f)
            .m12((p.m12() + p.m13()) * 0.5f)
            .m22((p.m22() + p.m23()) * 0.5f)
            .m32((p.m32() + p.m33()) * 0.5f);
    }

    /** 深度だけを見る ({@code w > 0} かつ {@code z/w} が {@code (0,1)})。 */
    public static boolean insideDepth(float[] clip) {
        if (!(clip[3] > 0)) return false;
        float z = clip[2] / clip[3];
        return z > 0 && z < 1;
    }

    /**
     * クリップ座標が視錐台の中にあるか。
     *
     * <p><b>{@code w > 0} を必ず見ること</b> — カメラの後ろの点は {@code w < 0} になり、
     * 透視除算で符号が反転するので<b>画面内に見えてしまう</b>。
     * 5c-1d ではこれで「NDC の xy は範囲内なのに 1 画素も描かれない」状態になった。
     */
    public static boolean insideFrustum(float[] clip) {
        if (!(clip[3] > 0)) return false;
        float x = clip[0] / clip[3], y = clip[1] / clip[3], z = clip[2] / clip[3];
        return Math.abs(x) <= 1 && Math.abs(y) <= 1 && z > 0 && z < 1;
    }

    /**
     * アンカーを {@code quantum} の倍数に<b>最近傍で</b>丸める。
     * ずれが半分で済むので、{@link #anchorInFront} の狙いから外れにくい。
     */
    public static int[] alignNearest(int[] anchor, int quantum) {
        return new int[]{
            Math.floorDiv(anchor[0] + quantum / 2, quantum) * quantum,
            Math.floorDiv(anchor[1] + quantum / 2, quantum) * quantum,
            Math.floorDiv(anchor[2] + quantum / 2, quantum) * quantum,
        };
    }

    /**
     * アンカーを {@code quantum} の倍数に切り下げる。
     *
     * <p>位置は<b>そのセクションの LoD 空間</b>で足されるので
     * ({@code anchor >> level})、倍数でないと切り捨てで
     * <b>レベルの違うセクションだけが相対位置を崩す</b>。
     */
    public static int[] align(int[] anchor, int quantum) {
        return new int[]{
            Math.floorDiv(anchor[0], quantum) * quantum,
            Math.floorDiv(anchor[1], quantum) * quantum,
            Math.floorDiv(anchor[2], quantum) * quantum,
        };
    }

    /** カメラのセクション内位置。{@code Viewport.innerTranslation} の一般形。 */
    public static float[] cameraSubPos(double cameraX, double cameraY, double cameraZ,
                                       int[] anchor) {
        return new float[]{
            (float) (cameraX - ((long) anchor[0] << SECTION_SHIFT)),
            (float) (cameraY - ((long) anchor[1] << SECTION_SHIFT)),
            (float) (cameraZ - ((long) anchor[2] << SECTION_SHIFT)),
        };
    }

    /**
     * シェーダへ渡す MVP (列優先 16 要素)。
     *
     * <p>{@code projection * modelView} に<b>アンカーぶんの平行移動</b>を掛ける。
     * 平行移動は<b>後ろから</b>掛ける (頂点に最初に効く) 必要がある —
     * JOML の {@code translate} は右から掛けるのでこれで正しい。
     */
    public static float[] mvp(Matrix4fc projection, Matrix4fc modelView, float[] cameraSubPos) {
        var m = new Matrix4f(projection).mul(modelView)
            .translate(-cameraSubPos[0], -cameraSubPos[1], -cameraSubPos[2]);
        float[] out = new float[16];
        m.get(out);   // JOML も VkSceneUniform も列優先
        return out;
    }
}
