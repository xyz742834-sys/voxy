package me.cortex.voxy.vk;

import com.google.gson.GsonBuilder;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.core.rendering.ISectionWatcher;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.junit.jupiter.api.*;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Independent analytic pixels and real arena reclamation, using no Minecraft/GL context. */
public class VkVisualRecoveryTest {
    private static final int W = 256, H = 192;
    private static final float[] CLEAR = {0, 0, 0, 1};
    private static final Path OUT = Path.of(System.getProperty("voxy.test.evidence", "build/vk-test-output"))
        .resolve("visual-recovery");
    // A literal contract, not a screenshot-derived golden: UP quad [0,1] x [0,1],
    // x_ndc = x/2 - 1/2, y_ndc = 1/2 - z/2, depth = y/10 = 0.1.
    // Pixel centres therefore occupy x=[64,128), y=[96,144) in bottom-up storage.
    // Model 192 / UP is the synthetic atlas texel (192,0,55); alpha encodes UP|AO.
    private static final int QUAD_RGBA = 0x413700C0;
    private static final int CLEAR_RGBA = 0xFF000000;
    private static boolean initialized;

    @BeforeAll static void setup() {
        VulkanTestSupport.requireVulkan();
        VkFrameTracker.init();
        initialized = true;
    }

    @AfterAll static void teardown() {
        if (!initialized) return;
        VkFrameTracker.get().waitIdle();
        VkSampler.shutdown();
        VkQuadIndexBuffer.shutdown();
        VkFrameTracker.shutdown();
    }

    private static float[] matrix(boolean mirror) {
        float[] m = new float[16];
        m[0] = mirror ? -0.5f : 0.5f;
        m[9] = -0.5f;
        m[6] = 0.1f;
        m[12] = mirror ? 0.5f : -0.5f;
        m[13] = 0.5f;
        m[15] = 1;
        return m;
    }

    private static boolean inside(int x, int y) { return x >= 64 && x < 128 && y >= 96 && y < 144; }

    private static final class Scene implements AutoCloseable {
        final VkTerrainResources res = new VkTerrainResources(8, 512, 56, 193);
        final VkMergedTableBuilder builder = new VkMergedTableBuilder(res, VkTerrainRenderer.Barriers.CONSERVATIVE);
        final VkTerrainRenderer renderer = new VkTerrainRenderer(res, W, H,
            VkTerrainRenderer.Barriers.CONSERVATIVE, VkTerrainRenderer.Mode.MERGED);
        final VkRenderTarget target = new VkRenderTarget(W, H);

        Scene() { res.fillModels(VkTerrainResources.MODEL_FLAG_SHADED); }

        void render(int sectionId, boolean mirror, boolean omit) { render(sectionId, mirror, omit, 0.1f); }

        void render(int sectionId, boolean mirror, boolean omit, float depthScale) {
            float[] m = matrix(mirror);
            m[6] = depthScale;
            VkSceneUniform.write(res.uniform, m, SyntheticTerrain.ORIGIN, 1, new float[]{0, 0, 0});
            MemoryUtil.memPutInt(res.indirectLookup.addr(), omit ? 0 : 1);
            MemoryUtil.memPutInt(res.indirectLookup.addr() + 4, sectionId);
            MemoryUtil.memPutInt(res.visibility.addr() + sectionId * 4L, 0x80000001);
            // Poison the outputs: this image must come from this GPU table build.
            res.mergedEntry.fill(0xDEADBEEF);
            res.mergedPrefix.fill(0xDEADBEEF);
            res.mergedDraw.fill(0xDEADBEEF);
            res.positionScratch.fill(0xDEADBEEF);
            var tracker = VkFrameTracker.get();
            var cmd = tracker.beginFrame();
            builder.record(cmd, 17, 7); // deliberately wrong host count
            renderer.record(cmd, target, 7, CLEAR);
            target.recordReadback(cmd);
            target.recordDepthReadback(cmd);
            tracker.endFrame();
            tracker.waitForFrame();
        }

        @Override public void close() {
            target.free(); renderer.free(); builder.free(); res.free();
        }
    }

    private static Scene minimal() {
        var scene = new Scene();
        var terrain = SyntheticTerrain.minimal();
        int[] starts = terrain.writeGeometry(scene.res.geometry);
        terrain.writeMetadata(scene.res.sectionMetadata, starts);
        return scene;
    }

    private record Pixels(long colorMismatches, long depthMismatches, long coveredPixels,
                          double maxDepthError) {
        boolean matches() { return colorMismatches == 0 && depthMismatches == 0 && coveredPixels == 3072; }
    }

    private record PressureCycle(int cycle, long capacityBytes, long usedBefore,
                                 long usedAfterReclaim, long usedAfterRelease, long usedAfterRecovery,
                                 long retryBytes, long reclaimAttempts, int newRequests,
                                 long rejectedTotal, int geometryId, Pixels pixels) {}

    private static Pixels evidence(String name, VkRenderTarget target) throws Exception {
        Files.createDirectories(OUT);
        var expected = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        var difference = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        long colorErrors = 0, depthErrors = 0, covered = 0;
        double maxError = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                long offset = ((long) y * W + x) * 4;
                boolean in = inside(x, y);
                int reference = in ? QUAD_RGBA : CLEAR_RGBA;
                int actual = MemoryUtil.memGetInt(target.readbackBuffer().addr() + offset);
                float depth = MemoryUtil.memGetFloat(target.depthReadbackBuffer().addr() + offset);
                double error = Math.abs(depth - (in ? 0.1f : 0));
                if (depth != 0) covered++;
                if (actual != reference) colorErrors++;
                if (!Float.isFinite(depth) || error > 1e-6) depthErrors++;
                maxError = Math.max(maxError, error);
                int argb = 0xFF000000 | ((reference & 255) << 16) | (reference & 0xFF00) | ((reference >>> 16) & 255);
                expected.setRGB(x, H - 1 - y, argb);
                difference.setRGB(x, H - 1 - y, actual != reference || error > 1e-6
                    || !Float.isFinite(depth) ? 0xFFFF0000 : 0xFF000000);
            }
        }
        ImageIO.write(expected, "PNG", OUT.resolve(name + "-expected.png").toFile());
        ImageIO.write(difference, "PNG", OUT.resolve(name + "-diff.png").toFile());
        target.writePng(OUT.resolve(name + "-actual.png"), true);
        byte[] depthBytes = new byte[W * H * 4];
        MemoryUtil.memByteBuffer(target.depthReadbackBuffer().addr(), depthBytes.length).get(depthBytes);
        Files.write(OUT.resolve(name + "-depth-f32le.bin"), depthBytes);
        var pixels = new Pixels(colorErrors, depthErrors, covered, maxError);
        json(name, Map.of("pixels", pixels, "scope", "Analytic opaque fixture on standalone Vulkan; not Minecraft-native integration or live GL parity"));
        System.out.println("[visual-recovery] " + name + " " + pixels);
        return pixels;
    }

    private static void json(String name, Object value) throws Exception {
        Files.createDirectories(OUT);
        Files.writeString(OUT.resolve(name + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(value));
    }

    @Test void analyticColorAndDepthMatchEveryPixel() throws Exception {
        try (var scene = minimal()) {
            scene.render(0, false, false);
            assertTrue(evidence("analytic", scene.target).matches());
        }
    }

    @Test void mirroredImageIsRejectedByIndependentReference() throws Exception {
        try (var scene = minimal()) {
            scene.render(0, true, false);
            var pixels = evidence("mirrored-control", scene.target);
            assertFalse(pixels.matches());
            assertEquals(6144, pixels.colorMismatches(), "the shifted rectangle and original must both differ");
        }
    }

    @Test void missingDrawIsRejectedByIndependentReference() throws Exception {
        try (var scene = minimal()) {
            scene.render(0, false, true);
            var pixels = evidence("missing-control", scene.target);
            assertFalse(pixels.matches());
            assertEquals(3072, pixels.colorMismatches());
            assertEquals(0, pixels.coveredPixels());
        }
    }

    @Test void wrongAtlasColorIsRejectedByIndependentReference() throws Exception {
        try (var scene = minimal()) {
            MemoryUtil.memPutLong(scene.res.geometry.addr(), 1L | (191L << 26) | (0xF0L << 55));
            scene.render(0, false, false);
            var pixels = evidence("color-control", scene.target);
            assertFalse(pixels.matches());
            assertEquals(3072, pixels.colorMismatches());
            assertEquals(0, pixels.depthMismatches());
        }
    }

    @Test void wrongDepthIsRejectedEvenWithMatchingColor() throws Exception {
        try (var scene = minimal()) {
            scene.render(0, false, false, 0.2f);
            var pixels = evidence("depth-control", scene.target);
            assertFalse(pixels.matches());
            assertEquals(0, pixels.colorMismatches());
            assertEquals(3072, pixels.depthMismatches());
        }
    }

    private static final class Watcher implements ISectionWatcher {
        final Long2IntOpenHashMap watched = new Long2IntOpenHashMap();
        int requests;
        @Override public boolean watch(long pos, int types) {
            if ((types & WorldEngine.UPDATE_TYPE_BLOCK_BIT) != 0) requests++;
            watched.put(pos, watched.get(pos) | types);
            return true;
        }
        @Override public boolean unwatch(long pos, int types) {
            int left = watched.get(pos) & ~types;
            if (left == 0) watched.remove(pos); else watched.put(pos, left);
            return left == 0;
        }
        @Override public int get(long pos) { return watched.get(pos); }
    }

    private static BuiltSection section(long pos, byte children, int quads) {
        var memory = new MemoryBuffer(quads * 8L);
        // Valid UP quad with model 192. Larger rejected meshes need not be rendered.
        long quad = 1L | (192L << 26) | (0xF0L << 55);
        for (int i = 0; i < quads; i++) MemoryUtil.memPutLong(memory.address + i * 8L, quad);
        return new BuiltSection(pos, children, 0, memory, new int[]{0, 0, 0, 0, quads, quads, quads, quads}, null);
    }

    private static int childGeometry(Scene scene) {
        for (int id = 0; id < 8; id++) {
            long base = scene.res.sectionMetadata.addr() + id * 32L;
            if (MemoryUtil.memGetLong(base) == 0 && MemoryUtil.memGetInt(base + 20) != 0) return id;
        }
        fail("the re-requested level-zero child has no uploaded geometry");
        return -1;
    }

    @Test void tinyArenaReclaimsRejectsAndRerequestsWithIdenticalPixels() throws Exception {
        final long capacity = 3072;
        var geometry = new BasicAsyncGeometryManager(8, capacity);
        var watcher = new Watcher();
        var nodes = new NodeManager(8, geometry, watcher);
        var roots = new IntOpenHashSet();
        nodes.setTLNCallbacks(roots::add, roots::remove);
        var reclaimer = new VkHierarchicalScene.GeometryReclaimer(nodes, roots::contains);
        nodes.setClear(reclaimer);
        var admission = new VkGeometryAdmission(geometry, reclaimer, nodes, 4);
        long parent = WorldEngine.getWorldSectionId(1, 0, 0, 0);
        long child = WorldEngine.getWorldSectionId(0, 0, 0, 0);
        long ballast = WorldEngine.getWorldSectionId(1, 1, 0, 0);
        var cycles = new ArrayList<PressureCycle>();
        var activeRoots = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        try (var scene = new Scene()) {
            for (int cycle = 0; cycle < 3; cycle++) {
                nodes.insertTopLevelNode(parent);
                activeRoots.add(parent);
                assertTrue(admission.accept(section(parent, (byte) 1, 1)));
                nodes.insertTopLevelNode(ballast);
                activeRoots.add(ballast);
                assertTrue(admission.accept(section(ballast, (byte) 0, 1)));
                nodes.processRequest(parent);
                assertTrue(admission.accept(section(child, (byte) 0, 1)));
                VkGeometryFlush.flush(geometry, scene.res.geometry, scene.res.sectionMetadata);
                scene.render(childGeometry(scene), false, false);
                if (cycle == 0) assertTrue(evidence("before-pressure", scene.target).matches());
                assertEquals(capacity, geometry.getGeometryUsedBytes());
                long usedBefore = geometry.getGeometryUsedBytes();
                long reclaimedBefore = reclaimer.totalReclaimed();
                int requestsBefore = watcher.requests;
                // Demand fits an empty arena, but cannot coexist with the protected root.
                // Real canFit fails, real NodeManager eviction frees the child; retry still fails.
                assertFalse(geometry.canFit(2048));
                assertFalse(admission.accept(section(child, (byte) 0, 256)));
                assertTrue(admission.exhausted());
                assertEquals(cycle + 1, admission.rejected());
                long afterReclaim = geometry.getGeometryUsedBytes();
                assertEquals(2048, afterReclaim, "eviction must free actual bytes, not merely increment a counter");
                assertTrue(reclaimer.totalReclaimed() > reclaimedBefore);
                assertEquals(2, roots.size(), "both protected top-level fallbacks must survive");
                // Release a separate top-level allocation, then retry exactly the same demand.
                nodes.removeTopLevelNode(ballast);
                activeRoots.remove(ballast);
                assertEquals(1024, geometry.getGeometryUsedBytes());
                long afterRelease = geometry.getGeometryUsedBytes();
                // Exercise the same request/admission/flush chain again after rejection.
                nodes.processRequest(parent);
                assertTrue(watcher.requests > requestsBefore, "evicted geometry must actually be requested again");
                var retry = section(child, (byte) 0, 256);
                long retryBytes = VkHierarchicalScene.geometryBytesNeeded(retry);
                assertTrue(admission.accept(retry));
                assertFalse(admission.exhausted());
                assertEquals(capacity, geometry.getGeometryUsedBytes());
                VkGeometryFlush.flush(geometry, scene.res.geometry, scene.res.sectionMetadata);
                int id = childGeometry(scene);
                scene.render(id, false, false);
                var pixels = evidence("recovered-" + cycle, scene.target);
                assertTrue(pixels.matches(), "reused geometry IDs/pointers must render the independent expected image");
                cycles.add(new PressureCycle(cycle, capacity, usedBefore, afterReclaim, afterRelease,
                    geometry.getGeometryUsedBytes(), retryBytes, reclaimer.totalReclaimed() - reclaimedBefore,
                    watcher.requests - requestsBefore, admission.rejected(), id, pixels));
                nodes.removeTopLevelNode(parent);
                activeRoots.remove(parent);
                assertEquals(0, geometry.getGeometryUsedBytes(), "cycle teardown must release every allocation");
                json("pressure", Map.of("complete", cycle == 2, "success", cycle == 2,
                    "cycles", cycles, "scope", "Real 3 KiB arena, NodeManager and production admission policy, GPU upload/table/render; no live Minecraft or OS-wide OOM"));
            }
        } finally {
            for (long pos : activeRoots.toLongArray()) nodes.removeTopLevelNode(pos);
            // Flush frees remaining owned CPU uploads even on an assertion failure.
            for (var buffer : geometry.getUploads().values()) buffer.free();
            geometry.getUploads().clear();
        }
    }
}
