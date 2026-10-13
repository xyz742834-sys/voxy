package me.cortex.voxy.client.core.vk.mcnative;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Harness-only causal trace of one native update (round-28 Codex review of record,
 * R28-NATIVE-UPDATE-OBSERVATION; R29-UPDATE-CAUSALITY). Inert outside the native harness.
 *
 * <p>Three events on one global sequence ({@link #now}), each with the identity of the world engine
 * it concerned:
 * <ol>
 *   <li>a visibility-change ingest of a chunk section was accepted into an engine (Sodium's
 *       visible-to-invisible transition on the native path; {@code MixinRenderSectionManager});</li>
 *   <li>the live native scene consumed a dirty update for the observed level-0 world section (the
 *       engine's change callback reached the scene; any mesh still in flight became stale);</li>
 *   <li>the scene accepted a mesh of exactly that section — submitted after the dirty update, so
 *       made from the changed world.</li>
 * </ol>
 * The gate requires them in that order, after the harness's action, on the scene's own engine.
 * No ancestor's mesh counts (a coarser section also covers unrelated changes).
 */
public final class McNativeIngestTrace {
    private static final boolean ON = Boolean.getBoolean("voxy.harness.native");
    private static final AtomicLong SEQ = new AtomicLong();

    /** One event: its sequence number and the identity of the engine it concerned. */
    public record Event(long seq, int engine) {}

    private static final ConcurrentHashMap<Long, Event> INGESTED = new ConcurrentHashMap<>();
    private static volatile long observedLeaf = Long.MIN_VALUE;
    private static volatile Event leafDirty, leafMeshed;

    private McNativeIngestTrace() {}

    static long sectionKey(int sx, int sy, int sz) {
        return ((long) sx & 0x3FFFFF) << 42 | ((long) sy & 0xFFFFF) << 22 | ((long) sz & 0x3FFFFF);
    }

    /** A point on the sequence (the harness's before/after marks). */
    public static long now() { return SEQ.incrementAndGet(); }

    /** A visibility-change ingest of chunk section (sx, sy, sz) was accepted into {@code engine}. */
    public static void visibilityIngested(int sx, int sy, int sz, Object engine) {
        if (!ON) return;
        INGESTED.put(sectionKey(sx, sy, sz), new Event(SEQ.incrementAndGet(), System.identityHashCode(engine)));
    }

    /** The last visibility-change ingest of chunk section (sx, sy, sz), or null. */
    public static Event lastIngest(int sx, int sy, int sz) { return INGESTED.get(sectionKey(sx, sy, sz)); }

    /** Observe one level-0 world section id from now on (forgets the previous one's events). */
    public static void observeLeaf(long worldSectionId) {
        leafDirty = null;
        leafMeshed = null;
        observedLeaf = worldSectionId;
    }

    /** The live scene consumed a dirty update for {@code pos} (render thread). */
    public static void dirtyConsumed(long pos, Object engine) {
        if (ON && pos == observedLeaf) leafDirty = new Event(SEQ.incrementAndGet(), System.identityHashCode(engine));
    }

    /** The live scene accepted a mesh of {@code pos} (render thread). */
    public static void meshed(long pos, Object engine) {
        if (ON && pos == observedLeaf) leafMeshed = new Event(SEQ.incrementAndGet(), System.identityHashCode(engine));
    }

    public static Event leafDirty() { return leafDirty; }
    public static Event leafMeshed() { return leafMeshed; }
}
