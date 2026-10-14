package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.WorldUpdaterOutcomeTest;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real {@link McNativeIngestTrace} on a real {@link WorldEngine} (in-memory storage, the real
 * {@link WorldUpdater}) against the prelaunch reviews' counterexamples (R29-PRELAUNCH-CAUSALITY; the R30
 * review's unrelated-same-leaf race is in {@link McNativeCausalityRaceTest}): what it records is the
 * updater's own published outcome and the content a mesher read under the same lock — never a cause
 * the observer made up. CPU only; the gate side of the same shapes is scripts/tests/test_render_gate.py.
 */
public class McNativeIngestTraceTest {
    static final int PX = 40, PY = 118, PZ = 72;                                        // LiveWorldHarness.PROBE_X/Y/Z
    static final long LEAF = WorldEngine.getWorldSectionId(0, PX >> 5, PY >> 5, PZ >> 5);   // (1, 3, 2)
    static final long OTHER = WorldEngine.getWorldSectionId(0, 1, 3, 3);
    static final long AIR = Mapper.composeMappingId((byte) 15, 0, 0);
    static long glass;

    private WorldEngine engine;

    @BeforeEach
    void world() {
        me.cortex.voxy.testsupport.MinecraftBootstrap.ensure();
        this.engine = new WorldEngine(new WorldUpdaterOutcomeTest.MemoryStorage());
        this.engine.setSaveCallback((into, s, nonBlocking, acquired) -> { s.setNotDirty(); if (acquired) s.release(); return true; });
        glass = Mapper.composeMappingId((byte) 15, this.engine.getMapper().getIdForBlockState(
            net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState()), 0);
        McNativeIngestTrace.setEnabled(true);
        McNativeIngestTrace.observe(LEAF, PX, PY, PZ, this.engine);
    }

    @AfterEach
    void off() {
        McNativeIngestTrace.setEnabled(false);
        try { this.engine.free(); } catch (RuntimeException ignored) { }
    }

    /** The chunk section at x (y 7, z 4), all air, with the probe's block set when asked. */
    static VoxelizedSection section(int x, long block) {
        var vs = VoxelizedSection.createEmpty().setPosition(x, PY >> 4, PZ >> 4);
        Arrays.fill(vs.section, AIR);
        if (block != AIR) {
            vs.section[((PY & 15) << 8) | ((PZ & 15) << 4) | (PX & 15)] = block;
            vs.lvl0NonAirCount = 1;
        }
        return vs;
    }

    /** A mesh read of the leaf as the mesher does it: lock, capture, read, release. */
    static McNativeIngestTrace.MeshRead meshRead(WorldEngine engine) {
        var s = engine.acquire(LEAF);   // loads as air when Voxy holds nothing there yet
        try {
            var read = McNativeIngestTrace.beginMesh(LEAF, engine, s);
            McNativeIngestTrace.endMesh(read);
            return read;
        } finally { s.release(); }
    }

    @Test
    void aQueuedIngestWithAnOlderDirtyKeyAndAnUnchangedRemeshIsNoCause() {
        // the R29 replay (pending_ingest_prequeued_dirty_same_leaf, target_ingest_executed=false): the
        // ingest was only queued, a dirty key queued earlier for the same leaf was consumed, and a mesh
        // of the leaf accepted — strictly ordered, one engine, exact leaf, one epoch
        long before = McNativeIngestTrace.now();
        McNativeIngestTrace.ingestQueued(PX >> 4, PY >> 4, PZ >> 4, this.engine);
        McNativeIngestTrace.dirtyConsumed(LEAF, this.engine);
        var read = meshRead(this.engine);
        McNativeIngestTrace.meshed(read, LEAF, this.engine);
        long after = McNativeIngestTrace.now();
        var ingest = McNativeIngestTrace.lastIngest(PX >> 4, PY >> 4, PZ >> 4);
        assertNotNull(ingest);
        assertEquals(McNativeIngestTrace.epoch(), ingest.epoch());
        assertTrue(before < ingest.seq() && McNativeIngestTrace.leafDirty().seq() < McNativeIngestTrace.leafMeshed().seq()
            && McNativeIngestTrace.leafMeshed().seq() < after);
        // ...and yet the updater never published an outcome: nothing to derive from (the gate refuses
        // "records no update event"); the mesh read version 0 and air at the probe
        assertNull(McNativeIngestTrace.lastUpdate());
        assertNull(McNativeIngestTrace.lastChange());
        assertEquals(0, McNativeIngestTrace.generation());
        assertEquals(0, McNativeIngestTrace.leafMeshed().version());
        assertEquals("minecraft:air", McNativeIngestTrace.leafMeshed().probeHeld());
    }

    @Test
    void onlyTheTargetSectionsPublishedChangeIsTheUpdateAndOnlyAChangeAdvancesTheVersion() {
        // the updater's real no-op on air Voxy already holds: recorded, not a change
        WorldUpdater.insertUpdate(this.engine, section(PX >> 4, AIR));
        var noop = McNativeIngestTrace.lastUpdate();
        assertNotNull(noop);
        assertFalse(noop.changed());
        assertNull(McNativeIngestTrace.lastChange());
        assertEquals(0, McNativeIngestTrace.generation());
        assertEquals("minecraft:air", noop.wrote());
        assertEquals(PX >> 4, noop.sx());

        // another chunk section of the same leaf changes: the version advances (it is the leaf's), but
        // it is not the target's update
        WorldUpdater.insertUpdate(this.engine, section(3, glass));
        assertEquals(1, McNativeIngestTrace.generation());
        assertSame(noop, McNativeIngestTrace.lastUpdate(), "an unrelated section's change is not the target's update");
        assertNull(McNativeIngestTrace.lastChange());
        assertEquals(1, McNativeIngestTrace.otherSectionUpdates());

        // the target's own change: published with the version it left and what it wrote at the probe
        WorldUpdater.insertUpdate(this.engine, section(PX >> 4, glass));
        assertEquals(2, McNativeIngestTrace.generation());
        var change = McNativeIngestTrace.lastChange();
        assertNotNull(change);
        assertTrue(change.changed());
        assertEquals(2, change.version());
        assertEquals(2, change.nonEmptyBlocks());
        assertEquals("minecraft:glass", change.wrote());
        assertEquals(McNativeIngestTrace.epoch(), change.epoch());
        assertEquals(System.identityHashCode(this.engine), change.engine());
        assertSame(change, McNativeIngestTrace.lastUpdate());

        // a later no-op keeps the change on record and the version where it is
        WorldUpdater.insertUpdate(this.engine, section(PX >> 4, glass));
        assertSame(change, McNativeIngestTrace.lastChange());
        assertFalse(McNativeIngestTrace.lastUpdate().changed());
        assertEquals(2, McNativeIngestTrace.generation());
    }

    @Test
    void aMeshIsCreditedOnlyWithTheContentItReadUnderTheLock() {
        var old = meshRead(this.engine);                                   // version 0, air
        WorldUpdater.insertUpdate(this.engine, section(PX >> 4, glass));
        McNativeIngestTrace.dirtyConsumed(LEAF, this.engine);
        McNativeIngestTrace.meshed(old, LEAF, this.engine);        // accepted late: still what it read
        assertEquals(1, McNativeIngestTrace.lastChange().version());
        assertEquals(0, McNativeIngestTrace.leafMeshed().version(), "read before the write");
        assertEquals("minecraft:air", McNativeIngestTrace.leafMeshed().probeHeld());
        assertTrue(McNativeIngestTrace.leafMeshed().seq() > McNativeIngestTrace.leafDirty().seq(),
            "strict order holds and is still no derivation");

        // a read begun after the publication contains it: version 1, glass
        var fresh = meshRead(this.engine);
        McNativeIngestTrace.meshed(fresh, LEAF, this.engine);
        assertEquals(1, McNativeIngestTrace.leafMeshed().version());
        assertEquals("minecraft:glass", McNativeIngestTrace.leafMeshed().probeHeld());

        // a mesh whose read was not captured is credited with nothing (-1, no content)
        McNativeIngestTrace.meshed(null, LEAF, this.engine);
        assertEquals(-1, McNativeIngestTrace.leafMeshed().version());
        assertNull(McNativeIngestTrace.leafMeshed().probeHeld());

        // another leaf's or an ancestor's mesh does not touch the leaf's record, and takes no lock
        var mine = McNativeIngestTrace.leafMeshed();
        var other = this.engine.acquire(OTHER);
        try {
            assertNull(McNativeIngestTrace.beginMesh(OTHER, this.engine, other));
        } finally { other.release(); }
        McNativeIngestTrace.meshed(null, OTHER, this.engine);
        McNativeIngestTrace.meshed(null, WorldEngine.getWorldSectionId(1, 0, 1, 1), this.engine);
        assertSame(mine, McNativeIngestTrace.leafMeshed());
    }

    @Test
    void anotherEnginesReadOrWriteIsNotObserved() {
        var foreign = new WorldEngine(new WorldUpdaterOutcomeTest.MemoryStorage());
        try {
            var s = foreign.acquire(LEAF);
            try {
                assertNull(McNativeIngestTrace.beginMesh(LEAF, foreign, s), "no lock, no identity for another engine");
                assertNull(McNativeIngestTrace.readHold(LEAF, foreign));
            } finally { s.release(); }
            foreign.setSaveCallback((into, sec, nb, acq) -> { sec.setNotDirty(); if (acq) sec.release(); return true; });
            WorldUpdater.insertUpdate(foreign, section(PX >> 4, glass));
            assertNull(McNativeIngestTrace.lastUpdate(), "another engine's write of the same leaf id is not this observation's");
            assertEquals(0, McNativeIngestTrace.generation());
        } finally {
            try { foreign.free(); } catch (RuntimeException ignored) { }
        }
    }

    @Test
    void observingAgainStartsANewEpochAndKeepsTheContentVersionOfTheSameLeafAndEngine() {
        WorldUpdater.insertUpdate(this.engine, section(PX >> 4, glass));
        McNativeIngestTrace.dirtyConsumed(LEAF, this.engine);
        var read = meshRead(this.engine);
        long epoch = McNativeIngestTrace.epoch();
        McNativeIngestTrace.observe(LEAF, PX, PY, PZ, this.engine);
        assertTrue(McNativeIngestTrace.epoch() > epoch, "a new epoch");
        assertEquals(1, McNativeIngestTrace.generation(), "the leaf's content is what it is");
        assertNull(McNativeIngestTrace.lastChange());
        assertNull(McNativeIngestTrace.lastUpdate());
        assertNull(McNativeIngestTrace.leafDirty());
        assertNull(McNativeIngestTrace.leafMeshed());
        assertNull(McNativeIngestTrace.leafUnwatched());
        // a read captured in the earlier epoch keeps that epoch when accepted now: the gate refuses it
        McNativeIngestTrace.meshed(read, LEAF, this.engine);
        assertEquals(epoch, McNativeIngestTrace.leafMeshed().epoch());
        assertNotEquals(McNativeIngestTrace.epoch(), McNativeIngestTrace.leafMeshed().epoch());
        McNativeIngestTrace.observe(OTHER, PX, PY, PZ + 32, this.engine);
        assertEquals(0, McNativeIngestTrace.generation(), "another leaf starts from nothing");
        var foreign = new WorldEngine(new WorldUpdaterOutcomeTest.MemoryStorage());
        try {
            McNativeIngestTrace.observe(LEAF, PX, PY, PZ, foreign);
            assertEquals(0, McNativeIngestTrace.generation(), "the same leaf in another engine starts from nothing");
        } finally {
            try { foreign.free(); } catch (RuntimeException ignored) { }
        }
    }

    @Test
    void theRemovedLeafTerminalRecordsTheVersionAndTheCountItReadUnderTheLock() {
        McNativeIngestTrace.unwatched(OTHER, this.engine);
        assertNull(McNativeIngestTrace.leafUnwatched());
        WorldUpdater.insertUpdate(this.engine, section(PX >> 4, glass));
        McNativeIngestTrace.unwatched(LEAF, this.engine);
        var removed = McNativeIngestTrace.leafUnwatched();
        assertNotNull(removed);
        assertEquals(1, removed.version());
        assertEquals(1, removed.nonEmptyBlocks(), "the leaf still holds the glass: the gate refuses this terminal");
        WorldUpdater.insertUpdate(this.engine, section(PX >> 4, AIR));
        McNativeIngestTrace.unwatched(LEAF, this.engine);
        removed = McNativeIngestTrace.leafUnwatched();
        assertEquals(2, removed.version());
        assertEquals(0, removed.nonEmptyBlocks());
        assertTrue(removed.seq() > McNativeIngestTrace.lastChange().seq());
        assertEquals(McNativeIngestTrace.epoch(), removed.epoch());
        assertEquals(System.identityHashCode(this.engine), removed.engine());
    }

    @Test
    void inertWhenOff() {
        McNativeIngestTrace.setEnabled(false);
        long a = McNativeIngestTrace.now();
        McNativeIngestTrace.ingestQueued(9, 9, 9, this.engine);
        WorldUpdater.insertUpdate(this.engine, section(PX >> 4, glass));
        McNativeIngestTrace.dirtyConsumed(LEAF, this.engine);
        var s = this.engine.acquireIfExists(LEAF);
        try {
            assertNull(McNativeIngestTrace.beginMesh(LEAF, this.engine, s));
            assertNull(McNativeIngestTrace.readHold(LEAF, this.engine));
        } finally { s.release(); }
        McNativeIngestTrace.meshed(null, LEAF, this.engine);
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
