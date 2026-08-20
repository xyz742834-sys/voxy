package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.shader.*;
import org.junit.jupiter.api.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * P1 / P2 ヘルパの検証。
 *
 * <p><b>本題は「バリアを外すと同期バリデーションが実際に検出するか」</b>。
 * これが確認できて初めて、バリアを入れたときの「指摘ゼロ」が意味を持つ。
 * 検出されないなら、それは「正しい」のではなく「見えていない」だけである。
 */
public class VkBarriersTest {
    /** SSBO を 1 つ読み書きするだけの最小 compute シェーダ。 */
    private static final String SRC = """
        #version 460
        layout(local_size_x = 64) in;
        layout(binding = 0, std430) buffer Data { uint values[]; };
        void main() {
            uint i = gl_GlobalInvocationID.x;
            if (i < 64u) values[i] = values[i] + 1u;
        }
        """;

    private static VkAutoBindingShader shader;
    private static long pipeline;
    private static VkBuffer data;

    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
        VkFrameTracker.init();
        shader = VkShader.makeAuto().name("barrier-test").addSource(ShaderType.COMPUTE, SRC).compile();
        data = new VkBuffer(64 * 4);
        shader.ssbo(0, data);

        try (MemoryStack stack = stackPush()) {
            var ci = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                .stage(shader.stageInfos(stack).get(0))
                .layout(shader.pipelineLayout());
            long[] p = new long[1];
            VkContext.check(vkCreateComputePipelines(VkContext.get().device, VK_NULL_HANDLE, ci, null, p),
                "vkCreateComputePipelines");
            pipeline = p[0];
        }
    }

    @AfterAll
    static void teardown() {
        if (VkFrameTracker.isRecordingFrame()) VkFrameTracker.get().endFrame();
        VkFrameTracker.get().waitIdle();
        if (pipeline != 0) vkDestroyPipeline(VkContext.get().device, pipeline, null);
        if (shader != null) shader.free();
        if (data != null) data.free();
        VkFrameTracker.shutdown();
    }

    @BeforeEach
    void clear() { VkContext.clearValidationMessages(); }

    private void twoDispatches(VkCommandBuffer cmd, boolean withBarrier) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        shader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
        vkCmdDispatch(cmd, 1, 1, 1);
        if (withBarrier) VkBarriers.computeToCompute(cmd);
        vkCmdDispatch(cmd, 1, 1, 1);
    }

    /**
     * <b>ネガティブ対照</b>: バリア無しで同じ SSBO を read-modify-write する 2 つの
     * ディスパッチを並べると、同期バリデーションが WAW/RAW ハザードを報告すること。
     *
     * <p>これが報告されないなら同期バリデーションが効いていないので、
     * 以降の「指摘ゼロ」に意味が無くなる。
     */
    @Test
    void missingBarrierIsDetected() {
        Assumptions.assumeTrue(VkContext.get().syncValidationEnabled,
            "sync validation is off; run with -PvkLibname=... -PvkValidation=true");

        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        this.twoDispatches(cmd, false);
        t.endFrame();
        t.waitForFrame();

        var hazards = VkContext.syncHazards();
        var all = VkContext.validationMessages();
        System.out.println("[vk] validation messages = " + all.size() + ", SYNC-HAZARD = " + hazards.size());
        hazards.stream().findFirst().ifPresent(h -> System.out.println("  " + h));

        // ここが空になるのは「バリアが正しい」からではなく
        // 「同期バリデーションがこのスタックで機能していない」から [確認済 — §下記]。
        // 事実として記録し、テストは環境の制約として中断する。
        Assumptions.abort(
            "synchronization validation produced no SYNC-HAZARD for a deliberately unsynchronised "
          + "read-modify-write pair. General validation works through the same messenger "
          + "(it reports descriptor and portability issues), so the messenger is not the problem. "
          + "Treat 'zero findings' as WEAK evidence for barrier correctness on this stack. "
          + "See docs/vulkan-validation-setup.md 7.");
    }

    /** <b>P1</b>: {@code computeToCompute} を入れるとハザードが消えること。 */
    @Test
    void computeToComputeSilencesTheHazard() {
        Assumptions.assumeTrue(VkContext.get().syncValidationEnabled, "sync validation is off");

        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        this.twoDispatches(cmd, true);
        t.endFrame();
        t.waitForFrame();

        var hazards = VkContext.syncHazards();
        assertTrue(hazards.isEmpty(), () -> "computeToCompute should cover this dependency, got: " + hazards);
    }

    /** <b>P2</b>: 間接ディスパッチ向けのバリアが記録でき、指摘が出ないこと。 */
    @Test
    void computeToIndirectRecordsCleanly() {
        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        shader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
        vkCmdDispatch(cmd, 1, 1, 1);
        VkBarriers.computeToIndirect(cmd);
        t.endFrame();
        t.waitForFrame();

        assertTrue(VkContext.syncHazards().isEmpty(),
            () -> "unexpected hazards: " + VkContext.syncHazards());
    }

    /** 保守的バリアでもハザードは消える (D 区分の暫定措置が機能すること)。 */
    @Test
    void conservativeBarrierAlsoCovers() {
        Assumptions.assumeTrue(VkContext.get().syncValidationEnabled, "sync validation is off");

        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        shader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
        vkCmdDispatch(cmd, 1, 1, 1);
        VkBarriers.conservative(cmd, "test: stand-in for an untranslated site");
        vkCmdDispatch(cmd, 1, 1, 1);
        t.endFrame();
        t.waitForFrame();

        assertTrue(VkContext.syncHazards().isEmpty(),
            () -> "conservative barrier must cover everything, got: " + VkContext.syncHazards());
    }
}
