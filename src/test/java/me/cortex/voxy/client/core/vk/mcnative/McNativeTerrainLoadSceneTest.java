package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.client.core.vk.SyntheticTerrain;
import me.cortex.voxy.client.core.vk.VkCullPass;
import me.cortex.voxy.client.core.vk.VkQuadIndexBuffer;
import me.cortex.voxy.client.core.vk.VkRenderTarget;
import me.cortex.voxy.client.core.vk.VkSampler;
import me.cortex.voxy.vk.VulkanTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * terrain-LOAD の参照シーンを<b>この機の GPU で</b>描き、足跡が梯子の帯の内側に収まること、
 * 色と深度が一致して「幾何の有無」を言うこと、深度が梯子の地形区間 (2⁻¹², 2⁻¹⁰] の両側に
 * 落ちることを確かめる。Minecraft は要らない (Voxy 自身の context)。MoltenVK が無ければ skip。
 */
public class McNativeTerrainLoadSceneTest {
    private static final int W = 1708, H = 960;

    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
    }

    @AfterAll
    static void teardown() {
        VkSampler.shutdown();
        VkQuadIndexBuffer.shutdown();
        VkCullPass.shutdown();
    }

    @Test
    void theReferenceLandsInTheBandWithGeometryOnBothSidesOfTheBracket() {
        var notes = new ArrayList<String>();
        int[] leaks = {0};
        var scene = McNativeTerrainScene.build(VkRenderTarget.FORMAT_COLOR, W, H,
            SyntheticTerrain.depthSweep(), McNativeTerrainLoad.mvp(W, H),
            McNativeTerrainLoad.cameraSection(), true, McNativeTerrainLoad.CLEAR, notes::add,
            () -> leaks[0]++);
        assertNotNull(scene, "the scene did not build: " + notes);
        try {
            assertEquals(0, leaks[0]);
            assertTrue(notes.isEmpty(), notes.toString());
            assertTrue(scene.set > 0, "nothing drawn");
            assertNotNull(scene.depth);
            assertEquals(5 * 2, scene.drawCount, "UP and NORTH draws for five sections");
            // Voxy's perspective keeps GL's y, like Minecraft's matrices: NDC y = +0.3 is the
            // ladder's "flipped" rect (rows counted from the image's row 0 = NDC y = -1).
            int[] rect = McNativeDepthLadder.bandRect(W, H, true);
            int[] other = McNativeDepthLadder.bandRect(W, H, false);
            int clear = McNativeTerrainScene.packRgb(McNativeTerrainLoad.CLEAR);
            float lo = Math.scalb(1f, -12), hi = Math.scalb(1f, -10);
            long inside = 0, outside = 0, inOther = 0, atOrAbove = 0, atOrBelow = 0, disagree = 0;
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int i = y * W + x;
                    boolean colour = scene.colour[i] != clear;
                    boolean depth = scene.depth[i] > 0f;
                    if (colour != depth) disagree++;
                    if (!depth) continue;
                    boolean in = x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3];
                    if (in) inside++; else outside++;
                    if (x >= other[0] && x < other[2] && y >= other[1] && y < other[3]) inOther++;
                    if (scene.depth[i] >= hi) atOrAbove++;
                    if (scene.depth[i] <= lo) atOrBelow++;
                }
            }
            String summary = "inside=" + inside + " outside=" + outside + " inOtherRect=" + inOther
                + " atOrAbove2^-10=" + atOrAbove + " atOrBelow2^-12=" + atOrBelow
                + " colourDepthDisagree=" + disagree;
            assertEquals(0, disagree, "colour and depth must agree on where geometry is: " + summary);
            assertTrue(inside > 0, summary);
            assertEquals(0, outside, "every geometry pixel lies in the ladder band: " + summary);
            assertEquals(0, inOther, "nothing lands in the other orientation's rect: " + summary);
            assertTrue(atOrAbove > 0, "pixels nearer than Minecraft's terrain bracket: " + summary);
            assertTrue(atOrBelow > 0, "pixels farther than Minecraft's terrain bracket: " + summary);
            // the depth crop the probe retains is the band, little-endian float32
            byte[] depthBytes = scene.depthBytes(rect);
            assertEquals((rect[2] - rect[0]) * (rect[3] - rect[1]) * 4, depthBytes.length);
            float first = java.nio.ByteBuffer.wrap(depthBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .getFloat(0);
            assertEquals(scene.depth[rect[1] * W + rect[0]], first, 0f);
            assertEquals((rect[2] - rect[0]) * (rect[3] - rect[1]) * 3, scene.colourBytes(rect).length);
        } finally {
            scene.free();
        }
    }
}
