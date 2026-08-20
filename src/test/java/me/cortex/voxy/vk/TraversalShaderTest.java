package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * queue.glsl の push constant 移行の検証。
 *
 * <p>binding 番号は {@code HierarchicalOcclusionTraverser} の
 * {@code BINDING_COUNTER = 1} からの採番に合わせる。
 */
public class TraversalShaderTest {
    @BeforeAll
    static void setup() { VulkanTestSupport.requireVulkan(); }

    private static VkShader.Builder<VkShader> traversalBuilder(String name) {
        // 本番の HierarchicalOcclusionTraverser と同じく printf プロセッサを通す。
        // 通さないと traversal_dev.comp の printf(...) が GL_EXT_debug_printf 要求で落ちる。
        return VkShader.make(me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil.PRINTF_processor)
            .name(name)
            .define("MAX_ITERATIONS", 5)          // WorldEngine.MAX_LOD_LAYER + 1
            .define("LOCAL_SIZE_BITS", 5)
            .define("MAX_REQUEST_QUEUE_SIZE", 1024)
            .define("HIZ_BINDING", 0)
            .define("SCENE_UNIFORM_BINDING", 1)
            .define("REQUEST_QUEUE_BINDING", 2)
            .define("RENDER_QUEUE_BINDING", 3)
            .define("NODE_DATA_BINDING", 4)
            // NODE_QUEUE_INDEX_BINDING は push constant になったので不要
            .define("NODE_QUEUE_META_BINDING", 6)
            .define("NODE_QUEUE_SOURCE_BINDING", 7)
            .define("NODE_QUEUE_SINK_BINDING", 8)
            .define("RENDER_TRACKER_BINDING", 9);
    }

    /** 移行後、traversal が SPIR-V にコンパイルできること。 */
    @Test
    void traversalCompilesWithPushConstant() {
        var shader = traversalBuilder("traversal-test")
            .addSource(ShaderType.COMPUTE, VkShaderLoader.parse("voxy:lod/hierarchical/traversal_dev.comp"))
            .compile();
        try {
            System.out.println("[vk] traversal push=" + shader.pushConstantSize()
                + "B stages=0x" + Integer.toHexString(shader.pushConstantStages())
                + " bindings=" + shader.bindings());

            // queueIdx (uint) 1 個ぶん
            assertEquals(4, shader.pushConstantSize(), "queueIdx is a single uint");

            // stageFlags はリフレクション由来 = COMPUTE のみ (全ステージに広げない)
            assertEquals(VK_SHADER_STAGE_COMPUTE_BIT, shader.pushConstantStages());

            // descriptor 側: 5 (旧 NODE_QUEUE_INDEX_BINDING) は使われなくなっている
            assertNull(shader.bindingAt(5), "binding 5 was the uniform location; it is a push constant now");
            assertNotNull(shader.bindingAt(1), "SceneUniform");
            assertEquals(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, shader.bindingAt(1).descriptorType());
            for (int b : new int[]{2, 4, 6, 7, 8, 9}) {
                assertNotNull(shader.bindingAt(b), "SSBO at binding " + b);
                assertEquals(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, shader.bindingAt(b).descriptorType(),
                    "binding " + b);
            }
            assertNotNull(shader.bindingAt(0), "hiZ sampler");
            assertEquals(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, shader.bindingAt(0).descriptorType());
        } finally {
            shader.free();
        }
    }

    /**
     * {@code #ifdef VULKAN} が実際に Vulkan 分岐を選ぶこと。
     *
     * <p>VULKAN マクロは shaderc/glslang が Vulkan ターゲット時に自動定義するもので、
     * 互換プレリュードでは定義していない (自前定義すると Macro redefined になる)。
     * したがって「注入されたか」ではなく「展開結果がどうなったか」で検証する。
     */
    @Test
    void vulkanBranchIsSelectedByPreprocessor() {
        String src = VkShaderLoader.parse("voxy:lod/hierarchical/traversal_dev.comp");
        String expanded = me.cortex.voxy.client.core.vk.shader.SpirvCompiler.preprocess(
            me.cortex.voxy.client.core.vk.shader.VkShaderType.COMPUTE, src, "queue-branch-test");
        assertNotNull(expanded, "preprocessing must succeed");
        assertTrue(expanded.contains("push_constant"),
            "the Vulkan branch of queue.glsl must survive preprocessing");
        assertFalse(expanded.contains("uniform uint queueIdx;"),
            "the GL default-block uniform must be preprocessed away");
    }

    /** GL 側のソースは push constant を持たない = GL バックエンドは無傷。 */
    @Test
    void glBranchIsPreservedOnDisk() throws Exception {
        String raw;
        try (var in = java.util.Objects.requireNonNull(TraversalShaderTest.class.getResourceAsStream(
                "/assets/voxy/shaders/lod/hierarchical/queue.glsl"))) {
            raw = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(raw.contains("#ifdef VULKAN"), "both backends must coexist in one file");
        assertTrue(raw.contains("#else"));
        assertTrue(raw.contains("layout(location = NODE_QUEUE_INDEX_BINDING) uniform uint queueIdx;"),
            "the GL declaration must stay intact -- it is the reference spec for the port");
    }
}
