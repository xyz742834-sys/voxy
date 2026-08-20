package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** sort_visibility.comp の CAS ループ上限化後もコンパイルできること。 */
public class SortVisibilityTest {
    @BeforeAll
    static void setup() { VulkanTestSupport.requireVulkan(); }

    @Test
    void compilesWithBoundedCasLoops() {
        var shader = VkShader.make(me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil.PRINTF_processor)
            .name("sort_visibility-test")
            .define("WORK_SIZE", 64)
            .define("ELEMS_PER_THREAD", 8)
            .define("OUTPUT_SIZE", 256)
            .define("VISIBILITY_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .define("NODE_DATA_BINDING", 3)
            .addSource(ShaderType.COMPUTE,
                VkShaderLoader.parse("voxy:lod/hierarchical/cleaner/sort_visibility.comp"))
            .compile();
        try {
            assertNotNull(shader.bindingAt(1));
            assertNotNull(shader.bindingAt(2));
            assertNotNull(shader.bindingAt(3));
            System.out.println("[vk] sort_visibility bindings=" + shader.bindings());
        } finally {
            shader.free();
        }
    }

    /** 無制限ループが残っていないこと (GPU ハング防止)。 */
    @Test
    void noUnboundedLoopsRemain() throws Exception {
        String raw;
        try (var in = java.util.Objects.requireNonNull(SortVisibilityTest.class.getResourceAsStream(
                "/assets/voxy/shaders/lod/hierarchical/cleaner/sort_visibility.comp"))) {
            raw = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertFalse(raw.contains("while (true)"),
            "unbounded GPU loops cause hard hangs and OS-level GPU resets");
        assertTrue(raw.contains("MAX_CAS_RETRIES"));
    }
}
