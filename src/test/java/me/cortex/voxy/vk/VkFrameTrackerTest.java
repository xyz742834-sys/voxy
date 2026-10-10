package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkFrameTracker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** in-flight = 1 のフレーム同期。 */
public class VkFrameTrackerTest {
    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
        VkFrameTracker.init();
    }

    @AfterAll
    static void teardown() {
        // 他のテストクラスと VkContext を共有しているので、トラッカだけ畳む
        VkFrameTracker.shutdown();
    }

    @Test
    void frameCyclesAdvanceGeneration() {
        var t = VkFrameTracker.get();
        long before = t.current();

        var cmd = t.beginFrame();
        assertNotNull(cmd);
        assertTrue(t.isRecording());
        t.endFrame();
        assertFalse(t.isRecording());

        assertEquals(before + 1, t.current(), "each frame advances the generation");

        // 次フレームの開始で前フレームの完了が確定する
        t.beginFrame();
        assertTrue(t.completed() >= before + 1,
            "beginFrame waits on the previous fence, so it must be complete");
        t.endFrame();
        t.waitIdle();
    }

    /** 空のコマンドバッファでもサブミットが通ること (fence の往復が成立している)。 */
    @Test
    void emptyFrameSubmitsAndCompletes() {
        var t = VkFrameTracker.get();
        t.beginFrame();
        t.endFrame();
        t.waitForFrame();
        assertEquals(t.current(), t.completed());
    }

    @Test
    void doubleBeginIsRejected() {
        var t = VkFrameTracker.get();
        t.beginFrame();
        try {
            assertThrows(IllegalStateException.class, t::beginFrame);
        } finally {
            t.endFrame();
            t.waitIdle();
        }
    }

    @Test
    void endWithoutBeginIsRejected() {
        assertThrows(IllegalStateException.class, () -> VkFrameTracker.get().endFrame());
    }

    /**
     * フレーム境界での遅延解放。in-flight = 1 なので世代管理は不要で、
     * 「次の beginFrame まで待てば GPU はアイドル」という規則だけで足りる。
     */
    @Test
    void deferredFreeRunsAtFrameBoundary() {
        var t = VkFrameTracker.get();
        var counter = new AtomicInteger();

        t.beginFrame();
        var buf = new VkBuffer(256);
        t.freeAtFrameEnd(() -> { buf.free(); counter.incrementAndGet(); });
        assertEquals(1, t.pendingFreeCount(), "not freed while the frame is still recording");
        assertEquals(0, counter.get());
        t.endFrame();

        // まだ実行されていない (サブミット済みだが完了待ちはしていない)
        assertEquals(0, counter.get());

        t.beginFrame();   // ここで前フレーム完了 -> 解放が走る
        assertEquals(1, counter.get(), "deferred free must run once the GPU is idle");
        assertEquals(0, t.pendingFreeCount());
        t.endFrame();
        t.waitIdle();
    }

    /** 解放中に例外が出ても後続の解放とフレーム進行を止めないこと。 */
    @Test
    void deferredFreeFailureDoesNotBreakTheFrame() {
        var t = VkFrameTracker.get();
        var ran = new AtomicInteger();
        t.beginFrame();
        t.freeAtFrameEnd(() -> { throw new RuntimeException("boom"); });
        t.freeAtFrameEnd(ran::incrementAndGet);
        t.endFrame();

        assertDoesNotThrow(t::beginFrame);
        assertEquals(1, ran.get(), "the second free must still run");
        t.endFrame();
        t.waitIdle();
    }

    /**
     * round-27 item 6, 故障注入: サブミットが失敗したフレーム。リセット済みのフェンスを signal
     * するものは無いので、以前は次の待ちが {@code Long.MAX_VALUE} で戻らなかった。
     * 失敗したフレームの記録は実行されず、次のフレームは普通に走ること。
     */
    @Test
    void aFailedSubmitDoesNotHangTheNextFrame() {
        var t = VkFrameTracker.get();
        var buf = new VkBuffer(256);
        try {
            t.waitIdle();
            org.lwjgl.system.MemoryUtil.memPutInt(buf.addr(), 0);

            var cmd = t.beginFrame();
            org.lwjgl.vulkan.VK10.vkCmdFillBuffer(cmd, buf.handle, 0, 4, 0x1111);
            VkFrameTracker.injectSubmitFailure(org.lwjgl.vulkan.VK10.VK_ERROR_DEVICE_LOST);
            var e = assertThrows(IllegalStateException.class, t::endFrame);
            assertTrue(e.getMessage().contains("vkQueueSubmit"), e.getMessage());
            assertFalse(t.isRecording(), "a failed submit closes the recording window");

            assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
                t.waitForFrame();
                var next = t.beginFrame();
                org.lwjgl.vulkan.VK10.vkCmdFillBuffer(next, buf.handle, 4, 4, 0x5555);
                t.endFrame();
                t.waitForFrame();
            }, "the frame after a failed submit must not wait on a fence nothing will signal");
            assertEquals(t.current(), t.completed());
            assertEquals(0, org.lwjgl.system.MemoryUtil.memGetInt(buf.addr()),
                "the failed frame's commands never ran");
            assertEquals(0x5555, org.lwjgl.system.MemoryUtil.memGetInt(buf.addr() + 4),
                "the next frame ran");
        } finally {
            t.waitIdle();
            buf.free();
        }
    }

    /** 待ちが失敗しても状態は壊れず、次の待ちで本物のフェンスを待てること。 */
    @Test
    void aFailedWaitLeavesTheFrameUsable() {
        var t = VkFrameTracker.get();
        t.beginFrame();
        t.endFrame();
        VkFrameTracker.injectWaitFailure(org.lwjgl.vulkan.VK10.VK_ERROR_DEVICE_LOST);
        var e = assertThrows(IllegalStateException.class, t::waitForFrame);
        assertTrue(e.getMessage().contains("vkWaitForFences"), e.getMessage());
        assertTrue(t.inFlight(t.current()), "a failed wait does not mark the frame complete");
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), t::waitForFrame);
        assertEquals(t.current(), t.completed());
        t.beginFrame();
        t.endFrame();
        t.waitIdle();
    }

    /** in-flight 検出は残してある (将来多重化したときの防波堤)。 */
    @Test
    void inFlightDetectionStillWorks() {
        var t = VkFrameTracker.get();
        t.waitIdle();
        long done = t.completed();
        assertFalse(t.inFlight(done), "a completed generation is not in flight");
        assertTrue(t.inFlight(done + 1), "a future generation is considered in flight");
    }
}
