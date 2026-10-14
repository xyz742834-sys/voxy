package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.thread.Deadline;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.thread.UnifiedServiceThreadPool;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.SectionSavingService;
import me.cortex.voxy.common.world.service.VoxelIngestService;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.StampedLock;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

//TODO: add thread access verification (I.E. only accessible on a single thread)
public abstract class VoxyInstance {
    private volatile boolean isRunning = true;
    private final Thread worldCleaner;
    public final BooleanSupplier savingServiceRateLimiter;//Can run if this returns true
    protected final UnifiedServiceThreadPool threadPool;
    protected final SectionSavingService savingService;
    protected final VoxelIngestService ingestService;

    private final StampedLock activeWorldLock = new StampedLock();
    private final HashMap<WorldIdentifier, WorldEngine> activeWorlds = new HashMap<>();

    protected final ImportManager importManager;

    public VoxyInstance() {
        if (!this.shouldCreateInstance()) {
            throw new DontCreateInstance();
        }
        Logger.info("Initializing voxy instance");
        this.threadPool = new UnifiedServiceThreadPool();
        this.savingService = new SectionSavingService(this.getServiceManager());
        this.ingestService = new VoxelIngestService(this.getServiceManager());
        this.importManager = this.createImportManager();
        this.savingServiceRateLimiter = ()->this.savingService.getTaskCount()<1200;
        this.worldCleaner = new Thread(()->{
            try {
                while (this.isRunning) {
                    //noinspection BusyWait
                    Thread.sleep(1000);
                    this.cleanIdle();
                }
            } catch (InterruptedException e) {
                //We are exiting, so just exit
            } catch (Exception e) {
                Logger.error("Exception in world cleaner",e);
            }
        });
        this.worldCleaner.setPriority(Thread.MIN_PRIORITY);
        this.worldCleaner.setName("Active world cleaner");
        this.worldCleaner.setDaemon(true);
        this.worldCleaner.start();
    }

    protected boolean shouldCreateInstance() {
        return true;
    }

    protected void setNumThreads(int threads) {
        if (threads<0) throw new IllegalArgumentException("Num threads <0");
        if (this.threadPool.setNumThreads(threads)) {
            Logger.info("Dedicated voxy thread pool size: " + threads);
        }
    }

    public void updateDedicatedThreads() {
        this.setNumThreads(3);
    }

    protected ImportManager createImportManager() {
        return new ImportManager();
    }

    public ServiceManager getServiceManager() {
        return this.threadPool.serviceManager;
    }
    public UnifiedServiceThreadPool getThreadPool() {
        return this.threadPool;
    }
    public VoxelIngestService getIngestService() {
        return this.ingestService;
    }
    public ImportManager getImportManager() {
        return this.importManager;
    }

    //TODO: reference count the world object
    // have automatic world cleanup after ~1 minute of inactivity and the reference count equaling zero possibly
    // note, the reference count should be separate from the number of active chunks to prevent many issues
    // a world is no longer active once it has no reference counts and no active chunks associated with it
    public WorldEngine getNullable(WorldIdentifier identifier) {
        if (!this.isRunning) return null;
        var cache = identifier.cachedEngineObject;
        WorldEngine world;
        if (cache == null) {
            world = null;
        } else {
            world = cache.get();
            if (world == null) {
                identifier.cachedEngineObject = null;
            } else {
                if (world.isLive()) {
                    if (world.instanceIn != this) {
                        throw new IllegalStateException("World cannot be in identifier cache, alive and not part of this instance");
                    }
                    //Successful cache hit
                } else {
                    identifier.cachedEngineObject = null;
                    world = null;
                }
            }
        }
        if (world == null) {//If the cached world is null, try get from the active worlds
            long stamp = this.activeWorldLock.readLock();
            world = this.activeWorlds.get(identifier);
            this.activeWorldLock.unlockRead(stamp);
            if (world != null) {//Setup cache
                identifier.cachedEngineObject = new WeakReference<>(world);
            }
        }
        if (world != null) {
            //Mark the world as active
            world.markActive();
        }
        return world;
    }

    public WorldEngine getOrCreate(WorldIdentifier identifier) {
        return this.getOrCreate(identifier, false);
    }

    public WorldEngine getOrCreate(WorldIdentifier identifier, boolean incrementRef) {
        if (!this.isRunning) {
            Logger.error("Tried getting world object on voxy instance but its not running");
            return null;
        }
        var world = this.getNullable(identifier);
        if (world != null) {
            world.markActive();
            if (incrementRef) world.acquireRef();
            return world;
        }
        long stamp = this.activeWorldLock.writeLock();

        if (!this.isRunning) {
            Logger.error("Tried getting world object on voxy instance but its not running");
            this.activeWorldLock.unlockWrite(stamp);
            return null;
        }

        world = this.activeWorlds.get(identifier);
        if (world == null) {
            //Create world here
            world = this.createWorld(identifier);
        }
        world.markActive();

        if (incrementRef) world.acquireRef();

        this.activeWorldLock.unlockWrite(stamp);
        identifier.cachedEngineObject = new WeakReference<>(world);
        return world;
    }


    protected abstract SectionStorage createStorage(WorldIdentifier identifier);

    private WorldEngine createWorld(WorldIdentifier identifier) {
        if (!this.isRunning) {
            throw new IllegalStateException("Cannot create world while not running");
        }
        if (this.activeWorlds.containsKey(identifier)) {
            throw new IllegalStateException("Existing world with identifier");
        }
        Logger.info("Creating new world engine: " + identifier.getLongHash() + "@" + System.identityHashCode(this));
        var world = new WorldEngine(this.createStorage(identifier), this);
        world.setSaveCallback(this.savingService::enqueueSave);
        this.activeWorlds.put(identifier, world);
        return world;
    }

    public void cleanIdle() {
        List<WorldIdentifier> idleWorlds = null;
        {
            long stamp = this.activeWorldLock.readLock();
            for (var pair : this.activeWorlds.entrySet()) {
                if (pair.getValue().isWorldIdle()) {
                    if (idleWorlds == null) idleWorlds = new ArrayList<>();
                    idleWorlds.add(pair.getKey());
                }
            }
            this.activeWorldLock.unlockRead(stamp);
        }

        if (idleWorlds != null) {
            //Shutdown and clear all idle worlds
            long stamp = this.activeWorldLock.writeLock();
            for (var id : idleWorlds) {
                var world = this.activeWorlds.remove(id);
                if (world == null) continue;//Race condition between unlock read and acquire write
                if (!world.isWorldIdle()) {this.activeWorlds.put(id, world); continue;}//No longer idle
                Logger.info("Shutting down idle world: " + id.getLongHash());
                //If is here close and free the world
                world.free();
            }
            this.activeWorldLock.unlockWrite(stamp);
        }
    }

    public void addDebug(List<String> debug) {
        debug.add("MemoryBuffer, Count/Size (mb): " + MemoryBuffer.getCount() + "/" + (MemoryBuffer.getTotalSize()/1_000_000));
        //TODO: fixme, doing this.activeWorlds.values() is not thread safe
        debug.add("I/S/AWSC: " + this.ingestService.getTaskCount() + "/" + this.savingService.getTaskCount() + "/[" + this.activeWorlds.values().stream().map(a->""+a.getActiveSectionCount()).collect(Collectors.joining(", ")) + "]");//Active world section count
    }


    /**
     * How long a shutdown's caller may wait in all, in milliseconds; negative waits for as long as it
     * takes, as Voxy always has (the default, so GL's behaviour is unchanged: the cleanup runs inline).
     * The native path bounds it (R29-WORLD-SHUTDOWN-BOUND, R29-PRELAUNCH-SHUTDOWN, R30 prelaunch
     * review): the complete cleanup — the cleaner, idle worlds, import cancellation, the ingest and
     * saving services, each world's wait and free (storage flush and close), the thread pool — runs as
     * <b>one retained {@link ShutdownOperation}</b> on its own thread, to completion, holding everything
     * it cleans up; the caller only waits on its completion handle until the deadline (or an interrupt),
     * then counts the operation as outstanding and returns. Nothing is ever freed on the caller after
     * that, nothing is freed under a running reader, and the operation publishes its completion or
     * failure exactly once; the harness report and gate see what is still outstanding, what finished
     * late and what failed. A bounded return is a refusal with accounting, not a completed cleanup.
     */
    private static volatile long shutdownDeadlineMillis = -1;
    private static volatile int worldsLeftUnfreed, saveEntriesUnflushed, poolThreadsLeft;
    private static volatile int shutdownCompletedLate, shutdownFailed, shutdownStageFailures;
    private static volatile boolean shutdownInterrupted;
    private static final List<String> SHUTDOWN_INCOMPLETE = java.util.Collections.synchronizedList(new ArrayList<>());
    private static final java.util.Set<ShutdownOperation> OUTSTANDING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void boundShutdown(long millis) { shutdownDeadlineMillis = millis; }

    /** Worlds a shutdown left unfreed (freeing them threw). */
    public static int worldsLeftUnfreed() { return worldsLeftUnfreed; }
    /** Section saves a shutdown left unflushed (an interrupted inline shutdown stopped waiting). */
    public static int saveEntriesUnflushed() { return saveEntriesUnflushed; }
    /** Service worker threads still alive when an inline shutdown stopped waiting (their semaphore is leaked). */
    public static int poolThreadsLeft() { return poolThreadsLeft; }
    /** Whether a shutdown's caller was interrupted (it stopped waiting; the flag was restored). */
    public static boolean shutdownInterrupted() { return shutdownInterrupted; }
    /** The stages at which shutdown callers stopped waiting, in order (empty: every caller saw completion). */
    public static List<String> shutdownIncomplete() { return List.copyOf(SHUTDOWN_INCOMPLETE); }
    /** Retained shutdown operations still running (their callers returned at the deadline or an interrupt). */
    public static int shutdownOutstanding() { return OUTSTANDING.size(); }
    /** Shutdown operations that completed after their caller had stopped waiting. */
    public static int shutdownCompletedLate() { return shutdownCompletedLate; }
    /** Shutdown operations that ended in an exception (logged; whatever they had not freed stays as it is). */
    public static int shutdownFailed() { return shutdownFailed; }
    /** Cleanup stages that threw inside a shutdown (logged, the remaining stages ran). */
    public static int shutdownStageFailures() { return shutdownStageFailures; }

    /**
     * Wait up to {@code millis} for every outstanding shutdown operation to complete (tests and the
     * harness reap them so). @return whether none is outstanding afterwards
     */
    public static boolean awaitOutstandingShutdowns(long millis) throws InterruptedException {
        long deadline = Deadline.in(millis);
        for (var op : OUTSTANDING.toArray(new ShutdownOperation[0])) {
            if (!op.done.await(Math.max(1, Deadline.remainingMillis(deadline)), java.util.concurrent.TimeUnit.MILLISECONDS)) return false;
        }
        return OUTSTANDING.isEmpty();
    }

    /**
     * Wait until {@code used} turns false, or until {@code deadlineMillis} has passed (negative: no
     * deadline). @return whether it was released
     */
    static boolean awaitWorldRelease(BooleanSupplier used, long deadlineMillis) {
        return awaitWorldReleaseUntil(used, Deadline.in(deadlineMillis)) == RELEASED;
    }

    static final int RELEASED = 1, TIMED_OUT = 0, INTERRUPTED = -1;

    /** Wait until {@code used} turns false or the deadline ({@link Deadline}); an interrupt stops the wait and keeps its flag. */
    static int awaitWorldReleaseUntil(BooleanSupplier used, long deadlineNanos) {
        while (used.getAsBoolean()) {
            if (Thread.currentThread().isInterrupted()) return INTERRUPTED;
            if (Deadline.passed(deadlineNanos)) return TIMED_OUT;
            if (!Deadline.sleep(10, deadlineNanos)) {
                if (Thread.currentThread().isInterrupted()) return INTERRUPTED;
                if (Deadline.passed(deadlineNanos)) return TIMED_OUT;
            }
        }
        return RELEASED;
    }

    /** The cleanup's progress: the stage it is in (read by a caller that stopped waiting). */
    private static class Progress {
        volatile String stage = "start";
    }

    /**
     * The complete cleanup as one retained operation: it owns the instance (and so its services,
     * worlds and storages) until it has run to its end, publishes its outcome exactly once, and is
     * listed in {@link #OUTSTANDING} from start to end. States: RUNNING, then COMPLETED (the caller saw
     * it end) or ABANDONED (the caller stopped waiting first; the end is then counted as late).
     */
    private final class ShutdownOperation extends Progress implements Runnable {
        static final int RUNNING = 0, COMPLETED = 1, ABANDONED = 2;
        final java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger(RUNNING);
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final Thread thread = new Thread(this, "Voxy instance shutdown");

        @Override public void run() {
            Throwable failure = null;
            try {
                VoxyInstance.this.runShutdown(this);
            } catch (Throwable t) {
                failure = t;
                Logger.error("Instance shutdown operation failed at stage " + this.stage + "; what it had not freed stays as it is", t);
            } finally {
                boolean onTime = this.state.compareAndSet(RUNNING, COMPLETED);
                if (failure != null) shutdownFailed++;
                if (!onTime) {
                    shutdownCompletedLate++;
                    Logger.error("Instance shutdown completed after its caller stopped waiting (last stage " + this.stage + ")");
                }
                OUTSTANDING.remove(this);
                this.done.countDown();
            }
        }
    }

    public void shutdown() {
        long millis = shutdownDeadlineMillis;
        if (millis < 0) {
            // the default: inline, waiting as long as it takes (GL, unchanged)
            this.runShutdown(new Progress());
            return;
        }
        Logger.info("Shutting down voxy instance (caller bound: " + millis + " ms)");
        this.isRunning = false;
        long deadline = Deadline.in(millis);
        var op = new ShutdownOperation();
        OUTSTANDING.add(op);
        op.thread.start();
        boolean interrupted = Thread.interrupted();
        if (!interrupted) {
            try {
                op.done.await(Math.max(1, Deadline.remainingMillis(deadline)), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (op.state.compareAndSet(ShutdownOperation.RUNNING, ShutdownOperation.ABANDONED)) {
            // still running: it keeps everything it owns and finishes on its own; counted until then
            String stage = op.stage;
            SHUTDOWN_INCOMPLETE.add(stage);
            Logger.error("Instance shutdown still running at stage " + stage + " when its caller stopped waiting ("
                + (interrupted ? "interrupted" : millis + " ms") + "); it continues in the background, outstanding");
        }
        if (interrupted) {
            shutdownInterrupted = true;
            Logger.error("Instance shutdown's caller was interrupted; it stopped waiting");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The complete cleanup, in Voxy's order, waiting as long as each step takes. Inline (the default)
     * an interrupt makes the remaining waits stop and what still runs is counted, as before; on the
     * retained operation's thread nothing interrupts it.
     */
    private void runShutdown(Progress progress) {
        Logger.info("Shutting down voxy instance");
        long deadline = Deadline.NONE;
        boolean interrupted = Thread.interrupted();
        if (interrupted) deadline = System.nanoTime();
        var incomplete = new ArrayList<String>();
        this.isRunning = false;
        progress.stage = "world-cleaner";
        try {
            if (deadline == Deadline.NONE) this.worldCleaner.join();
            else {
                long left = Deadline.remainingMillis(deadline);
                if (left > 0) this.worldCleaner.join(left);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (this.worldCleaner.isAlive()) incomplete.add("world-cleaner");
        if (Thread.interrupted()) { interrupted = true; deadline = System.nanoTime(); }
        progress.stage = "clean-idle";
        try {this.cleanIdle();} catch (Exception e) {shutdownStageFailures++; Logger.error(e);}

        progress.stage = "cancel-imports";
        if (!this.activeWorlds.isEmpty()) {
            long stamp = this.activeWorldLock.readLock();
            try {
                for (var world : this.activeWorlds.values()) {
                    try {this.importManager.cancelImport(world);} catch (Exception e) {shutdownStageFailures++; Logger.error(e);}
                }
            } finally {
                this.activeWorldLock.unlockRead(stamp);
            }
        }

        progress.stage = "ingest";
        try {
            if (!this.ingestService.shutdownUntil(deadline)) incomplete.add("ingest");
        } catch (Exception e) {shutdownStageFailures++; Logger.error(e);}
        if (Thread.interrupted()) { interrupted = true; deadline = System.nanoTime(); }
        progress.stage = "saving";
        try {
            int unflushed = this.savingService.shutdownUntil(deadline);
            if (unflushed != 0) { incomplete.add("saving"); saveEntriesUnflushed += unflushed; }
        } catch (Exception e) {shutdownStageFailures++; Logger.error(e);}
        if (Thread.interrupted()) { interrupted = true; deadline = System.nanoTime(); }

        progress.stage = "worlds";
        long stamp = this.activeWorldLock.writeLock();
        try {
            if (!this.activeWorlds.isEmpty()) {
                boolean printedNotice = false;
                for (var world : new ArrayList<>(this.activeWorlds.values())) {
                    if (world.isWorldUsed()) {
                        if (!printedNotice) {
                            printedNotice = true;
                            Logger.error("Not all worlds shutdown, force closing worlds");
                        }
                        //Dont lock in the loopy thing, this should basicly never happen if it does something horrific happened
                        this.activeWorldLock.unlockWrite(stamp);
                        progress.stage = "world-wait";
                        int released = awaitWorldReleaseUntil(world::isWorldUsed, deadline);
                        stamp = this.activeWorldLock.writeLock();
                        if (released == INTERRUPTED) { interrupted = true; deadline = System.nanoTime(); Thread.interrupted(); }
                        if (released != RELEASED) {
                            // still read by someone: freeing it would free it under its reader; leave it
                            worldsLeftUnfreed++;
                            incomplete.add("world");
                            Logger.error("A world was still in use when the interrupted shutdown stopped waiting; left unfreed (not flushed or closed)");
                            continue;
                        }
                    }
                    //Free the world (storage flush and close)
                    progress.stage = "world-free";
                    try {
                        world.free();
                    } catch (Exception e) {
                        worldsLeftUnfreed++;
                        shutdownStageFailures++;
                        incomplete.add("world");
                        Logger.error("Freeing a world at shutdown failed; left as it is", e);
                    }
                }
                this.activeWorlds.clear();
            }

            progress.stage = "thread-pool";
            try {
                int alive = this.threadPool.shutdownUntil(deadline);
                if (alive != 0) { incomplete.add("thread-pool"); poolThreadsLeft += alive; }
            } catch (Exception e) {shutdownStageFailures++; Logger.error(e);}
        } finally {
            this.activeWorldLock.unlockWrite(stamp);
        }
        if (Thread.interrupted()) interrupted = true;

        if (!incomplete.isEmpty()) {
            SHUTDOWN_INCOMPLETE.addAll(incomplete);
            Logger.error("Instance shutdown stopped waiting on: " + incomplete);
        }
        if (interrupted) {
            shutdownInterrupted = true;
            Logger.error("Instance shutdown was interrupted; it finished without waiting");
            Thread.currentThread().interrupt();
        }
        progress.stage = "done";
        Logger.info("Instance shutdown");
    }

    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return true;
    }

    public boolean isRunning() {
        return this.isRunning;
    }
}