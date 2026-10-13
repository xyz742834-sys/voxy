package me.cortex.voxy.commonImpl;

import me.cortex.voxy.client.core.vk.VkMeshWorkers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R29-WORLD-SHUTDOWN-BOUND: the outer wait in {@link VoxyInstance#shutdown} for a world still in
 * use. Unbounded by default (Voxy's behaviour, unchanged for GL); bounded, it gives up instead of
 * waiting forever on a native mesher worker that outlived its stop, and the caller then leaves the
 * world unfreed. With the real {@link VkMeshWorkers} and a counted world reference. CPU only.
 */
public class WorldReleaseBoundTest {
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void unblock() { this.release.countDown(); }

    @Test
    void theDefaultWaitsUntilTheWorldIsReleased() throws Exception {
        var used = new AtomicBoolean(true);
        var t = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            used.set(false);
        });
        long t0 = System.nanoTime();
        t.start();
        assertTrue(VoxyInstance.awaitWorldRelease(used::get, -1), "no deadline: waits it out");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) >= 250);
        t.join();
    }

    @Test
    void anUnusedWorldIsReleasedAtOnce() {
        assertTrue(VoxyInstance.awaitWorldRelease(() -> false, 0));
        assertTrue(VoxyInstance.awaitWorldRelease(() -> false, -1));
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)   // a missing deadline fails here instead of hanging
    void aStuckNativeWorkerDoesNotHoldTheOuterShutdownForever() throws Exception {
        var held = new AtomicInteger();
        var ownedFreed = new AtomicBoolean();
        var workers = new VkMeshWorkers<String>(() -> 1, "test mesher", new VkMeshWorkers.WorldHold() {
            @Override public boolean tryAcquire() { held.incrementAndGet(); return true; }
            @Override public void release() { held.decrementAndGet(); }
        }, r -> { }, () -> ownedFreed.set(true), 100);
        var started = new CountDownLatch(1);
        workers.submit(() -> {
            started.countDown();
            while (true) {
                try {
                    if (this.release.await(10, TimeUnit.SECONDS)) return "late";
                } catch (InterruptedException ignored) {
                    // a worker that does not honour interruption
                }
            }
        }, "closed");
        assertTrue(started.await(10, TimeUnit.SECONDS));

        assertFalse(workers.stop(), "the inner stop is bounded and reports the worker alive");
        assertEquals(1, held.get(), "the running task still holds its world");
        long t0 = System.nanoTime();
        assertFalse(VoxyInstance.awaitWorldRelease(() -> held.get() != 0, 200),
            "the outer wait gives up at its deadline instead of waiting forever");
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(waited >= 190 && waited < 2000, "waited " + waited + " ms for a 200 ms bound");
        assertFalse(ownedFreed.get(), "nothing the worker reads was freed");

        this.release.countDown();
        assertTrue(VoxyInstance.awaitWorldRelease(() -> held.get() != 0, 10_000),
            "once the worker ends, its reference is given back");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (workers.alive() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(workers.reapIfStopped());
        assertTrue(ownedFreed.get());
    }

    @Test
    void theNativeBoundIsOptIn() {
        // the GL default is to wait without a deadline; only the native path sets one
        assertEquals(0, VoxyInstance.worldsLeftUnfreed());
    }
}
