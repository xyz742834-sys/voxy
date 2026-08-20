package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.shader.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * traversal の 3 構成 descriptor set (カテゴリ B) と、
 * 反復ごとの push constant 更新を実際に記録・サブミットして検証する。
 */
public class TraversalDispatchTest {
    private static final int MAX_ITERATIONS = 5;   // WorldEngine.MAX_LOD_LAYER + 1

    // HierarchicalOcclusionTraverser の BINDING_COUNTER = 1 起点の採番
    private static final int SCENE_UNIFORM = 1, REQUEST_QUEUE = 2, RENDER_QUEUE = 3,
        NODE_DATA = 4, QUEUE_META = 6, QUEUE_SOURCE = 7, QUEUE_SINK = 8, RENDER_TRACKER = 9;

    private static VkShader shader;
    private static VkDescriptorSetGroup sets;
    private static long pipeline;
    private static final List<VkBuffer> buffers = new ArrayList<>();
    private static VkTexture hiZ;

    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
        VkFrameTracker.init();

        shader = VkShader.make(me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil.PRINTF_processor)
            .name("traversal-dispatch-test")
            .define("MAX_ITERATIONS", MAX_ITERATIONS)
            .define("LOCAL_SIZE_BITS", 5)
            .define("MAX_REQUEST_QUEUE_SIZE", 1024)
            .define("HIZ_BINDING", 0)
            .define("SCENE_UNIFORM_BINDING", SCENE_UNIFORM)
            .define("REQUEST_QUEUE_BINDING", REQUEST_QUEUE)
            .define("RENDER_QUEUE_BINDING", RENDER_QUEUE)
            .define("NODE_DATA_BINDING", NODE_DATA)
            .define("NODE_QUEUE_META_BINDING", QUEUE_META)
            .define("NODE_QUEUE_SOURCE_BINDING", QUEUE_SOURCE)
            .define("NODE_QUEUE_SINK_BINDING", QUEUE_SINK)
            .define("RENDER_TRACKER_BINDING", RENDER_TRACKER)
            .addSource(ShaderType.COMPUTE, VkShaderLoader.parse("voxy:lod/hierarchical/traversal_dev.comp"))
            .compile();
    }

    @AfterAll
    static void teardown() {
        if (VkFrameTracker.isRecordingFrame()) VkFrameTracker.get().endFrame();
        VkFrameTracker.get().waitIdle();
        if (pipeline != 0) vkDestroyPipeline(VkContext.get().device, pipeline, null);
        if (sets != null) sets.free();
        if (shader != null) shader.free();
        if (hiZ != null) { hiZ.free(); hiZ = null; }
        VkSampler.shutdown();
        VkQuadIndexBuffer.shutdown();
        buffers.forEach(VkBuffer::free);
        buffers.clear();
        VkFrameTracker.shutdown();
    }

    private static VkBuffer buf(long size) {
        var b = new VkBuffer(size);
        buffers.add(b);
        return b;
    }

    /** 3 構成の set を作り、フリップフロップが期待どおり割り当てられること。 */
    @Test
    void threeVariantsCoverTheFlipFlop() {
        var uniform    = buf(1024);
        var request    = buf(1024 * 8 + 8);
        var renderList = buf(1024 * 4 + 4);
        var nodeData   = buf(1024 * 16);
        var queueMeta  = buf(16L * MAX_ITERATIONS);
        var topNodeIds = buf(1024 * 4);
        var scratchA   = buf(1024 * 4);
        var scratchB   = buf(1024 * 4);
        var tracker    = buf(1024 * 4);

        sets = new VkDescriptorSetGroup(shader, 3);
        sets.shared(SCENE_UNIFORM, uniform)
            .shared(REQUEST_QUEUE, request)
            .shared(RENDER_QUEUE, renderList)
            .shared(NODE_DATA, nodeData)
            .shared(QUEUE_META, queueMeta)
            .shared(RENDER_TRACKER, tracker);

        // (SOURCE, SINK) の 3 通り
        sets.variant(0, QUEUE_SOURCE, topNodeIds).variant(0, QUEUE_SINK, scratchB);
        sets.variant(1, QUEUE_SOURCE, scratchB).variant(1, QUEUE_SINK, scratchA);
        sets.variant(2, QUEUE_SOURCE, scratchA).variant(2, QUEUE_SINK, scratchB);

        // hiZ サンプラ (binding 0)。mip チェーンを持つ深度テクスチャ
        if (hiZ == null) {
            hiZ = new VkTexture(VK_FORMAT_D32_SFLOAT, 4, 64, 64,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
        }
        sets.sharedTexture(0, hiZ, VkSampler.nearestMipClamp());

        for (int v = 0; v < 3; v++) {
            assertEquals(List.of(), sets.unbound(v), "every binding must now be assigned");
        }
        sets.update();
    }

    /** update() 前に bind しようとしたら落ちること (記録後更新の事故防止)。 */
    @Test
    void bindBeforeUpdateIsRejected() {
        var g = new VkDescriptorSetGroup(shader, 2);
        try {
            var b = buf(256);
            g.shared(SCENE_UNIFORM, b);
            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            assertThrows(IllegalStateException.class,
                () -> g.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, 0));
            t.endFrame();
            t.waitIdle();
        } finally {
            g.free();
        }
    }

    /**
     * 実際のディスパッチループを記録してサブミットする。
     * 反復ごとに variant を切り替え、push constant を流し直す。
     *
     * <p>hiZ サンプラを含む全 binding を割り当てたうえで <b>実際に {@code vkCmdDispatch} し、
     * サブミットして完了まで待つ</b>。バリデーション (同期バリデーション込み) で
     * 指摘が出ないことが本題。
     */
    @Test
    void recordsFullDispatchLoop() {
        this.threeVariantsCoverTheFlipFlop();   // sets を用意
        var ctx = VkContext.get();

        // 計算パイプラインを作る
        try (MemoryStack stack = stackPush()) {
            var ci = VkComputePipelineCreateInfo.calloc(1, stack)
                .sType$Default()
                .stage(shader.stageInfos(stack).get(0))
                .layout(shader.pipelineLayout());
            long[] p = new long[1];
            VkContext.check(vkCreateComputePipelines(ctx.device, VK_NULL_HANDLE, ci, null, p),
                "vkCreateComputePipelines");
            pipeline = p[0];
        }

        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();

        // hiZ は UNDEFINED で作られるので、サンプリング可能なレイアウトへ遷移させる。
        // レベルごとに追跡されているため 4 レベルぶん発行される。
        hiZ.barrierAll(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);

        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);

        // iter 0: variant 0, queueIdx = 0
        recordIteration(cmd, 0, 0);
        // iter 1..4: 奇数 -> variant 1, 偶数 -> variant 2
        for (int iter = 1; iter < MAX_ITERATIONS; iter++) {
            recordIteration(cmd, iter, (iter & 1) == 0 ? 2 : 1);
        }

        t.endFrame();
        t.waitForFrame();
        assertEquals(t.current(), t.completed(), "the whole dispatch loop must complete");
    }

    private void recordIteration(org.lwjgl.vulkan.VkCommandBuffer cmd, int iter, int variant) {
        sets.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, variant);

        // queueIdx は反復ごとに変わる -> 各記録の直前に流す
        try (MemoryStack stack = stackPush()) {
            vkCmdPushConstants(cmd, shader.pipelineLayout(), shader.pushConstantStages(), 0,
                stack.ints(iter));
        }
        vkCmdDispatch(cmd, 1, 1, 1);

        // P1: 次の反復は前の反復が書いた SINK を SOURCE として読む (compute -> compute)。
        // 元は Traverser:340 の glMemoryBarrier(SHADER_STORAGE|COMMAND) だが、
        // このテストは間接ディスパッチではなく直接ディスパッチなので COMMAND 側は不要。
        VkBarriers.computeToCompute(cmd);
    }
}
