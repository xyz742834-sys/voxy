package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.SyntheticTerrain;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkNodeTree;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>合成ノード木が、シェーダの読み方どおりに詰められていること</b> (Phase 5c-4b)。
 *
 * <h2>期待値の独立性 [規約 4]</h2>
 * 書き side は {@link VkNodeTree#write} の式、読み side は
 * <b>{@code node.glsl} の {@code unpackNode} を literal で写したもの</b>。
 * どちらかがずれれば食い違う。
 *
 * <p>⚠ 5c-2a で {@code ModelAtlasLayout} を書き込みと読み戻しの<b>両方</b>で引いて
 * 変異を素通しした (失敗例 19)。同じ式を 2 回呼ばない。
 */
public class VkNodeTreeTest {
    @BeforeAll
    static void setup() { VulkanTestSupport.requireVulkan(); }

    // ---- node.glsl の unpackNode を literal で写したもの ----
    private static int meshPtrOf(int z)   { return z & 0xFFFFFF; }
    private static int childPtrOf(int w)  { return w & 0xFFFFFF; }
    private static int flagsOf(int z, int w) {
        return ((z >>> 24) & 0xFF) | (((w >>> 24) & 0xFF) << 8);
    }
    /** {@code getChildCount} = {@code ((flags>>2)&7)+1}。<b>3 ビット幅で 1..8</b>。 */
    private static int childCountOf(int flags) { return ((flags >> 2) & 7) + 1; }

    @Test
    void nodesAreWrittenTheWayTheShaderReadsThem() {
        var t = new VkNodeTree();
        // ⚠ 負の座標と、レベル・子の数の端を必ず含める
        int a = t.add(0,  0,  0,  0, 5, VkNodeTree.NULL_NODE, 1);
        int b = t.add(2, -7, 11, -3, VkNodeTree.EMPTY_MESH, 40, 8);
        int c = t.add(4, 100, -60, 33, VkNodeTree.NULL_MESH, 7, 5);

        var buf = new VkBuffer(4096);
        try {
            t.write(buf);
            int[][] expect = {
                {0,  0,  0,  0, 5, VkNodeTree.NULL_NODE, 1},
                {2, -7, 11, -3, VkNodeTree.EMPTY_MESH, 40, 8},
                {4, 100, -60, 33, VkNodeTree.NULL_MESH, 7, 5},
            };
            for (int i = 0; i < expect.length; i++) {
                long base = buf.addr() + (long) i * VkNodeTree.NODE_SIZE;
                int x0 = MemoryUtil.memGetInt(base);
                int y0 = MemoryUtil.memGetInt(base + 4);
                int z0 = MemoryUtil.memGetInt(base + 8);
                int w0 = MemoryUtil.memGetInt(base + 12);

                long packed = Integer.toUnsignedLong(x0) | (Integer.toUnsignedLong(y0) << 32);
                int[] pos = SyntheticTerrain.unpackPosition(packed);
                String at = "node " + i;
                assertArrayEquals(new int[]{expect[i][1], expect[i][2], expect[i][3], expect[i][0]},
                    pos, at + ": position/level");

                assertEquals(expect[i][4], meshPtrOf(z0), at + ": meshPtr");
                assertEquals(expect[i][5], childPtrOf(w0), at + ": childPtr");
                assertEquals(expect[i][6], childCountOf(flagsOf(z0, w0)), at + ": childCount");
            }
            assertEquals(0, a);
            assertEquals(1, b);
            assertEquals(2, c);
        } finally { buf.free(); }
    }

    /**
     * <b>子の数は 3 ビットにしか入らない</b> — 範囲外は<b>黙って丸めず</b>弾くこと。
     *
     * <p>丸めると「8 個入れたつもりが 1 個」になり、
     * <b>予約した数と書いた数が食い違って</b> キューに未書き込みの穴ができる。
     */
    @Test
    void childCountsOutsideTheThreeBitFieldAreRejected() {
        var t = new VkNodeTree();
        assertThrows(IllegalArgumentException.class, () -> t.add(0, 0, 0, 0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> t.add(0, 0, 0, 0, 0, 1, 9));
        // 端は通すこと (弾きすぎの対照)
        assertDoesNotThrow(() -> t.add(0, 0, 0, 0, 0, 1, 1));
        assertDoesNotThrow(() -> t.add(0, 0, 0, 0, 0, 1, 8));
    }

    /**
     * <b>祖先関係が引けること。</b>
     * 「描かれた集合が反鎖である」の検査がこれに乗る [docs/phase5c4-plan.md 4.1]。
     */
    @Test
    void ancestryCanBeResolvedForTheCutCheck() {
        var t = VkNodeTree.oneLevelOctree(1, 0, 0, 0);
        assertEquals(9, t.size(), "one root plus eight children");
        assertArrayEquals(new int[]{0}, t.topNodes());

        for (int child = 1; child <= 8; child++) {
            assertEquals(0, t.parentOf(child), "child " + child + " must hang off the root");
            assertTrue(t.isAncestorOf(0, child), "the root is an ancestor of child " + child);
            assertFalse(t.isAncestorOf(child, 0), "a child is not an ancestor of the root");
        }
        assertEquals(-1, t.parentOf(0), "the root has no parent");
        // 兄弟どうしは祖先関係にない (反鎖の検査が兄弟を誤検出しないこと)
        assertFalse(t.isAncestorOf(1, 2), "siblings must not be ancestors of each other");
    }

    /**
     * ⚠ <b>子は根の体積を分割していること。</b>
     * 分割していなければ「根の代わりに子を描く」が体積として成立せず、
     * <b>穴か重なりが出る</b>。
     */
    @Test
    void theChildrenOfTheOctreeTileTheirParent() {
        var t = VkNodeTree.oneLevelOctree(1, 3, -2, 5);
        var root = t.node(0);
        var seen = new java.util.HashSet<String>();
        for (int i = 1; i <= 8; i++) {
            var c = t.node(i);
            assertEquals(root.level() - 1, c.level(), "child " + i + " must be one level finer");
            // 子の座標を親のスケールへ戻すと親に一致すること
            assertEquals(root.x(), c.x() >> 1, "child " + i + " x is outside its parent");
            assertEquals(root.y(), c.y() >> 1, "child " + i + " y is outside its parent");
            assertEquals(root.z(), c.z() >> 1, "child " + i + " z is outside its parent");
            assertTrue(seen.add(c.x() + "," + c.y() + "," + c.z()),
                "child " + i + " duplicates another child's position");
        }
        assertEquals(8, seen.size(), "the eight children must occupy eight distinct octants");
    }
}
