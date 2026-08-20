package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/** Stage 0: dynamic_rendering によるオフスクリーン描画と読み戻し。 */
public class VkRenderTargetTest {
    private static final int W = 64, H = 64;

    /** 頂点入力なしでフルスクリーン三角形を出す (gl_VertexIndex から座標を作る)。 */
    private static final String VERT = """
        #version 460
        void main() {
            vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;
    private static final String FRAG = """
        #version 460
        layout(location = 0) out vec4 outColour;
        void main() { outColour = vec4(0.0, 1.0, 0.0, 1.0); }
        """;

    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
        VkFrameTracker.init();
    }

    @AfterAll
    static void teardown() {
        if (VkFrameTracker.isRecordingFrame()) VkFrameTracker.get().endFrame();
        VkFrameTracker.get().waitIdle();
        VkFrameTracker.shutdown();
    }

    /** 1.4 コア機能が本当に有効か。これが無いと以降が成り立たない。 */
    @Test
    void dynamicRenderingIsAvailable() {
        assertTrue(VkContext.get().hasVulkan14(),
            "dynamic_rendering はコア機能として使う前提 (Vulkan 1.4 要求)");
    }

    /** クリアだけして読み戻す。描画パイプライン抜きでターゲットが動くこと。 */
    @Test
    void clearAndReadback() {
        var rt = new VkRenderTarget(W, H);
        var t = VkFrameTracker.get();
        try {
            var cmd = t.beginFrame();
            rt.beginRendering(cmd, new float[]{1.0f, 0.0f, 0.0f, 1.0f}, VkDepth.CLEAR);
            rt.endRendering(cmd);
            rt.recordReadback(cmd);
            t.endFrame();
            t.waitForFrame();

            var px = rt.pixelAt(W / 2, H / 2);
            assertArrayEquals(new int[]{255, 0, 0, 255}, px,
                "clear colour must survive the round trip");
        } finally {
            rt.free();
        }
    }

    /** グラフィクスパイプラインで実際に描いて読み戻す。 */
    @Test
    void drawAndReadback() {
        var shader = VkShader.make().name("stage0-triangle")
            .addSource(ShaderType.VERTEX, VERT)
            .addSource(ShaderType.FRAGMENT, FRAG)
            .compile();
        var pipeline = VkGraphicsPipeline.builder(shader)
            .depthTest(false).depthWrite(false)
            .build();
        var rt = new VkRenderTarget(W, H);
        var t = VkFrameTracker.get();
        try {
            var cmd = t.beginFrame();
            rt.beginRendering(cmd, new float[]{1.0f, 0.0f, 0.0f, 1.0f}, VkDepth.CLEAR);
            pipeline.bind(cmd);
            vkCmdDraw(cmd, 3, 1, 0, 0);     // 頂点バッファ無しの 3 頂点
            rt.endRendering(cmd);
            rt.recordReadback(cmd);
            t.endFrame();
            t.waitForFrame();

            var centre = rt.pixelAt(W / 2, H / 2);
            assertArrayEquals(new int[]{0, 255, 0, 255}, centre,
                "the fullscreen triangle must cover the centre; got " + java.util.Arrays.toString(centre));
        } finally {
            rt.free();
            pipeline.free();
            shader.free();
        }
    }

    /**
     * <b>Stage 2b の検証手段そのもの</b>: 2 つのターゲットを比較して差分ゼロを確かめる。
     * 同じ内容を描けば差分は 0 になるはず。
     */
    @Test
    void identicalRendersCompareEqual() {
        var a = new VkRenderTarget(W, H);
        var b = new VkRenderTarget(W, H);
        var t = VkFrameTracker.get();
        try {
            for (var rt : new VkRenderTarget[]{a, b}) {
                var cmd = t.beginFrame();
                rt.beginRendering(cmd, new float[]{0.25f, 0.5f, 0.75f, 1.0f}, VkDepth.CLEAR);
                rt.endRendering(cmd);
                rt.recordReadback(cmd);
                t.endFrame();
                t.waitForFrame();
            }
            assertEquals(0, VkRenderTarget.compareColor(a, b),
                "identical renders must be pixel-identical");
        } finally {
            a.free();
            b.free();
        }
    }

    /** 比較が差分を実際に検出すること (対照)。 */
    @Test
    void differingRendersAreDetected() {
        var a = new VkRenderTarget(W, H);
        var b = new VkRenderTarget(W, H);
        var t = VkFrameTracker.get();
        try {
            var cmd = t.beginFrame();
            a.beginRendering(cmd, new float[]{1, 0, 0, 1}, VkDepth.CLEAR);
            a.endRendering(cmd);
            a.recordReadback(cmd);
            t.endFrame();
            t.waitForFrame();

            cmd = t.beginFrame();
            b.beginRendering(cmd, new float[]{0, 0, 1, 1}, VkDepth.CLEAR);
            b.endRendering(cmd);
            b.recordReadback(cmd);
            t.endFrame();
            t.waitForFrame();

            assertEquals((long) W * H, VkRenderTarget.compareColor(a, b),
                "every pixel differs, so the comparison must report all of them");
        } finally {
            a.free();
            b.free();
        }
    }

    /** PNG 出力。人間が見て判断するための手段 (Stage 1 の完了条件に使う)。 */
    @Test
    void writesPng() throws Exception {
        var shader = VkShader.make().name("png-triangle")
            .addSource(ShaderType.VERTEX, VERT)
            .addSource(ShaderType.FRAGMENT, FRAG)
            .compile();
        var pipeline = VkGraphicsPipeline.builder(shader).depthTest(false).depthWrite(false).build();
        var rt = new VkRenderTarget(W, H);
        var t = VkFrameTracker.get();
        var out = java.nio.file.Path.of("build", "vk-test-output", "triangle.png");
        try {
            var cmd = t.beginFrame();
            rt.beginRendering(cmd, new float[]{0.1f, 0.1f, 0.2f, 1.0f}, VkDepth.CLEAR);
            pipeline.bind(cmd);
            vkCmdDraw(cmd, 3, 1, 0, 0);
            rt.endRendering(cmd);
            rt.recordReadback(cmd);
            t.endFrame();
            t.waitForFrame();

            rt.writePng(out);
            assertTrue(java.nio.file.Files.exists(out), "PNG must be written");
            assertTrue(java.nio.file.Files.size(out) > 0);
            System.out.println("[vk] wrote " + out.toAbsolutePath());
        } finally {
            rt.free(); pipeline.free(); shader.free();
        }
    }

    /** 差分 PNG。Stage 2b でずれた箇所を目で見るため。 */
    @Test
    void writesDiffPng() throws Exception {
        var a = new VkRenderTarget(W, H);
        var b = new VkRenderTarget(W, H);
        var t = VkFrameTracker.get();
        var out = java.nio.file.Path.of("build", "vk-test-output", "diff.png");
        try {
            for (var pair : new Object[][]{{a, 1.0f}, {b, 0.0f}}) {
                var rt = (VkRenderTarget) pair[0];
                float r = (Float) pair[1];
                var cmd = t.beginFrame();
                rt.beginRendering(cmd, new float[]{r, 0, 0, 1}, VkDepth.CLEAR);
                rt.endRendering(cmd);
                rt.recordReadback(cmd);
                t.endFrame();
                t.waitForFrame();
            }
            VkRenderTarget.writeDiffPng(a, b, out);
            assertTrue(java.nio.file.Files.exists(out));
            System.out.println("[vk] wrote " + out.toAbsolutePath());
        } finally {
            a.free(); b.free();
        }
    }

    /** レイアウトがフレームを跨いで追跡され続けること (2 フレーム描いても壊れない)。 */
    @Test
    void survivesMultipleFrames() {
        var rt = new VkRenderTarget(W, H);
        var t = VkFrameTracker.get();
        try {
            for (int i = 0; i < 3; i++) {
                float v = i / 3.0f;
                var cmd = t.beginFrame();
                rt.beginRendering(cmd, new float[]{v, v, v, 1.0f}, VkDepth.CLEAR);
                rt.endRendering(cmd);
                rt.recordReadback(cmd);
                t.endFrame();
                t.waitForFrame();
                int expect = Math.round(v * 255);
                assertEquals(expect, rt.pixelAt(0, 0)[0], 1, "frame " + i);
            }
        } finally {
            rt.free();
        }
    }
}
