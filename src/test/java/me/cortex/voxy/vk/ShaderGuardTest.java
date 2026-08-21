package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 検証 5: 未移行のシェーダが「黙って失敗」せず、原因の分かる例外で落ちること。
 *
 * <p>これらのガードは shaderc に渡す<b>前</b>に発火する。shaderc のエラーは
 * "undeclared identifier" のように原因が読み取れないため。
 */
public class ShaderGuardTest {
    @BeforeAll
    static void setup() { VulkanTestSupport.requireVulkan(); }

    /**
     * default-block uniform (`layout(location=N) uniform ...`) を含むシェーダ。
     * 追跡リストは docs/phase2-pushconstant-todo.md。
     */
    @Test
    void defaultBlockUniformIsRejectedWithGuidance() {
        var e = assertThrows(IllegalStateException.class, () ->
            VkShader.make().name("scatter-guard")
                .define("INPUT_BUFFER_BINDING", 0)
                .define("OUTPUT_BUFFER1_BINDING", 1)
                .define("OUTPUT_BUFFER2_BINDING", 2)
                .addSource(ShaderType.COMPUTE, VkShaderLoader.parse("voxy:util/scatter.comp"))
                .compile());

        System.out.println("[vk] default-block uniform guard:\n" + e.getMessage());
        assertTrue(e.getMessage().contains("default-block uniform"),
            "must name the actual problem, not a shaderc parse error");
        assertTrue(e.getMessage().contains("push constant"), "must say what to do about it");
        assertTrue(e.getMessage().contains("uniform uint count"), "must quote the offending declaration");
        assertTrue(e.getMessage().contains("phase2-pushconstant-todo.md"), "must point at the tracking list");
    }

    /**
     * gl_InstanceID は意味論が違うため互換プレリュードで置換していない。
     * 詳細は docs/phase2-glsl-compat.md 3.1。
     */
    @Test
    void unshimmedInstanceIdIsRejectedWithGuidance() {
        // ⚠ 深度規約の define を**渡しておく**。raster.vert は depthutils を取り込むので、
        // 渡さないと 5c-4a のガードが先に発火し、**この検査が見たいものに届かない**。
        // (実際にそうなった。ガードは早く弾くほうが正しいので、検査の側を直した)
        var e = assertThrows(IllegalStateException.class, () ->
            me.cortex.voxy.client.core.vk.VkDepth.defines(
                VkShader.make().name("raster-guard")
                    .addSource(ShaderType.VERTEX, VkShaderLoader.parse("voxy:lod/gl46/cull/raster.vert")))
                .compile());

        System.out.println("[vk] gl_InstanceID guard:\n" + e.getMessage());
        assertTrue(e.getMessage().contains("gl_InstanceID"));
        assertTrue(e.getMessage().contains("baseInstance"),
            "must explain the semantic difference, since that is the trap");
        assertTrue(e.getMessage().contains("phase2-glsl-compat.md"));
    }

    /** 互換プレリュードが実際に注入されていること (docs/phase2-glsl-compat.md T-2)。 */
    @Test
    void compatPreludeIsInjected() {
        String src = VkShaderLoader.parse("voxy:lod/gl46/quads3.vert");
        assertTrue(src.contains("#define gl_VertexID gl_VertexIndex"),
            "gl_VertexID shim must be present in the Vulkan path");
        assertTrue(VkShaderLoader.compatPrelude().contains("gl_VertexID"));
        // gl_InstanceID は意図的に含めない
        assertFalse(VkShaderLoader.compatPrelude().contains("gl_InstanceID"),
            "gl_InstanceID must NOT be shimmed; see docs/phase2-glsl-compat.md 3.1");
    }

    /** GL 側のシェーダソースは変更していないこと (Vulkan 経路だけが変換する)。 */
    @Test
    void glSourcesAreUntouched() throws Exception {
        // VkShaderLoader が付ける行を除けば、生のリソースには shim が入っていない
        String raw;
        try (var in = java.util.Objects.requireNonNull(ShaderGuardTest.class.getResourceAsStream(
                "/assets/voxy/shaders/lod/gl46/quads3.vert"))) {
            raw = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertFalse(raw.contains("gl_VertexIndex"),
            "the on-disk shader must stay GL-native; the shim is injected at load time only");
        assertTrue(raw.contains("gl_VertexID"));
    }

    /**
     * <b>深度規約の define を忘れた Vulkan シェーダはコンパイルできないこと</b> (Phase 5c-4a)。
     *
     * <p>忘れると {@code depthutils.glsl} が非逆Z側の分岐に落ち、
     * {@code REDUCTION} が min↔max、{@code CLOSER_SIGN} が符号反転、
     * {@code NEAR}/{@code FAR} が入れ替わる。
     * <b>落ちないし警告も出ない</b> — 5c-3a と 5c-4a の両方で変異を入れて確かめた壊れ方である。
     *
     * <p>⚠ <b>実在のシェーダで試す。</b> 手で書いた断片では
     * {@code #import} が展開されず、<b>ガードの発火条件を満たさない</b>
     * (実際に最初そう書いて空振りした)。
     */
    @Test
    void aVulkanShaderThatForgetsTheDepthConventionCannotCompile() {
        var e = assertThrows(IllegalStateException.class, () ->
            VkShader.make().name("depthutils-guard")
                .addSource(ShaderType.FRAGMENT, VkShaderLoader.parse("voxy:hiz/blit.fsh"))
                .compile());

        System.out.println("[vk] depth convention guard:\n" + e.getMessage());
        assertTrue(e.getMessage().contains("VkDepth.defines"),
            "must say what to call: " + e.getMessage());
        assertTrue(e.getMessage().contains("USE_REVERSE_Z"),
            "must name the missing define: " + e.getMessage());
        assertTrue(e.getMessage().contains("only the picture is wrong"),
            "must say why it matters — this failure has no other symptom");

        // ...そして**定義があれば通る**。これが無いと「常に落ちるガード」と区別が付かない
        assertDoesNotThrow(() ->
            me.cortex.voxy.client.core.vk.VkDepth.defines(
                VkShader.make().name("depthutils-guard-ok")
                    .addSource(ShaderType.FRAGMENT, VkShaderLoader.parse("voxy:hiz/blit.fsh")))
                .compile().free(),
            "a shader built through VkDepth.defines must still compile");
    }
}
