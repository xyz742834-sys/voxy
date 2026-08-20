package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryUtil;

/**
 * {@code SceneUniform} ブロックの書き込みと、それに渡す行列の生成。
 *
 * <h2>ブロックの並び [確認済 — `lod/gl46/bindings.glsl`]</h2>
 * <pre>
 * layout(binding = 0, std140) uniform SceneUniform {
 *     mat4  MVP;             // offset  0, 64 バイト (列優先)
 *     ivec3 baseSectionPos;  // offset 64
 *     uint  frameId;         // offset 76
 *     vec3  cameraSubPos;    // offset 80
 * };
 * </pre>
 * GL 側 {@code MDICSectionRenderer.uploadUniformBuffer} と同じ順に詰める。
 *
 * <h2>なぜ行列ライブラリを使わないか</h2>
 * <b>投影の約束事そのものが検証対象だから。</b> Stage 1 の出力は Stage 2b の基準画像に
 * なるので、クリップ空間の向きと深度範囲がどう決まっているかがソース上で読めるほうがよい。
 * 16 要素の積を数行書くだけで済むため、依存を増やす利点もない。
 *
 * <h2>GL と Vulkan で違うところ [確認済 — 仕様ベース]</h2>
 * <table>
 *   <tr><th></th><th>GL 既定</th><th>Vulkan</th></tr>
 *   <tr><td>クリップ空間 Y</td><td>上が +1</td><td><b>下が +1</b></td></tr>
 *   <tr><td>深度範囲</td><td>-1..1</td><td><b>0..1</b></td></tr>
 * </table>
 * {@link #perspective} はどちらも Vulkan 側に合わせる。深度が 0..1 になるので
 * シェーダの define は {@link VkDepth#defines} が出す (逆Z + 0..1)
 * (GL 側では {@code RenderProperties.isZero2One} が担う値)。
 *
 * <p>Minecraft 本体と合成する Phase 5c では、この行列は Minecraft 側から来る
 * {@code viewport.MVP} に置き換わる。<b>その行列は GL 規約なので、
 * ここで作る行列も GL 規約に揃えてある</b> ({@link #perspective} 参照) —
 * 揃えておかないと「テストが本番でない設定を検証している」状態になる。
 */
public final class VkSceneUniform {
    /** {@code SceneUniform} が占めるバイト数 (std140)。 */
    public static final int SIZE = 92;

    private VkSceneUniform() {}

    /**
     * ユニフォームバッファへ書く。
     *
     * @param mvp            列優先 16 要素
     * @param baseSectionPos セクション座標の原点。頂点位置はここからの相対で組まれる
     * @param frameId        {@code cmdgen} の可視判定用。地形描画自体は読まない [確認済]
     * @param cameraSubPos   {@code cmdgen} の距離計算用。地形描画自体は読まない [確認済]
     */
    public static void write(VkBuffer target, float[] mvp,
                             int[] baseSectionPos, int frameId, float[] cameraSubPos) {
        if (mvp.length != 16) throw new IllegalArgumentException("mvp must have 16 elements");
        if (target.size() < SIZE) {
            throw new IllegalArgumentException("uniform buffer too small: need " + SIZE
                + " got " + target.size());
        }
        long a = target.addr();
        for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(a + (long) i * 4, mvp[i]);
        MemoryUtil.memPutInt(a + 64, baseSectionPos[0]);
        MemoryUtil.memPutInt(a + 68, baseSectionPos[1]);
        MemoryUtil.memPutInt(a + 72, baseSectionPos[2]);
        MemoryUtil.memPutInt(a + 76, frameId);
        MemoryUtil.memPutFloat(a + 80, cameraSubPos[0]);
        MemoryUtil.memPutFloat(a + 84, cameraSubPos[1]);
        MemoryUtil.memPutFloat(a + 88, cameraSubPos[2]);
    }

    // ---------------- matrices (column major, as GLSL mat4 expects) ----------------

    /**
     * 右手系・<b>逆Z</b>・深度 0..1・<b>GL 規約の Y</b> の透視投影。
     *
     * <p><b>⚠ Vulkan の Y 反転 ({@code m[5] = -f}) を意図的に入れていない。</b>
     * Voxy の Vulkan 経路は<b>GL 規約の Y をそのまま通す</b>と決めた
     * [docs/phase5c-y-orientation.md]。理由は<b>経路上の Y 反転を 0 個にする</b>ため:
     *
     * <ul>
     *   <li>本番の行列は MC 由来 ({@code viewport.MVP}) で GL 規約である。
     *       ここで反転を入れると、interop の色と深度の<b>2 経路それぞれ</b>に
     *       打ち消しの反転が要る (計 3 箇所)</li>
     *   <li>反転が 1 つも無ければ<b>「打ち消し合う変換」が原理的に起きない</b>。
     *       5b で機械的検査 10 項目が全て通ってしまったのは、
     *       反転が 2 つあって打ち消し合ったためである [docs/phase5b-composite.md §6]</li>
     * </ul>
     *
     * <p>結果として <b>framebuffer の 0 行目は絵の下端</b>になる (GL と同じ)。
     * PNG に書き出すときだけ上下を入れ替える (表示のための反転であって経路上の変換ではない)。
     *
     * <p><b>逆Zなので near が 1.0、far が 0.0 に写る</b> ({@link VkDepth})。
     * 逆Zを採る理由は<b>MC 26.2 が逆Zだから</b>という一点である。
     */
    public static float[] perspective(float fovyRad, float aspect, float near, float far) {
        float f = (float) (1.0 / Math.tan(fovyRad * 0.5));
        float[] m = new float[16];
        m[0]  = f / aspect;
        m[5]  = f;                         // GL 規約の Y。Vulkan の反転は入れない (上記)
        m[10] = near / (far - near);       // near -> 1, far -> 0 (逆Z)
        m[11] = -1.0f;
        m[14] = (far * near) / (far - near);
        return m;
    }

    /** 右手系の視点行列。 */
    public static float[] lookAt(float[] eye, float[] centre, float[] up) {
        float[] fwd = normalise(sub(centre, eye));
        float[] side = normalise(cross(fwd, up));
        float[] u = cross(side, fwd);

        float[] m = new float[16];
        m[0] = side[0]; m[4] = side[1]; m[8]  = side[2];  m[12] = -dot(side, eye);
        m[1] = u[0];    m[5] = u[1];    m[9]  = u[2];     m[13] = -dot(u, eye);
        m[2] = -fwd[0]; m[6] = -fwd[1]; m[10] = -fwd[2];  m[14] = dot(fwd, eye);
        m[15] = 1.0f;
        return m;
    }

    /** {@code a * b} (列優先)。 */
    public static float[] mul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[k * 4 + row] * b[c * 4 + k];
                r[c * 4 + row] = s;
            }
        }
        return r;
    }

    /** 点をこの行列で変換し、透視除算した結果 (x, y, z) を返す。テストの当たり判定用。 */
    public static float[] project(float[] m, float x, float y, float z) {
        float cx = m[0] * x + m[4] * y + m[8]  * z + m[12];
        float cy = m[1] * x + m[5] * y + m[9]  * z + m[13];
        float cz = m[2] * x + m[6] * y + m[10] * z + m[14];
        float cw = m[3] * x + m[7] * y + m[11] * z + m[15];
        return new float[]{cx / cw, cy / cw, cz / cw};
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        };
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] normalise(float[] v) {
        float l = (float) Math.sqrt(dot(v, v));
        if (l == 0) throw new IllegalArgumentException("cannot normalise a zero vector");
        return new float[]{v[0] / l, v[1] / l, v[2] / l};
    }
}
