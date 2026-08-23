package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.vk.VkHierarchicalScene;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5c-5a: <b>ジオメトリ領域の見積もり</b>。装置が要らない。
 *
 * <h2>⚠ なぜ切り出したのか</h2>
 * 最初この計算を {@code built.geometryBuffer.size} の 1 行で書いて
 * <b>空のセクションで NPE を出した</b>。{@code BuiltSection} は
 * {@code geometryBuffer == null} を「空」の表現に使っている
 * [確認済 — {@code BuiltSection.isEmpty}]。
 *
 * <p>⚠ <b>空でも木には渡さなければならない。</b> {@code childExistence} を運んでいるので、
 * 捨てると要求が満たされず<b>トラバーサルが降りられなくなる</b> —
 * 落ちない代わりに遠景が粗いまま止まる。
 */
public class VkGeometryBudgetTest {
    /** ⚠ 空のセクションは領域を取らない。<b>ここで NPE を出した。</b> */
    @Test
    void anEmptySectionCostsNothing() {
        assertEquals(0, VkHierarchicalScene.geometryBytesNeeded(BuiltSection.empty(0L)));
        assertEquals(0, VkHierarchicalScene.geometryBytesNeeded(
            BuiltSection.emptyWithChildren(0L, (byte) 0xFF)),
            "a section can be empty and still carry child existence");
    }

    /**
     * <b>127 要素単位に切り上げる</b> [確認済 — {@code createMeta} の {@code upsized}]。
     *
     * <p>⚠ 切り上げを見ないと「ちょうど入る」と判断して<b>溢れる</b>。
     */
    @Test
    void aSectionIsRoundedUpToTheAllocationGranularity() {
        // 1 要素 = 8 バイト。1 要素だけでも 128 要素ぶん取られる
        assertEquals(128 * 8, needFor(1 * 8));
        assertEquals(128 * 8, needFor(128 * 8), "128 elements already fits the block");
        assertEquals(256 * 8, needFor(129 * 8));
        assertEquals(256 * 8, needFor(200 * 8));
    }

    /** ⚠ <b>対照</b>: 切り上げないと足りない見積もりになることを示す。 */
    @Test
    void ignoringTheGranularityWouldUnderestimate() {
        long actual = needFor(129 * 8);
        long naive = 129 * 8;
        assertTrue(actual > naive,
            "if the estimate equalled the raw size, a section that 'just fits' would"
                + " still fail to allocate and throw inside the geometry manager");
    }

    private static long needFor(long bytes) {
        var buf = new MemoryBuffer(bytes);
        try {
            var section = new BuiltSection(0L, (byte) 0, 0, buf, new int[]{0}, null);
            return VkHierarchicalScene.geometryBytesNeeded(section);
        } finally {
            buf.free();
        }
    }
}
