package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.client.VoxyClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link McNativeInstanceProbe} と native instance mode のうち、Minecraft を要らない部分:
 * フラグが無い限り何もしないこと、既定の証跡が何も測ったと言わないこと。
 */
public class McNativeInstanceProbeTest {

    @Test
    void theProbeIsInertWithoutItsFlag() {
        assertFalse(McNativeInstanceProbe.enabled(), "must not be enabled by default in a test JVM");
        assertFalse(VoxyClient.nativeInstanceMode(), "native instance mode is off by default");
        assertEquals("voxy.native.instance", VoxyClient.NATIVE_INSTANCE_FLAG);
        assertEquals(McNativeInstanceProbe.FLAG, VoxyClient.NATIVE_INSTANCE_FLAG);
        assertDoesNotThrow(McNativeInstanceProbe::sampleIfEnabled);
        assertEquals(0, McNativeInstanceProbe.frames(), "a disabled probe counts no frames");
        assertTrue(McNativeInstanceProbe.samples().isEmpty());
    }

    @Test
    void theDefaultEvidenceClaimsNothing() {
        String json = McNativeInstanceProbe.json();
        assertTrue(json.contains("\"enabled\": false"), json);
        assertTrue(json.contains("\"frames\": 0"), json);
        assertTrue(json.contains("\"engineEverPresent\": false"), json);
        assertTrue(json.contains("\"rendererEverCreated\": false"), json);
        assertTrue(json.contains("\"maxActiveSections\": 0"), json);
        assertTrue(json.contains("\"samples\": []"), json);
        assertTrue(json.contains("\"notes\": []"), json);
        assertEquals(60, McNativeInstanceProbe.SAMPLE_INTERVAL);
    }

    /**
     * round-24 R24-INSTANCE-STORED: the section tracker hands back a missing section as an all-air
     * placeholder (sky light 15, block 0), so only content separates it from ingested terrain.
     */
    @Test
    void anAllAirPlaceholderIsNotCountedAndOneBlockIs() {
        var section = me.cortex.voxy.common.world.WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        long air = me.cortex.voxy.common.world.other.Mapper.composeMappingId((byte) 15, 0, 0);
        java.util.Arrays.fill(section._unsafeGetRawDataArray(), air);
        assertFalse(McNativeInstanceProbe.holdsABlock(section), "the tracker's missing-section placeholder");
        long stone = me.cortex.voxy.common.world.other.Mapper.composeMappingId((byte) 0, 1, 0);
        section._unsafeGetRawDataArray()[12345] = stone;
        assertTrue(McNativeInstanceProbe.holdsABlock(section), "one non-air block");
    }
}
