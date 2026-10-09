package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.client.core.vk.SyntheticTerrain;
import me.cortex.voxy.client.core.vk.VkDepth;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;

/**
 * {@link McNativeTerrainLoad} のうち、Minecraft のデバイスを要らない部分を固定する:
 * (1) フラグが無い限り何もしない、(2) 視点が掃引の足跡を梯子の帯の内側に写し、深度を変えず、
 * 梯子の区間 (2⁻¹², 2⁻¹⁰] の<b>両側</b>に幾何を置く、(3) 期待の規則が区間の端を正しく扱う、
 * (4) 画素の判定が各計数を正しく進める、(5) 既定の証跡が測っていないものを言わない。
 * 実機での合成は {@code scripts/verify.py --only native} の二つ目の起動が測る。
 */
public class McNativeTerrainLoadTest {

    @Test
    void theExperimentIsInertWithoutItsFlag() {
        assertFalse(McNativeTerrainLoad.enabled(), "must not be enabled by default in a test JVM");
        assertFalse(McNativeTerrainLoad.attempted());
        assertEquals(0, McNativeTerrainLoad.drawsRecorded());
        assertTrue(McNativeTerrainLoad.results().isEmpty());
        assertDoesNotThrow(McNativeTerrainLoad::renderIfEnabled);
        assertDoesNotThrow(McNativeTerrainLoad::shutdown);
        assertDoesNotThrow(() -> McNativeTerrainLoad.shutdownImmediate(null));
        assertFalse(McNativeTerrainLoad.attempted(), "a disabled probe must not record an attempt");
        assertEquals(0, McNativeTerrainLoad.drawsRecorded());
        // the ladder hands this frame's sample to nobody when nothing sampled
        assertEquals(-1, McNativeDepthLadder.takeSampleThisFrame());
        assertNull(McNativeDepthLadder.takeBand(1));
    }

    @Test
    void theSceneIsTheDepthSweepAndItsCellsFollowTheQuadLayout() {
        var terrain = SyntheticTerrain.depthSweep();
        assertEquals(5, terrain.sectionCount());
        assertEquals(5 * 96, terrain.totalQuads());
        List<float[]> cells = terrain.opaqueQuadCells();
        assertEquals(5 * 96, cells.size(), "every quad is opaque and has a cell");
        // first panel: section (2, 0, 1): x 64.., z 32..; k 0..31 are UP quads at y 0, k 32..95
        // NORTH quads at y 1..2 (k >> 5)
        assertArrayEquals(new float[] {64, 0, 32, 65, 1, 33}, cells.get(0), 0f);
        assertArrayEquals(new float[] {64, 1, 32, 65, 2, 33}, cells.get(32), 0f);
        assertArrayEquals(new float[] {95, 2, 32, 96, 3, 33}, cells.get(95), 0f);
        // last panel: section (2, 0, 16): z 512
        assertArrayEquals(new float[] {64, 0, 512, 65, 1, 513}, cells.get(4 * 96), 0f);
        // a level-1 section scales its cells by two (the rule the shader applies)
        var lod = new SyntheticTerrain().add(new SyntheticTerrain.Section(1, -2, 3, 1)
            .translucent(2).face(SyntheticTerrain.Face.UP, 2));
        var lodCells = lod.opaqueQuadCells();
        assertEquals(2, lodCells.size(), "translucent quads are not opaque cells");
        // k starts after the 2 translucent quads: k = 2 -> px 2
        assertArrayEquals(new float[] {64 + 4, -128, 192, 64 + 6, -126, 194}, lodCells.get(0), 0f);
    }

    @Test
    void theViewFitsTheSweepInsideTheLadderBandWithoutTouchingDepth() {
        float[] band = McNativeDepthLadder.band();
        float bx0 = Math.min(band[0], band[2]), bx1 = Math.max(band[0], band[2]);
        float by0 = Math.min(band[1], band[3]), by1 = Math.max(band[1], band[3]);
        List<float[]> cells = McNativeTerrainLoad.depthSweepCells();
        for (int[] size : new int[][] {{1708, 960}, {1920, 1080}, {960, 540}}) {
            float[] raw = McNativeTerrainLoad.footprint(McNativeTerrainLoad.view(size[0], size[1]), cells);
            float[] mvp = McNativeTerrainLoad.mvp(size[0], size[1]);
            float[] fitted = McNativeTerrainLoad.footprint(mvp, cells);
            String where = size[0] + "x" + size[1] + " " + java.util.Arrays.toString(fitted);
            assertTrue(fitted[0] >= bx0 && fitted[2] <= bx1, "x inside the band: " + where);
            assertTrue(fitted[1] >= by0 && fitted[3] <= by1, "y inside the band: " + where);
            // most of the band is used (the margin is 5 % a side)
            assertTrue(fitted[2] - fitted[0] > 0.8f * (bx1 - bx0), where);
            assertTrue(fitted[3] - fitted[1] > 0.8f * (by1 - by0), where);
            // the fit is an NDC affine map in x and y only: depth is exactly the view's
            assertEquals(raw[4], fitted[4], 1e-7f, where);
            assertEquals(raw[5], fitted[5], 1e-7f, where);
        }
    }

    @Test
    void theSweepPutsGeometryOnBothSidesOfMinecraftsTerrainBracket() {
        // The ladder measured overworld terrain in (2^-12, 2^-10]. A pixel whose Voxy depth is
        // at or above 2^-10 must appear over it; at or below 2^-12 it must not. Both kinds
        // must exist, or the experiment would decide nothing where it matters.
        float[] z = McNativeDepthLadder.depths();
        float top = z[5], bottom = z[4];   // 2^-6?? no: indices: z[i] = 2^-(16-2i): z[2]=2^-12, z[3]=2^-10
        assertEquals(Math.scalb(1f, -12), z[2], 0f);
        assertEquals(Math.scalb(1f, -10), z[3], 0f);
        float[] mvp = McNativeTerrainLoad.mvp(1708, 960);
        int above = 0, below = 0, between = 0;
        for (float[] cell : McNativeTerrainLoad.depthSweepCells()) {
            float[] fp = McNativeTerrainLoad.footprint(mvp, List.of(cell));
            if (fp[4] >= z[3]) above++;
            else if (fp[5] <= z[2]) below++;
            else between++;
        }
        assertTrue(above > 0, "cells nearer than 2^-10");
        assertTrue(below > 0, "cells farther than 2^-12");
        assertTrue(between > 0, "cells inside the bracket (undetermined, counted)");
        // the two near panels, the far panel, and the two in between
        assertEquals(2 * 96, above);
        assertEquals(96, below);
        assertEquals(2 * 96, between);
        assertArrayEquals(new int[] {2, 0, 0}, McNativeTerrainLoad.cameraSection());
    }

    @Test
    void theExpectationRuleHonoursTheBracketEnds() {
        float[] z = McNativeDepthLadder.depths();
        int LOW = McNativeDepthLadder.LOW, BASE = McNativeDepthLadder.BASE, R0 = McNativeDepthLadder.RUNG0;
        // sky: d < z0 — Voxy at or above z0 appears; below z0 it is undecided
        assertEquals(1, McNativeTerrainLoad.expectation(LOW, z[0]));
        assertEquals(1, McNativeTerrainLoad.expectation(LOW, 0.5f));
        assertEquals(0, McNativeTerrainLoad.expectation(LOW, z[0] / 2));
        // anomaly: d == z0 exactly
        assertEquals(1, McNativeTerrainLoad.expectation(BASE, z[0]));
        assertEquals(-1, McNativeTerrainLoad.expectation(BASE, Math.nextDown(z[0])));
        // rung i: z_i < d <= z_{i+1}; GREATER_OR_EQUAL passes at d_V >= d
        for (int i = 0; i < McNativeDepthLadder.RUNGS - 1; i++) {
            assertEquals(-1, McNativeTerrainLoad.expectation(R0 + i, z[i]), "at the bottom: hidden");
            assertEquals(-1, McNativeTerrainLoad.expectation(R0 + i, z[i] / 2));
            assertEquals(1, McNativeTerrainLoad.expectation(R0 + i, z[i + 1]), "at the top: visible");
            assertEquals(1, McNativeTerrainLoad.expectation(R0 + i, 1.0f));
            assertEquals(0, McNativeTerrainLoad.expectation(R0 + i, (z[i] + z[i + 1]) / 2), "inside");
        }
        int last = R0 + McNativeDepthLadder.RUNGS - 1;
        assertEquals(-1, McNativeTerrainLoad.expectation(last, z[McNativeDepthLadder.RUNGS - 1]));
        assertEquals(0, McNativeTerrainLoad.expectation(last, 0.5f));
        assertEquals(1, McNativeTerrainLoad.expectation(last, 1.0f));
        // a pixel outside the palette has no bracket
        assertEquals(0, McNativeTerrainLoad.expectation(McNativeDepthLadder.PALETTE.length, 0.5f));
    }

    @Test
    void theJudgeCountsEveryKindOfPixel() {
        int R0 = McNativeDepthLadder.RUNG0;
        float[] z = McNativeDepthLadder.depths();
        // seven pixels; ladder classes: rung 2 (terrain bracket) everywhere except pixel 6 (sky)
        byte[] classes = {(byte) (R0 + 2), (byte) (R0 + 2), (byte) (R0 + 2), (byte) (R0 + 2),
            (byte) (R0 + 2), (byte) (R0 + 2), (byte) McNativeDepthLadder.LOW};
        int ref = 10 | (90 << 8) | (200 << 16);
        byte[] before = new byte[7 * 3];
        for (int i = 0; i < 7; i++) { before[i * 3] = 0; before[i * 3 + 1] = (byte) 255; before[i * 3 + 2] = (byte) 255; }
        int[] refRgb = {ref, ref, ref, ref, ref, ref, ref};
        float[] refDepth = {
            0.5f,          // 0: visible expected, and visible          -> VISIBLE
            z[2] / 2,      // 1: hidden expected, and hidden            -> HIDDEN
            (z[2] + z[3]) / 2, // 2: undetermined, shown                -> UNDETERMINED + VISIBLE
            0.5f,          // 3: visible expected but hidden            -> HIDDEN_WHERE_VISIBLE
            z[2] / 2,      // 4: hidden expected but visible            -> VISIBLE_WHERE_HIDDEN
            VkDepth.CLEAR, // 5: no geometry, but the pixel changed     -> CHANGED_WHERE_NO_GEOMETRY
            0.5f           // 6: sky, geometry, neither colour          -> OTHER
        };
        byte[] after = before.clone();
        set(after, 0, 10, 90, 200);
        set(after, 2, 10, 90, 200);
        set(after, 4, 10, 90, 200);
        set(after, 5, 1, 2, 3);
        set(after, 6, 7, 7, 7);
        long[] c = McNativeTerrainLoad.judge(classes, before, after, refRgb, refDepth);
        assertEquals(6, c[McNativeTerrainLoad.GEOMETRY]);
        assertEquals(1, c[McNativeTerrainLoad.NO_GEOMETRY]);
        assertEquals(3, c[McNativeTerrainLoad.EXPECT_VISIBLE]);   // 0, 3, 6
        assertEquals(2, c[McNativeTerrainLoad.EXPECT_HIDDEN]);    // 1, 4
        assertEquals(1, c[McNativeTerrainLoad.UNDETERMINED]);     // 2
        assertEquals(3, c[McNativeTerrainLoad.VISIBLE]);          // 0, 2, 4
        assertEquals(2, c[McNativeTerrainLoad.HIDDEN]);           // 1, 3
        assertEquals(0, c[McNativeTerrainLoad.AMBIGUOUS]);
        assertEquals(1, c[McNativeTerrainLoad.OTHER]);            // 6
        assertEquals(1, c[McNativeTerrainLoad.VISIBLE_WHERE_HIDDEN]);
        assertEquals(1, c[McNativeTerrainLoad.HIDDEN_WHERE_VISIBLE]);
        assertEquals(1, c[McNativeTerrainLoad.CHANGED_WHERE_NO_GEOMETRY]);
        assertTrue(McNativeTerrainLoad.violated(c));
        assertEquals(McNativeTerrainLoad.COUNTS, McNativeTerrainLoad.COUNT_NAMES.length);
        assertTrue(McNativeTerrainLoad.describe(c).startsWith("geometry=6 noGeometry=1 expectVisible=3"));

        // a clean band: visible where expected, unchanged where hidden or empty; one ambiguous
        // pixel whose reference colour equals the previous readback's
        byte[] clean = before.clone();
        set(clean, 0, 10, 90, 200);
        set(clean, 2, 10, 90, 200);
        float[] cleanDepth = {0.5f, z[2] / 2, (z[2] + z[3]) / 2, 0.5f, z[2] / 2, VkDepth.CLEAR, 0.5f};
        set(clean, 3, 10, 90, 200);
        int[] refs = refRgb.clone();
        refs[6] = 0 | (255 << 8) | (255 << 16);   // the reference equals the ladder colour there
        long[] ok = McNativeTerrainLoad.judge(classes, before, clean, refs, cleanDepth);
        assertFalse(McNativeTerrainLoad.violated(ok), McNativeTerrainLoad.describe(ok));
        assertEquals(1, ok[McNativeTerrainLoad.AMBIGUOUS]);
        assertEquals(3, ok[McNativeTerrainLoad.VISIBLE]);
        assertEquals(2, ok[McNativeTerrainLoad.HIDDEN]);
    }

    private static void set(byte[] rgb, int i, int r, int g, int b) {
        rgb[i * 3] = (byte) r;
        rgb[i * 3 + 1] = (byte) g;
        rgb[i * 3 + 2] = (byte) b;
    }

    @Test
    void theDefaultEvidenceClaimsNothingUnmeasured() {
        assertArrayEquals(new int[] {VK_COMPARE_OP_GREATER_OR_EQUAL, 1, 1},
            McNativeTerrainLoad.DECLARED_DEPTH_STATE);
        assertEquals(VkDepth.COMPARE_OP, McNativeTerrainLoad.DECLARED_DEPTH_STATE[0],
            "the declared compare op is Voxy's own");
        String json = McNativeTerrainLoad.json();
        assertTrue(json.contains("\"enabled\": false"), json);
        assertTrue(json.contains("\"attempted\": false"), json);
        assertTrue(json.contains("\"built\": false"), json);
        assertTrue(json.contains("\"drawsRecorded\": 0"), json);
        assertTrue(json.contains("\"scene\": \"depthSweep\""), json);
        assertTrue(json.contains("\"declaredDepthState\": [6, 1, 1]"), json);
        assertTrue(json.contains("\"depthStateReadBack\": false"), json);
        assertTrue(json.contains("\"results\": []"), json);
        assertTrue(json.contains("\"ladderEnabled\": false"), json);
        assertTrue(json.contains("\"deviceDiverged\": false"), json);
        // the ladder publishes the pass count the gate reconciles
        String ladder = McNativeDepthLadder.json();
        assertTrue(ladder.contains("\"terrainLoadEnabled\": false"), ladder);
        assertTrue(ladder.contains("\"terrainLoadDrawsRecorded\": 0"), ladder);
        assertEquals("depthSweep", McNativeTerrainLoad.SCENE);
    }
}
