package me.cortex.voxy.client.core.vk.mcnative;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link McNativeHierarchicalLoad} and the ladder's three-way hand-off, the parts that need no
 * Minecraft: inert without its flag, default evidence claims nothing, the hand-off rule the gate
 * replicates (scripts/verify.py ladder_expected_consumers).
 */
public class McNativeHierarchicalLoadTest {

    @Test
    void theExperimentIsInertWithoutItsFlag() {
        assertFalse(McNativeHierarchicalLoad.enabled(), "must not be enabled by default in a test JVM");
        assertDoesNotThrow(McNativeHierarchicalLoad::renderIfEnabled);
        assertDoesNotThrow(McNativeHierarchicalLoad::shutdown);
        assertDoesNotThrow(() -> McNativeHierarchicalLoad.shutdownImmediate(null));
        assertEquals(0, McNativeHierarchicalLoad.drawsRecorded());
        assertTrue(McNativeHierarchicalLoad.results().isEmpty());
        assertEquals(-1, McNativeDepthLadder.takeSampleThisFrame(McNativeDepthLadder.EXPERIMENT_HIER_LOAD));
        String json = McNativeHierarchicalLoad.json();
        assertTrue(json.contains("\"enabled\": false"), json);
        assertTrue(json.contains("\"attempted\": false"), json);
        assertTrue(json.contains("\"results\": []"), json);
        assertTrue(json.contains("\"iterations\": 3"), json);
        assertTrue(json.contains("\"topRadius\": 1"), json);
        assertTrue(json.contains("\"depth\": 2"), json);
        assertTrue(json.contains("\"buildBudget\": 6"), json);
        assertTrue(json.contains("\"declaredDepthState\": [6, 1, 1]"), json);
        String ladder = McNativeDepthLadder.json();
        assertTrue(ladder.contains("\"hierLoadEnabled\": false"), ladder);
        assertTrue(ladder.contains("\"hierLoadDrawsRecorded\": 0"), ladder);
    }

    @Test
    void theHandOffRotatesThreeExperimentsAndKeepsHorizonForTheRealWorldOnes() {
        String T = McNativeDepthLadder.EXPERIMENT_TERRAIN_LOAD, R = McNativeDepthLadder.EXPERIMENT_REAL_LOAD,
            H = McNativeDepthLadder.EXPERIMENT_HIER_LOAD;
        assertEquals(T, McNativeDepthLadder.assignConsumer(true, true, true, false, 0));
        assertEquals(R, McNativeDepthLadder.assignConsumer(true, true, true, false, 1));
        assertEquals(H, McNativeDepthLadder.assignConsumer(true, true, true, false, 2));
        assertEquals(T, McNativeDepthLadder.assignConsumer(true, true, true, false, 3));
        assertEquals(R, McNativeDepthLadder.assignConsumer(true, true, true, true, 0));
        assertEquals(H, McNativeDepthLadder.assignConsumer(true, true, true, true, 1));
        assertEquals(T, McNativeDepthLadder.assignConsumer(true, false, false, true, 0));
        assertNull(McNativeDepthLadder.assignConsumer(false, false, false, true, 0));
        // the two-argument form is unchanged
        assertEquals(R, McNativeDepthLadder.assignConsumer(true, true, 1));
        assertEquals("hierLoad", H);
        assertEquals("horizon", McNativeDepthLadder.HORIZON_STAGE);
        // one sample per eligible experiment: two in horizon only when both real-world ones are on
        assertEquals(1, McNativeDepthLadder.samplesWantedIn("horizon"));
        assertEquals(1, McNativeDepthLadder.samplesWantedIn("edit"));
    }
}
