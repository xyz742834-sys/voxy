package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>native instance mode の観測</b>: Minecraft が Vulkan バックエンドで動いているとき、Voxy の
 * インスタンス (ワールドエンジン、ストレージ、取り込み) が GL 描画系<b>なしに</b>立ち上がり、
 * 実セクションを持っているかを記録する。
 *
 * <h2>何のための測定か</h2>
 * 実セクションを Voxy の地形パイプラインで MC の LOAD パスに描く次の実験 (handoff の次の継ぎ目)
 * は、まず<b>実セクションが存在する</b>ことを要る。MC の Vulkan では {@link VoxyClient} が
 * {@code BACKEND = null} としてインスタンス工場を登録しないので、何もしなければワールドエンジンは
 * 無い。フラグ {@code -Dvoxy.native.instance=true} はその工場だけを登録する (描画系は作らない)。
 * この probe は毎フレームの末尾で、工場・インスタンス・エンジン・描画系の有無と、エンジンが
 * 保持するセクション数を記録する。GPU には触らない。
 *
 * <h2>言わないこと</h2>
 * 取り込んだ内容が正しいことも、描けることも言わない。「エンジンがあり、セクションが入った」だけ。
 */
public final class McNativeInstanceProbe {
    public static final String FLAG = VoxyClient.NATIVE_INSTANCE_FLAG;
    /** 記録の間隔 (フレーム)。 */
    static final int SAMPLE_INTERVAL = 60;
    static final int SAMPLE_LIMIT = 256;

    /** 1 回の観測。 */
    public record Sample(long frame, String stage, boolean factorySet, boolean instancePresent,
                         boolean enginePresent, boolean engineLive, int activeSections,
                         boolean rendererCreated, boolean ingestEnabled, long cameraCaptures,
                         int storedNearCamera) {}

    private static final List<String> NOTES = new ArrayList<>();
    private static final List<Sample> SAMPLES = new ArrayList<>();
    private static long frames;
    private static int maxActiveSections, maxStoredNearCamera;

    /** Level-0 sections (32 blocks) around the camera, x/z within one, y from two below to the camera's. */
    static final int STORED_PROBE_RADIUS = 1, STORED_PROBE_BELOW = 2;

    /** How many level-0 sections near the camera the engine can load from storage. */
    private static int storedNear(me.cortex.voxy.common.world.WorldEngine engine, double x, double y,
                                  double z) {
        int cx = (int) Math.floor(x) >> 5, cy = (int) Math.floor(y) >> 5, cz = (int) Math.floor(z) >> 5;
        int count = 0;
        for (int dx = -STORED_PROBE_RADIUS; dx <= STORED_PROBE_RADIUS; dx++) {
            for (int dz = -STORED_PROBE_RADIUS; dz <= STORED_PROBE_RADIUS; dz++) {
                for (int dy = -STORED_PROBE_BELOW; dy <= 0; dy++) {
                    var section = engine.acquireIfExists(0, cx + dx, cy + dy, cz + dz);
                    if (section != null) {
                        count++;
                        section.release();
                    }
                }
            }
        }
        return count;
    }
    private static boolean rendererEverCreated;
    private static boolean engineEverPresent;

    private McNativeInstanceProbe() {}

    public static boolean enabled() { return Boolean.getBoolean(FLAG); }
    public static long frames() { return frames; }

    public static List<Sample> samples() {
        synchronized (NOTES) {
            return List.copyOf(SAMPLES);
        }
    }

    /** レベル描画の末尾で呼ぶ。フラグが無効なら<b>何もしない</b>。 */
    public static void sampleIfEnabled() {
        if (!enabled()) return;
        try {
            sample();
        } catch (Throwable t) {
            note("the native instance probe failed: " + t);
        }
    }

    private static void sample() {
        frames++;
        if (frames != 1 && frames % SAMPLE_INTERVAL != 0) return;
        var mc = Minecraft.getInstance();
        var level = mc == null ? null : mc.level;
        boolean factorySet = VoxyCommon.isAvailable();
        boolean instancePresent = VoxyCommon.getInstance() != null;
        var engine = level == null ? null : WorldIdentifier.ofEngineNullable(level);
        boolean enginePresent = engine != null;
        boolean engineLive = enginePresent && engine.isLive();
        int active = enginePresent ? engine.getActiveSectionCount() : 0;
        boolean rendererCreated = IVoxyRenderSystemHolder.getNullable() != null;
        boolean ingestEnabled = instancePresent
            && VoxyCommon.getInstance().isIngestEnabled(null);
        // Minecraft's own matrices, copied by the Sodium CUTOUT hook in this mode: the count of
        // captures so far says the hook runs on Minecraft's Vulkan backend (the next
        // experiment draws real sections with that matrix).
        long cameraCaptures = McNativeCamera.frame();
        // ⚠ activeSections is cache occupancy: sections are acquired while ingested and released
        // right after, so it can read 0 at every sample although the engine ingested (measured
        // 2026-10-10: 80 samples, all 0). What the engine HOLDS is what it can load: count the
        // level-0 sections around the camera that exist in storage (acquire, then release).
        int stored = engineLive && mc.player != null ? storedNear(engine, mc.player.getX(),
            mc.player.getY(), mc.player.getZ()) : 0;
        var s = new Sample(frames, System.getProperty("voxy.harness.stage", ""), factorySet,
            instancePresent, enginePresent, engineLive, active, rendererCreated, ingestEnabled,
            cameraCaptures, stored);
        synchronized (NOTES) {
            if (SAMPLES.size() < SAMPLE_LIMIT) SAMPLES.add(s);
            maxActiveSections = Math.max(maxActiveSections, active);
            maxStoredNearCamera = Math.max(maxStoredNearCamera, stored);
            rendererEverCreated |= rendererCreated;
            engineEverPresent |= enginePresent;
        }
        if (rendererCreated) {
            // ⚠ 描画系 (GL/interop) が作られていたら、このモードの前提が破れている。
            note("a VoxyRenderSystem exists in native instance mode; the GL render path must"
                + " not be created when Minecraft is on Vulkan");
        }
        Logger.info("[native-vk] native instance at frame " + frames + " stage=" + s.stage()
            + " factory=" + factorySet + " instance=" + instancePresent + " engine=" + enginePresent
            + " live=" + engineLive + " activeSections=" + active + " renderer=" + rendererCreated
            + " ingest=" + ingestEnabled + " cameraCaptures=" + cameraCaptures
            + " storedNearCamera=" + stored);
        writeEvidence();
    }

    static String json() {
        List<Sample> samples = samples();
        List<String> notes;
        int maxActive;
        boolean rendererEver, engineEver;
        synchronized (NOTES) {
            notes = List.copyOf(NOTES);
            maxActive = maxActiveSections;
            rendererEver = rendererEverCreated;
            engineEver = engineEverPresent;
        }
        var sb = new StringBuilder("{\n");
        sb.append("  \"enabled\": ").append(enabled()).append(",\n");
        sb.append("  \"backend\": ").append(McNativeVulkanProbe.quote(
            VoxyClient.backend() == null ? null : VoxyClient.backend().name())).append(",\n");
        sb.append("  \"frames\": ").append(frames).append(",\n");
        sb.append("  \"engineEverPresent\": ").append(engineEver).append(",\n");
        sb.append("  \"rendererEverCreated\": ").append(rendererEver).append(",\n");
        sb.append("  \"maxActiveSections\": ").append(maxActive).append(",\n");
        int maxStored;
        synchronized (NOTES) {
            maxStored = maxStoredNearCamera;
        }
        sb.append("  \"maxStoredNearCamera\": ").append(maxStored).append(",\n");
        sb.append("  \"sampleInterval\": ").append(SAMPLE_INTERVAL).append(",\n");
        sb.append("  \"samples\": [");
        for (int i = 0; i < samples.size(); i++) {
            var s = samples.get(i);
            sb.append(i > 0 ? ",\n    {" : "\n    {");
            sb.append("\"frame\": ").append(s.frame());
            sb.append(", \"stage\": ").append(McNativeVulkanProbe.quote(s.stage()));
            sb.append(", \"factorySet\": ").append(s.factorySet());
            sb.append(", \"instancePresent\": ").append(s.instancePresent());
            sb.append(", \"enginePresent\": ").append(s.enginePresent());
            sb.append(", \"engineLive\": ").append(s.engineLive());
            sb.append(", \"activeSections\": ").append(s.activeSections());
            sb.append(", \"rendererCreated\": ").append(s.rendererCreated());
            sb.append(", \"ingestEnabled\": ").append(s.ingestEnabled());
            sb.append(", \"cameraCaptures\": ").append(s.cameraCaptures());
            sb.append(", \"storedNearCamera\": ").append(s.storedNearCamera()).append('}');
        }
        sb.append(samples.isEmpty() ? "],\n" : "\n  ],\n");
        sb.append("  \"notes\": [");
        for (int i = 0; i < notes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(McNativeVulkanProbe.quote(notes.get(i)));
        }
        sb.append("]\n}\n");
        return sb.toString();
    }

    private static void writeEvidence() {
        McNativeVulkanProbe.writeFile("native-instance.json", json());
    }

    private static void note(String message) {
        synchronized (NOTES) {
            if (NOTES.contains(message) || NOTES.size() >= 32) return;
            NOTES.add(message);
        }
        Logger.warn("[native-vk] " + message);
        writeEvidence();
    }
}
