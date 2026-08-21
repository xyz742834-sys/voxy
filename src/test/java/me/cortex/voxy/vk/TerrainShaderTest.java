package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.shader.SpirvReflect;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 検証 2 / 3 / 4: 地形シェーダが SPIR-V になり、リフレクションが正しい
 * binding / type / stage を返し、C-1 / C-2 が解消されていること。
 */
public class TerrainShaderTest {
    private static final String VERT = "voxy:lod/gl46/quads3.vert";
    private static final String FRAG = "voxy:lod/gl46/quads.frag";

    @BeforeAll
    static void setup() { VulkanTestSupport.requireVulkan(); }

    /**
     * 本番では {@code AbstractSectionRenderer.addDirectionalFaceTint} が
     * {@code ClientLevel.cardinalLighting()} から供給する。{@code #ifdef} ガードが無いため
     * 未定義だとコンパイルできない。テストでは代表値を入れる。
     * 詳細は docs/phase2-glsl-compat.md 4.
     */
    private static VkShader.Builder<VkShader> terrainBuilder(String name) {
        // ⚠ quads.frag は DEPTH_SCALAR_COMPARE を使う = 深度規約が要る。
        // 渡さないと非逆Z側の分岐に落ち、**深度で落とす向きが裏返る** [5c-4a のガード]
        return me.cortex.voxy.client.core.vk.VkDepth.defines(VkShader.make().name(name))
            .define("NO_SHADE_FACE_TINT", "1.0")
            .define("UP_FACE_TINT", "1.0")
            .define("DOWN_FACE_TINT", "0.5")
            .define("Z_AXIS_FACE_TINT", "0.8")
            .define("X_AXIS_FACE_TINT", "0.6");
    }

    private static VkShader compileTerrain() {
        return terrainBuilder("terrain-test")
            .addSource(ShaderType.VERTEX, VkShaderLoader.parse(VERT))
            .addSource(ShaderType.FRAGMENT, VkShaderLoader.parse(FRAG))
            .compile();
    }

    /** 検証 2: 地形シェーダが SPIR-V にコンパイルできること。 */
    @Test
    void terrainCompilesToSpirv() {
        var shader = compileTerrain();
        try {
            assertNotEquals(0L, shader.descriptorSetLayout());
            assertNotEquals(0L, shader.pipelineLayout());
            System.out.println("[vk] terrain bindings: " + shader.bindings());
        } finally {
            shader.free();
        }
    }

    /** 検証 3: リフレクションが期待どおりの binding / type を返すこと。 */
    @Test
    void reflectionReportsExpectedBindings() {
        var shader = compileTerrain();
        try {
            // docs/phase2-binding-audit.md 8.2 の表と一致すること
            assertBinding(shader, 0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);          // SceneUniform
            assertBinding(shader, 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);          // QuadBuffer
            assertBinding(shader, 2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);  // depthTex
            assertBinding(shader, 3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);          // ModelBuffer
            assertBinding(shader, 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);          // ModelColourBuffer
            assertBinding(shader, 5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);          // PositionScratchBuffer
            assertBinding(shader, 6, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);  // lightSampler  (C-2 後)
            assertBinding(shader, 7, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);  // blockModelAtlas (C-1 後)

            // set は必ず 0 (規約)
            for (var b : shader.bindings()) assertEquals(0, b.set(), "all resources live in set 0");

            // 想定外の binding が増えていないこと
            var nums = shader.bindings().stream().map(SpirvReflect.Binding::binding).sorted().toList();
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), nums);
        } finally {
            shader.free();
        }
    }

    /** 検証 4a: 現行の番号では衝突しないこと。 */
    @Test
    void currentBindingsHaveNoCollision() {
        // compile() が衝突時に例外を投げるので、通ること自体が検証になる
        var shader = assertDoesNotThrow(TerrainShaderTest::compileTerrain);
        shader.free();
    }

    /**
     * 検証 4b: <b>修正前の番号に戻すと衝突として検出されること。</b>
     * 検出機構が生きていることの確認であり、これが落ちると
     * 「衝突が無い」ではなく「検出できていない」を意味する。
     */
    @Test
    void oldLightingBindingIsDetectedAsCollision() {
        // C-2: LIGHTING_SAMPLER_BINDING を 6 -> 1 に戻す (QUAD_BUFFER_BINDING と衝突)
        String vert = VkShaderLoader.parse(VERT)
            .replace("#define LIGHTING_SAMPLER_BINDING 6", "#define LIGHTING_SAMPLER_BINDING 1");
        assertTrue(vert.contains("#define LIGHTING_SAMPLER_BINDING 1"), "test fixture must actually patch");

        var e = assertThrows(IllegalStateException.class, () ->
            terrainBuilder("terrain-C2-regression")
                .addSource(ShaderType.VERTEX, vert)
                .addSource(ShaderType.FRAGMENT, VkShaderLoader.parse(FRAG))
                .compile());

        System.out.println("[vk] C-2 detection:\n" + e.getMessage());
        assertTrue(e.getMessage().contains("binding 1"), "should name the colliding binding");
        assertTrue(e.getMessage().contains("STORAGE_BUFFER"));
        assertTrue(e.getMessage().contains("COMBINED_IMAGE_SAMPLER"));
    }

    /** 検証 4c: C-1 も同様に検出されること。 */
    @Test
    void oldAtlasBindingIsDetectedAsCollision() {
        // C-1: blockModelAtlas を 7 -> 0 に戻す (SceneUniform UBO と衝突)
        String frag = VkShaderLoader.parse(FRAG)
            .replace("layout(binding = 7) uniform sampler2D blockModelAtlas;",
                     "layout(binding = 0) uniform sampler2D blockModelAtlas;");
        assertTrue(frag.contains("layout(binding = 0) uniform sampler2D blockModelAtlas;"),
            "test fixture must actually patch");

        var e = assertThrows(IllegalStateException.class, () ->
            terrainBuilder("terrain-C1-regression")
                .addSource(ShaderType.VERTEX, VkShaderLoader.parse(VERT))
                .addSource(ShaderType.FRAGMENT, frag)
                .compile());

        System.out.println("[vk] C-1 detection:\n" + e.getMessage());
        assertTrue(e.getMessage().contains("binding 0"), "should name the colliding binding");
        assertTrue(e.getMessage().contains("UNIFORM_BUFFER"));
        assertTrue(e.getMessage().contains("COMBINED_IMAGE_SAMPLER"));
    }

    /** 半透明バリアント (TRANSLUCENT define) も通ること。 */
    @Test
    void translucentVariantCompiles() {
        var shader = terrainBuilder("terrain-translucent-test")
            .define("TRANSLUCENT")
            .addSource(ShaderType.VERTEX, VkShaderLoader.parse(VERT))
            .addSource(ShaderType.FRAGMENT, VkShaderLoader.parse(FRAG))
            .compile();
        shader.free();
    }

    private static void assertBinding(VkShader shader, int index, int expectedType) {
        var b = shader.bindingAt(index);
        assertNotNull(b, () -> "no descriptor at binding " + index + "; have: "
            + shader.bindings().stream().map(x -> x.binding() + ":" + x.typeName())
                .collect(Collectors.joining(", ")));
        assertEquals(expectedType, b.descriptorType(),
            () -> "binding " + index + " ('" + b.name() + "') is " + b.typeName());
    }
}
