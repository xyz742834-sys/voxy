package me.cortex.voxy.vk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5b の前提調査 — <b>{@code initDepthStencil} のステンシルマスクは
 * 同じパスが書く深度で置き換えられるか。</b>
 *
 * <p>IOSurface に stencil aspect は無いので、ステンシルは interop で運べない
 * [確認済 — Phase 0 §8.2]。運べないものが本当に要るのかを先に確かめる
 * (docs/phase5-proposal.md §3.1 の質問 1)。
 *
 * <h2>調べている対象 [確認済 — 実物を読んだ]</h2>
 * {@code AbstractRenderPipeline.initDepthStencil} + {@code post/setup_stencil_depth.frag}
 * が Voxy 自前のフレームバッファ (d24s8) に作る状態:
 *
 * <pre>
 * glClearNamedFramebufferfi(fb, GL_DEPTH_STENCIL, 0, clearDepth=FAR, stencil=1)
 * glDepthFunc(GL_ALWAYS); glStencilOp(KEEP, KEEP, REPLACE); glStencilFunc(GL_ALWAYS, ref=0, 0xFF)
 * フルスクリーンパス:  gl_FragDepth = NEAR;  if (mcDepth == FAR) discard;
 * → 以降 glDepthFunc(closerEqual); glStencilFunc(GL_EQUAL, 1, 0xFF)
 * </pre>
 *
 * つまり 1 パスで<b>2 つのマスクを同時に作っている</b>:
 *
 * <table>
 *   <tr><th></th><th>ステンシル</th><th>深度</th></tr>
 *   <tr><td>MC 地形あり</td><td>0 (描かない)</td><td>NEAR</td></tr>
 *   <tr><td>MC 地形なし</td><td>1 (描く)</td><td>FAR (クリア値のまま)</td></tr>
 * </table>
 *
 * <p><b>NEAR は最も手前の深度値である。</b>そこに closerEqual (LEQUAL / GEQUAL) で
 * 比較する以上、MC 地形がある画素では<b>ちょうど NEAR の断片しか通らない</b>。
 * ステンシルは同じ画素を無条件に落とす。
 * → <b>両者は「ちょうど NEAR」を除いて同じ集合を落とすはずである</b>、というのが検証する命題。
 *
 * <h2>この検査を空虚に満たす方法と、その塞ぎ方</h2>
 * <ol>
 *   <li><b>両者を同じ式から計算してしまう</b> → 一致は自明になる。
 *       {@link #stencilPasses} はステンシル値だけ、{@link #depthPasses} は深度値だけを見る、
 *       独立した 2 つの関数として書いている</li>
 *   <li><b>比べている深度値の集合が薄い</b> → たまたま一致する。
 *       NEAR / FAR ちょうどと、その両隣 (ulp) を必ず含める</li>
 *   <li><b>そもそも不一致を検出できない検査になっている</b> →
 *       {@link #detectsAStrictDepthCompare} と {@link #detectsWritingTheMcDepthInstead} が
 *       <b>わざと壊した 2 種類の実装で不一致が出ること</b>を確かめる。
 *       これが無いと「一致した」に意味が無い</li>
 * </ol>
 */
public class StencilMaskEquivalenceTest {

    /**
     * <b>このモデルの逆Z側が、Vulkan 経路が実際に使う値と一致していること。</b>
     *
     * <p>MC 26.2 は逆Zなので [確認済 — {@code DepthStencilState.DEFAULT}]、
     * 本番で効くのは<b>逆Z側の分岐だけ</b>である。
     * ここが {@link me.cortex.voxy.client.core.vk.VkDepth} とずれると、
     * 「モデルの上では等価だが実装は別の規約で動いている」という
     * <b>最も気づきにくい形</b>で食い違う。
     *
     * <p>非逆Z側も残してあるのは、これが<b>GL 側の挙動のモデル</b>であって
     * Voxy の実行経路ではないため。両方に対して等価性が成り立つことを示すほうが、
     * 命題として強い。
     */
    @Test
    void theReverseZBranchMatchesTheVulkanPath() {
        assertEquals(me.cortex.voxy.client.core.vk.VkDepth.NEAR, near(true),
            "the model's reverse-Z NEAR must be the value the Vulkan path uses");
        assertEquals(me.cortex.voxy.client.core.vk.VkDepth.FAR, far(true),
            "the model's reverse-Z FAR must be the value the Vulkan path uses");
        assertEquals(me.cortex.voxy.client.core.vk.VkDepth.CLEAR, far(true),
            "clearing depth means 'furthest away'");
    }

    // ---------------- 対象のモデル ----------------

    /** {@code initDepthStencil} 後の、ある画素の状態。 */
    private record PixelState(float depth, int stencil) {}

    private static float near(boolean reverseZ) { return reverseZ ? 1.0f : 0.0f; }
    private static float far(boolean reverseZ) { return reverseZ ? 0.0f : 1.0f; }

    /**
     * {@code initDepthStencil} が 1 画素に残す状態。
     *
     * @param mcHasTerrain MC が何か描いた画素か (= MC 深度が FAR でない)
     */
    private static PixelState initDepthStencil(boolean mcHasTerrain, boolean reverseZ) {
        if (!mcHasTerrain) {
            // setup_stencil_depth.frag が discard する → クリア値のまま
            return new PixelState(far(reverseZ), 1);
        }
        // 断片が残る → gl_FragDepth = NEAR が書かれ、stencil は REPLACE で ref=0 になる
        return new PixelState(near(reverseZ), 0);
    }

    /** {@code glStencilFunc(GL_EQUAL, 1, 0xFF)}。深度値は<b>見ない</b>。 */
    private static boolean stencilPasses(PixelState s) {
        return s.stencil() == 1;
    }

    /** {@code glDepthFunc(closerEqualDepthCompare())}。ステンシル値は<b>見ない</b>。 */
    private static boolean depthPasses(PixelState s, float fragZ, boolean reverseZ) {
        return reverseZ ? fragZ >= s.depth() : fragZ <= s.depth();
    }

    // ---------------- 比べる深度値 ----------------

    /** NEAR / FAR ちょうどと、その隣、および内部の代表値。 */
    private static float[] fragmentDepths(boolean reverseZ) {
        float n = near(reverseZ), f = far(reverseZ);
        var out = new ArrayList<Float>(List.of(
            n, f,
            Math.nextAfter(n, f), Math.nextAfter(f, n),
            0.25f, 0.5f, 0.75f,
            1e-7f, 1.0f - 1e-7f));
        float[] a = new float[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    private record Case(boolean reverseZ, boolean mcHasTerrain, float fragZ) {
        @Override public String toString() {
            return (reverseZ ? "reverseZ" : "normalZ")
                + (mcHasTerrain ? " mc=terrain" : " mc=empty")
                + " z=" + fragZ;
        }
    }

    private static List<Case> allCases() {
        var out = new ArrayList<Case>();
        for (boolean rz : new boolean[]{false, true}) {
            for (boolean mc : new boolean[]{false, true}) {
                for (float z : fragmentDepths(rz)) out.add(new Case(rz, mc, z));
            }
        }
        return out;
    }

    // ---------------- 本題 ----------------

    /**
     * <b>ステンシルマスクと深度マスクは「ちょうど NEAR」を除いて一致する。</b>
     *
     * <p>一致するなら Vulkan 側にステンシルは要らず、
     * <b>深度を interop で運ぶだけで同じ絵になる</b> (提案書 §3.1 の案 A)。
     */
    @Test
    void stencilAndDepthMaskAgreeExceptExactlyAtNear() {
        var disagreements = new ArrayList<Case>();
        for (Case c : allCases()) {
            var s = initDepthStencil(c.mcHasTerrain(), c.reverseZ());
            if (stencilPasses(s) != depthPasses(s, c.fragZ(), c.reverseZ())) {
                disagreements.add(c);
            }
        }

        // 不一致は「MC 地形あり かつ 断片がちょうど NEAR」だけであること
        for (Case c : disagreements) {
            assertTrue(c.mcHasTerrain(),
                "a disagreement appeared where MC drew nothing: " + c);
            assertEquals(near(c.reverseZ()), c.fragZ(),
                "the only allowed disagreement is a fragment exactly at NEAR, got " + c);
        }

        // かつ、その場合は必ず不一致になること (逆Z / 非逆Z の両方で 1 件ずつ)
        assertEquals(2, disagreements.size(),
            "expected exactly one tie case per depth convention, got " + disagreements);
    }

    /**
     * <b>唯一の不一致「ちょうど NEAR」で、深度マスクのほうが緩い</b>ことを明示する。
     * つまりステンシルを外すと<b>描画が増える側に間違う</b>。
     * 消える側に間違うより安全だが、間違いの向きは記録しておく。
     */
    @Test
    void theTieCaseDrawsMoreWithoutStencil() {
        for (boolean rz : new boolean[]{false, true}) {
            var s = initDepthStencil(true, rz);
            assertFalse(stencilPasses(s), "stencil must reject where MC has terrain");
            assertTrue(depthPasses(s, near(rz), rz),
                "a fragment exactly at NEAR passes the depth test, so dropping the stencil"
                    + " draws it (reverseZ=" + rz + ")");
        }
    }

    /**
     * MC 地形が無い画素では<b>どの深度でも両方が通す</b>こと。
     * ここが本来 Voxy が描く領域なので、<b>取りこぼしが無いこと</b>が本質。
     */
    @Test
    void everythingIsDrawnWhereMinecraftHasNoTerrain() {
        for (boolean rz : new boolean[]{false, true}) {
            var s = initDepthStencil(false, rz);
            assertTrue(stencilPasses(s));
            for (float z : fragmentDepths(rz)) {
                assertTrue(depthPasses(s, z, rz),
                    "depth must not reject anything where MC is empty (reverseZ="
                        + rz + ", z=" + z + ")");
            }
        }
    }

    // ---------------- 対照: 検査が壊れた実装を検出できるか ----------------

    /**
     * 対照 1: 深度比較が <b>closerEqual ではなく closer (strict)</b> だったら
     * 不一致が出ること。
     *
     * <p>{@code closerEqualDepthCompare()} の {@code EQUAL} が効いていることが
     * 等価性の要である。strict にすると、MC 地形が無い画素の背景 (ちょうど FAR) が
     * 落ちるようになり、ステンシルと食い違う。
     */
    @Test
    void detectsAStrictDepthCompare() {
        var found = new ArrayList<Case>();
        for (Case c : allCases()) {
            var s = initDepthStencil(c.mcHasTerrain(), c.reverseZ());
            boolean strict = c.reverseZ() ? c.fragZ() > s.depth() : c.fragZ() < s.depth();
            if (stencilPasses(s) != strict) found.add(c);
        }
        assertFalse(found.isEmpty(),
            "a strict depth compare must break the equivalence; if it does not, "
                + "the comparison above proves nothing");
    }

    /**
     * 対照 2: {@code setup_stencil_depth.frag} が <b>{@code gl_FragDepth = NEAR} ではなく
     * MC の深度そのもの</b>を書いていたら不一致が出ること。
     *
     * <p>等価性は「MC 地形の画素に<b>最も手前の値</b>が入る」ことに依存していて、
     * 「MC の深度が入る」では成立しない。<b>どちらの読み方も自然に見える</b>ので、
     * ここを取り違えたまま案 A を採ると静かに壊れる。
     */
    @Test
    void detectsWritingTheMcDepthInstead() {
        var found = new ArrayList<Case>();
        for (boolean rz : new boolean[]{false, true}) {
            // MC 地形が中間の深度 0.5 にある、という想定
            var broken = new PixelState(0.5f, 0);
            for (float z : fragmentDepths(rz)) {
                if (stencilPasses(broken) != depthPasses(broken, z, rz)) {
                    found.add(new Case(rz, true, z));
                }
            }
        }
        assertFalse(found.isEmpty(),
            "writing the MC depth instead of NEAR must break the equivalence");
        // 「手前にあるものが描かれてしまう」という具体的な壊れ方であることも示す
        assertTrue(found.stream().anyMatch(c -> !c.reverseZ() && c.fragZ() < 0.5f),
            "the breakage must show up as fragments closer than the MC surface being drawn");
    }
}
