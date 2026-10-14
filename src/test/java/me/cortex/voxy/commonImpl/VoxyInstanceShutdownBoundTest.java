package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.thread.Deadline;
import me.cortex.voxy.common.thread.UnifiedServiceThreadPool;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdaterOutcomeTest;
import me.cortex.voxy.common.world.service.SectionSavingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R29-PRELAUNCH-SHUTDOWN: the real {@link VoxyInstance#shutdown} (a real instance: thread pool,
 * ingest and saving services, world cleaner) under one deadline for the whole lifecycle, with a
 * service job that never ends — the reviewer's pre-deadline service hold — and under interruption.
 * Nothing a running job reads is freed; what still runs is counted; the default stays unbounded.
 * CPU only; no world engine with storage is opened by the instance here.
 */
public class VoxyInstanceShutdownBoundTest {
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void unblock() {
        this.release.countDown();
        VoxyInstance.boundShutdown(-1);
    }

    private static VoxyInstance instance() {
        return new VoxyInstance() {
            @Override protected me.cortex.voxy.common.config.section.SectionStorage createStorage(WorldIdentifier id) {
                throw new UnsupportedOperationException("no worlds are opened in this test");
            }
        };
    }

    /** A job on the instance's own pool that blocks until {@link #release}, ignoring interrupts. */
    private CountDownLatch stuckJob(VoxyInstance instance) throws InterruptedException {
        var started = new CountDownLatch(1);
        var stuck = instance.getServiceManager().createServiceNoCleanup(() -> () -> {
            started.countDown();
            while (true) {
                try { if (this.release.await(10, TimeUnit.SECONDS)) return; }
                catch (InterruptedException ignored) { }
            }
        }, 1, "stuck test job");
        instance.getThreadPool().setNumThreads(1);
        stuck.execute();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        // the service's own bounded shutdown reports the job alive instead of waiting for it
        long t0 = System.nanoTime();
        assertFalse(stuck.shutdownUntil(Deadline.in(100)));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 5000);
        return started;
    }

    @Test
    @Timeout(60)
    void aServiceJobStuckBeforeTheWorldWaitDoesNotHoldTheShutdownPastItsDeadline() throws Exception {
        var instance = instance();
        this.stuckJob(instance);
        int threadsBefore = VoxyInstance.poolThreadsLeft(), stagesBefore = VoxyInstance.shutdownIncomplete().size();
        // one deadline for everything: the cleaner's last sleep (up to 1 s) is inside it, the stuck
        // job's thread is still alive at it
        VoxyInstance.boundShutdown(1500);
        long t0 = System.nanoTime();
        instance.shutdown();
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(took >= 1000 && took < 10_000, "took " + took + " ms for a 1500 ms bound");
        assertEquals(List.of("thread-pool"), VoxyInstance.shutdownIncomplete().subList(stagesBefore, VoxyInstance.shutdownIncomplete().size()),
            "only the pool stage ran past the deadline; cleaner, ingest, saving and worlds completed");
        assertEquals(threadsBefore + 1, VoxyInstance.poolThreadsLeft(), "the stuck worker thread is counted, not killed");
        assertFalse(Thread.currentThread().isInterrupted());
        assertFalse(instance.isRunning());
    }

    @Test
    @Timeout(60)
    void anInterruptedShutdownFinishesWithoutWaitingAndKeepsItsAccounting() throws Exception {
        var instance = instance();
        this.stuckJob(instance);
        int threadsBefore = VoxyInstance.poolThreadsLeft();
        var failure = new AtomicReference<Throwable>();
        var flagAtEnd = new AtomicReference<Boolean>();
        // the default (no deadline) waits on the stuck worker for ever; the interrupt ends the waiting
        var t = new Thread(() -> {
            try { instance.shutdown(); } catch (Throwable e) { failure.set(e); }
            flagAtEnd.set(Thread.currentThread().isInterrupted());
        }, "shutdown under test");
        t.start();
        Thread.sleep(1500);
        assertTrue(t.isAlive(), "unbounded: still waiting on the stuck worker");
        t.interrupt();
        t.join(20_000);
        assertFalse(t.isAlive(), "the interrupt ended the waiting");
        assertNull(failure.get(), "no exception escapes the lifecycle");
        assertEquals(Boolean.TRUE, flagAtEnd.get(), "the interrupt flag is restored for the caller");
        assertTrue(VoxyInstance.shutdownInterrupted());
        assertEquals(threadsBefore + 1, VoxyInstance.poolThreadsLeft(), "the worker still alive is counted");
        assertTrue(VoxyInstance.shutdownIncomplete().contains("thread-pool"));
    }

    @Test
    @Timeout(60)
    void theDefaultShutdownCompletesAndCountsNothing() throws Exception {
        var instance = instance();
        instance.getThreadPool().setNumThreads(2);
        var ran = new CountDownLatch(1);
        var quick = instance.getServiceManager().createServiceNoCleanup(() -> ran::countDown, 1, "quick test job");
        quick.execute();
        assertTrue(ran.await(10, TimeUnit.SECONDS));
        assertEquals(0, quick.shutdown());
        int stages = VoxyInstance.shutdownIncomplete().size(), threads = VoxyInstance.poolThreadsLeft();
        int unflushed = VoxyInstance.saveEntriesUnflushed(), worlds = VoxyInstance.worldsLeftUnfreed();
        instance.shutdown();
        assertEquals(stages, VoxyInstance.shutdownIncomplete().size(), "every stage completed");
        assertEquals(threads, VoxyInstance.poolThreadsLeft());
        assertEquals(unflushed, VoxyInstance.saveEntriesUnflushed());
        assertEquals(worlds, VoxyInstance.worldsLeftUnfreed());
    }

    @Test
    @Timeout(60)
    void theSavingServiceSavesWhatItCanAndCountsTheRest() {
        // no worker threads: the queued save never runs on its own
        var pool = new UnifiedServiceThreadPool();
        var saving = new SectionSavingService(pool.serviceManager);
        me.cortex.voxy.testsupport.MinecraftBootstrap.ensure();
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
