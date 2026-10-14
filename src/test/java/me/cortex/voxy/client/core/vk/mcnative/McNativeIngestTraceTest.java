package me.cortex.voxy.client.core.vk.mcnative;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real {@link McNativeIngestTrace} against the R29 prelaunch review's counterexamples
 * (R29-PRELAUNCH-CAUSALITY): what it records is the updater's own outcome and the content a mesher
 * could have read — never a cause the observer made up. CPU only; the gate side of the same shapes
 * is scripts/tests/test_render_gate.py.
 */
public class McNativeIngestTraceTest {
    private static final long LEAF = me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(0, 1, 3, 2);
    private static final long OTHER = me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(0, 1, 3, 3);
    private final Object engine = new Object();

    @BeforeEach
    void observe() {
        McNativeIngestTrace.setEnabled(true);
        McNativeIngestTrace.observeLeaf(OTHER);   // so the generation starts at 0 for LEAF below
        McNativeIngestTrace.observeLeaf(LEAF);
    }

    @AfterEach
    void off() {
        McNativeIngestTrace.setEnabled(false);
    }

    @Test
    void aQueuedIngestWithAnOlderDirtyKeyAndAnUnchangedRemeshIsNoCause() {
        // the reviewer's replay (pending_ingest_prequeued_dirty_same_leaf, target_ingest_executed=false):
        // the ingest was only queued, a dirty key queued earlier for the same leaf was consumed, and a
        // mesh of the leaf accepted — strictly ordered, one engine, exact leaf
        long before = McNativeIngestTrace.now();
        McNativeIngestTrace.ingestQueued(2, 7, 4, this.engine);
        McNativeIngestTrace.dirtyConsumed(LEAF, this.engine);
        McNativeIngestTrace.meshStarted(LEAF);
        McNativeIngestTrace.meshed(LEAF, this.engine);
        long after = McNativeIngestTrace.now();
        assertNotNull(McNativeIngestTrace.lastIngest(2, 7, 4));
        assertTrue(before < McNativeIngestTrace.lastIngest(2, 7, 4).seq());
        assertTrue(McNativeIngestTrace.leafDirty().seq() < McNativeIngestTrace.leafMeshed().seq());
        assertTrue(McNativeIngestTrace.leafMeshed().seq() < after);
        // ...and yet the updater never reported an outcome, so the trace carries no change to derive
        // from: the gate refuses this ("records no update event"), whatever the order of the rest
        assertNull(McNativeIngestTrace.lastUpdate());
        assertNull(McNativeIngestTrace.lastChange());
        assertEquals(0, McNativeIngestTrace.generation());
        assertEquals(0, McNativeIngestTrace.leafMeshed().generation(), "the mesh read generation 0: nothing");
    }

    @Test
    void onlyTheUpdatersRealChangeAdvancesTheGeneration() {
        McNativeIngestTrace.leafUpdated(LEAF, this.engine, false, 0);
        assertNotNull(McNativeIngestTrace.lastUpdate());
        assertFalse(McNativeIngestTrace.lastUpdate().changed());
        assertNull(McNativeIngestTrace.lastChange(), "an unchanged re-write is recorded, not a change");
        assertEquals(0, McNativeIngestTrace.generation());

        McNativeIngestTrace.leafUpdated(OTHER, this.engine, true, 5);
        assertEquals(0, McNativeIngestTrace.generation(), "another leaf's change is not this leaf's");
        assertNull(McNativeIngestTrace.lastChange());

        McNativeIngestTrace.leafUpdated(LEAF, this.engine, true, 1);
        assertEquals(1, McNativeIngestTrace.generation());
        var change = McNativeIngestTrace.lastChange();
        assertNotNull(change);
        assertTrue(change.changed());
        assertEquals(1, change.generation());
        assertEquals(1, change.nonEmptyBlocks());
        assertEquals(System.identityHashCode(this.engine), change.engine());
        assertSame(change, McNativeIngestTrace.lastUpdate());

        // a later no-op keeps the change on record and the generation where it is
        McNativeIngestTrace.leafUpdated(LEAF, this.engine, false, 1);
        assertSame(change, McNativeIngestTrace.lastChange());
        assertFalse(McNativeIngestTrace.lastUpdate().changed());
        assertEquals(1, McNativeIngestTrace.generation());
    }

    @Test
    void aMeshIsCreditedOnlyWithTheContentItCouldHaveRead() {
        // the mesher began reading before the updater wrote: however late the scene accepts that
        // mesh, it derives from generation 0, not from the change
        McNativeIngestTrace.meshStarted(LEAF);
        McNativeIngestTrace.leafUpdated(LEAF, this.engine, true, 0);
        McNativeIngestTrace.dirtyConsumed(LEAF, this.engine);
        McNativeIngestTrace.meshed(LEAF, this.engine);
        assertEquals(1, McNativeIngestTrace.lastChange().generation());
        assertEquals(0, McNativeIngestTrace.leafMeshed().generation(), "read before the write");
        assertTrue(McNativeIngestTrace.leafMeshed().seq() > McNativeIngestTrace.leafDirty().seq(),
            "strict order holds and is still no derivation");

        // a mesh that began after the write reaches the change's generation
        McNativeIngestTrace.meshStarted(LEAF);
        McNativeIngestTrace.meshed(LEAF, this.engine);
        assertEquals(1, McNativeIngestTrace.leafMeshed().generation());

        // a mesh whose start was never seen is credited with nothing (-1)
        McNativeIngestTrace.meshed(LEAF, this.engine);
        assertEquals(-1, McNativeIngestTrace.leafMeshed().generation());

        // another leaf's or an ancestor's mesh does not touch the leaf's record
        var mine = McNativeIngestTrace.leafMeshed();
        McNativeIngestTrace.meshStarted(OTHER);
        McNativeIngestTrace.meshed(OTHER, this.engine);
        McNativeIngestTrace.meshed(me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(1, 0, 1, 1), this.engine);
        assertSame(mine, McNativeIngestTrace.leafMeshed());
    }

    @Test
    void observingAgainForgetsTheEventsButNotTheContent() {
        McNativeIngestTrace.leafUpdated(LEAF, this.engine, true, 1);
        McNativeIngestTrace.dirtyConsumed(LEAF, this.engine);
        McNativeIngestTrace.meshStarted(LEAF);
        McNativeIngestTrace.observeLeaf(LEAF);
        assertEquals(1, McNativeIngestTrace.generation(), "the leaf's content is what it is");
        assertNull(McNativeIngestTrace.lastChange());
        assertNull(McNativeIngestTrace.lastUpdate());
        assertNull(McNativeIngestTrace.leafDirty());
        assertNull(McNativeIngestTrace.leafMeshed());
        assertNull(McNativeIngestTrace.leafUnwatched());
        McNativeIngestTrace.meshed(LEAF, this.engine);
        assertEquals(-1, McNativeIngestTrace.leafMeshed().generation(), "a start before re-observing is forgotten");
        McNativeIngestTrace.observeLeaf(OTHER);
        assertEquals(0, McNativeIngestTrace.generation(), "another leaf starts from nothing");
    }

    @Test
    void theRemovedLeafTerminalIsRecordedForTheLeafOnly() {
        McNativeIngestTrace.unwatched(OTHER, this.engine);
        assertNull(McNativeIngestTrace.leafUnwatched());
        McNativeIngestTrace.leafUpdated(LEAF, this.engine, true, 0);
        McNativeIngestTrace.unwatched(LEAF, this.engine);
        assertNotNull(McNativeIngestTrace.leafUnwatched());
        assertTrue(McNativeIngestTrace.leafUnwatched().seq() > McNativeIngestTrace.lastChange().seq());
        assertEquals(System.identityHashCode(this.engine), McNativeIngestTrace.leafUnwatched().engine());
    }

    @Test
    void inertWhenOff() {
        McNativeIngestTrace.setEnabled(false);
        long a = McNativeIngestTrace.now();
        McNativeIngestTrace.ingestQueued(9, 9, 9, this.engine);
        McNativeIngestTrace.leafUpdated(LEAF, this.engine, true, 1);
        McNativeIngestTrace.dirtyConsumed(LEAF, this.engine);
        McNativeIngestTrace.meshStarted(LEAF);
        McNativeIngestTrace.meshed(LEAF, this.engine);
        McNativeIngestTrace.unwatched(LEAF, this.engine);
        assertNull(McNativeIngestTrace.lastIngest(9, 9, 9));
        assertNull(McNativeIngestTrace.lastUpdate());
        assertNull(McNativeIngestTrace.leafDirty());
        assertNull(McNativeIngestTrace.leafMeshed());
        assertNull(McNativeIngestTrace.leafUnwatched());
        assertEquals(0, McNativeIngestTrace.generation());
        assertTrue(McNativeIngestTrace.now() > a, "the marks still count");
    }
}
