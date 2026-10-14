package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.WorldUpdaterOutcomeTest;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTraceTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The R30 prelaunch review's counterexample (R29-PRELAUNCH-CAUSALITY, surviving in 5bdc067e), replayed
 * against the repaired trace with the real {@link WorldUpdater}, {@link WorldEngine} and a controlled
 * pause: chunk section (3,7,4) of the probe's leaf (1,3,2) changes and publishes; a mesh read captures
 * the leaf with the target (2,7,4) still glass; the target's removal writes air and pauses before its
 * publication. Then: the held-content read-back blocks until the publication (it cannot see the
 * unpublished air); the old mesh, accepted during the pause, is recorded with the version and content
 * it actually read; the target's update, once published, is the target's own; the gate's shape refuses
 * the old mesh and accepts only a read begun after the publication. A variant with only the unrelated
 * section's change produces no target update at all. Every owned thread is released and joined. CPU only.
 */
public class McNativeCausalityRaceTest {
    private WorldEngine engine;
    private final CountDownLatch release = new CountDownLatch(1);
    private Thread target;

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
    void off() throws InterruptedException {
        this.release.countDown();
        if (this.target != null) { this.target.join(10_000); assertFalse(this.target.isAlive(), "the target thread was joined"); }
        McNativeIngestTrace.setEnabled(false);
        try { this.engine.free(); } catch (RuntimeException ignored) { }
    }

    /** Voxy's held content at the probe, read as the harness does (under the trace's read lock). */
    private String held() {
        var hold = McNativeIngestTrace.readHold(LEAF, this.engine);
        try {
            var s = this.engine.acquireIfExists(LEAF);
            if (s == null) return "none";
            try { return McNativeIngestTrace.blockName(this.engine, s._unsafeGetRawDataArray()[me.cortex.voxy.common.world.WorldSection.getIndex(PX & 31, PY & 31, PZ & 31)]); }
            finally { s.release(); }
        } finally { if (hold != null) hold.release(); }
    }

    /** The write of the probe's chunk section, pausing inside its publication boundary until {@link #release}. */
    private McNativeIngestTrace.MeshRead raceSetup(CountDownLatch written) {
        // Voxy holds the placement (the chunk-unload ingest's content): version 1
        WorldUpdater.insertUpdate(this.engine, section(PX >> 4, glass));
        McNativeIngestTrace.observe(LEAF, PX, PY, PZ, this.engine);   // the remove stage's observation
        assertEquals(1, McNativeIngestTrace.generation());
        McNativeIngestTrace.ingestQueued(PX >> 4, PY >> 4, PZ >> 4, this.engine);

        // the unrelated chunk section of the same leaf changes: version 2, published
        WorldUpdater.insertUpdate(this.engine, section(3, glass));
        McNativeIngestTrace.dirtyConsumed(LEAF, this.engine);
        assertEquals(2, McNativeIngestTrace.generation());
        assertNull(McNativeIngestTrace.lastUpdate(), "not the target's update");

        // a mesh read of the leaf now: version 2, target still glass
        var old = meshRead(this.engine);
        assertEquals(2, old.version);
        assertEquals("minecraft:glass", old.probeHeld);

        // the target's removal: real updater, paused inside the boundary between its content write and
        // its publication (the trace's observer is wrapped; it is still the one that publishes)
        var trace = McNativeIngestTrace.observer();
        WorldUpdater.setObserver((into, leaf, source) -> {
            var write = trace.beginLeafWrite(into, leaf, source);
            if (write == null || !Thread.currentThread().getName().equals("target removal")) return write;
            return changed -> {
                written.countDown();
                boolean interrupted = false;
                while (true) {
                    try { this.release.await(); break; } catch (InterruptedException e) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
                write.end(changed);
            };
        });
        var failure = new AtomicReference<Throwable>();
        this.target = new Thread(() -> {
            try { WorldUpdater.insertUpdate(this.engine, section(PX >> 4, AIR)); } catch (Throwable t) { failure.set(t); }
        }, "target removal");
        this.target.start();
        return old;
    }

    @Test
    @Timeout(60)
    void thePausedTargetPublicationCannotLendTheUnrelatedChangeToTheOldMesh() throws Exception {
        var written = new CountDownLatch(1);
        var old = this.raceSetup(written);
        assertTrue(written.await(10, TimeUnit.SECONDS), "the target reached its publication");
        // the raw array already holds air — the reviewer's observation — but every reader the gate
        // relies on reads under the lock the writer holds: the held-content read-back blocks
        var heldDuringPause = new AtomicReference<String>();
        var reader = new Thread(() -> heldDuringPause.set(this.held()), "held-content reader");
        reader.start();
        reader.join(300);
        assertTrue(reader.isAlive(), "the held-content read-back waits for the publication");
        assertNull(heldDuringPause.get());

        // the old mesh accepted during the pause: credited with what it read, version 2 and glass
        McNativeIngestTrace.meshed(old, LEAF, this.engine);
        var mesh = McNativeIngestTrace.leafMeshed();
        assertEquals(2, mesh.version());
        assertEquals("minecraft:glass", mesh.probeHeld());
        assertNull(McNativeIngestTrace.lastUpdate(), "the target's update is not published yet: nothing to credit");
        assertEquals(2, McNativeIngestTrace.generation());

        // the publication
        this.release.countDown();
        this.target.join(10_000);
        reader.join(10_000);
        assertFalse(this.target.isAlive() || reader.isAlive());
        assertEquals("minecraft:air", heldDuringPause.get(), "the read-back saw the published write");
        var update = McNativeIngestTrace.lastChange();
        assertNotNull(update);
        assertSame(update, McNativeIngestTrace.lastUpdate());
        assertEquals(3, update.version());
        assertEquals(PX >> 4, update.sx());
        assertEquals("minecraft:air", update.wrote());
        assertTrue(update.changed());
        assertEquals(1, update.nonEmptyBlocks(), "the unrelated section's glass remains");
        assertEquals(3, McNativeIngestTrace.generation());
        // the gate's terms: the mesh's version is before the update's and its content is the glass
        assertTrue(mesh.version() < update.version());
        assertNotEquals(update.wrote(), mesh.probeHeld());
        assertTrue(mesh.seq() < update.seq(), "and it was even accepted before the publication");

        // only a read begun after the publication contains it
        var fresh = meshRead(this.engine);
        McNativeIngestTrace.meshed(fresh, LEAF, this.engine);
        assertEquals(3, McNativeIngestTrace.leafMeshed().version());
        assertEquals("minecraft:air", McNativeIngestTrace.leafMeshed().probeHeld());
        assertEquals(update.epoch(), McNativeIngestTrace.leafMeshed().epoch());
    }

    @Test
    @Timeout(60)
    void aMeshReadBlocksAWriteUntilItEndsAndTheWriteCannotBeHalfSeen() throws Exception {
        WorldUpdater.insertUpdate(this.engine, section(PX >> 4, glass));
        var s = this.engine.acquireIfExists(LEAF);
        assertNotNull(s);
        var read = McNativeIngestTrace.beginMesh(LEAF, this.engine, s);   // the mesher is reading
        var done = new CountDownLatch(1);
        this.target = new Thread(() -> { WorldUpdater.insertUpdate(this.engine, section(PX >> 4, AIR)); done.countDown(); }, "target removal");
        this.target.start();
        assertFalse(done.await(300, TimeUnit.MILLISECONDS), "the write waits for the read to end");
        assertEquals(glass, s._unsafeGetRawDataArray()[me.cortex.voxy.common.world.WorldSection.getIndex(PX & 31, PY & 31, PZ & 31)],
            "the content the mesher reads is unchanged while it reads");
        McNativeIngestTrace.endMesh(read);
        s.release();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals("minecraft:air", this.held());
        assertEquals(2, McNativeIngestTrace.lastChange().version());
        assertEquals(1, read.version);
        assertEquals("minecraft:glass", read.probeHeld);
    }

    @Test
    @Timeout(60)
    void theUnrelatedSectionsChangeAloneProducesNoTargetUpdate() {
        WorldUpdater.insertUpdate(this.engine, section(PX >> 4, glass));
        McNativeIngestTrace.observe(LEAF, PX, PY, PZ, this.engine);
        McNativeIngestTrace.ingestQueued(PX >> 4, PY >> 4, PZ >> 4, this.engine);
        WorldUpdater.insertUpdate(this.engine, section(3, glass));
        McNativeIngestTrace.dirtyConsumed(LEAF, this.engine);
        var read = meshRead(this.engine);
        McNativeIngestTrace.meshed(read, LEAF, this.engine);
        assertEquals(2, McNativeIngestTrace.generation());
        assertEquals(2, McNativeIngestTrace.leafMeshed().version());
        assertNull(McNativeIngestTrace.lastUpdate(), "no update of the target: the gate refuses 'records no update event'");
        assertNull(McNativeIngestTrace.lastChange());
        assertEquals(1, McNativeIngestTrace.otherSectionUpdates());
        assertEquals("minecraft:glass", this.held());
    }
}
