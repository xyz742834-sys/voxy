package me.cortex.voxy.client.core.vk.mcnative;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link McNativeDepthLadder} のうち、Minecraft のデバイスを要らない部分を固定する。
 *
 * <p>梯子が MC の深度に対して実際に何を測ったかは {@code scripts/verify.py --only native} の
 * 二つ目の起動が測る。ここで確かめるのは、(1) フラグが無い限り何もしないこと、(2) 証跡が
 * gate の<b>固定した</b>基準 (段の深度、帯の位置) と同じ定数を出すこと、(3) 既定状態の証跡が
 * 測っていないものを測ったと言わないこと、である。
 */
public class McNativeDepthLadderTest {

    @Test
    void theLadderIsInertWithoutItsFlag() {
        assertFalse(Boolean.getBoolean(McNativeDepthLadder.FLAG),
            "the depth ladder must not be enabled by default in a test JVM");
        var before = McNativeDepthLadder.status();
        assertFalse(before.attempted());
        assertFalse(before.completed());
        assertNull(before.sampleFile());
        assertNull(before.lowerBound());
        assertNull(before.upperBound());

        // Minecraft is not running here; every entry point must still be a silent no-op.
        assertDoesNotThrow(McNativeDepthLadder::renderIfEnabled);
        assertDoesNotThrow(McNativeDepthLadder::shutdown);
        assertDoesNotThrow(() -> McNativeDepthLadder.shutdownImmediate(null));

        var after = McNativeDepthLadder.status();
        assertFalse(after.attempted(), "a disabled ladder must not record an attempt");
        String json = McNativeDepthLadder.json();
        assertTrue(json.contains("\"enabled\": false"), json);
        assertTrue(json.contains("\"drawsRecorded\": 0"), json);
        assertTrue(json.contains("\"notes\": []"), json);
    }

    /**
     * <b>gate が固定している基準と同じ定数を出すこと。</b>
     *
     * <p>{@code scripts/verify.py} は段の深度と三つの帯を<b>自分の定数</b>と比べる
     * (producer が公開した幾何は基準にならない — round 6 B1)。ここが変われば gate も
     * 変えなければならず、そのことをこのテストが思い出させる。
     */
    @Test
    void thePublishedGeometryIsTheOneTheGatePins() {
        float[] z = McNativeDepthLadder.depths();
        assertEquals(8, McNativeDepthLadder.RUNGS);
        assertEquals(8, z.length);
        for (int i = 0; i < z.length; i++) {
            assertEquals((i + 0.5f) / 8, z[i], 1e-7f, "rung " + i);
        }
        String json = McNativeDepthLadder.json();
        assertTrue(json.contains("\"rungs\": 8"), json);
        assertTrue(json.contains("\"rungDepths\": [0.0625, 0.1875, 0.3125, 0.4375, 0.5625,"
            + " 0.6875, 0.8125, 0.9375]"), json);
        assertTrue(json.contains("\"band\": [-0.6, 0.36, 0.6, 0.2]"), json);
        assertTrue(json.contains("\"inlineControlBand\": [-0.6, 0.4, 0.6, 0.37]"), json);
        assertTrue(json.contains("\"controlBand\": [-0.6, 0.16, 0.6, 0.06]"), json);
    }

    /**
     * <b>既定状態の証跡が、測っていないことを測ったと言わないこと。</b>
     *
     * <p>深度は書かない、規約は測っていない、向きは採用していない、terrain/marker は
     * 動いていない — gate はこれらを<b>必須の欄</b>として読む。欄が無ければ gate は
     * 既定値で補わずに失敗するので、欄が常に出ることもここで固定する。
     */
    @Test
    void theDefaultEvidenceClaimsNothingUnmeasured() {
        String json = McNativeDepthLadder.json();
        assertTrue(json.contains("\"attempted\": false"), json);
        assertTrue(json.contains("\"completed\": false"), json);
        assertTrue(json.contains("\"depthWritesEnabled\": false"), json);
        assertTrue(json.contains("\"zConventionMeasuredHere\": false"), json);
        assertTrue(json.contains("\"flipped\": false"), json);
        assertTrue(json.contains("\"targetWidth\": 0"), json);
        assertTrue(json.contains("\"targetHeight\": 0"), json);
        assertTrue(json.contains("\"terrainProbeEnabled\": false"), json);
        assertTrue(json.contains("\"terrainDrawsRecorded\": 0"), json);
        assertTrue(json.contains("\"markerDrawEnabled\": false"), json);
        assertTrue(json.contains("\"markerDrawsRecorded\": 0"), json);
        assertTrue(json.contains("\"lowerBound\": null"), json);
        assertTrue(json.contains("\"upperBound\": null"), json);
        assertTrue(json.contains("\"sampleFile\": null"), json);
        assertFalse(json.contains("reversedZ"), "no convention may be published: " + json);
    }
}
