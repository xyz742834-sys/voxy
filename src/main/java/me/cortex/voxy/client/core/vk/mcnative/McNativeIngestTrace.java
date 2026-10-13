package me.cortex.voxy.client.core.vk.mcnative;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Harness-only trace of the native path's visibility-change ingest (round-28 Codex review of
 * record, R28-NATIVE-UPDATE-OBSERVATION): how many times Sodium's invisible/visible transition of a
 * chunk section was ingested into Voxy's world on the native path. Lets the harness attribute a
 * native mesh change to that route. Inert (records nothing) outside the native harness.
 */
public final class McNativeIngestTrace {
    private static final boolean ON = Boolean.getBoolean("voxy.harness.native");
    private static final ConcurrentHashMap<Long, AtomicInteger> VISIBILITY = new ConcurrentHashMap<>();

    private McNativeIngestTrace() {}

    private static long key(int sx, int sy, int sz) {
        return ((long) sx & 0x3FFFFF) << 42 | ((long) sy & 0xFFFFF) << 22 | ((long) sz & 0x3FFFFF);
    }

    /** A visibility-change ingest of chunk section (sx, sy, sz) reached Voxy's world. */
    public static void visibilityIngested(int sx, int sy, int sz) {
        if (!ON) return;
        VISIBILITY.computeIfAbsent(key(sx, sy, sz), k -> new AtomicInteger()).incrementAndGet();
    }

    /** How many visibility-change ingests of chunk section (sx, sy, sz) were recorded. */
    public static int visibilityIngests(int sx, int sy, int sz) {
        var n = VISIBILITY.get(key(sx, sy, sz));
        return n == null ? 0 : n.get();
    }
}
