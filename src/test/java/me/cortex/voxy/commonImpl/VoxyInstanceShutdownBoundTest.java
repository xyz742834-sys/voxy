package me.cortex.voxy.commonImpl;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.thread.Deadline;
import me.cortex.voxy.common.thread.UnifiedServiceThreadPool;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdaterOutcomeTest;
import me.cortex.voxy.common.world.service.SectionSavingService;
import me.cortex.voxy.commonImpl.importers.IDataImporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R29-PRELAUNCH-SHUTDOWN and the R30 prelaunch review of 5bdc067e: the real {@link VoxyInstance#shutdown}
 * (a real instance: thread pool, ingest and saving services, world cleaner, a real {@link WorldEngine}
 * registered through {@link VoxyInstance#getOrCreate} with a controllable storage) as one retained
 * operation whose caller waits at most its bound. The reviewer's counterexample — a storage flush inside
 * {@code world.free()} that outlives a 1200 ms bound and an interrupt — and the other synchronous waits
 * (a save, an import cancellation, a reader still holding a section, a stuck pool job): the caller
 * returns inside its bound with the operation counted outstanding at its stage, nothing is freed under
 * a reader or run twice, and after the explicit release the operation completes once (counted late).
 * The default (no bound) runs inline and completes everything. Every owned thread is released and
 * joined. CPU only.
 */
public class VoxyInstanceShutdownBoundTest {
    private final CountDownLatch release = new CountDownLatch(1);

    /** In-memory storage whose flush or save can be made to block until {@link #release}. */
    final class Storage extends SectionStorage {
        final CountDownLatch flushEntered = new CountDownLatch(1), saveEntered = new CountDownLatch(1);
        volatile boolean blockFlush, blockSave;
        volatile int flushes, closes, saves;
        @Override public int loadSection(WorldSection into) { return 1; }
        @Override public void saveSection(WorldSection section) { this.saves++; this.saveEntered.countDown(); if (this.blockSave) await(); }
        @Override public void putIdMapping(int id, ByteBuffer data) { }
        @Override public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() { return new Int2ObjectOpenHashMap<>(); }
        @Override public void flush() { this.flushes++; this.flushEntered.countDown(); if (this.blockFlush) await(); }
        @Override public void close() { this.closes++; }
        @Override public void iteratePositions(int level, LongConsumer callback) { }
    }

    /** Wait for {@link #release}, as the reviewer's storage did: an interrupt does not end the wait. */
    private void await() {
        boolean interrupted = false;
        while (true) {
            try { this.release.await(); break; } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private final Storage storage = new Storage();
    private VoxyInstance instance;
    private WorldEngine world;
    private int outstandingBefore, lateBefore, failedBefore, stageFailuresBefore, stagesBefore;

    @BeforeEach
    void counts() {
        me.cortex.voxy.testsupport.MinecraftBootstrap.ensure();
        this.outstandingBefore = VoxyInstance.shutdownOutstanding();
        this.lateBefore = VoxyInstance.shutdownCompletedLate();
        this.failedBefore = VoxyInstance.shutdownFailed();
        this.stageFailuresBefore = VoxyInstance.shutdownStageFailures();
        this.stagesBefore = VoxyInstance.shutdownIncomplete().size();
    }

    @AfterEach
    void unblock() throws InterruptedException {
        this.release.countDown();
        assertTrue(VoxyInstance.awaitOutstandingShutdowns(20_000), "every retained operation of this test completed");
        VoxyInstance.boundShutdown(-1);
    }

    private VoxyInstance instance() {
        var instance = new VoxyInstance() {
            @Override protected SectionStorage createStorage(WorldIdentifier id) { return VoxyInstanceShutdownBoundTest.this.storage; }
        };
        this.instance = instance;
        return instance;
    }

    /** A real world of the instance, in its active map, with this test's storage. */
    private WorldEngine world() {
        var id = new WorldIdentifier(net.minecraft.world.level.Level.OVERWORLD, 0, null);
        this.world = this.instance.getOrCreate(id);
        assertNotNull(this.world);
        return this.world;
    }

    private List<String> newStages() {
        var all = VoxyInstance.shutdownIncomplete();
        return all.subList(this.stagesBefore, all.size());
    }

    /** The bounded caller returned inside its bound with the operation outstanding at {@code stage}. */
    private void assertBoundedRefusal(long took, long bound, String stage) {
        assertTrue(took < bound + 1500, "took " + took + " ms for a " + bound + " ms bound");
        assertEquals(List.of(stage), this.newStages(), "the stage the operation was in when the caller stopped waiting");
        assertEquals(this.outstandingBefore + 1, VoxyInstance.shutdownOutstanding(), "the operation is outstanding, owned, still running");
        assertEquals(this.lateBefore, VoxyInstance.shutdownCompletedLate(), "not completed yet");
        assertFalse(this.instance.isRunning());
    }

    /** After the explicit release: the operation completed once, late, with no failure. */
    private void assertCompletedLateOnce() throws InterruptedException {
        this.release.countDown();
        assertTrue(VoxyInstance.awaitOutstandingShutdowns(20_000), "the operation completed after the release");
        assertEquals(this.outstandingBefore, VoxyInstance.shutdownOutstanding());
        assertEquals(this.lateBefore + 1, VoxyInstance.shutdownCompletedLate(), "its completion is counted as late");
        assertEquals(this.failedBefore, VoxyInstance.shutdownFailed());
        assertEquals(this.stageFailuresBefore, VoxyInstance.shutdownStageFailures());
    }

    @Test
    @Timeout(60)
    void aSlowStorageFlushDoesNotHoldTheBoundedCallerAndIsFinishedOnceByTheRetainedOperation() throws Exception {
        // the reviewer's probe: a real instance and world, the storage flush inside world.free() blocks
        this.instance();
        this.world();
        this.storage.blockFlush = true;
        VoxyInstance.boundShutdown(1200);
        long t0 = System.nanoTime();
        this.instance.shutdown();
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(took >= 1100, "the caller waited for its bound: " + took + " ms");
        this.assertBoundedRefusal(took, 1200, "world-free");
        assertTrue(this.storage.flushEntered.await(0, TimeUnit.SECONDS), "the operation is inside the flush");
        assertEquals(1, this.storage.flushes);
        assertEquals(0, this.storage.closes, "nothing after the flush ran yet: the operation is still in it");
        assertFalse(this.world.isLive(), "the world is the operation's: freeing it has begun and is not run again");
        assertFalse(Thread.currentThread().isInterrupted());

        this.assertCompletedLateOnce();
        assertEquals(1, this.storage.flushes, "flushed once");
        assertEquals(1, this.storage.closes, "closed once, after the flush");
        assertThrows(IllegalStateException.class, () -> this.instance.getThreadPool().shutdownUntil(Deadline.in(100)),
            "the pool was already shut down by the operation (its last stage ran)");
    }

    @Test
    @Timeout(60)
    void anInterruptedCallerStopsWaitingAtOnceAndTheOperationIsUnaffected() throws Exception {
        this.instance();
        this.world();
        this.storage.blockFlush = true;
        VoxyInstance.boundShutdown(30_000);
        var flagAtEnd = new AtomicReference<Boolean>();
        var failure = new AtomicReference<Throwable>();
        var caller = new Thread(() -> {
            try { this.instance.shutdown(); } catch (Throwable t) { failure.set(t); }
            flagAtEnd.set(Thread.currentThread().isInterrupted());
        }, "shutdown caller under test");
        long t0 = System.nanoTime();
        caller.start();
        assertTrue(this.storage.flushEntered.await(10, TimeUnit.SECONDS), "the operation reached the flush");
        caller.interrupt();
        caller.join(10_000);
        assertFalse(caller.isAlive(), "the interrupt ended the caller's wait");
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(took < 10_000, "returned at the interrupt, not the 30 s bound: " + took + " ms");
        assertNull(failure.get(), "no exception escapes");
        assertEquals(Boolean.TRUE, flagAtEnd.get(), "the interrupt flag is restored for the caller");
        assertTrue(VoxyInstance.shutdownInterrupted());
        assertEquals(List.of("world-free"), this.newStages());
        assertEquals(this.outstandingBefore + 1, VoxyInstance.shutdownOutstanding());
        assertEquals(0, this.storage.closes, "the operation's thread was not interrupted: it still waits inside the flush");

        this.assertCompletedLateOnce();
        assertEquals(1, this.storage.flushes);
        assertEquals(1, this.storage.closes, "the interrupted caller skipped no cleanup: the operation finished it");
    }

    @Test
    @Timeout(60)
    void aSlowSectionSaveIsTheSavingStageAndIsFinishedAfterRelease() throws Exception {
        this.instance();
        this.world();
        this.instance.getThreadPool().setNumThreads(1);
        var section = this.world.acquire(0, 1, 3, 2);
        this.storage.blockSave = true;
        assertTrue(this.instance.savingService.enqueueSave(this.world, section, true, true), "a save is queued (the section stays acquired by it)");
        assertTrue(this.storage.saveEntered.await(10, TimeUnit.SECONDS), "the worker is inside the save");
        VoxyInstance.boundShutdown(1200);
        long t0 = System.nanoTime();
        this.instance.shutdown();
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        this.assertBoundedRefusal(took, 1200, "saving");
        assertTrue(this.world.isLive(), "the world is not freed while its save is in progress");
        assertEquals(0, this.storage.flushes);

        this.assertCompletedLateOnce();
        assertEquals(1, this.storage.saves, "saved once");
        assertEquals(1, this.storage.flushes);
        assertEquals(1, this.storage.closes);
    }

    @Test
    @Timeout(60)
    void aBlockingImportCancellationIsTheCancelImportsStageAndIsFinishedAfterRelease() throws Exception {
        this.instance();
        this.world();
        var shutdowns = new java.util.concurrent.atomic.AtomicInteger();
        var importer = new IDataImporter() {
            volatile boolean running;
            @Override public void runImport(IUpdateCallback u, ICompletionCallback c) { this.running = true; }
            @Override public WorldEngine getEngine() { return VoxyInstanceShutdownBoundTest.this.world; }
            @Override public void shutdown() { shutdowns.incrementAndGet(); VoxyInstanceShutdownBoundTest.this.await(); this.running = false; }
            @Override public boolean isRunning() { return this.running; }
        };
        assertTrue(this.instance.getImportManager().tryRunImport(importer));
        VoxyInstance.boundShutdown(1200);
        long t0 = System.nanoTime();
        this.instance.shutdown();
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        this.assertBoundedRefusal(took, 1200, "cancel-imports");
        assertEquals(1, shutdowns.get(), "the importer's shutdown is in progress, once");
        assertTrue(this.world.isLive(), "the world is not freed while its import is being cancelled");

        this.assertCompletedLateOnce();
        assertEquals(1, shutdowns.get());
        assertFalse(importer.isRunning());
        assertEquals(1, this.storage.flushes);
        assertEquals(1, this.storage.closes);
    }

    @Test
    @Timeout(60)
    void aReaderStillHoldingASectionKeepsTheWorldAliveUntilItReleases() throws Exception {
        this.instance();
        this.world();
        var section = this.world.acquire(0, 1, 3, 2);   // a reader (a mesher worker that outlived its stop)
        VoxyInstance.boundShutdown(1200);
        long t0 = System.nanoTime();
        this.instance.shutdown();
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        this.assertBoundedRefusal(took, 1200, "world-wait");
        assertTrue(this.world.isLive(), "nothing is freed under the reader");
        assertNotNull(section._unsafeGetRawDataArray(), "what the reader reads is intact");
        assertEquals(0, this.storage.flushes);

        section.release();
        this.assertCompletedLateOnce();
        assertFalse(this.world.isLive(), "freed once the reader released");
        assertEquals(1, this.storage.flushes);
        assertEquals(1, this.storage.closes);
    }

    /** A job on the instance's own pool that blocks until {@link #release}, ignoring interrupts. */
    private void stuckJob() throws InterruptedException {
        var started = new CountDownLatch(1);
        var stuck = this.instance.getServiceManager().createServiceNoCleanup(() -> () -> {
            started.countDown();
            this.await();
        }, 1, "stuck test job");
        this.instance.getThreadPool().setNumThreads(1);
        stuck.execute();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        // the service's own bounded shutdown reports the job alive instead of waiting for it
        long t0 = System.nanoTime();
        assertFalse(stuck.shutdownUntil(Deadline.in(100)));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 5000);
    }

    @Test
    @Timeout(60)
    void aStuckPoolJobIsTheThreadPoolStageAndItsWorkerIsNeitherKilledNorLeaked() throws Exception {
        this.instance();
        this.stuckJob();
        int threads = VoxyInstance.poolThreadsLeft();
        VoxyInstance.boundShutdown(1500);
        long t0 = System.nanoTime();
        this.instance.shutdown();
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(took >= 1000, "took " + took + " ms");
        this.assertBoundedRefusal(took, 1500, "thread-pool");
        assertEquals(threads, VoxyInstance.poolThreadsLeft(), "the operation waits for the worker: nothing is counted as leaked");

        this.assertCompletedLateOnce();
        assertEquals(threads, VoxyInstance.poolThreadsLeft(), "the worker ended and was joined by the operation");
    }

    @Test
    @Timeout(60)
    void theDefaultShutdownRunsInlineCompletesEverythingAndCountsNothing() throws Exception {
        this.instance();
        this.world();
        this.instance.getThreadPool().setNumThreads(2);
        var ran = new CountDownLatch(1);
        var quick = this.instance.getServiceManager().createServiceNoCleanup(() -> ran::countDown, 1, "quick test job");
        quick.execute();
        assertTrue(ran.await(10, TimeUnit.SECONDS));
        assertEquals(0, quick.shutdown());
        int threads = VoxyInstance.poolThreadsLeft(), unflushed = VoxyInstance.saveEntriesUnflushed(), worlds = VoxyInstance.worldsLeftUnfreed();
        VoxyInstance.boundShutdown(-1);
        this.instance.shutdown();
        assertEquals(this.stagesBefore, VoxyInstance.shutdownIncomplete().size(), "every stage completed");
        assertEquals(this.outstandingBefore, VoxyInstance.shutdownOutstanding(), "inline: no retained operation");
        assertEquals(this.lateBefore, VoxyInstance.shutdownCompletedLate());
        assertEquals(this.failedBefore, VoxyInstance.shutdownFailed());
        assertEquals(this.stageFailuresBefore, VoxyInstance.shutdownStageFailures());
        assertEquals(threads, VoxyInstance.poolThreadsLeft());
        assertEquals(unflushed, VoxyInstance.saveEntriesUnflushed());
        assertEquals(worlds, VoxyInstance.worldsLeftUnfreed());
        assertFalse(this.world.isLive(), "the world was freed inline");
        assertEquals(1, this.storage.flushes);
        assertEquals(1, this.storage.closes);
    }

    @Test
    @Timeout(60)
    void theSavingServiceSavesWhatItCanAndCountsTheRest() {
        // the service's own bounded drain (used by an interrupted inline shutdown): no worker threads,
        // the queued save never runs on its own
        var pool = new UnifiedServiceThreadPool();
        var saving = new SectionSavingService(pool.serviceManager);
        var storage = new WorldUpdaterOutcomeTest.MemoryStorage();
        var engine = new WorldEngine(storage);
        var section = engine.acquire(0, 1, 3, 2);
        assertTrue(saving.enqueueSave(engine, section, true, true));
        // at a deadline already passed: the entry is left unsaved and counted, never dropped silently
        assertEquals(1, saving.shutdownUntil(System.nanoTime() - 1));
        assertEquals(0, storage.saves);
        assertEquals(0, pool.shutdownUntil(Deadline.in(2000)), "no worker thread; the drained permit leaves no job behind");

        // with a worker and no deadline, everything is saved, as Voxy always has
        var pool2 = new UnifiedServiceThreadPool();
        pool2.setNumThreads(1);
        var saving2 = new SectionSavingService(pool2.serviceManager);
        var section2 = engine.acquire(0, 1, 3, 3);
        assertTrue(saving2.enqueueSave(engine, section2, true, true));
        assertEquals(0, saving2.shutdownUntil(Deadline.NONE));
        assertEquals(1, storage.saves);
        assertEquals(0, pool2.shutdownUntil(Deadline.in(2000)));
        // the unflushed entry keeps its section acquired and in the save queue: that world cannot be
        // freed cleanly either — the unflushed state the report counts, not something freed here
        assertThrows(IllegalStateException.class, section::release);
    }
}
