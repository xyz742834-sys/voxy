package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** テスト環境そのものの疎通確認。Vulkan デバイスを必要としない。 */
public class SmokeTest {
    @Test
    void shaderResourcesAreOnTheTestClasspath() {
        String src = VkShaderLoader.parse("voxy:lod/gl46/quads3.vert");
        assertTrue(src.startsWith("#version 460\n"), "should carry a Vulkan-style #version");
        assertFalse(src.contains("#import"), "all #import directives should be expanded");
        // bindings.glsl 由来の宣言が展開されて入っていること
        assertTrue(src.contains("SceneUniform"), "bindings.glsl should have been inlined");
        assertTrue(src.contains("quadData"), "QuadBuffer should have been inlined");
    }

    @Test
    void importExpansionIsRecursive() {
        // quads3.vert -> quad_util.glsl -> lighting.glsl -> getLightmapUv
        String src = VkShaderLoader.parse("voxy:lod/gl46/quads3.vert");
        assertTrue(src.contains("getLightmapUv"),
            "lighting.glsl is reached only through quad_util.glsl, so this proves recursive expansion");
    }

    @Test
    void missingShaderFailsClearly() {
        var e = assertThrows(IllegalArgumentException.class,
            () -> VkShaderLoader.parse("voxy:does/not/exist.comp"));
        assertTrue(e.getMessage().contains("does/not/exist.comp"));
    }
}
