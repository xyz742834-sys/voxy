package me.cortex.voxy.client.core.vk;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The native mesher's worker threads and what they own (GL meshes on Voxy's service threads,
 * {@code RenderGenerationService}). Submissions and polling happen on the render thread.
 *
 * <p>⚠ Round-28 Codex review R28-WORKER-SHUTDOWN-BOUND: stopping used to wait ten seconds on the
 * render thread and then return with workers alive, after which the scene freed the model factory
 * they read. Here ownership is explicit:
 * <ul>
 *   <li>each task holds the world ({@link WorldHold}) from submission until it ran or was cancelled,
 *       so a worker never reads a closed world;</li>
 *   <li>{@link #stop} waits a bounded time; workers still running then keep their undelivered
 *       results and per-thread resources, and the owner must keep what they read, until
 *       {@link #reapIfStopped} sees them terminated (an interrupted wait is not an answer either);</li>
 *   <li>a result delivered after the stop (a stale one) is freed by the reap, never handed out.</li>
 * </ul>
 */
public final class VkMeshWorkers<R> {
    /** A reference on what the workers read; {@link #tryAcquire} false when it has closed. */
    public interface WorldHold {
        boolean tryAcquire();
        void release();
    }

    private final Supplier<Integer> threads;
    private final String name;
    private final WorldHold hold;
    private final Consumer<R> freeResult;
    private final Runnable freeOwned;
    private final long stopWaitMillis;
    private final ConcurrentLinkedQueue<R> results = new ConcurrentLinkedQueue<>();
    private ExecutorService pool;
    private boolean stopped, reaped;

    /**
     * @param threads    worker count, read when the first task is submitted
     * @param freeResult frees an undelivered result
     * @param freeOwned  frees the workers' per-thread resources, once they have terminated
     */
    public VkMeshWorkers(Supplier<Integer> threads, String name, WorldHold hold, Consumer<R> freeResult,
                         Runnable freeOwned, long stopWaitMillis) {
        this.threads = threads;
        this.name = name;
        this.hold = hold;
        this.freeResult = freeResult;
        this.freeOwned = freeOwned;
        this.stopWaitMillis = stopWaitMillis;
    }

    /**
     * Run {@code work} on a worker; its result arrives through {@link #poll}. When the world has
     * closed, {@code closed} is delivered instead, without running anything.
     */
    public void submit(Supplier<R> work, R closed) {
        if (this.stopped) throw new IllegalStateException("the workers were stopped");
        if (this.pool == null) {
            var counter = new AtomicInteger();
            this.pool = Executors.newFixedThreadPool(Math.max(1, this.threads.get()), r -> {
                var t = new Thread(r, this.name + " " + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
        }
        if (!this.hold.tryAcquire()) {
            this.results.add(closed);
            return;
        }
        this.pool.execute(new Task(work));
    }

    /** A delivered result, or null. Null after {@link #stop}: what arrives later is stale. */
    public R poll() {
        return this.stopped ? null : this.results.poll();
    }

    /**
     * Cancel queued tasks, interrupt running ones and wait up to the stop bound. Idempotent.
     * @return whether the workers have terminated and what they owned is freed
     */
    public boolean stop() {
        if (!this.stopped) {
            this.stopped = true;
            if (this.pool != null) {
                for (Runnable never : this.pool.shutdownNow()) ((Task) never).release();
                try {
                    this.pool.awaitTermination(this.stopWaitMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    // not an answer about the workers: keep the interrupt, and their resources
                    Thread.currentThread().interrupt();
                }
            }
        }
        return this.reapIfStopped();
    }

    /** Whether stopped (or running) workers are still alive. */
    public boolean alive() {
        return this.pool != null && !this.pool.isTerminated();
    }

    /**
     * Once the stopped workers have terminated, free their undelivered results and per-thread
     * resources (render thread). @return whether they have terminated (and those are freed)
     */
    public boolean reapIfStopped() {
        if (!this.stopped) throw new IllegalStateException("the workers were not stopped");
        if (this.alive()) return false;
        if (!this.reaped) {
            this.reaped = true;
            for (R r; (r = this.results.poll()) != null; ) this.freeResult.accept(r);
            this.freeOwned.run();
        }
        return true;
    }

    /** Tasks waiting for a worker (diagnostics and tests). */
    int queued() {
        return this.pool instanceof java.util.concurrent.ThreadPoolExecutor tpe ? tpe.getQueue().size() : 0;
    }

    private final class Task implements Runnable {
        private final Supplier<R> work;
        private boolean released;

        Task(Supplier<R> work) { this.work = work; }

        @Override
        public void run() {
            try {
                VkMeshWorkers.this.results.add(this.work.get());
            } finally {
                this.release();
            }
        }

        /** Give the world back: after running, or when cancelled before it ran. */
        void release() {
            if (this.released) return;
            this.released = true;
            VkMeshWorkers.this.hold.release();
        }
    }
}
