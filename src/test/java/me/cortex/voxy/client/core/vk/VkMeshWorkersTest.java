package me.cortex.voxy.client.core.vk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R28-WORKER-SHUTDOWN-BOUND (round-28 Codex review of record): the native mesher's workers with real
 * threads. A stopped worker that does not end keeps what it reads until it ends; the render thread
 * waits only the stop bound; an interrupted stop is not an answer; stale results are freed, never
 * delivered; every task gives the world back, cancelled or not. CPU only.
 */
public class VkMeshWorkersTest {
    private static final long BOUND = 100;

    /** The world reference, counted; can be closed. */
    private static final class Hold implements VkMeshWorkers.WorldHold {
        final AtomicInteger held = new AtomicInteger();
        volatile boolean closed;
        @Override public boolean tryAcquire() {
            if (this.closed) return false;
            this.held.incrementAndGet();
            return true;
        }
        @Override public void release() { this.held.decrementAndGet(); }
    }

    private final Hold hold = new Hold();
    private final List<String> freedResults = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean ownedFreed = new AtomicBoolean();
    /** Released at the end so no test leaves a thread blocked. */
    private final CountDownLatch release = new CountDownLatch(1);

    private VkMeshWorkers<String> workers(int threads) {
        return new VkMeshWorkers<>(() -> threads, "test mesher", this.hold, this.freedResults::add,
            () -> assertFalse(this.ownedFreed.getAndSet(true), "freed twice"), BOUND);
    }

    /** A task that ignores interruption until {@link #release} (a section mid-mesh that will not stop). */
    private String stuck(CountDownLatch started, String result) {
        started.countDown();
        while (true) {
            try {
                if (this.release.await(10, TimeUnit.SECONDS)) return result;
            } catch (InterruptedException ignored) {
                // a worker that does not honour interruption
            }
        }
    }

    @AfterEach
    void unblock() {
        this.release.countDown();
        Thread.interrupted();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier c) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) fail("condition not reached in 10 s");
            Thread.sleep(5);
        }
    }

    @Test
    void ordinaryWorkIsDeliveredAndAStopFreesWhatTheWorkersOwned() throws Exception {
        var w = workers(2);
        for (int i = 0; i < 8; i++) {
            int n = i;
            w.submit(() -> "mesh" + n, "closed");
        }
        var got = new ArrayList<String>();
        awaitTrue(() -> {
            for (String r; (r = w.poll()) != null; ) got.add(r);
            return got.size() == 8;
        });
        assertTrue(w.stop(), "idle workers end within the bound");
        assertFalse(w.alive());
        assertTrue(this.ownedFreed.get(), "per-thread resources freed once the workers ended");
        assertEquals(0, this.hold.held.get(), "every task gave the world back");
    }

    @Test
    void aStuckWorkerKeepsWhatItReadsUntilItEnds() throws Exception {
        var w = workers(1);
        var started = new CountDownLatch(1);
        w.submit(() -> stuck(started, "late"), "closed");
        assertTrue(started.await(10, TimeUnit.SECONDS));

        long t0 = System.nanoTime();
        assertFalse(w.stop(), "the worker is still running: the owner must keep what it reads");
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(waited < BOUND + 2000, "the render thread waited " + waited + " ms, bound " + BOUND);
        assertTrue(w.alive());
        assertFalse(this.ownedFreed.get(), "nothing the worker reads is freed under it");
        assertEquals(1, this.hold.held.get(), "the running task still holds the world");
        assertFalse(w.reapIfStopped(), "a later reap waits too");
        assertFalse(w.stop(), "a repeated stop does not wait or free again");

        this.release.countDown();
        awaitTrue(() -> !w.alive());
        assertTrue(w.reapIfStopped());
        assertTrue(this.ownedFreed.get());
        assertEquals(List.of("late"), this.freedResults, "the stale result is freed by the reap");
        assertNull(w.poll(), "and never delivered");
        assertEquals(0, this.hold.held.get());
        assertTrue(w.reapIfStopped(), "idempotent");
    }

    @Test
    void anInterruptedStopKeepsTheInterruptAndTheResources() throws Exception {
        var w = workers(1);
        var started = new CountDownLatch(1);
        w.submit(() -> stuck(started, "late"), "closed");
        assertTrue(started.await(10, TimeUnit.SECONDS));

        Thread.currentThread().interrupt();
        long t0 = System.nanoTime();
        assertFalse(w.stop(), "an interrupted wait says nothing about the workers");
        assertTrue(Thread.interrupted(), "the render thread's interrupt is kept");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2000);
        assertFalse(this.ownedFreed.get());
        assertEquals(1, this.hold.held.get());

        this.release.countDown();
        awaitTrue(() -> !w.alive());
        assertTrue(w.reapIfStopped());
        assertTrue(this.ownedFreed.get());
        assertEquals(0, this.hold.held.get());
    }

    @Test
    void cancelledTasksGiveTheWorldBack() throws Exception {
        var w = workers(1);
        var started = new CountDownLatch(1);
        w.submit(() -> stuck(started, "late"), "closed");
        assertTrue(started.await(10, TimeUnit.SECONDS));
        var ran = new AtomicInteger();
        for (int i = 0; i < 5; i++) w.submit(() -> "never" + ran.incrementAndGet(), "closed");
        assertEquals(5, w.queued());
        assertEquals(6, this.hold.held.get());

        assertFalse(w.stop());
        assertEquals(1, this.hold.held.get(), "the five never-run tasks released at the stop");
        this.release.countDown();
        awaitTrue(() -> !w.alive());
        assertTrue(w.reapIfStopped());
        assertEquals(0, ran.get(), "cancelled tasks never ran");
        assertEquals(0, this.hold.held.get());
    }

    @Test
    void aClosedWorldRunsNothing() throws Exception {
        var w = workers(1);
        this.hold.closed = true;
        w.submit(() -> fail("must not run on a closed world"), "closed");
        assertEquals("closed", w.poll());
        assertEquals(0, this.hold.held.get());
        assertTrue(w.stop());
    }

    @Test
    void nothingIsSubmittedAfterAStop() {
        var w = workers(1);
        assertTrue(w.stop(), "never started: nothing to wait for");
        assertTrue(this.ownedFreed.get());
        assertThrows(IllegalStateException.class, () -> w.submit(() -> "x", "closed"));
    }

    @Test
    void aReapBeforeAStopIsRefused() {
        var w = workers(1);
        assertThrows(IllegalStateException.class, w::reapIfStopped);
    }
}
