package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.VkHierarchicalScene;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>セクション容量が 2 の冪へ切り上がること</b> (Phase 5c-4c)。
 *
 * <p>{@code NodeManager} と {@code AbstractSectionGeometryManager} が
 * 2 の冪を要求する。⚠ <b>実際に 20000 を渡して落ちた</b>
 * ({@code "Max node count must be a power of 2"})。
 *
 * <p>⚠ <b>既に 2 の冪なら増やさないこと</b>も要求する —
 * 増やすと確保が<b>黙って倍</b>になる。密テーブルは容量に比例するので効く。
 */
public class VkSectionCapacityTest {
    @Test
    void capacitiesRoundUpButNeverDouble() {
        assertEquals(32768, VkHierarchicalScene.roundUpToPowerOfTwo(20000),
            "20000 is what crashed; it must become the next power of two");
        assertEquals(32768, VkHierarchicalScene.roundUpToPowerOfTwo(32768),
            "an exact power of two must not be doubled");
        assertEquals(65536, VkHierarchicalScene.roundUpToPowerOfTwo(32769));
        assertEquals(4, VkHierarchicalScene.roundUpToPowerOfTwo(4));
        assertEquals(8, VkHierarchicalScene.roundUpToPowerOfTwo(5));

        // 小さすぎる値でも 2 の冪であること (負や 0 で壊れない)
        for (int v : new int[]{Integer.MIN_VALUE, -1, 0, 1, 2, 3}) {
            int r = VkHierarchicalScene.roundUpToPowerOfTwo(v);
            assertTrue(r >= 4 && (r & (r - 1)) == 0, "roundUp(" + v + ") = " + r);
        }

        // 一般に: 結果は 2 の冪で、入力以上で、その半分は入力未満
        for (int v = 4; v < 100000; v += 997) {
            int r = VkHierarchicalScene.roundUpToPowerOfTwo(v);
            assertEquals(0, r & (r - 1), v + " -> " + r + " is not a power of two");
            assertTrue(r >= v, v + " -> " + r + " is smaller than the request");
            assertTrue(r / 2 < v, v + " -> " + r + " overshot by more than a factor of two");
        }
    }
}
