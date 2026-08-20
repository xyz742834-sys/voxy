package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** UploadStream / DownloadStream の Vulkan 版。 */
public class VkStreamTest {
    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
        VkFrameTracker.init();
        VkUploadStream.init();
        VkDownloadStream.init();
    }

    /** テストが記録中に落ちても後続に影響させない。 */
    @org.junit.jupiter.api.AfterEach
    void ensureFrameClosed() {
        if (VkFrameTracker.isRecordingFrame()) {
            VkFrameTracker.get().endFrame();
        }
        VkFrameTracker.get().waitIdle();
    }

    @AfterAll
    static void teardown() {
        VkFrameTracker.get().waitIdle();
        VkDownloadStream.shutdown();
        VkUploadStream.shutdown();
        VkFrameTracker.shutdown();
    }

    // ---------------- upload: mode 1 ----------------

    /** モード 1 は転送を伴わず、対象のマップ先をそのまま返す。 */
    @Test
    void mode1WritesStraightIntoTheTarget() {
        var t = VkFrameTracker.get();
        var buf = new VkBuffer(1024);
        try {
            t.beginFrame();
            long ptr = VkUploadStream.get().upload(buf, 256, 64);
            assertEquals(buf.addr() + 256, ptr, "mode 1 must hand back the target's own mapping");

            MemoryUtil.memPutInt(ptr, 0xCAFEBABE);
            VkUploadStream.get().commit();
            // 転送を挟まないので、書いた瞬間に対象へ反映されている
            assertEquals(0xCAFEBABE, MemoryUtil.memGetInt(buf.addr() + 256));
            t.endFrame();
            t.waitIdle();
        } finally {
            buf.free();
        }
    }

    /** 記録窓の外での書き込みは拒否される (多重化した瞬間に壊れるため)。 */
    @Test
    void mode1RejectsWritesOutsideTheRecordingWindow() {
        var buf = new VkBuffer(256);
        try {
            VkFrameTracker.get().waitIdle();   // 記録していない状態
            var e = assertThrows(IllegalStateException.class,
                () -> VkUploadStream.get().upload(buf, 0, 16));
            assertTrue(e.getMessage().contains("recording window"));
            assertTrue(e.getMessage().contains("in-flight=1"));
        } finally {
            buf.free();
        }
    }

    @Test
    void mode1RejectsOutOfRange() {
        var t = VkFrameTracker.get();
        var buf = new VkBuffer(128);
        try {
            t.beginFrame();
            assertThrows(IllegalArgumentException.class, () -> VkUploadStream.get().upload(buf, 100, 100));
            t.endFrame();
            t.waitIdle();
        } finally {
            buf.free();
        }
    }

    // ---------------- upload: mode 2 ----------------

    /** モード 2 のオフセットは dynamic offset の要件を満たす。 */
    @Test
    void mode2OffsetsAreAligned() {
        var t = VkFrameTracker.get();
        long align = VkContext.get().minStorageBufferOffsetAlignment;
        t.beginFrame();
        long a = VkUploadStream.get().rawUploadAddress(1);    // わざと非アラインなサイズ
        long b = VkUploadStream.get().rawUploadAddress(7);
        long c = VkUploadStream.get().rawUploadAddress(4096);
        assertEquals(0, a % align);
        assertEquals(0, b % align);
        assertEquals(0, c % align);
        assertTrue(b > a && c > b, "bump allocator must not hand out overlapping ranges");
        t.endFrame();
        t.waitIdle();
    }

    /** リングはフレーム境界で巻き戻る。 */
    @Test
    void mode2ResetsEachFrame() {
        var t = VkFrameTracker.get();
        t.beginFrame();
        VkUploadStream.get().rawUploadAddress(4096);
        assertTrue(VkUploadStream.get().usedThisFrame() >= 4096);
        t.endFrame();

        t.beginFrame();   // frame-begin フックでリセットされる
        assertEquals(0, VkUploadStream.get().usedThisFrame(), "the ring must rewind at the frame boundary");
        long first = VkUploadStream.get().rawUploadAddress(16);
        assertEquals(0, first, "first allocation of a frame starts at 0");
        t.endFrame();
        t.waitIdle();
    }

    /** 枯渇時は要求元・要求量・残量を含む例外で落ちる。 */
    @Test
    void mode2ExhaustionReportsDetail() {
        var t = VkFrameTracker.get();
        t.beginFrame();
        try {
            var e = assertThrows(IllegalStateException.class,
                () -> VkUploadStream.get().rawUploadAddress(Integer.MAX_VALUE - 64, "giant-test-alloc"));
            System.out.println("[vk] scratch exhaustion:\n" + e.getMessage());
            assertTrue(e.getMessage().contains("giant-test-alloc"), "must name the requester");
            assertTrue(e.getMessage().contains("requested"), "must state how much was requested");
            assertTrue(e.getMessage().contains("remaining"), "must state what was left");
            assertTrue(e.getMessage().contains("capacity"));
        } finally {
            t.endFrame();
            t.waitIdle();
        }
    }

    // ---------------- download ----------------

    /**
     * 退避経路: コピー後に対象を書き換えても、配送される内容はコピー時点のもの。
     * これが `requestBuffer` (download の次の行で clear) を守る仕組み。
     */
    @Test
    void stagedDownloadKeepsTheSnapshot() {
        var t = VkFrameTracker.get();
        var buf = new VkBuffer(256);
        var got = new AtomicLong(-1);
        try {
            var cmd = t.beginFrame();
            MemoryUtil.memPutInt(buf.addr(), 0x11223344);

            VkDownloadStream.get().download(buf, 0, 4, (ptr, size) -> got.set(MemoryUtil.memGetInt(ptr) & 0xFFFFFFFFL));

            // GL 版 downloadResetRequestQueue と同じ形: download の直後に GPU 側でクリアする。
            // (CPU 側の memPut では記録済みコマンドとの順序が付かないので再現にならない)
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var war = org.lwjgl.vulkan.VkBufferMemoryBarrier.calloc(1, stack)
                    .sType$Default()
                    .srcAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .dstAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .srcQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buf.handle).offset(0).size(4);
                org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier(cmd,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, null, war, null);
            }
            org.lwjgl.vulkan.VK10.vkCmdFillBuffer(cmd, buf.handle, 0, 4, 0);
            t.endFrame();

            t.beginFrame();   // ここで配送
            assertEquals(0x11223344L, got.get(),
                "the staged copy must preserve the value from before the clear");
            t.endFrame();
            t.waitIdle();
        } finally {
            buf.free();
        }
    }

    /** 直接読み経路: フレーム最終状態が見える。 */
    @Test
    void directDownloadSeesFinalState() {
        var t = VkFrameTracker.get();
        var buf = new VkBuffer(256);
        var got = new AtomicLong(-1);
        try {
            t.beginFrame();
            MemoryUtil.memPutInt(buf.addr(), 0xAAAA);
            VkDownloadStream.get().downloadDirect(buf, 0, 4, (ptr, size) -> got.set(MemoryUtil.memGetInt(ptr)));
            MemoryUtil.memPutInt(buf.addr(), 0xBBBB);   // 後から書き換える
            t.endFrame();

            t.beginFrame();
            assertEquals(0xBBBB, got.get(), "direct reads observe the end-of-frame state");
            t.endFrame();
            t.waitIdle();
        } finally {
            buf.free();
        }
    }

    /** 退避経路は記録中でなければ使えない (コマンドバッファが要るため)。 */
    @Test
    void stagedDownloadRequiresRecording() {
        var buf = new VkBuffer(64);
        try {
            VkFrameTracker.get().waitIdle();
            assertThrows(IllegalStateException.class,
                () -> VkDownloadStream.get().download(buf, 0, 4, (p, s) -> {}));
        } finally {
            buf.free();
        }
    }

    /** 配送後は保留がゼロに戻る。 */
    @Test
    void pendingClearsAfterDelivery() {
        var t = VkFrameTracker.get();
        var buf = new VkBuffer(64);
        try {
            t.beginFrame();
            VkDownloadStream.get().download(buf, 0, 4, (p, s) -> {});
            VkDownloadStream.get().downloadDirect(buf, 0, 4, (p, s) -> {});
            assertEquals(2, VkDownloadStream.get().pendingCount());
            t.endFrame();

            t.beginFrame();
            assertEquals(0, VkDownloadStream.get().pendingCount());
            t.endFrame();
            t.waitIdle();
        } finally {
            buf.free();
        }
    }
}
