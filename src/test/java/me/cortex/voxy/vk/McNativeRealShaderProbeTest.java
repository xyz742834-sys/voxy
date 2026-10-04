package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.VkFrameTracker;
import me.cortex.voxy.client.core.vk.VkQuadIndexBuffer;
import me.cortex.voxy.client.core.vk.VkSampler;
import me.cortex.voxy.client.core.vk.mcnative.McNativeRealShaderProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link McNativeRealShaderProbe} 自身の検査。
 *
 * <p>この probe は「Voxy の本番シェーダ機構が<b>与えられた</b> device 上で動くか」を測る。
 * ここでは Voxy 自前の device に対して走らせ、<b>probe が正しく測れること</b>を固定する。
 * 実機では同じ経路が Minecraft の device に対して走る — この継ぎ目があるおかげで、
 * probe の不具合と「MC の device だから動かない」を切り分けられる。
 */
public class McNativeRealShaderProbeTest {

    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
    }

    @AfterAll
    static void teardown() {
        if (VkFrameTracker.isRecordingFrame()) VkFrameTracker.get().endFrame();
        VkSampler.shutdown();
        VkQuadIndexBuffer.shutdown();
        VkFrameTracker.shutdown();
    }

    @Test
    void theProbeResolvesEveryQuadOrdinalThroughVoxysRealShaderStack() {
        var result = McNativeRealShaderProbe.runAgainstCurrentContext();
        assertTrue(result.attempted(), "the probe did not run");
        assertEquals(0, result.mismatches(),
            () -> "the real shader disagreed with the CPU reference: " + result.firstMismatch());
        assertTrue(result.quadsChecked() > 100,
            () -> "only " + result.quadsChecked() + " ordinals were checked; the data is too small"
                + " to prove anything (notes: " + result.notes() + ")");
        assertTrue(result.succeeded(), () -> "the probe failed: " + result.notes());
    }
}
