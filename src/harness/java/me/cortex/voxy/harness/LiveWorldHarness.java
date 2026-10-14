package me.cortex.voxy.harness;

import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.interop.VkInteropProbe;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A development-only mod. Every action is confined to its fresh, isolated game directory. */
public final class LiveWorldHarness implements ClientModInitializer {
    private record Checkpoint(String stage, double seconds, VkInteropProbe.Diagnostics renderer,
                              String dimension, double x, double y, double z,
                              long editMeshVersion, long priorEditMeshVersion) {}
    private final List<Checkpoint> checkpoints = new ArrayList<>();
    private final List<Object> nativeCheckpoints = new ArrayList<>();
    private final boolean nativeMode = Boolean.getBoolean("voxy.harness.native");
    private final List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
    private Path output;
    private int stage;
    /** Marker draws recorded when this stage began; native mode waits for real frames. */
    private long markerStart;
    /**
     * The depth ladder's request count when this stage first became ready (-1 until then). In a
     * native launch with the ladder on, a stage is not left until the ladder has requested a
     * sample after that point: a slower frame rate (measured 2026-10-09: under 240 ladder draws
     * in the 8-second `descend`) must not silently drop a gated look.
     */
    private long ladderReadyMark = -1;
    private boolean entered;
    private boolean done;
    private long started = System.nanoTime();
    private long stageStarted = started;
    private long frameStart;
    private final java.util.concurrent.atomic.AtomicInteger screenshotsPending = new java.util.concurrent.atomic.AtomicInteger();
    private double dwell;
    /**
     * The soak (round-27 item 6): after `reconnect`, run `travel`..`reconnect` again until this many
     * lifecycles are done — fast travel, edits, a resize (alternating sizes), a reload, both
     * dimension changes, the looks down, disconnect and reconnect, each repeated. Repeats take no
     * checkpoints; Voxy's per-stage snapshots carry the cycle.
     */
    private int cycles, cycle = 1;
    private static int cycleFrom() { return java.util.Arrays.asList(STAGES).indexOf("travel"); }
    /**
     * Far-world evidence (round 27): `travel` goes on from x = 768 to x = 2560, so Voxy ingests
     * terrain 2432–2688 blocks from spawn — beyond Minecraft's far plane (2048 here). The `far` look
     * then faces it from (0, 185, 0), below the clouds, pitch 16: the depth ladder's band rays run
     * 2–8° below horizontal and meet the ground ~820–3300 blocks out; Minecraft draws nothing there
     * (its terrain ends at 128 blocks) and the x = 768 terrain (~9°) is outside the band.
     */
    static final int FAR_TRAVEL_X = 2560, FAR_Y = 185, FAR_YAW = -90, FAR_PITCH = 16;
    /**
     * Voxy's fade (GL's final blit fades 90–100 % of its render distance: ~7344–8160 blocks at the
     * default 16): `travel`'s third leg ingests terrain at x 7672–7928, and the `fade` look faces it
     * from (0, 185, 0) at pitch 10, the band's rays 4° above to 2° below horizontal reaching it at
     * ~0.9° below — blended pixels the gate judges against GL's blend.
     */
    static final int FADE_TRAVEL_X = 7800, FADE_PITCH = 10;
    private int travelLeg;
    private String name() { return STAGES[stage]; }
    private CompletableFuture<Void> reload;
    private long editVersion;
    private static final String WORLD = "voxy-harness";
    private static final String[] STAGES = {"create", "warmup", "turn", "travel", "return",
        "horizon", "far", "fade", "night", "edit", "remove", "resize", "reload", "nether", "overworld", "descend",
        "ascend", "disconnect", "reconnect"};
    /**
     * The `horizon` look: back at spawn after the travel to x = 768, facing +x (yaw -90) with
     * the camera 15° down. Minecraft renders terrain to 8 chunks; the sections ingested around
     * x = 768 (±128 blocks) are Voxy-only, 640–896 blocks away, 3.5–4.9° below horizontal, inside
     * the native depth ladder's band (rays 1–9° below) where Minecraft shows sky. The real-section
     * LOAD experiment's expected-visible pixels come from this look.
     */
    static final int HORIZON_YAW = -90, HORIZON_PITCH = 18, HORIZON_Y = 160, HORIZON_WALL_X = 20;
    /**
     * The Z-direction experiment: the same ground, looked at straight down from two heights.
     * The depth ladder (its own launch) samples both; the gate compares the brackets. The
     * offsets are pinned in scripts/verify.py too; the eye height is Minecraft's.
     */
    static final int DESCEND_ABOVE_GROUND = 12, ASCEND_ABOVE_GROUND = 108;
    private int groundY = Integer.MIN_VALUE;

    @Override public void onInitializeClient() {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment() || !Boolean.getBoolean("voxy.harness")) return;
        output = Path.of(System.getProperty("voxy.harness.output")).toAbsolutePath();
        dwell = Double.parseDouble(System.getProperty("voxy.harness.seconds", "10"));
        cycles = Integer.getInteger("voxy.harness.cycles", 1);
        if (cycles < 1) throw new IllegalArgumentException("harness.cycles must be >= 1");
        if (!Double.isFinite(dwell) || dwell < 1) throw new IllegalArgumentException("harness.seconds must be >= 1");
        try {
            Files.createDirectories(output);
            Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath();
            // The runner creates this marker. Never operate on an ordinary user's saves.
            if (!Files.isRegularFile(game.resolve(".voxy-harness")) || Files.exists(game.resolve("saves"))) {
                throw new IllegalStateException("harness requires a marked, fresh game directory");
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        if (done) return;
        try {
            double elapsed = (System.nanoTime() - stageStarted) / 1e9;
            if (elapsed > Math.max(180, dwell + 120)) {
                failures.add("timeout in " + STAGES[stage] + "; screen="
                    + (mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName()));
                finish(mc);
                return;
            }
            if (name().equals("create") && !entered && !(mc.isGameLoadFinished() && mc.gui.screen() instanceof TitleScreen)) return;
            if (!entered) {
                entered = true;
                var d = nativeMode ? null : VkInteropProbe.diagnostics(false);
                frameStart = d == null ? 0 : d.frames();
                markerStart = me.cortex.voxy.client.core.vk.mcnative.McNativeMarkerDraw
                    .status().drawsRecorded();
                ladderReadyMark = -1;
                enter(mc);
                return;
            }
            if (name().equals("create")) {
                if (!inWorld(mc)) return;
                if (nativeMode) { advance(); return; }
                if (VoxyClient.backend() != VoxyClient.Backend.VULKAN) {
                    failures.add("expected Vulkan renderer; selected=" + VoxyClient.backend());
                    finish(mc);
                    return;
                }
                if (!VkContext.get().validationEnabled || !VkContext.get().syncValidationEnabled) {
                    failures.add("Vulkan validation and synchronization validation must both be active");
                    finish(mc);
                    return;
                }
                advance();
                return;
            }
            if (name().equals("disconnect")) {
                if (mc.level != null || mc.hasSingleplayerServer()) return;
                advance();
                return;
            }
            // the native update probe's placement: at warmup, once the client holds the probe's chunk
            // (an EmptyLevelChunk would say "all air" of a section it does not have)
            if (nativeMode && name().equals("warmup") && probeBefore == null && inWorld(mc)
                    && mc.level.getChunkSource().hasChunk(PROBE_X >> 4, PROBE_Z >> 4)) {
                updateProbeBefore(mc);
                command(mc, "setblock " + PROBE_X + " " + PROBE_Y + " " + PROBE_Z + " minecraft:glass");
            }
            if (!inWorld(mc) || !ready(mc)) return;
            if (ladderReadyMark < 0) {
                ladderReadyMark = me.cortex.voxy.client.core.vk.mcnative.McNativeDepthLadder.samplesRequested();
            }
            // Prevent an inactive window or a pause menu from silently halting the test.
            mc.options.pauseOnLostFocus = false;
            if (mc.gui.screen() != null && mc.gui.screen().isPauseScreen()) mc.gui.setScreen(null);
            // travel's second leg: after its dwell at x = 768, on to the far terrain
            if (name().equals("travel") && travelLeg < 2 && elapsed >= dwell) {
                travelLeg++;
                command(mc, "tp @s " + (travelLeg == 1 ? FAR_TRAVEL_X : FADE_TRAVEL_X) + " 120 0 0 30");
                stageStarted = System.nanoTime();
                return;
            }
            if (name().equals("turn") && elapsed < dwell) {
                mc.player.setYRot((float) (elapsed * 180));
                mc.player.setXRot(30);
            } else if (name().equals("descend") || name().equals("ascend")) {
                mc.player.setYRot(0);
                mc.player.setXRot(90);
            } else if (name().equals("horizon")) {
                mc.player.setYRot(HORIZON_YAW);
                mc.player.setXRot(HORIZON_PITCH);
            } else if (name().equals("far")) {
                mc.player.setYRot(FAR_YAW);
                mc.player.setXRot(FAR_PITCH);
            } else if (name().equals("fade")) {
                mc.player.setYRot(FAR_YAW);
                mc.player.setXRot(FADE_PITCH);
            } else {
                mc.player.setYRot(0);
                mc.player.setXRot(30);
            }
            var d = nativeMode ? null : VkInteropProbe.diagnostics(false);
            // Native mode waits for the renderer to have actually drawn into recent frames,
            // the same way the GL path waits for Voxy frames. Without this a checkpoint can
            // land on a frame where the level is not rendered at all (a dimension change),
            // and its screenshot then shows nothing — which says nothing about the draw.
            var marker = me.cortex.voxy.client.core.vk.mcnative.McNativeMarkerDraw.status();
            if (elapsed < dwell + (name().equals("turn") ? 1 : 0)
                || (!nativeMode && (d == null || d.frames() - frameStart < 20))
                || (nativeMode && marker.enabled() && marker.drawsRecorded() - markerStart < 2)
                || (nativeMode && me.cortex.voxy.client.core.vk.mcnative.McNativeDepthLadder.sampling()
                    && me.cortex.voxy.client.core.vk.mcnative.McNativeDepthLadder.samplesRequested()
                        - ladderReadyMark < me.cortex.voxy.client.core.vk.mcnative.McNativeDepthLadder
                            .samplesWantedIn(STAGES[stage]))) return;
            if (nativeMode) nativeCheckpoint(mc); else checkpoint(mc);
            if (stage == STAGES.length - 1) {
                if (screenshotsPending.get() != 0) return;
                if (cycle < cycles) {
                    cycle++;
                    stage = cycleFrom() - 1;
                    advance();
                } else finish(mc);
            } else advance();
        } catch (Throwable t) {
            t.printStackTrace();
            failures.add(STAGES[stage] + ": " + t);
            finish(mc);
        }
    }

    private void enter(Minecraft mc) {
        System.out.println("[voxy-harness] stage=" + STAGES[stage] + (cycle > 1 ? " cycle=" + cycle : ""));
        // Diagnostics that sample during the run (the depth ladder) read this to label
        // their samples with the lifecycle stage they were taken in.
        System.setProperty("voxy.harness.cycle", Integer.toString(cycle));
        System.setProperty("voxy.harness.stage", STAGES[stage]);
        switch (name()) {
            case "create" -> {
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(8);
                mc.options.simulationDistance().set(5);
                var settings = new LevelSettings(WORLD, GameType.CREATIVE,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                    true, WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(WORLD, settings,
                    new WorldOptions(20261004L, false, false), WorldPresets::createNormalWorldDimensions,
                    new TitleScreen());
            }
            case "warmup" -> {
                var server = mc.getSingleplayerServer();
                var id = mc.player.getUUID();
                server.execute(() -> {
                    var player = server.getPlayerList().getPlayer(id);
                    if (player == null) throw new IllegalStateException("harness player missing");
                    player.setGameMode(GameType.CREATIVE);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                });
                command(mc, "time set noon");
                command(mc, "weather clear");
                command(mc, "tp @s 0 120 0 0 30");
            }
            case "travel" -> {
                travelLeg = 0;
                command(mc, "tp @s 768 120 0 0 30");
            }
            case "return" -> command(mc, "tp @s 0 120 0 0 30");
            case "horizon" -> {
                // From y 160, pitch 18, the ladder band's rays run 4-10 degrees below the
                // horizon: over the hills Minecraft draws within its 128-block render distance,
                // so Minecraft shows sky there, and they meet the ground at x 530+ where only
                // Voxy has terrain (ingested around x 768 during `travel`). A stone wall 20 blocks
                // ahead (the rays meet x = 20 at y 156-158) on the +z side only: the Voxy-only
                // terrain lies within ~11 degrees of the view axis, so the -z half stays in front
                // of Minecraft's sky (must appear) and the +z half is behind the wall (must be
                // hidden).
                command(mc, "fill " + HORIZON_WALL_X + " 150 0 " + HORIZON_WALL_X + " 165 12 minecraft:stone");
                command(mc, "tp @s 0 " + HORIZON_Y + " 0 " + HORIZON_YAW + " " + HORIZON_PITCH);
            }
            case "far" -> command(mc, "tp @s 0 " + FAR_Y + " 0 " + FAR_YAW + " " + FAR_PITCH);
            case "fade" -> command(mc, "tp @s 0 " + FAR_Y + " 0 " + FAR_YAW + " " + FADE_PITCH);
            // the round-28 readiness references: Voxy at night (Minecraft's night lightmap, its sky)
            case "night" -> {
                command(mc, "time set midnight");
                command(mc, "tp @s 0 120 0 0 30");
            }
            case "edit" -> {
                command(mc, "time set noon");   // back from the night look
                command(mc, "fill " + HORIZON_WALL_X + " 150 0 " + HORIZON_WALL_X + " 165 12 minecraft:air");
                // back to where `edit` always ran, so later stages see what they saw before `horizon`
                command(mc, "tp @s 0 120 0 0 30");
                if (!nativeMode) editVersion = VkInteropProbe.meshVersionAt(0, 104, 24);
                command(mc, "fill -8 100 20 8 108 28 minecraft:glass");
            }
            case "remove" -> {
                if (nativeMode) {
                    updateProbeBefore(mc);
                    command(mc, "setblock " + PROBE_X + " " + PROBE_Y + " " + PROBE_Z + " minecraft:air");
                }
                if (!nativeMode) editVersion = VkInteropProbe.meshVersionAt(0, 104, 24);
                command(mc, "fill -8 100 20 8 108 28 minecraft:air");
            }
            case "resize" -> mc.getWindow().setWindowed(resizeWidth(), resizeHeight());
            case "reload" -> reload = mc.reloadResourcePacks();
            case "nether" -> command(mc, "execute in minecraft:the_nether run tp @s 0 100 0 0 30");
            case "overworld" -> command(mc, "execute in minecraft:overworld run tp @s 0 120 0 0 30");
            case "descend" -> {
                // the highest motion-blocking block under (0, 0) in the loaded chunk: the
                // ground the two looks straight down compare against
                groundY = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0);
                command(mc, "tp @s 0 " + (groundY + DESCEND_ABOVE_GROUND) + " 0 0 90");
            }
            case "ascend" -> command(mc, "tp @s 0 " + (groundY + ASCEND_ABOVE_GROUND) + " 0 0 90");
            case "disconnect" -> mc.disconnect(new TitleScreen(), false);
            case "reconnect" -> mc.createWorldOpenFlows().openWorld(WORLD, () -> {});
            default -> { }
        }
    }

    /**
     * Native update observation (round-28 Codex review of record, R28-NATIVE-UPDATE-OBSERVATION; R29;
     * R29-PRELAUNCH-CAUSALITY and -REMOVAL): one glass block at an interior position of a chunk section
     * that must be all air (local 8, 6, 8), in a level-0 Voxy section of its own (1, 3, 2: away from
     * the edit fill's), in view from the spawn pose.
     *
     * <p>Three observations, each with Voxy's own held content read back from its world
     * ({@code heldBlockAt}) and the trace's events ({@code McNativeIngestTrace}):
     * <ul>
     *   <li>{@code warmup}: the placement, as a precondition only. As on GL, Sodium's visibility-change
     *       ingest admits only a section that WAS visible, so a placement into an empty section reaches
     *       Voxy only when its chunk unloads.</li>
     *   <li>{@code return}: that ordinary chunk-unload ingest happened during {@code travel} (the
     *       spawn chunks leave the client's view distance): Voxy now holds the glass, and the updater's
     *       outcome that changed the leaf's content is dated between the placement and this mark.</li>
     *   <li>{@code remove}: with Voxy holding the glass (precondition, read back; a removal of a block
     *       Voxy never held is correctly a no-op), the removal empties the chunk section again, Sodium's
     *       ingest is queued, the updater changes the leaf (its generation advances), the scene consumes
     *       the dirty update, and the leaf ends in one of its truthful terminal states: re-meshed from
     *       content at or after that generation, or — the leaf emptied — its geometry removed.</li>
     * </ul>
     */
    private static final int PROBE_X = 40, PROBE_Y = 118, PROBE_Z = 72;
    private java.util.Map<String, Object> probeBefore;

    private static java.util.Map<String, Object> event(me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace.Event e) {
        if (e == null) return null;
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("seq", e.seq());
        m.put("epoch", e.epoch());
        m.put("engine", e.engine());
        return m;
    }

    private static java.util.Map<String, Object> unwatch(me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace.Unwatch e) {
        if (e == null) return null;
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("seq", e.seq());
        m.put("epoch", e.epoch());
        m.put("engine", e.engine());
        m.put("generation", e.version());
        m.put("nonEmptyBlocks", e.nonEmptyBlocks());
        return m;
    }

    private static java.util.Map<String, Object> update(me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace.Update u) {
        if (u == null) return null;
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("seq", u.seq());
        m.put("epoch", u.epoch());
        m.put("engine", u.engine());
        m.put("changed", u.changed());
        m.put("generation", u.version());
        m.put("nonEmptyBlocks", u.nonEmptyBlocks());
        m.put("section", java.util.List.of(u.sx(), u.sy(), u.sz()));
        m.put("wrote", u.wrote());
        return m;
    }

    private static java.util.Map<String, Object> mesh(me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace.Mesh e) {
        if (e == null) return null;
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("seq", e.seq());
        m.put("epoch", e.epoch());
        m.put("engine", e.engine());
        m.put("generation", e.version());
        m.put("probeHeld", e.probeHeld());
        return m;
    }

    private java.util.Map<String, Object> probeState(Minecraft mc) {
        var state = new java.util.LinkedHashMap<String, Object>();
        state.put("seq", me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace.now());
        state.put("block", blockAt(mc));
        state.put("voxyHeld", me.cortex.voxy.client.core.vk.mcnative.McNativeHierarchicalLoad.heldBlockAt(PROBE_X, PROBE_Y, PROBE_Z));
        state.put("leafGeneration", me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace.generation());
        state.put("epoch", me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace.epoch());
        state.put("otherSectionUpdates", me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace.otherSectionUpdates());
        state.put("builds", me.cortex.voxy.client.core.vk.mcnative.McNativeHierarchicalLoad.buildsSoFar());
        state.put("sceneEngine", me.cortex.voxy.client.core.vk.mcnative.McNativeHierarchicalLoad.sceneEngineIdentity());
        state.put("leafMeshVersion", me.cortex.voxy.client.core.vk.mcnative.McNativeHierarchicalLoad.level0MeshVersionAt(PROBE_X, PROBE_Y, PROBE_Z));
        return state;
    }

    /** Before the stage's action: the trace observes the probe's level-0 section from here on. */
    private void updateProbeBefore(Minecraft mc) {
        me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace.observe(
            me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(0, PROBE_X >> 5, PROBE_Y >> 5, PROBE_Z >> 5),
            PROBE_X, PROBE_Y, PROBE_Z, me.cortex.voxy.client.core.vk.mcnative.McNativeHierarchicalLoad.sceneWorld());
        boolean loaded = mc.level.getChunkSource().hasChunk(PROBE_X >> 4, PROBE_Z >> 4);
        var section = mc.level.getChunk(PROBE_X >> 4, PROBE_Z >> 4).getSection(mc.level.getSectionIndex(PROBE_Y));
        var before = probeState(mc);
        before.put("chunkLoaded", loaded);
        before.put("sectionEmpty", section.hasOnlyAir());
        probeBefore = before;
    }

    private static String blockAt(Minecraft mc) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(mc.level.getBlockState(new BlockPos(PROBE_X, PROBE_Y, PROBE_Z)).getBlock()).toString();
    }

    private java.util.Map<String, Object> updateProbe(Minecraft mc) {
        var probe = new java.util.LinkedHashMap<String, Object>();
        probe.put("block", java.util.List.of(PROBE_X, PROBE_Y, PROBE_Z));
        probe.put("before", probeBefore);
        probe.put("after", probeState(mc));
        probe.put("ingest", event(me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace
            .lastIngest(PROBE_X >> 4, PROBE_Y >> 4, PROBE_Z >> 4)));
        probe.put("update", update(me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace.lastChange()));
        probe.put("lastUpdate", update(me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace.lastUpdate()));
        probe.put("leafDirty", event(me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace.leafDirty()));
        probe.put("leafMeshed", mesh(me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace.leafMeshed()));
        probe.put("leafUnwatched", unwatch(me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace.leafUnwatched()));
        return probe;
    }

    private static final java.util.Set<String> PROBE_STAGES = java.util.Set.of("warmup", "return", "remove");

    /** Odd cycles grow the window to 960x540, even ones return it to the default 854x480. */
    private int resizeWidth() { return cycle % 2 == 1 ? 960 : 854; }
    private int resizeHeight() { return cycle % 2 == 1 ? 540 : 480; }

    private static boolean isNight(Minecraft mc) {
        long t = mc.level.getDefaultClockTime() % 24000;
        return t >= 13000 && t < 23000;
    }

    private boolean ready(Minecraft mc) {
        return switch (name()) {
            case "warmup" -> mc.level.dimension() == Level.OVERWORLD && near(mc, 0, 120, 0)
                && (!nativeMode || (probeBefore != null && blockAt(mc).equals("minecraft:glass")));
            case "return", "overworld" -> mc.level.dimension() == Level.OVERWORLD && near(mc, 0, 120, 0);
            case "travel" -> near(mc, travelLeg == 0 ? 768 : travelLeg == 1 ? FAR_TRAVEL_X : FADE_TRAVEL_X, 120, 0);
            case "far", "fade" -> near(mc, 0, FAR_Y, 0);
            case "night" -> near(mc, 0, 120, 0) && isNight(mc);
            case "horizon" -> near(mc, 0, HORIZON_Y, 0)
                && mc.level.getBlockState(new BlockPos(HORIZON_WALL_X, 157, 6)).is(Blocks.STONE);
            case "edit" -> near(mc, 0, 120, 0) && mc.level.getBlockState(new BlockPos(0, 104, 24)).is(Blocks.GLASS)
                && (nativeMode || VkInteropProbe.meshVersionAt(0, 104, 24) > editVersion);
            case "remove" -> mc.level.getBlockState(new BlockPos(0, 104, 24)).isAir()
                && (nativeMode || VkInteropProbe.meshVersionAt(0, 104, 24) > editVersion);
            case "resize" -> mc.getWindow().getScreenWidth() == resizeWidth()
                && mc.getWindow().getScreenHeight() == resizeHeight();
            case "reload" -> {
                if (reload.isCompletedExceptionally()) reload.join();
                yield reload.isDone();
            }
            case "nether" -> mc.level.dimension() == Level.NETHER && near(mc, 0, 100, 0);
            case "descend" -> groundY != Integer.MIN_VALUE && near(mc, 0, groundY + DESCEND_ABOVE_GROUND, 0);
            case "ascend" -> groundY != Integer.MIN_VALUE && near(mc, 0, groundY + ASCEND_ABOVE_GROUND, 0);
            default -> true;
        };
    }

    private void checkpoint(Minecraft mc) {
        // Checkpoints are captured once; screenshot readback completes asynchronously.
        if (checkpoints.stream().anyMatch(c -> c.stage().equals(STAGES[stage]))) return;
        var d = VkInteropProbe.diagnostics(true);
        String name = STAGES[stage];
        if (d == null || d.meshed() == 0 || d.selected() == 0 || d.depthPixels() == 0) failures.add(name + ": no Voxy terrain rendered");
        if (d != null && !d.currentWorld()) failures.add(name + ": renderer retains a different world engine");
        if (d != null && d.invalidIds() != 0) failures.add(name + ": invalid geometry IDs=" + d.invalidIds());
        if (d != null && d.exhausted()) failures.add(name + ": geometry exhaustion did not recover");
        checkpoints.add(new Checkpoint(name, (System.nanoTime() - started) / 1e9, d,
            mc.level.dimension().toString(), mc.player.getX(), mc.player.getY(), mc.player.getZ(),
            VkInteropProbe.meshVersionAt(0, 104, 24), editVersion));
        screenshotsPending.incrementAndGet();
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(output.resolve(name + ".png"));
            } catch (Exception e) {
                failures.add(name + ": screenshot failed: " + e);
            } finally { screenshotsPending.decrementAndGet(); }
        });
        writeResult(false);
    }

    private void advance() {
        stage++;
        entered = false;
        stageStarted = System.nanoTime();
    }

    private void nativeCheckpoint(Minecraft mc) {
        String name = STAGES[stage];
        if (nativeCheckpoints.stream().anyMatch(c -> ((java.util.Map<?, ?>) c).get("stage").equals(name))) return;
        var report = me.cortex.voxy.client.core.vk.mcnative.McNativeVulkanProbe.probe();
        if (!report.mcUsesVulkan() || report.vkDevice() == 0 || report.vkInstance() == 0
            || report.colour() == null || report.depth() == null
            || report.colour().vkImage() == 0 || report.colour().vkImageView() == 0
            || report.depth().vkImage() == 0 || report.depth().vkImageView() == 0
            || !report.notes().isEmpty()) failures.add(name + ": incomplete Minecraft-native Vulkan observation");
        var entry = new java.util.LinkedHashMap<String, Object>();
        entry.put("stage", name);
        entry.put("renderer", report);
        entry.put("voxyBackend", String.valueOf(VoxyClient.backend()));
        entry.put("dimension", mc.level.dimension().toString());
        entry.put("seconds", (System.nanoTime() - started) / 1e9);
        entry.put("deviceDebuggingEnabled", com.mojang.blaze3d.systems.RenderSystem.getDevice().isDebuggingEnabled());
        entry.put("deviceDebugMessages", com.mojang.blaze3d.systems.RenderSystem.getDevice().getLastDebugMessages());
        // How many bounded marker draws had been recorded when this frame was captured.
        // The gate needs an implementation-sourced answer to "was anything drawn into the
        // frame we are about to screenshot?" — inferring it from how dark the image looks
        // was wrong in both directions (round-1 review B4).
        entry.put("markerDraws",
            me.cortex.voxy.client.core.vk.mcnative.McNativeMarkerDraw.status().drawsRecorded());
        // Whether Minecraft had a screen or overlay up when this frame was captured. Anything
        // Voxy records at the end of level rendering is drawn BEFORE Minecraft's GUI, so a
        // loading overlay (a dimension change, for instance) covers it. The gate needs this
        // as a fact rather than guessing from how the image looks.
        entry.put("frameCoveredByGui",
            mc.gui.screen() != null || mc.gui.overlay() != null);
        // Where the player and camera were, and (for the two straight-down stages) the ground
        // height the teleport was computed from. The ladder gate compares its samples'
        // camera positions against these.
        entry.put("playerY", mc.player.getY());
        entry.put("cameraY", mc.gameRenderer.mainCamera().position().y);
        entry.put("playerPitch", mc.player.getXRot());
        entry.put("groundY", groundY == Integer.MIN_VALUE ? null : groundY);
        if (PROBE_STAGES.contains(name) && probeBefore != null) entry.put("updateProbe", updateProbe(mc));
        nativeCheckpoints.add(entry);
        screenshotsPending.incrementAndGet();
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) { image.writeToFile(output.resolve(name + ".png")); }
            catch (Exception e) { failures.add(name + ": screenshot failed: " + e); }
            finally { screenshotsPending.decrementAndGet(); }
        });
        writeResult(false);
    }

    private void finish(Minecraft mc) {
        done = true;
        if (!nativeMode) failures.addAll(VkContext.validationMessages());
        writeResult(true);
        System.out.println("[voxy-harness] finished failures=" + failures.size());
        mc.stop();
    }

    private void writeResult(boolean complete) {
        try {
            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("complete", complete);
            result.put("success", complete && failures.isEmpty());
            result.put("stage", STAGES[stage]);
            result.put("cycles", cycles);
            result.put("cycle", cycle);
            result.put("checkpoints", nativeMode ? nativeCheckpoints : checkpoints);
            result.put("failures", List.copyOf(failures));
            result.put("scope", nativeMode ? "Minecraft-native Vulkan environment and lifecycle observations; Voxy LoD integration is not implemented"
                : "Live Vulkan liveness, world identity, geometry IDs, depth coverage and mesh regeneration after edits; pixel-level visual parity is not asserted");
            if (nativeMode) result.put("voxyIntegrationStatus", "BLOCKED_UNIMPLEMENTED");
            Files.writeString(output.resolve(nativeMode ? "native-result.json" : "live-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (Exception e) { throw new IllegalStateException("cannot save harness result", e); }
    }

    private static boolean inWorld(Minecraft mc) { return mc.level != null && mc.player != null && mc.getConnection() != null; }
    private static boolean near(Minecraft mc, double x, double y, double z) {
        return mc.player.distanceToSqr(x, y, z) < 4;
    }
    private static void command(Minecraft mc, String command) { mc.getConnection().sendCommand(command); }
}
