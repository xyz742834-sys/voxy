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
 * section's change produces no target update at all. The R31 review's reset schedule (old writers holding
 * or waiting for the lock across a re-observation) is replayed too. Every owned thread is released and
 * joined. CPU only.
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

    /** Whether {@code t} is parked inside the trace lock's write acquisition. */
    private static boolean waitingForWriteLock(Thread t) {
        return java.util.Arrays.stream(t.getStackTrace()).anyMatch(f ->
            f.getClassName().equals("java.util.concurrent.locks.StampedLock") && f.getMethodName().equals("acquireWrite"));
    }

    private static void spinUntil(java.util.function.BooleanSupplier done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!done.getAsBoolean()) { assertTrue(System.nanoTime() < deadline, "timed out"); Thread.yield(); }
    }

    /**
     * The R31 prelaunch review's survivor (reset_lock_split, ResetProbe): two writers captured the old
     * observation — the first holding its write lock, paused before its content write; the second
     * positively waiting for that lock — when the harness re-observes the same engine and leaf. In
     * dd230016 the new observation had a lock of its own, so the old writers could change the target's
     * content while a new mesh read held the new lock. Now the lock is the leaf's, not the observation's.
     */
    @Test
    @Timeout(60)
    void reObservingWhileOldWritersHoldOrWaitForTheLockKeepsOneContentLock() throws Exception {
        WorldUpdater.insertUpdate(this.engine, section(PX >> 4, glass));   // version 1: target glass
        WorldUpdater.insertUpdate(this.engine, section(3, glass));         // version 2: unrelated glass
        var pin = this.engine.acquire(LEAF);
        var trace = McNativeIngestTrace.observer();
        var entered = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1)};
        var allowWrite = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1)};
        var written = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1)};
        var failure = new AtomicReference<Throwable>();
        // the reviewer's wrapping: old writers pause after taking the lock (before writing) and after
        // their publication; the trace's own observer still does the locking and publishing
        WorldUpdater.setObserver((into, leaf, source) -> {
            var write = trace.beginLeafWrite(into, leaf, source);
            String name = Thread.currentThread().getName();
            if (write == null || !name.startsWith("old writer ")) return write;
            int n = name.charAt(name.length() - 1) - '0';
            entered[n].countDown();
            awaitQuietly(allowWrite[n]);
            return changed -> { write.end(changed); written[n].countDown(); };
        });
        var writers = new Thread[2];
        for (int n = 0; n < 2; n++) {
            long block = n == 0 ? glass : AIR;   // old writer 0 writes glass (the content the reviewer saw mid-read), old writer 1 restores air
            writers[n] = new Thread(() -> {
                try { WorldUpdater.insertUpdate(this.engine, section(PX >> 4, block)); } catch (Throwable t) { failure.set(t); }
            }, "old writer " + n);
        }
        try {
            writers[0].start();
            assertTrue(entered[0].await(10, TimeUnit.SECONDS), "old writer 0 holds the write lock, paused before its write");
            writers[1].start();
            spinUntil(() -> waitingForWriteLock(writers[1]));   // positively waiting for the same lock
            long oldEpoch = McNativeIngestTrace.epoch();

            // the harness's reset at a stage's before mark: same engine, same leaf, new epoch
            McNativeIngestTrace.observe(LEAF, PX, PY, PZ, this.engine);
            assertTrue(McNativeIngestTrace.epoch() > oldEpoch);
            assertEquals(2, McNativeIngestTrace.generation(), "the content version is the leaf's, not the observation's");

            // a new-epoch read and the new target removal both use THE SAME lock the old writers hold: they
            // wait (fail closed) instead of proceeding on a lock of their own
            var newRead = new AtomicReference<McNativeIngestTrace.MeshRead>();
            var reader = new Thread(() -> newRead.set(McNativeIngestTrace.beginMesh(LEAF, this.engine, pin)), "new mesh read");
            reader.start();
            reader.join(300);
            assertTrue(reader.isAlive(), "the new observation's read waits for the old writer's lock");
            var removalDone = new CountDownLatch(1);
            this.target = new Thread(() -> {
                try { WorldUpdater.insertUpdate(this.engine, section(PX >> 4, AIR)); } catch (Throwable t) { failure.set(t); }
                removalDone.countDown();
            }, "target removal");
            this.target.start();
            assertFalse(removalDone.await(300, TimeUnit.MILLISECONDS), "the new target removal waits too");

            // the old writers may proceed as the lock lets them (old writer 0 writes glass, old writer 1
            // restores air); they publish into THEIR (old) observation, the version is the leaf's. Which of
            // the four — old writer 0, old writer 1, the new removal, the new read — the lock serves first
            // after this is the lock's choice; what matters is that it is ONE lock
            allowWrite[0].countDown();
            allowWrite[1].countDown();
            reader.join(20_000);
            assertFalse(reader.isAlive(), "the new observation's read got the lock once the writers let it");
            var read = newRead.get();
            assertNotNull(read);
            // the read holds the lock now: nothing still pending can write until it ends
            boolean w1Pending = written[1].getCount() != 0, removalPending = removalDone.getCount() != 0;
            int probe = me.cortex.voxy.common.world.WorldSection.getIndex(PX & 31, PY & 31, PZ & 31);
            for (int i = 0; i < 40; i++) {
                assertEquals(read.probeHeld, McNativeIngestTrace.blockName(this.engine, pin._unsafeGetRawDataArray()[probe]),
                    "no writer, old or new, changes the content while the read lock is held");
                assertEquals(read.version, McNativeIngestTrace.generation(), "nor the version");
                if (w1Pending) assertEquals(1, written[1].getCount(), "old writer 1 cannot publish under the read");
                if (removalPending) assertEquals(1, removalDone.getCount(), "the new removal cannot run under the read");
                Thread.sleep(5);
            }
            assertTrue(read.version >= 3, "old writer 0's glass (version 3) was serialized before this read");
            McNativeIngestTrace.endMesh(read);
            McNativeIngestTrace.meshed(read, LEAF, this.engine);

            assertTrue(written[1].await(10, TimeUnit.SECONDS));
            assertTrue(removalDone.await(10, TimeUnit.SECONDS));
            this.target.join(10_000);
            assertEquals("minecraft:air", this.held(), "old writer 1 and the removal both wrote air");
            assertNull(failure.get(), "no writer failed: " + failure.get());
            // the gate's terms, whatever the order was: the result's content is the leaf's content at the
            // result's version. The old writers' publications are not this epoch's update; the new removal's
            // is (a change at a version past old writer 1's air, or a no-op). A glass result is therefore
            // before any change of this epoch (refused: "made from content holding glass" / "before the
            // removal's"); an air result at or past the change is one that really contains it
            var mesh = McNativeIngestTrace.leafMeshed();
            assertEquals(read.version, mesh.version());
            var update = McNativeIngestTrace.lastUpdate();
            assertNotNull(update, "the new removal published in this epoch");
            assertEquals("minecraft:air", update.wrote());
            assertEquals(PX >> 4, update.sx());
            var change = McNativeIngestTrace.lastChange();
            if (mesh.probeHeld().equals("minecraft:glass")) {
                assertTrue(change == null || mesh.version() < change.version(), "a glass result precedes any change of this epoch");
            } else {
                assertEquals("minecraft:air", mesh.probeHeld());
                assertTrue(change == null || mesh.version() >= change.version() || update.version() > mesh.version(),
                    "an air result either contains the change or precedes a later no-op");
            }
            WorldUpdater.setObserver(trace);

            // a fresh post-publication target result is accepted: place and remove again in this epoch
            WorldUpdater.insertUpdate(this.engine, section(PX >> 4, glass));
            McNativeIngestTrace.observe(LEAF, PX, PY, PZ, this.engine);
            long v = McNativeIngestTrace.generation();
            WorldUpdater.insertUpdate(this.engine, section(PX >> 4, AIR));
            McNativeIngestTrace.dirtyConsumed(LEAF, this.engine);
            var fresh = meshRead(this.engine);
            McNativeIngestTrace.meshed(fresh, LEAF, this.engine);
            var removal = McNativeIngestTrace.lastChange();
            assertNotNull(removal);
            assertEquals(v + 1, removal.version());
            assertEquals("minecraft:air", removal.wrote());
            assertEquals(PX >> 4, removal.sx());
            assertEquals(removal.version(), McNativeIngestTrace.leafMeshed().version());
            assertEquals("minecraft:air", McNativeIngestTrace.leafMeshed().probeHeld());
            assertEquals(removal.epoch(), McNativeIngestTrace.leafMeshed().epoch());
            assertNull(failure.get(), "no writer failed: " + failure.get());
        } finally {
            for (var l : allowWrite) l.countDown();
            for (var w : writers) { w.join(10_000); assertFalse(w.isAlive(), "old writer joined"); }
            WorldUpdater.setObserver(trace);
            pin.release();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
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
