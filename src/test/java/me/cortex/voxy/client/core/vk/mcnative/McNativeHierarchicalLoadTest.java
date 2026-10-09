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
        // the every-frame path is off by default too and composited nothing
        assertFalse(McNativeHierarchicalLoad.everyFrame());
        // the product switch is off by default and implies nothing then
        assertFalse(McNativeRender.on());
        assertEquals("voxy.native.render", McNativeRender.FLAG);
        assertTrue(json.contains("\"product\": false"), json);
        assertTrue(json.contains("\"renderCalls\": 0"), json);
        assertEquals(0, McNativeHierarchicalLoad.framesComposited());
        assertTrue(json.contains("\"everyFrame\": false"), json);
        assertTrue(json.contains("\"framesComposited\": 0"), json);
        assertTrue(json.contains("\"frameSkips\": {}"), json);
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

    /** round-27 R27-EMPTY-REBUILD: an empty build's suppression ends; a stationary player recovers. */
    @Test
    void anEmptyBuildIsRetriedInNormalPlayButNotUnderTheLadder() {
        int retry = McNativeHierarchicalLoad.EMPTY_RETRY_FRAMES;
        // normal play, same place: suppressed for a while, then a build is tried again
        assertTrue(McNativeHierarchicalLoad.emptySuppressed(false, 0, true));
        assertTrue(McNativeHierarchicalLoad.emptySuppressed(false, retry - 1, true));
        assertFalse(McNativeHierarchicalLoad.emptySuppressed(false, retry, true));
        assertFalse(McNativeHierarchicalLoad.emptySuppressed(false, 1_000_000, true));
        // anything changed (engine, section, extent, atlas): build at once
        assertFalse(McNativeHierarchicalLoad.emptySuppressed(false, 0, false));
        assertFalse(McNativeHierarchicalLoad.emptySuppressed(true, 0, false));
        // under the ladder each build spends budget: suppressed while the key holds
        assertTrue(McNativeHierarchicalLoad.emptySuppressed(true, 1_000_000, true));
        assertEquals("rendering-disabled", McNativeHierarchicalLoad.RENDERING_DISABLED);
    }

    /** The kill switch wins over the product switch. */
    @Test
    void theKillSwitchTurnsTheProductPathOff() {
        String before = System.getProperty(McNativeRender.FLAG), kill = System.getProperty(McNativeRender.DISABLE_FLAG);
        try {
            System.setProperty(McNativeRender.FLAG, "true");
            assertTrue(McNativeRender.on());
            System.setProperty(McNativeRender.DISABLE_FLAG, "true");
            assertFalse(McNativeRender.on());
            assertEquals("voxy.native.disable", McNativeRender.DISABLE_FLAG);
        } finally {
            if (before == null) System.clearProperty(McNativeRender.FLAG); else System.setProperty(McNativeRender.FLAG, before);
            if (kill == null) System.clearProperty(McNativeRender.DISABLE_FLAG); else System.setProperty(McNativeRender.DISABLE_FLAG, kill);
        }
    }
}
