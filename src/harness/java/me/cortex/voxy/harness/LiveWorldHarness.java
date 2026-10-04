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
        "edit", "remove", "resize", "reload", "nether", "overworld", "disconnect", "reconnect"};

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
            if (stage == 11) {
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
            } else {
                mc.player.setYRot(0);
                mc.player.setXRot(30);
            }
            var d = nativeMode ? null : VkInteropProbe.diagnostics(false);
            if (elapsed < dwell + (stage == 2 ? 1 : 0)
                || (!nativeMode && (d == null || d.frames() - frameStart < 20))) return;
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
                if (!nativeMode) editVersion = VkInteropProbe.meshVersionAt(0, 104, 24);
                command(mc, "fill -8 100 20 8 108 28 minecraft:glass");
            }
            case 6 -> {
                if (!nativeMode) editVersion = VkInteropProbe.meshVersionAt(0, 104, 24);
                command(mc, "fill -8 100 20 8 108 28 minecraft:air");
            }
            case 7 -> mc.getWindow().setWindowed(960, 540);
            case 8 -> reload = mc.reloadResourcePacks();
            case 9 -> command(mc, "execute in minecraft:the_nether run tp @s 0 100 0 0 30");
            case 10 -> command(mc, "execute in minecraft:overworld run tp @s 0 120 0 0 30");
            case 11 -> mc.disconnect(new TitleScreen(), false);
            case 12 -> mc.createWorldOpenFlows().openWorld(WORLD, () -> {});
            default -> { }
        }
    }

    private boolean ready(Minecraft mc) {
        return switch (stage) {
            case 1, 4, 10 -> mc.level.dimension() == Level.OVERWORLD && near(mc, 0, 120, 0);
            case 3 -> near(mc, 768, 120, 0);
            case 5 -> mc.level.getBlockState(new BlockPos(0, 104, 24)).is(Blocks.GLASS)
                && (nativeMode || VkInteropProbe.meshVersionAt(0, 104, 24) > editVersion);
            case 6 -> mc.level.getBlockState(new BlockPos(0, 104, 24)).isAir()
                && (nativeMode || VkInteropProbe.meshVersionAt(0, 104, 24) > editVersion);
            case 7 -> mc.getWindow().getScreenWidth() == 960 && mc.getWindow().getScreenHeight() == 540;
            case 8 -> {
                if (reload.isCompletedExceptionally()) reload.join();
                yield reload.isDone();
            }
            case 9 -> mc.level.dimension() == Level.NETHER && near(mc, 0, 100, 0);
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
