package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * <b>GPU の区間計測が、仕事量に追随すること</b> (Phase 5c-4c)。
 *
 * <h2>この検査を空虚に満たす方法と、その塞ぎ方</h2>
 * <ol>
 *   <li><b>定数を返す</b> → 「正の数である」だけでは通ってしまう。
 *       {@link #aBiggerWorkloadMeasuresLonger} が<b>仕事量に追随すること</b>を要求する</li>
 *   <li><b>対応していないのに 0 を返す</b> → 「速いから 0」と読めてしまう。
 *       {@link VkGpuTimer#readMillis} は取れないとき <b>{@code null}</b> を返す [規約 18]</li>
 * </ol>
 */
public class VkGpuTimerTest {
    private static VkTexture src;
    private static VkHiZ big, small;

    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
        VkFrameTracker.init();
        src = new VkTexture(VkHiZ.FORMAT, 1, 512, 512,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT).name("timerSrc");
        big = new VkHiZ(src, 512, 512);
        small = new VkHiZ(src, 16, 16);
    }

    @AfterAll
    static void teardown() {
        if (VkFrameTracker.isRecordingFrame()) VkFrameTracker.get().endFrame();
        VkFrameTracker.get().waitIdle();
        if (big != null) big.free();
        if (small != null) small.free();
        if (src != null) src.free();
        VkSampler.shutdown();
        VkFrameTracker.shutdown();
    }

    /** この装置でタイムスタンプが取れるかを<b>まず言う</b>。 */
    @Test
    void reportWhetherTimestampsAreAvailableAtAll() {
        var timer = new VkGpuTimer("probe");
        try {
            System.out.println("[vk] timestampValidBits=" + VkContext.get().timestampValidBits
                + " period=" + VkContext.get().timestampPeriod + "ns"
                + " supported=" + timer.supported());
            if (!timer.supported()) {
                System.out.println("[vk] " + timer.describe());
                assertNull(timer.readMillis(),
                    "an unsupported timer must return no numbers, not zeros");
            }
        } finally { timer.free(); }
    }

    /**
     * <b>8 倍の仕事をした区間は、1 回の区間より長く測れること。</b>
     *
     * <p>⚠ これが<b>「定数を返す計測器」を落とす対照</b>である。
     * 「正の数が返る」だけでは何も主張していない [規約 11]。
     */
    @Test
    void aBiggerWorkloadMeasuresLonger() {
        var timer = new VkGpuTimer("many", "one");
        try {
            if (!timer.supported()) {
                System.out.println("[vk] skipping: " + timer.describe());
                return;
            }
            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            timer.reset(cmd);
            timer.mark(cmd, 0);
            for (int i = 0; i < 8; i++) big.record(cmd);    // 512x512 の連鎖を 8 回
            timer.mark(cmd, 1);
            small.record(cmd);                              // 16x16 を 1 回
            timer.mark(cmd, 2);
            t.endFrame();
            t.waitForFrame();

            double[] ms = timer.readMillis();
            assertNotNull(ms, "the timer reported support but produced no numbers");
            System.out.println("[vk] " + timer.describe());

            assertEquals(2, ms.length);
            for (double v : ms) {
                assertTrue(v >= 0, "a span measured negative: " + v);
            }
            assertTrue(ms[0] > ms[1],
                "eight 512x512 chains (" + ms[0] + "ms) must measure longer than one 16x16 ("
                    + ms[1] + "ms); the timer is not tracking the workload");
        } finally { timer.free(); }
    }

    /** 区間の数と名前が合っていること。 */
    @Test
    void spansAreNamedAndCounted() {
        var timer = new VkGpuTimer("a", "b", "c");
        try {
            assertArrayEquals(new String[]{"a", "b", "c"}, timer.labels());
            assertThrows(IndexOutOfBoundsException.class, () -> timer.mark(null, 4));
        } finally { timer.free(); }
    }
}
