package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer.Barriers;
import org.junit.jupiter.api.*;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-28 R28-QUEUE-OVERFLOW: the traversal's render-queue counter can pass the queue's capacity
 * (its bound check and atomicAdd are separate; only the store is guarded), and every per-draw table
 * is sized by that capacity. prep must clamp the count it hands on: the cull's instance count,
 * cmdgen's dispatch and the entry counts all stay within the capacity.
 */
public class VkRenderQueueClampTest {
    private static final int CAPACITY = 32;

    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
        VkFrameTracker.init();
    }

    @AfterAll
    static void teardown() {
        if (VkFrameTracker.isRecordingFrame()) VkFrameTracker.get().endFrame();
        VkFrameTracker.get().waitIdle();
        VkSampler.shutdown();
        VkQuadIndexBuffer.shutdown();
        VkFrameTracker.shutdown();
    }

    private static int[] prepOutputs(int counted) {
        var res = new VkTerrainResources(64, 4096, CAPACITY * 7, 16, VkQuadIndexBuffer.DEFAULT_QUAD_CAPACITY,
            VkTerrainResources.AtlasScale.SMALL, CAPACITY, null);
        var table = new VkMergedTableBuilder(res, Barriers.CONSERVATIVE);
        try {
            MemoryUtil.memPutInt(res.indirectLookup.addr(), counted);
            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            table.recordPrep(cmd);
            t.endFrame();
            t.waitForFrame();
            return new int[] {
                MemoryUtil.memGetInt(res.mergedPrefix.addr()),        // mergedEntryCount
                MemoryUtil.memGetInt(res.mergedDispatch.addr()),      // cmdGenDispatchX
                MemoryUtil.memGetInt(res.cullDraw.addr() + 4)};       // cullInstanceCount
        } finally {
            t().waitIdle();
            table.free();
            res.free();
        }
    }

    private static VkFrameTracker t() { return VkFrameTracker.get(); }

    @Test
    void aCountPastTheCapacityIsClamped() {
        int[] out = prepOutputs(1000);
        assertEquals(CAPACITY * 7, out[0], "mergedEntryCount");
        assertEquals((CAPACITY + 127) / 128, out[1], "cmdgen dispatch");
        assertEquals(CAPACITY, out[2], "cull instance count");
    }

    @Test
    void aCountWithinTheCapacityIsUnchanged() {
        int[] out = prepOutputs(20);
        assertEquals(20 * 7, out[0]);
        assertEquals(1, out[1]);
        assertEquals(20, out[2]);
    }
}
