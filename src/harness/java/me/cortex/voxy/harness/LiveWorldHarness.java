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
    private boolean entered;
    private boolean done;
    private long started = System.nanoTime();
    private long stageStarted = started;
    private long frameStart;
    private final java.util.concurrent.atomic.AtomicInteger screenshotsPending = new java.util.concurrent.atomic.AtomicInteger();
    private double dwell;
    private CompletableFuture<Void> reload;
    private long editVersion;
    private static final String WORLD = "voxy-harness";
    private static final String[] STAGES = {"create", "warmup", "turn", "travel", "return",
        "horizon", "edit", "remove", "resize", "reload", "nether", "overworld", "descend",
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
            if (stage == 0 && !entered && !(mc.isGameLoadFinished() && mc.gui.screen() instanceof TitleScreen)) return;
            if (!entered) {
                entered = true;
                var d = nativeMode ? null : VkInteropProbe.diagnostics(false);
                frameStart = d == null ? 0 : d.frames();
                markerStart = me.cortex.voxy.client.core.vk.mcnative.McNativeMarkerDraw
                    .status().drawsRecorded();
                enter(mc);
                return;
            }
            if (stage == 0) {
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
            if (stage == 14) {
                if (mc.level != null || mc.hasSingleplayerServer()) return;
                advance();
                return;
            }
            if (!inWorld(mc) || !ready(mc)) return;
            // Prevent an inactive window or a pause menu from silently halting the test.
            mc.options.pauseOnLostFocus = false;
            if (mc.gui.screen() != null && mc.gui.screen().isPauseScreen()) mc.gui.setScreen(null);
            if (stage == 2 && elapsed < dwell) {
                mc.player.setYRot((float) (elapsed * 180));
                mc.player.setXRot(30);
            } else if (stage == 12 || stage == 13) {
                mc.player.setYRot(0);
                mc.player.setXRot(90);
            } else if (stage == 5) {
                mc.player.setYRot(HORIZON_YAW);
                mc.player.setXRot(HORIZON_PITCH);
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
            if (elapsed < dwell + (stage == 2 ? 1 : 0)
                || (!nativeMode && (d == null || d.frames() - frameStart < 20))
                || (nativeMode && marker.enabled() && marker.drawsRecorded() - markerStart < 2)) return;
            if (nativeMode) nativeCheckpoint(mc); else checkpoint(mc);
            if (stage == STAGES.length - 1) {
                if (screenshotsPending.get() != 0) return;
                finish(mc);
            } else advance();
        } catch (Throwable t) {
            t.printStackTrace();
            failures.add(STAGES[stage] + ": " + t);
            finish(mc);
        }
    }

    private void enter(Minecraft mc) {
        System.out.println("[voxy-harness] stage=" + STAGES[stage]);
        // Diagnostics that sample during the run (the depth ladder) read this to label
        // their samples with the lifecycle stage they were taken in.
        System.setProperty("voxy.harness.stage", STAGES[stage]);
        switch (stage) {
            case 0 -> {
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
            case 1 -> {
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
            case 3 -> command(mc, "tp @s 768 120 0 0 30");
            case 4 -> command(mc, "tp @s 0 120 0 0 30");
            case 5 -> {
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
            case 6 -> {
                command(mc, "fill " + HORIZON_WALL_X + " 150 0 " + HORIZON_WALL_X + " 165 12 minecraft:air");
                // back to where `edit` always ran, so later stages see what they saw before `horizon`
                command(mc, "tp @s 0 120 0 0 30");
                if (!nativeMode) editVersion = VkInteropProbe.meshVersionAt(0, 104, 24);
                command(mc, "fill -8 100 20 8 108 28 minecraft:glass");
            }
            case 7 -> {
                if (!nativeMode) editVersion = VkInteropProbe.meshVersionAt(0, 104, 24);
                command(mc, "fill -8 100 20 8 108 28 minecraft:air");
            }
            case 8 -> mc.getWindow().setWindowed(960, 540);
            case 9 -> reload = mc.reloadResourcePacks();
            case 10 -> command(mc, "execute in minecraft:the_nether run tp @s 0 100 0 0 30");
            case 11 -> command(mc, "execute in minecraft:overworld run tp @s 0 120 0 0 30");
            case 12 -> {
                // the highest motion-blocking block under (0, 0) in the loaded chunk: the
                // ground the two looks straight down compare against
                groundY = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0);
                command(mc, "tp @s 0 " + (groundY + DESCEND_ABOVE_GROUND) + " 0 0 90");
            }
            case 13 -> command(mc, "tp @s 0 " + (groundY + ASCEND_ABOVE_GROUND) + " 0 0 90");
            case 14 -> mc.disconnect(new TitleScreen(), false);
            case 15 -> mc.createWorldOpenFlows().openWorld(WORLD, () -> {});
            default -> { }
        }
    }

    private boolean ready(Minecraft mc) {
        return switch (stage) {
            case 1, 4, 11 -> mc.level.dimension() == Level.OVERWORLD && near(mc, 0, 120, 0);
            case 3 -> near(mc, 768, 120, 0);
            case 5 -> near(mc, 0, HORIZON_Y, 0)
                && mc.level.getBlockState(new BlockPos(HORIZON_WALL_X, 157, 6)).is(Blocks.STONE);
            case 6 -> near(mc, 0, 120, 0) && mc.level.getBlockState(new BlockPos(0, 104, 24)).is(Blocks.GLASS)
                && (nativeMode || VkInteropProbe.meshVersionAt(0, 104, 24) > editVersion);
            case 7 -> mc.level.getBlockState(new BlockPos(0, 104, 24)).isAir()
                && (nativeMode || VkInteropProbe.meshVersionAt(0, 104, 24) > editVersion);
            case 8 -> mc.getWindow().getScreenWidth() == 960 && mc.getWindow().getScreenHeight() == 540;
            case 9 -> {
                if (reload.isCompletedExceptionally()) reload.join();
                yield reload.isDone();
            }
            case 10 -> mc.level.dimension() == Level.NETHER && near(mc, 0, 100, 0);
            case 12 -> groundY != Integer.MIN_VALUE && near(mc, 0, groundY + DESCEND_ABOVE_GROUND, 0);
            case 13 -> groundY != Integer.MIN_VALUE && near(mc, 0, groundY + ASCEND_ABOVE_GROUND, 0);
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
