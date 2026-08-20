package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.vk.shader.VkShader;

import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER;

/**
 * <b>深度の約束事を 1 箇所に集めたもの。逆Z (reverse-Z)、深度範囲 0..1。</b>
 *
 * <h2>なぜ逆Zなのか</h2>
 * <b>Minecraft 26.2 が逆Zだから</b> [確認済 —
 * {@code com.mojang.blaze3d.pipeline.DepthStencilState.DEFAULT} が
 * {@code CompareOp.GREATER_THAN_OR_EQUAL}]。
 * MC のフレームバッファに合成する以上、Voxy 側も同じ約束に揃えるしかない。
 *
 * <p>GL 側では {@code RenderProperties.isReverseZ()} が同じ役割を持ち、
 * シェーダに {@code USE_REVERSE_Z} を定義する。Vulkan 側の定義もここから出す。
 *
 * <h2>⚠ 非逆Zの経路は持たない</h2>
 * Phase 4 は非逆Zで組んで非逆Zで検証していたが、
 * <b>本番で使う設定ではなかった</b>。両方を設定可能にする案は採らなかった:
 *
 * <ul>
 *   <li>テストが 2 倍になる</li>
 *   <li><b>使わないほうの経路が腐っても気づけない</b></li>
 *   <li>境界で変換する案は<b>「打ち消し合う変換」の温床</b>である
 *       — 5b で上下反転がまさにそれで、端点の一致では捕まらなかった
 *       [docs/phase5b-composite.md §6]</li>
 * </ul>
 *
 * <p>したがって<b>切り替えフラグを置いていない</b>。定数はすべて逆Zの値である。
 * 一貫性は {@code VkDepthConventionTest} が常設で見張る。
 *
 * <h2>値の対応表</h2>
 * <table>
 *   <tr><th></th><th>逆Z (ここ)</th><th>非逆Z (捨てた)</th></tr>
 *   <tr><td>{@link #NEAR} 最も手前</td><td>1.0</td><td>0.0</td></tr>
 *   <tr><td>{@link #FAR} 最も奥</td><td>0.0</td><td>1.0</td></tr>
 *   <tr><td>{@link #CLEAR} 深度クリア値</td><td>0.0 (= FAR)</td><td>1.0</td></tr>
 *   <tr><td>{@link #COMPARE_OP}</td><td>{@code GREATER_OR_EQUAL}</td><td>{@code LESS_OR_EQUAL}</td></tr>
 *   <tr><td>{@link #BOUND_NEUTRAL} 深度境界の中立値</td><td>1.0</td><td>0.0</td></tr>
 * </table>
 */
public final class VkDepth {
    private VkDepth() {}

    /** 最も手前の深度値。 */
    public static final float NEAR = 1.0f;

    /** 最も奥の深度値。深度クリアはこれ。 */
    public static final float FAR = 0.0f;

    /** 深度アタッチメントのクリア値。「何も描かれていない」= 最も奥。 */
    public static final float CLEAR = FAR;

    /**
     * 通常の描画で使う深度比較。
     * GL 側 {@code RenderProperties.closerEqualDepthCompare()} の逆Z側 ({@code GL_GEQUAL}) に対応。
     */
    public static final int COMPARE_OP = VK_COMPARE_OP_GREATER_OR_EQUAL;

    /** 等号を含まない版。GL 側 {@code closerDepthCompare()} 相当。 */
    public static final int COMPARE_OP_STRICT = VK_COMPARE_OP_GREATER;

    /**
     * {@code depthTex} (深度境界バッファ) の<b>中立値</b> — 「誰も落とさない」状態。
     *
     * <p>{@code quads.frag} は {@code DEPTH_SCALAR_COMPARE(gl_FragCoord.z, bound)} が真なら
     * discard する。逆Zでは {@code (a > b)} なので、<b>bound を最大値 (NEAR) にすると
     * 常に偽</b>になり誰も落ちない。
     *
     * <p><b>⚠ 非逆Zとは値が反転する。</b> 非逆Zでは {@code (a < b)} なので中立値は 0.0 だった。
     * ここを間違えると<b>全部落ちる / 何も落ちない</b>のどちらかになり、
     * 「地形が一切出ない」という形で出る。
     */
    public static final float BOUND_NEUTRAL = NEAR;

    /**
     * <b>GL 側で使う「手前または同じなら通す」比較関数</b> ({@code GL_GEQUAL})。
     *
     * <p>interop の合成は GL のパスなので、Vulkan の {@link #COMPARE_OP} ではなく
     * GL の定数が要る。<b>2 箇所に規約を書かないため</b>ここから出す —
     * 逆Zをやめるなら両方が同時に変わらなければならない [規約 6]。
     *
     * <p>GL 側 {@code RenderProperties.closerEqualDepthCompare()} の逆Z側と同じ値である。
     * 一致は {@code VkDepthConventionTest} が常設で見張る。
     */
    public static final int GL_CLOSER_EQUAL_COMPARE = org.lwjgl.opengl.GL11C.GL_GEQUAL;

    /**
     * シェーダに深度の約束事を定義する。<b>深度に触る全てのシェーダで呼ぶこと。</b>
     *
     * <p>{@code util/depthutils.glsl} がこの 2 つを見て
     * {@code NEAR} / {@code FAR} / {@code CLOSER_SIGN} /
     * {@code DEPTH_SCALAR_COMPARE} を切り替える。
     * <b>片方のシェーダだけ定義し忘れると符号が逆になる</b> —
     * 例えば {@code cull_raster.vert} の {@code CLOSER_SIGN} 補正が
     * 「手前に寄せる」つもりで奥へ押しやることになる。
     */
    public static <T extends VkShader> VkShader.Builder<T> defines(VkShader.Builder<T> builder) {
        return builder
            // 深度は 0..1。Vulkan のクリップ空間に合わせる (GL 側は RenderProperties.isZero2One)
            .define("USE_ZERO_ONE_DEPTH")
            // 逆Z。MC 26.2 に合わせる (GL 側は RenderProperties.isReverseZ)
            .define("USE_REVERSE_Z");
    }
}
