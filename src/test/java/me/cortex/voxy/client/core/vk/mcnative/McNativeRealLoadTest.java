package me.cortex.voxy.client.core.vk.mcnative;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link McNativeRealLoad} と梯子の標本の渡し方のうち、Minecraft を要らない部分。
 */
public class McNativeRealLoadTest {

    @Test
    void theExperimentIsInertWithoutItsFlag() {
        assertFalse(McNativeRealLoad.enabled(), "must not be enabled by default in a test JVM");
        assertDoesNotThrow(McNativeRealLoad::renderIfEnabled);
        assertDoesNotThrow(McNativeRealLoad::shutdown);
        assertDoesNotThrow(() -> McNativeRealLoad.shutdownImmediate(null));
        assertEquals(0, McNativeRealLoad.drawsRecorded());
        assertTrue(McNativeRealLoad.results().isEmpty());
        assertEquals(-1, McNativeDepthLadder.takeSampleThisFrame(McNativeDepthLadder.EXPERIMENT_REAL_LOAD));
        String json = McNativeRealLoad.json();
        assertTrue(json.contains("\"enabled\": false"), json);
        assertTrue(json.contains("\"attempted\": false"), json);
        assertTrue(json.contains("\"drawsRecorded\": 0"), json);
        assertTrue(json.contains("\"results\": []"), json);
        assertTrue(json.contains("\"level\": 3"), json);
        assertTrue(json.contains("\"radius\": 4"), json);
        assertTrue(json.contains("\"declaredDepthState\": [6, 1, 1]"), json);
        assertTrue(json.contains("\"depthStateReadBack\": false"), json);
        String ladder = McNativeDepthLadder.json();
        assertTrue(ladder.contains("\"realLoadEnabled\": false"), ladder);
        assertTrue(ladder.contains("\"realLoadDrawsRecorded\": 0"), ladder);
    }

    @Test
    void eachSampleGoesToExactlyOneExperiment() {
        String T = McNativeDepthLadder.EXPERIMENT_TERRAIN_LOAD, R = McNativeDepthLadder.EXPERIMENT_REAL_LOAD;
        assertEquals(T, McNativeDepthLadder.assignConsumer(true, true, 0));
        assertEquals(R, McNativeDepthLadder.assignConsumer(true, true, 1));
        assertEquals(T, McNativeDepthLadder.assignConsumer(true, true, 2));
        assertEquals(T, McNativeDepthLadder.assignConsumer(true, false, 1));
        assertEquals(R, McNativeDepthLadder.assignConsumer(false, true, 0));
        assertNull(McNativeDepthLadder.assignConsumer(false, false, 0));
        assertEquals("terrainLoad", T);
        assertEquals("realLoad", R);
    }

    @Test
    void theSkipReasonsAreTheFixedSet() {
        assertEquals("judged", McNativeRealLoad.JUDGED);
        assertEquals(java.util.List.of("no-world-engine", "no-camera-this-frame",
                "camera-extent-mismatch", "nothing-meshed", "build-budget-spent"),
            java.util.List.of(McNativeRealLoad.NO_ENGINE, McNativeRealLoad.NO_CAMERA,
                McNativeRealLoad.EXTENT, McNativeRealLoad.NOTHING_MESHED,
                McNativeRealLoad.BUILD_BUDGET_SPENT));
    }
}
