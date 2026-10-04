package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.mcnative.McNativeMarkerDraw;
import me.cortex.voxy.client.core.vk.shader.SpirvCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderType;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link McNativeMarkerDraw} のうち、Minecraft のデバイスを要らない部分を固定する。
 *
 * <p>実際に MC の Vulkan バックエンドへ記録できたかは
 * {@code scripts/verify.py --only native} が測る (MC 自身の検証レイヤが有効なので、
 * 記録が誤っていればそのステージが落ちる)。ここで確かめるのは、そこへ持ち込む
 * 材料が正しいこと — GLSL が本当に SPIR-V になること — と、
 * <b>フラグが無い限り何もしない</b>ことである。
 */
public class McNativeMarkerDrawTest {

    @Test
    void theMarkerShadersCompileToSpirv() {
        for (var pair : new Object[][] {
            {VkShaderType.VERTEX, McNativeMarkerDraw.VERTEX_SOURCE, "marker.vert"},
            {VkShaderType.FRAGMENT, McNativeMarkerDraw.FRAGMENT_SOURCE, "marker.frag"}}) {
            ByteBuffer spirv = SpirvCompiler.compile((VkShaderType) pair[0], (String) pair[1], (String) pair[2]);
            assertNotNull(spirv, pair[2] + " produced no SPIR-V");
            assertTrue(spirv.remaining() >= 20 * 4, pair[2] + " produced a suspiciously small module");
            assertEquals(0x07230203, spirv.getInt(spirv.position()),
                pair[2] + " does not start with the SPIR-V magic number");
        }
    }

    @Test
    void theDrawIsOffUnlessItsFlagIsSet() {
        assertFalse(Boolean.getBoolean(McNativeMarkerDraw.FLAG),
            "the marker must not be enabled by default in a test JVM");
        var before = McNativeMarkerDraw.status();
        assertFalse(before.enabled());
        assertEquals(0, before.drawsRecorded());
        assertFalse(before.pipelineLive());

        // Minecraft is not running here; this must still be a silent no-op.
        assertDoesNotThrow(McNativeMarkerDraw::renderIfEnabled);
        assertDoesNotThrow(McNativeMarkerDraw::shutdown);

        var after = McNativeMarkerDraw.status();
        assertEquals(0, after.drawsRecorded(), "a disabled marker must record nothing");
        assertEquals(before.notes(), after.notes(), "a disabled marker must not accumulate notes");
    }

    @Test
    void theMarkerColourIsOneAScreenshotCanFind() {
        assertEquals(255, McNativeMarkerDraw.MARKER_R);
        assertEquals(0, McNativeMarkerDraw.MARKER_G);
        assertEquals(255, McNativeMarkerDraw.MARKER_B);
    }
}
