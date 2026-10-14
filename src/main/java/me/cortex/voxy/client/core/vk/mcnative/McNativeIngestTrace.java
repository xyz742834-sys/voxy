package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

/**
 * Harness-only causal trace of one native update (round-28 Codex review of record,
 * R28-NATIVE-UPDATE-OBSERVATION; R29-UPDATE-CAUSALITY; R29-PRELAUNCH-CAUSALITY and -REMOVAL; R30
 * prelaunch review of 5bdc067e: the unrelated-same-leaf race). Inert outside the native harness: the
 * updater's observer is installed only while the trace is enabled, so production runs with none.
 *
 * <p>The trace holds one {@link Observation}: the level-0 world section (the leaf) that holds the
 * probe block, the probe's chunk section and index, and the identity of the world engine the scene
 * reads. Every event records the observation's <b>epoch</b>, so a reset, a scene rebuild or a later
 * start cannot lend its identity to an earlier one. Two things make an accepted mesh derive from a
 * specific update rather than merely follow it in time:
 * <ol>
 *   <li><b>One synchronization boundary.</b> The leaf's content in one engine has exactly one
 *       {@link Domain}: a {@link StampedLock} and the content version, kept per (engine, leaf) for the
 *       engine's lifetime and <em>shared by every observation of that leaf</em> — replacing the
 *       observation (a new epoch) never replaces the lock, so a writer captured by an earlier observation
 *       still serializes with the current readers (R31 prelaunch review of dd230016: the reset lock
 *       split). The real
 *       {@link WorldUpdater#insertUpdate} holds its write lock from before the leaf's level-0 content
 *       write until after the publication of that write's outcome ({@link Update}: the leaf's content
 *       <em>version</em> it left, whether it changed, which chunk section it wrote and what it wrote
 *       at the probe). Every reader of the leaf's content that the gate relies on — the mesher
 *       ({@link #beginMesh} … {@link #endMesh}, held through the mesh factory's read), the held-content
 *       read-back ({@link #readHold}) and the removed-geometry terminal ({@link #unwatched}) — holds
 *       the read lock while it reads and records the version it read under. So a read at version
 *       {@code v} contains every write published at or before {@code v}, and a paused, unpublished
 *       write is unobservable.</li>
 *   <li><b>Identity on the result.</b> {@link #beginMesh} returns an immutable {@link MeshRead} that
 *       travels with the mesher's own result to the scene's acceptance ({@link #meshed}); no map keyed
 *       by position, so nothing can be overwritten, reset or supplied by another start.</li>
 * </ol>
 * The gate (scripts/verify.py native_update_problem) requires: one epoch per stage; the update event
 * to be the <em>target chunk section's</em> own ({@link Update#sx} …), changed, with the content it
 * wrote at the probe; the accepted mesh's version at or past that update's and its probe content equal
 * to what the update wrote (or, the leaf emptied, its geometry removed at a version at or past the
 * update's with no non-air block left); and Voxy's held content read back before and after.
 */
public final class McNativeIngestTrace {
    private static volatile boolean on = Boolean.getBoolean("voxy.harness.native");
    private static final AtomicLong SEQ = new AtomicLong(), EPOCHS = new AtomicLong();

    /** One event: its sequence number, the observation epoch it belongs to and the engine it concerned. */
    public record Event(long seq, long epoch, int engine) {}
    /**
     * The updater's outcome for one chunk section of the observed leaf, published inside the write
     * boundary: the leaf's content version it left, whether it changed anything, the leaf's non-air
     * block count now, the chunk section it wrote, and (for the target chunk section) the block it
     * wrote at the probe.
     */
    public record Update(long seq, long epoch, int engine, long version, boolean changed, int nonEmptyBlocks,
                         int sx, int sy, int sz, String wrote) {}
    /** An accepted mesh of the observed leaf: the version its mesher read under and the probe content it read. */
    public record Mesh(long seq, long epoch, int engine, long version, String probeHeld) {}
    /** The scene stopped watching the leaf's geometry: the version then and the leaf's non-air count (-1: not obtainable). */
    public record Unwatch(long seq, long epoch, int engine, long version, int nonEmptyBlocks) {}

    /**
     * The content synchronization domain of one leaf in one engine: its lock and its content version.
     * Exactly one per (engine, leaf) while the engine is alive, shared by every observation of the leaf
     * and never replaced by a reset: evidence identity (the observation's epoch) and physical content
     * synchronization are separate things.
     */
    private static final class Domain {
        final StampedLock lock = new StampedLock();
        /** The leaf's content version: advanced inside the write boundary, by changed writes only. */
        volatile long version;
    }

    /** Domains by engine (identity, weakly held: an engine's domains live as long as it does) and leaf. */
    private static final java.util.Map<Object, ConcurrentHashMap<Long, Domain>> DOMAINS =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static Domain domain(Object engine, long leaf) {
        if (engine == null) return new Domain();   // no engine: nothing can ever match this observation
        return DOMAINS.computeIfAbsent(engine, e -> new ConcurrentHashMap<>()).computeIfAbsent(leaf, l -> new Domain());
    }

    /** A read lock held on the current observation's leaf; released once by {@link #release}. */
    public static class ReadHold {
        private final Domain domain;
        private long stamp;
        ReadHold(Domain domain, long stamp) { this.domain = domain; this.stamp = stamp; }
        public final void release() {
            long s = this.stamp;
            if (s != 0) { this.stamp = 0; this.domain.lock.unlockRead(s); }
        }
    }

    /**
     * A mesher's read of the observed leaf, begun under the read lock: immutable identity that travels
     * on the mesher's result to the scene's acceptance.
     */
    public static final class MeshRead extends ReadHold {
        public final long epoch;
        public final int engine;
        public final long version;
        public final String probeHeld;
        MeshRead(Observation obs, long stamp, int engine, long version, String probeHeld) {
            super(obs.domain, stamp);
            this.epoch = obs.epoch;
            this.engine = engine;
            this.version = version;
            this.probeHeld = probeHeld;
        }
    }

    private static final class Observation {
        final long epoch = EPOCHS.incrementAndGet();
        final long leaf;
        final int engine;
        final int sx, sy, sz;          // the target chunk section
        final int probeIndex;          // the probe's index in the leaf's data
        final int probeInSource;       // the probe's index in a level-0 VoxelizedSection of the target chunk section
        /** The leaf's one content domain in this engine (shared across observations; never replaced). */
        final Domain domain;
        volatile Update lastUpdate, lastChange;
        volatile Event leafDirty;
        volatile Mesh leafMeshed;
        volatile Unwatch leafUnwatched;
        final AtomicInteger otherSectionUpdates = new AtomicInteger();
        final ConcurrentHashMap<Long, Event> ingested = new ConcurrentHashMap<>();

        Observation(long leaf, int px, int py, int pz, int engine, Domain domain) {
            this.leaf = leaf;
            this.engine = engine;
            this.sx = px >> 4; this.sy = py >> 4; this.sz = pz >> 4;
            this.probeIndex = WorldSection.getIndex(px & 31, py & 31, pz & 31);
            this.probeInSource = ((py & 15) << 8) | ((pz & 15) << 4) | (px & 15);
            this.domain = domain;
        }
    }

    private static volatile Observation current;

    /** The updater's write boundary for the observed leaf (installed only while enabled). */
    private static final WorldUpdater.Observer OBSERVER = (into, leaf, source) -> {
        var obs = current;
        if (!on || obs == null || leaf.key != obs.leaf) return null;
        int engine = identity(into);
        if (engine != obs.engine) return null;   // another engine's section of the same id: not observed
        var domain = obs.domain;   // the leaf's one lock, whatever observation is current when this ends
        long stamp = domain.lock.writeLock();
        return changed -> {
            try {
                if (changed) domain.version++;
                boolean target = source.x == obs.sx && source.y == obs.sy && source.z == obs.sz;
                var update = new Update(SEQ.incrementAndGet(), obs.epoch, engine, domain.version, changed,
                    leaf.getNonEmptyBlockCount(), source.x, source.y, source.z,
                    target ? blockName(into, source.section[obs.probeInSource]) : null);
                if (target) {
                    obs.lastUpdate = update;
                    if (changed) obs.lastChange = update;
                } else {
                    obs.otherSectionUpdates.incrementAndGet();
                }
            } finally {
                domain.lock.unlockWrite(stamp);
            }
        };
    };

    static {
        if (on) WorldUpdater.setObserver(OBSERVER);
    }

    private McNativeIngestTrace() {}

    /** Tests only: the harness flag is read once at class load. Installs or removes the updater's observer. */
    static void setEnabled(boolean enabled) {
        on = enabled;
        WorldUpdater.setObserver(enabled ? OBSERVER : null);
    }

    /** Tests only: the observer the trace installs, so a test can wrap it (pause inside the boundary). */
    static WorldUpdater.Observer observer() { return OBSERVER; }

    static long sectionKey(int sx, int sy, int sz) {
        return ((long) sx & 0x3FFFFF) << 42 | ((long) sy & 0xFFFFF) << 22 | ((long) sz & 0x3FFFFF);
    }

    private static int identity(Object engine) { return System.identityHashCode(engine); }

    /** A point on the sequence (the harness's before/after marks). */
    public static long now() { return SEQ.incrementAndGet(); }

    /**
     * The block a packed mapping id denotes in {@code world}: {@code minecraft:air}, the block's
     * registry key, or {@code unknown:<id>} for an id the mapper has no state for.
     */
    public static String blockName(WorldEngine world, long id) {
        if (Mapper.isAir(id)) return "minecraft:air";
        int blockId = Mapper.getBlockId(id);
        try {
            var state = world.getMapper().getBlockStateFromBlockId(blockId);
            return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        } catch (RuntimeException e) {
            return "unknown:" + blockId;
        }
    }

    /**
     * Observe, from now on, the level-0 section {@code leaf} holding the probe block at (px, py, pz) as
     * read by {@code engine}. Events of earlier observations are forgotten (their epoch stays theirs);
     * the leaf's content domain — its lock and version — is the engine's own for that leaf and is
     * shared, not replaced: a writer or reader captured before this call still holds or waits for the
     * same lock the new observation's readers use.
     */
    public static void observe(long leaf, int px, int py, int pz, Object engine) {
        current = new Observation(leaf, px, py, pz, identity(engine), domain(engine, leaf));
    }

    /** The current observation's epoch (0: none). */
    public static long epoch() { var o = current; return o == null ? 0 : o.epoch; }

    /** The observed leaf's content version so far (0 until the updater first changed it). */
    public static long generation() { var o = current; return o == null ? 0 : o.domain.version; }

    /** Updates of the observed leaf by chunk sections other than the target's, since the observation began. */
    public static int otherSectionUpdates() { var o = current; return o == null ? 0 : o.otherSectionUpdates.get(); }

    /** Chunk section (sx, sy, sz) was accepted into {@code engine}'s ingest queue (not yet applied). */
    public static void ingestQueued(int sx, int sy, int sz, Object engine) {
        var obs = current;
        if (!on || obs == null) return;
        obs.ingested.put(sectionKey(sx, sy, sz), new Event(SEQ.incrementAndGet(), obs.epoch, identity(engine)));
    }

    /** The last ingest queued for chunk section (sx, sy, sz) in the current observation, or null. */
    public static Event lastIngest(int sx, int sy, int sz) {
        var obs = current;
        return obs == null ? null : obs.ingested.get(sectionKey(sx, sy, sz));
    }

    /**
     * Hold the read lock of the observed leaf while reading its content as {@code engine} holds it
     * (the harness's held-content read-back): null when {@code pos} is not observed, or off.
     */
    public static ReadHold readHold(long pos, Object engine) {
        var obs = current;
        if (!on || obs == null || pos != obs.leaf || identity(engine) != obs.engine) return null;
        return new ReadHold(obs.domain, obs.domain.lock.readLock());
    }

    /** The live scene consumed a dirty update for {@code pos} (render thread). */
    public static void dirtyConsumed(long pos, Object engine) {
        var obs = current;
        if (on && obs != null && pos == obs.leaf) obs.leafDirty = new Event(SEQ.incrementAndGet(), obs.epoch, identity(engine));
    }

    /**
     * A mesher is about to read {@code section} (the leaf's content, as {@code engine} holds it): take
     * the read lock and capture the version and the probe content under it. Null when the position is
     * not the observed leaf, when the engine is not the observed one, or when off — no lock is taken.
     * The caller must {@link #endMesh} it on the same code path once the mesh factory has read.
     */
    public static MeshRead beginMesh(long pos, WorldEngine engine, WorldSection section) {
        var obs = current;
        if (!on || obs == null || pos != obs.leaf) return null;
        int id = identity(engine);
        if (id != obs.engine) return null;
        long stamp = obs.domain.lock.readLock();
        long content = section._unsafeGetRawDataArray()[obs.probeIndex];
        return new MeshRead(obs, stamp, id, obs.domain.version, blockName(engine, content));
    }

    /** The mesher finished reading: release the read lock (idempotent; null is nothing). */
    public static void endMesh(MeshRead read) { if (read != null) read.release(); }

    /**
     * The live scene accepted a mesh of {@code pos} (render thread), the one {@code read} was captured
     * for. A mesh of the observed leaf whose read was not captured is recorded with version -1 and no
     * content: it derives from nothing the gate can credit.
     */
    public static void meshed(MeshRead read, long pos, Object engine) {
        var obs = current;
        if (!on || obs == null || pos != obs.leaf) return;
        if (read == null) obs.leafMeshed = new Mesh(SEQ.incrementAndGet(), obs.epoch, identity(engine), -1, null);
        else obs.leafMeshed = new Mesh(SEQ.incrementAndGet(), read.epoch, read.engine, read.version, read.probeHeld);
    }

    /**
     * The live scene stopped watching {@code pos} for meshes (its geometry is gone; render thread): the
     * other truthful terminal state of an emptied leaf, recorded under the read lock with the leaf's
     * version and its non-air block count read back from {@code engine} (-1 when not obtainable).
     */
    public static void unwatched(long pos, WorldEngine engine) {
        var obs = current;
        if (!on || obs == null || pos != obs.leaf) return;
        int id = identity(engine);
        long stamp = obs.domain.lock.readLock();
        try {
            int nonEmpty = -1;
            if (engine.isLive()) {
                var section = engine.acquireIfExists(pos);
                if (section != null) {
                    try { nonEmpty = section.getNonEmptyBlockCount(); } finally { section.release(); }
                }
            }
            obs.leafUnwatched = new Unwatch(SEQ.incrementAndGet(), obs.epoch, id, obs.domain.version, nonEmpty);
        } finally {
            obs.domain.lock.unlockRead(stamp);
        }
    }

    /** The updater's last outcome for the target chunk section since {@link #observe}, changed or not. */
    public static Update lastUpdate() { var o = current; return o == null ? null : o.lastUpdate; }
    /** The updater's last outcome for the target chunk section that changed the leaf, since {@link #observe}. */
    public static Update lastChange() { var o = current; return o == null ? null : o.lastChange; }
    public static Event leafDirty() { var o = current; return o == null ? null : o.leafDirty; }
    public static Mesh leafMeshed() { var o = current; return o == null ? null : o.leafMeshed; }
    public static Unwatch leafUnwatched() { var o = current; return o == null ? null : o.leafUnwatched; }
}
