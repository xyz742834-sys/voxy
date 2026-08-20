package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.VkBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 検証 6: VkBuffer の生成・zero・addr。
 *
 * <p>ユニファイドメモリ前提なので、確保したバッファは常時マップされ
 * ホストから直接読み書きできる。ステージングもコピーコマンドも介さない。
 */
public class VkBufferTest {
    @BeforeAll
    static void setup() { VulkanTestSupport.requireVulkan(); }

    @Test
    void createsMappedBuffer() {
        var buf = new VkBuffer(4096);
        try {
            assertNotEquals(0L, buf.handle);
            assertNotEquals(0L, buf.memory);
            assertNotEquals(0L, buf.addr(), "unified memory buffers are always mapped");
            assertEquals(4096, buf.size());
            assertFalse(buf.isSparse(), "MoltenVK has sparseBinding=false; sparse must stay off");
        } finally {
            buf.free();
        }
    }

    @Test
    void zeroActuallyZeroes() {
        var buf = new VkBuffer(1024, false);
        try {
            // 事前に非ゼロで汚してから zero する
            MemoryUtil.memSet(buf.addr(), 0xAB, 1024);
            assertEquals((byte) 0xAB, MemoryUtil.memGetByte(buf.addr()));

            buf.zero();
            for (long i = 0; i < 1024; i++) {
                assertEquals(0, MemoryUtil.memGetByte(buf.addr() + i), "byte " + i);
            }
        } finally {
            buf.free();
        }
    }

    @Test
    void hostWritesAreVisibleThroughTheMapping() {
        var buf = new VkBuffer(256);
        try {
            for (int i = 0; i < 64; i++) MemoryUtil.memPutInt(buf.addr() + i * 4L, i * 7);
            for (int i = 0; i < 64; i++) {
                assertEquals(i * 7, MemoryUtil.memGetInt(buf.addr() + i * 4L));
            }
        } finally {
            buf.free();
        }
    }

    @Test
    void fillWritesWordPattern() {
        var buf = new VkBuffer(64, false);
        try {
            buf.fill(0xDEADBEEF);
            for (int i = 0; i < 16; i++) {
                assertEquals(0xDEADBEEF, MemoryUtil.memGetInt(buf.addr() + i * 4L), "word " + i);
            }
        } finally {
            buf.free();
        }
    }

    @Test
    void zeroRangeOnlyTouchesItsRange() {
        var buf = new VkBuffer(64, false);
        try {
            buf.fill(0x11111111);
            buf.zeroRange(16, 16);
            assertEquals(0x11111111, MemoryUtil.memGetInt(buf.addr()));       // 範囲外
            assertEquals(0, MemoryUtil.memGetInt(buf.addr() + 16));           // 範囲内
            assertEquals(0, MemoryUtil.memGetInt(buf.addr() + 28));           // 範囲内 末尾
            assertEquals(0x11111111, MemoryUtil.memGetInt(buf.addr() + 32));  // 範囲外
        } finally {
            buf.free();
        }
    }

    @Test
    void allocationAccountingTracksLiveBuffers() {
        int before = VkBuffer.getCount();
        long sizeBefore = VkBuffer.getTotalSize();
        var buf = new VkBuffer(2048);
        assertEquals(before + 1, VkBuffer.getCount());
        assertEquals(sizeBefore + 2048, VkBuffer.getTotalSize());
        buf.free();
        assertEquals(before, VkBuffer.getCount());
        assertEquals(sizeBefore, VkBuffer.getTotalSize());
    }
}
