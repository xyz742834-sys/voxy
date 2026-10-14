package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.common.world.WorldUpdater;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Harness-only causal trace of one native update (round-28 Codex review of record,
 * R28-NATIVE-UPDATE-OBSERVATION; R29-UPDATE-CAUSALITY; R29-PRELAUNCH-CAUSALITY and -REMOVAL).
 * Inert outside the native harness.
 *
 * <p>The trace observes one level-0 world section (the leaf holding the probe block). Its spine is
 * the leaf's <b>content generation</b>: a counter that only {@link WorldUpdater#insertUpdate} advances,
 * at the moment it has actually changed the leaf's level-0 content (R29-PRELAUNCH-CAUSALITY: an ingest
 * that was merely queued, or that re-wrote identical content, advances nothing). Everything else is
 * dated on one global sequence ({@link #now}) and tagged with the identity of the world engine it
 * concerned:
 * <ol>
 *   <li>{@link #ingestQueued}: a chunk section was accepted into an engine's ingest queue
 *       (Sodium's visible-to-invisible transition on the native path; {@code MixinRenderSectionManager}).
 *       Queueing, not completion.</li>
 *   <li>{@link #leafUpdated}: the real updater finished the leaf's level-0 content — whether it
 *       changed, the generation it left, and how many non-air blocks the leaf has now (zero: the leaf
 *       emptied, and the scene may remove it instead of re-meshing it).</li>
 *   <li>{@link #dirtyConsumed}: the live scene consumed a dirty update for the leaf (a mesh in flight
 *       became stale).</li>
 *   <li>{@link #meshStarted} / {@link #meshed}: a mesher began reading the leaf — the generation it saw
 *       is captured <em>before</em> the read, so a mesh is never credited with content newer than what it
 *       could have read — and the scene then accepted that mesh. The mesh's generation must reach the
 *       update's for the mesh to derive from it.</li>
 *   <li>{@link #unwatched}: the scene stopped watching the leaf (its geometry was removed): the other
 *       truthful terminal state of an emptied leaf.</li>
 * </ol>
 * The gate (scripts/verify.py native_update_problem) also needs the content Voxy held before the
 * action, read back from the world ({@code McNativeHierarchicalLoad.heldBlockAt}): a removal of a
 * block Voxy never held is correctly a no-op, and no observation of it would mean anything.
 */
public final class McNativeIngestTrace {
    private static volatile boolean on = Boolean.getBoolean("voxy.harness.native");
    private static final AtomicLong SEQ = new AtomicLong();

    /** One event: its sequence number and the identity of the engine it concerned. */
    public record Event(long seq, int engine) {}
    /** The updater's outcome for the observed leaf's level-0 content. */
    public record Update(long seq, int engine, boolean changed, long generation, int nonEmptyBlocks) {}
    /** An accepted mesh of the observed leaf and the content generation its mesher read. */
    public record Mesh(long seq, int engine, long generation) {}

    private static final ConcurrentHashMap<Long, Event> INGESTED = new ConcurrentHashMap<>();
    /** Generation captured when a mesher began reading the observed leaf, by position. */
    private static final ConcurrentHashMap<Long, Long> READING = new ConcurrentHashMap<>();
    private static volatile long observedLeaf = Long.MIN_VALUE;
    private static volatile long generation;
    private static volatile Update lastUpdate, lastChange;
    private static volatile Event leafDirty, leafUnwatched;
    private static volatile Mesh leafMeshed;

    static {
        // the updater reports its level-0 outcomes to the trace only while the harness observes
        WorldUpdater.setObserver((into, leaf, changed) -> leafUpdated(leaf.key, into, changed, leaf.getNonEmptyBlockCount()));
    }

    private McNativeIngestTrace() {}

    /** Tests only: the harness flag is read once at class load. */
    static void setEnabled(boolean enabled) { on = enabled; }

    static long sectionKey(int sx, int sy, int sz) {
        return ((long) sx & 0x3FFFFF) << 42 | ((long) sy & 0xFFFFF) << 22 | ((long) sz & 0x3FFFFF);
    }

    private static int identity(Object engine) { return System.identityHashCode(engine); }

    /** A point on the sequence (the harness's before/after marks). */
    public static long now() { return SEQ.incrementAndGet(); }

    /** Chunk section (sx, sy, sz) was accepted into {@code engine}'s ingest queue (not yet applied). */
    public static void ingestQueued(int sx, int sy, int sz, Object engine) {
        if (!on) return;
        INGESTED.put(sectionKey(sx, sy, sz), new Event(SEQ.incrementAndGet(), identity(engine)));
    }

    /** The last ingest queued for chunk section (sx, sy, sz), or null. */
    public static Event lastIngest(int sx, int sy, int sz) { return INGESTED.get(sectionKey(sx, sy, sz)); }

    /**
     * Observe one level-0 world section id from now on: its events are forgotten, its content
     * generation is not (the content is what it is, however often it is watched).
     */
    public static void observeLeaf(long worldSectionId) {
        if (worldSectionId != observedLeaf) generation = 0;
        lastUpdate = null;
        lastChange = null;
        leafDirty = null;
        leafMeshed = null;
        leafUnwatched = null;
        READING.clear();
        observedLeaf = worldSectionId;
    }

    /** The observed leaf's content generation so far (0 until the updater first changed it). */
    public static long generation() { return generation; }

    /** The updater finished {@code pos}'s level-0 content (the updater's thread, before its dirty mark). */
    static void leafUpdated(long pos, Object engine, boolean changed, int nonEmptyBlocks) {
        if (!on || pos != observedLeaf) return;
        if (changed) generation++;
        var update = new Update(SEQ.incrementAndGet(), identity(engine), changed, generation, nonEmptyBlocks);
        lastUpdate = update;
        if (changed) lastChange = update;
    }

    /** The live scene consumed a dirty update for {@code pos} (render thread). */
    public static void dirtyConsumed(long pos, Object engine) {
        if (on && pos == observedLeaf) leafDirty = new Event(SEQ.incrementAndGet(), identity(engine));
    }

    /** A mesher is about to read {@code pos}'s content (worker or render thread; before the read). */
    public static void meshStarted(long pos) {
        if (on && pos == observedLeaf) READING.put(pos, generation);
    }

    /** The live scene accepted a mesh of {@code pos} (render thread). */
    public static void meshed(long pos, Object engine) {
        if (!on || pos != observedLeaf) return;
        Long read = READING.remove(pos);
        // a mesh whose start was not seen cannot be credited with any content: generation -1
        leafMeshed = new Mesh(SEQ.incrementAndGet(), identity(engine), read == null ? -1 : read);
    }

    /** The live scene stopped watching {@code pos} for meshes (its geometry is gone; render thread). */
    public static void unwatched(long pos, Object engine) {
        if (on && pos == observedLeaf) leafUnwatched = new Event(SEQ.incrementAndGet(), identity(engine));
    }

    /** The updater's last outcome for the leaf since {@link #observeLeaf}, changed or not. */
    public static Update lastUpdate() { return lastUpdate; }
    /** The updater's last outcome that changed the leaf's content, since {@link #observeLeaf}. */
    public static Update lastChange() { return lastChange; }
    public static Event leafDirty() { return leafDirty; }
    public static Mesh leafMeshed() { return leafMeshed; }
    public static Event leafUnwatched() { return leafUnwatched; }
}
