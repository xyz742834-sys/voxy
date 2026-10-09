package me.cortex.voxy.client.core.vk.mcnative;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.VkDepth;
import me.cortex.voxy.client.core.vk.VkFrameTracker;
import me.cortex.voxy.client.core.vk.VkHierarchicalScene;
import me.cortex.voxy.client.core.vk.VkHostViewport;
import me.cortex.voxy.client.core.vk.VkRenderTarget;
import me.cortex.voxy.client.core.vk.VkSceneUniform;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * <b>Voxy's hierarchical pipeline natively</b> (EXPERIMENTAL, flag {@code voxy.native.hierload},
 * needs native instance mode): {@link VkHierarchicalScene} — the real world mapper/bakery,
 * {@code NodeManager}, HiZ, traversal, prep/cull, table, opaque/temporal/translucent — driven with
 * Minecraft's own matrix ({@link McNativeCamera}) on the frames the ladder hands it, rendering
 * into Voxy's own {@link VkRenderTarget}; then {@link McNativeComposite} writes Voxy's colour and
 * depth into a pass that LOADs Minecraft's colour and depth, with Voxy's GREATER_OR_EQUAL and
 * writes on — the native analogue of the GL path's resolve, without GL.
 *
 * <h2>What is judged</h2>
 * The same per-pixel rule as real-LOAD: the ladder's bracket of Minecraft's depth against Voxy's
 * own depth of the same frame (the target, read back after the last iteration). Voxy's colour
 * must appear exactly where its depth is at or above the bracket top, the previous readback must
 * stay where it is at or below the bottom; between, either; no Voxy pixel, unchanged.
 *
 * <h2>Per hand-off</h2>
 * {@link #ITERATIONS} rounds of: device observed idle (no earlier Minecraft submission can still
 * read the uniform, the tables or the target), scene uniform + {@code prepare} with this frame's
 * matrix, {@code record} into the target in Voxy's own submission (fence waited), then
 * {@code serviceRequests} (it reads the traversal's request buffer, so only after the fence). The
 * last round also reads the target back and makes it shader-readable for the composite. Several
 * rounds let LoD selection converge on a frame that is rendered only every few seconds.
 *
 * <h2>Stated limits of this slice</h2>
 * Voxy renders with Minecraft's projection (far plane 2048), not its own near 16 / far 48000 with a
 * depth reprojection at the composite as the GL path does, so LoD beyond Minecraft's far plane is
 * clipped. No near cut: Voxy's vanilla-bound mechanism (visible-section stream feeding the depth
 * bound) is not native, so near terrain coincides with Minecraft's (undetermined at bracket
 * grain). The device-idle wait per hand-off is safe but blocking.
 */
public final class McNativeHierarchicalLoad implements Destroyable {
    /**
     * round-24 R24-RETIRED-CONTEXT: scenes queued on Minecraft's destroy queue and not destroyed
     * yet. Minecraft may drain that queue after Voxy's context is released; the immediate shutdown
     * (after its device-idle wait) destroys these itself, so Minecraft's later destroy() is a no-op.
     */
    private static final java.util.List<McNativeHierarchicalLoad> QUEUED = new java.util.ArrayList<>();

    public static final String FLAG = "voxy.native.hierload";
    /**
     * With {@link #FLAG}: also render and composite on <b>every</b> frame, not only on the frames
     * the ladder hands over. Those frames are not judged; they wait only for Voxy's own previous
     * submission (see {@link #drawEveryFrame}).
     */
    public static final String EVERY_FRAME_FLAG = "voxy.native.hierframes";
    static final int ITERATIONS = 3;
    static final int TOP_RADIUS = 1, DEPTH = 2;
    static final double SUBDIVISION_PX = 128.0;
    static final int MESHES_PER_PASS = 64;
    static final int SECTIONS = 8192;
    static final int MAX_QUADS = 4_000_000;
    static final int BUILD_BUDGET = 6;
    static final float[] CLEAR = {0.05f, 0.05f, 0.10f, 1.0f};
    static final VkHierarchicalScene.Visibility VISIBILITY = VkHierarchicalScene.Visibility.CULL;
    static final int[] DECLARED_DEPTH_STATE = McNativeTerrainLoad.DECLARED_DEPTH_STATE;
    static final String JUDGED = "judged", NO_ENGINE = "no-world-engine",
        NO_CAMERA = "no-camera-this-frame", EXTENT = "camera-extent-mismatch",
        NOTHING_MESHED = "nothing-meshed", BUILD_BUDGET_SPENT = "build-budget-spent",
        ATLAS_PENDING = "atlas-pending";

    private static final long READBACK_BUDGET_BYTES = 40L << 20;
    private static final int FAILURE_BUDGET = 3;

    private static final List<String> NOTES = new ArrayList<>();
    private static final java.util.Map<Long, Result> RESULTS = new java.util.LinkedHashMap<>();
    private static final java.util.Map<Long, Pending> PENDING = new java.util.HashMap<>();
    private static McNativeHierarchicalLoad instance;
    private static long drawsRecorded, lastCaptureSeen = -1;
    /** Every-frame composites recorded into Minecraft's frames (not judged; not in drawsRecorded). */
    private static long framesComposited;
    /**
     * Where the last build meshed nothing (engine identity + camera section). The every-frame path
     * does not rebuild there — it would spend the whole build budget on consecutive frames;
     * handed samples still may. Cleared by any build that meshes something.
     */
    private static int emptyEngine;
    private static int[] emptySection;
    /** Every-frame attempts that drew nothing, by the same reasons a handed sample would skip with. */
    private static final java.util.TreeMap<String, Long> FRAME_SKIPS = new java.util.TreeMap<>();
    private static int builds, problems, closeFailures, leakedScenes, readbacksInFlight, frameId = 1;
    private static boolean attempted, deviceDiverged, startedTracker;
    private static String firstProblem;
    private static long ownerDeviceSeen;

    /**
     * One handed sample. Non-judged results carry no counts or files.
     *
     * @param sceneBuild   build ordinal of the scene drawn (matches its "hier-LOAD scene #N" log line)
     * @param meshed       sections meshed by {@code populate}
     * @param iterations   prepare/record/service rounds run for this sample
     */
    public record Result(long at, String status, String stage, long[] counts, float minDepth,
                         float maxDepth, String file, String frameFile, String referenceFile,
                         String referenceDepthFile, Boolean projectionAdjusted, double farPlane,
                         long referenceSet, long cameraCapture, int engineId, int sceneBuild,
                         int atlasGeneration, float[] mcProjection, float[] projection,
                         int meshed, int iterations, long previousCapture, int buildsSoFar,
                         int atlasState, int meshedAtBuild, String visibility) {}

    private record Pending(int[][] rects, int[][] colour, float[][] depth, Result partial) {}

    private final VulkanDevice device;
    private final long ownerDevice;
    private final VkRenderTarget target;
    private final VkHierarchicalScene scene;
    private final McNativeComposite composite;
    private final java.lang.ref.WeakReference<me.cortex.voxy.common.world.WorldEngine> engine;
    private final int atlasGeneration, buildOrdinal, width, height;
    /** Sections populate() meshed when the scene was built (its log line); {@code meshed} grows after. */
    private final int meshedAtBuild;
    private boolean destroyed;
    /**
     * A submission's request buffer has not been serviced yet. Each submission's requests are
     * serviced exactly once, after its fence and before the next {@code prepare} resets them —
     * whichever path (hand-off or every-frame) submits next.
     */
    private boolean requestsUnread;

    private McNativeHierarchicalLoad(VulkanDevice device, long ownerDevice, VkRenderTarget target,
                                     VkHierarchicalScene scene, McNativeComposite composite,
                                     me.cortex.voxy.common.world.WorldEngine engine,
                                     int atlasGeneration, int buildOrdinal, int width, int height,
                                     int meshedAtBuild) {
        this.device = device;
        this.ownerDevice = ownerDevice;
        this.target = target;
        this.scene = scene;
        this.composite = composite;
        this.engine = new java.lang.ref.WeakReference<>(engine);
        this.atlasGeneration = atlasGeneration;
        this.buildOrdinal = buildOrdinal;
        this.width = width;
        this.height = height;
        this.meshedAtBuild = meshedAtBuild;
    }

    public static boolean enabled() { return Boolean.getBoolean(FLAG); }
    public static boolean everyFrame() { return enabled() && Boolean.getBoolean(EVERY_FRAME_FLAG); }
    public static long framesComposited() { return framesComposited; }
    public static long drawsRecorded() { return drawsRecorded; }

    public static List<Result> results() {
        synchronized (NOTES) {
            return List.copyOf(RESULTS.values());
        }
    }

    /** Level-render tail, after the ladder, every frame. Inert without its flag. */
    public static void renderIfEnabled() {
        if (!enabled()) return;
        long capture = McNativeCamera.frame();
        long previousCapture = lastCaptureSeen;
        boolean fresh = capture != previousCapture;
        lastCaptureSeen = capture;
        try {
            render(fresh, capture, previousCapture);
        } catch (Throwable t) {
            fail("the hierarchical-LOAD experiment failed: " + t);
        }
    }

    private static void render(boolean freshCamera, long capture, long previousCapture) {
        long at = McNativeDepthLadder.takeSampleThisFrame(McNativeDepthLadder.EXPERIMENT_HIER_LOAD);
        // at < 0: not a handed sample; with the every-frame flag it is still drawn, not judged
        if (at < 0 && !everyFrame()) return;
        attempted = true;
        String stage = System.getProperty("voxy.harness.stage", "");
        if (!VoxyClient.nativeInstanceMode()) {
            fail("hierarchical-LOAD needs native instance mode (-Dvoxy.native.instance=true)");
            return;
        }
        var notes = new ArrayList<String>();
        VulkanDevice device = McNativeVulkan.device(notes);
        if (device == null) {
            notes.forEach(McNativeHierarchicalLoad::note);
            return;
        }
        long mcDevice = McNativeVulkan.vkDeviceHandle(device, notes);
        long ourDevice = adoptedDeviceHandle();
        if (mcDevice == 0 || ourDevice == 0 || mcDevice != ourDevice) {
            deviceDiverged = true;
            var stale = instance;
            instance = null;
            if (stale != null) { stale.destroyed = true; leakedScenes++; }
            fail("Minecraft's device is not Voxy's adopted one; hierarchical-LOAD stops for the session");
            return;
        }
        if (deviceDiverged || problems >= FAILURE_BUDGET) return;
        ownerDeviceSeen = mcDevice;
        var mc = Minecraft.getInstance();
        var mainTarget = mc.gameRenderer.mainRenderTarget();
        GpuTextureView colour = mainTarget.getColorTextureView();
        GpuTextureView depth = mainTarget.getDepthTextureView();
        if (colour == null || depth == null) {
            fail("the main render target has no colour or depth view");
            return;
        }
        int format = VulkanConst.toVk(colour.texture().getFormat());
        int depthVk = VulkanConst.toVk(depth.texture().getFormat());
        int width = colour.getWidth(0), height = colour.getHeight(0);
        if (format != VkRenderTarget.FORMAT_COLOR || depthVk != VkRenderTarget.FORMAT_DEPTH) {
            fail("Minecraft's formats are colour " + format + " depth " + depthVk
                + ", not the composite's " + VkRenderTarget.FORMAT_COLOR + "/" + VkRenderTarget.FORMAT_DEPTH);
            return;
        }
        var view = McNativeCamera.latest();
        if (view == null || !freshCamera) {
            skip(at, stage, NO_CAMERA, capture, previousCapture, -1);
            return;
        }
        if (view.width() != width || view.height() != height) {
            skip(at, stage, EXTENT, capture, previousCapture, -1);
            return;
        }
        var world = mc.level == null ? null : WorldIdentifier.ofEngineNullable(mc.level);
        if (world == null || !world.isLive()) {
            skip(at, stage, NO_ENGINE, capture, previousCapture, -1);
            return;
        }
        McNativeAtlas.refreshIfReplaced();
        McNativeAtlas.requestOnce();
        if (McNativeAtlas.state() == McNativeAtlas.State.FAILED) {
            fail(McNativeAtlas.failure());
            return;
        }
        if (McNativeAtlas.state() != McNativeAtlas.State.READY) {
            skip(at, stage, ATLAS_PENDING, capture, previousCapture, McNativeAtlas.state().ordinal());
            return;
        }

        // a frame tracker kept alive for the probe's lifetime (the upload stream hooks into it)
        if (!McNativeTerrainScene.frameTrackerRunning()) {
            VkFrameTracker.init();
            startedTracker = true;
        }
        var probe = instance;
        if (probe != null && (probe.destroyed || probe.width != width || probe.height != height
                || probe.engine.get() != world || !probe.scene.worldIsLive()
                || !probe.scene.coversCamera(view.x(), view.y(), view.z())
                || probe.atlasGeneration != McNativeAtlas.generation())) {
            retire(probe);
            probe = null;
        }
        int[] cameraSection = {VkHostViewport.sectionOf(view.x()), VkHostViewport.sectionOf(view.y()),
            VkHostViewport.sectionOf(view.z())};
        if (probe == null && at < 0 && emptySection != null
                && emptyEngine == System.identityHashCode(world)
                && java.util.Arrays.equals(emptySection, cameraSection)) {
            skip(at, stage, NOTHING_MESHED, capture, previousCapture, -1);
            return;
        }
        if (probe == null) {
            if (builds >= BUILD_BUDGET) {
                skip(at, stage, BUILD_BUDGET_SPENT, capture, previousCapture, -1);
                return;
            }
            builds++;
            probe = build(device, mcDevice, world, view, width, height);
            emptyEngine = probe == null ? System.identityHashCode(world) : 0;
            emptySection = probe == null ? cameraSection : null;
            if (probe == null) {
                skip(at, stage, NOTHING_MESHED, capture, previousCapture, -1);
                return;
            }
            instance = probe;
        }

        // ---- Minecraft's own matrix, 0..1 depth ----
        int[] anchor = {VkHostViewport.sectionOf(view.x()), VkHostViewport.sectionOf(view.y()),
            VkHostViewport.sectionOf(view.z())};
        float[] sub = VkHostViewport.cameraSubPos(view.x(), view.y(), view.z(), anchor);
        var forward = VkHostViewport.forward(view.modelView(), new Vector3f());
        int[] ahead = {Math.round(forward.x * 4), Math.round(forward.y * 4), Math.round(forward.z * 4)};
        var vkProjection = VkHostViewport.projectionForVulkan(view.projection(), view.modelView(), sub, ahead);
        boolean adjusted = !vkProjection.equals(view.projection());
        if (VkHostViewport.depthStillOutOfRange(vkProjection, view.modelView(), sub, ahead)) {
            fail("Minecraft's projection does not put a point ahead into 0..1 depth");
            return;
        }
        double farPlane = VkHostViewport.farPlaneDistance(vkProjection);
        float[] mvp = VkHostViewport.mvp(vkProjection, view.modelView(), sub);
        float minSSS = (float) ((SUBDIVISION_PX * SUBDIVISION_PX) / ((double) width * height));
        if (at < 0) {
            drawEveryFrame(probe, mvp, anchor, sub, minSSS, colour, depth, width, height);
            return;
        }

        // ---- the hierarchical pipeline in Voxy's own submissions ----
        var tracker = VkFrameTracker.get();
        int[] colours = null;
        float[] depths = null;
        for (int it = 0; it < ITERATIONS; it++) {
            boolean last = it == ITERATIONS - 1;
            if (!tracker.waitIdleChecked()) {
                fail("vkDeviceWaitIdle did not succeed before the hierarchical-LOAD uniform write");
                return;
            }
            if (probe.requestsUnread) {
                // an every-frame submission before this hand-off left its requests unserviced
                probe.scene.serviceRequests(MESHES_PER_PASS);
                probe.requestsUnread = false;
            }
            int frame = frameId++;
            VkSceneUniform.write(probe.scene.res.uniform, mvp, anchor, frame, sub);
            probe.scene.prepare(new org.joml.Matrix4f().set(mvp), anchor, sub, minSSS, frame, -1.0f);
            var cmd = tracker.beginFrame();
            probe.scene.record(cmd, probe.target, CLEAR, null);
            if (last) {
                probe.target.recordReadback(cmd);
                probe.target.recordDepthReadback(cmd);
                probe.composite.prepareSources(cmd);
            }
            tracker.endFrame();
            tracker.waitForFrame();
            probe.scene.serviceRequests(MESHES_PER_PASS);
            probe.requestsUnread = false;
        }
        int n = width * height;
        colours = new int[n];
        depths = new float[n];
        long base = probe.target.readbackBuffer().addr();
        long dbase = probe.target.depthReadbackBuffer().addr();
        int clearRgb = McNativeTerrainScene.packRgb(CLEAR);
        for (int i = 0; i < n; i++) {
            colours[i] = MemoryUtil.memGetInt(base + (long) i * 4) & 0x00FFFFFF;
            depths[i] = MemoryUtil.memGetFloat(dbase + (long) i * 4);
        }

        // ---- the composite into Minecraft's LOADed frame ----
        boolean drawn = false;
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "voxy native hierarchical composite", colour, Optional.empty(), depth,
                OptionalDouble.empty())) {
            VkCommandBuffer cmd = McNativeVulkan.commandBufferOf(pass, notes);
            if (cmd == null) {
                notes.forEach(McNativeHierarchicalLoad::note);
                return;
            }
            probe.composite.recordInPass(cmd, width, height);
            drawsRecorded++;
            drawn = true;
        }
        if (!drawn) return;
        logFrames(at);
        int[][] rects = {McNativeDepthLadder.bandRect(width, height, false),
            McNativeDepthLadder.bandRect(width, height, true)};
        int[][] bandColour = new int[2][];
        float[][] bandDepth = new float[2][];
        for (int o = 0; o < 2; o++) {
            int[] q = rects[o];
            int w = q[2] - q[0], h = q[3] - q[1];
            bandColour[o] = new int[w * h];
            bandDepth[o] = new float[w * h];
            int i = 0;
            for (int y = q[1]; y < q[3]; y++) {
                for (int x = q[0]; x < q[2]; x++, i++) {
                    bandColour[o][i] = colours[y * width + x];
                    bandDepth[o][i] = depths[y * width + x];
                }
            }
        }
        float[] mcProjection = new float[16], usedProjection = new float[16];
        view.projection().get(mcProjection);
        vkProjection.get(usedProjection);
        var partial = new Result(at, JUDGED, stage, null, Float.NaN, Float.NaN, null, null, null, null,
            adjusted, farPlane, 0, capture, System.identityHashCode(world), probe.buildOrdinal,
            probe.atlasGeneration, mcProjection, usedProjection, probe.scene.meshedSections(),
            ITERATIONS, previousCapture, builds, -1, probe.meshedAtBuild,
            probe.scene.visibility().name());
        synchronized (NOTES) {
            PENDING.put(at, new Pending(rects, bandColour, bandDepth, partial));
        }
        requestReadback(colour, width, height, at);
    }

    /**
     * The every-frame path: one round, no readback, not judged.
     *
     * <h2>Why waiting for Voxy's own fence is enough</h2>
     * Voxy's frame tracker keeps one submission in flight. After {@code waitForFrame} every Voxy
     * submission has completed, so the uniform, the node/geometry/table buffers and the request
     * buffer are free for the host — exactly what the hand-off path's device-idle wait buys for
     * them. The only Voxy resources Minecraft's command buffers touch are the target's colour and
     * depth (the composite samples them). Voxy submits on Minecraft's own graphics queue (the
     * adopted queue), and this frame's submission is queued after Minecraft submitted the
     * previous frame; the target's next {@code beginRendering} barrier has ALL_COMMANDS as its
     * source stage, so it waits for the earlier composite's fragment reads (write-after-read on
     * one queue), and {@code prepareSources} makes this frame's writes visible to the composite
     * Minecraft records after this submission. Retirement still goes through Minecraft's destroy
     * queue, whose frame fence orders after every earlier submission on the queue.
     */
    private static void drawEveryFrame(McNativeHierarchicalLoad probe, float[] mvp, int[] anchor,
                                       float[] sub, float minSSS, GpuTextureView colour,
                                       GpuTextureView depth, int width, int height) {
        var tracker = VkFrameTracker.get();
        tracker.waitForFrame();
        if (probe.requestsUnread) {
            probe.scene.serviceRequests(MESHES_PER_PASS);
            probe.requestsUnread = false;
        }
        int frame = frameId++;
        VkSceneUniform.write(probe.scene.res.uniform, mvp, anchor, frame, sub);
        probe.scene.prepare(new org.joml.Matrix4f().set(mvp), anchor, sub, minSSS, frame, -1.0f);
        var cmd = tracker.beginFrame();
        probe.scene.record(cmd, probe.target, CLEAR, null);
        probe.composite.prepareSources(cmd);
        tracker.endFrame();
        probe.requestsUnread = true;
        var notes = new ArrayList<String>();
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "voxy native hierarchical frame", colour, Optional.empty(), depth,
                OptionalDouble.empty())) {
            VkCommandBuffer mcCmd = McNativeVulkan.commandBufferOf(pass, notes);
            if (mcCmd == null) {
                notes.forEach(McNativeHierarchicalLoad::note);
                return;
            }
            probe.composite.recordInPass(mcCmd, width, height);
            framesComposited++;
        }
    }

    private static McNativeHierarchicalLoad build(VulkanDevice device, long mcDevice,
                                                  me.cortex.voxy.common.world.WorldEngine world,
                                                  McNativeCamera.View view, int width, int height) {
        VkRenderTarget target = null;
        VkHierarchicalScene scene = null;
        McNativeComposite composite = null;
        try {
            target = new VkRenderTarget(width, height);
            scene = new VkHierarchicalScene(world, target, width, height, SECTIONS, MAX_QUADS,
                VkRenderTarget.FORMAT_COLOR);
            // round-24 R24-HIER-CULL: Voxy's production mode — the raster cull writes visibility,
            // so the temporal pass draws the newly visible subset (the default is a test mode)
            scene.setVisibility(VISIBILITY);
            scene.populate(view.x(), view.y(), view.z(), TOP_RADIUS, DEPTH);
            int meshed = scene.meshedSections();
            if (meshed == 0) {
                // the finally block frees scene and target (freeing them here too was a double free)
                Logger.info("[native-vk] hier-LOAD: nothing meshed (scene #" + builds + ")");
                return null;
            }
            composite = new McNativeComposite(target.color, target.depth);
            Logger.info("[native-vk] hier-LOAD scene #" + builds + ": " + meshed + " sections meshed,"
                + " top radius " + TOP_RADIUS + ", depth " + DEPTH);
            var built = new McNativeHierarchicalLoad(device, mcDevice, target, scene, composite, world,
                McNativeAtlas.generation(), builds, width, height, meshed);
            target = null; scene = null; composite = null;
            return built;
        } catch (Throwable t) {
            fail("could not build the hierarchical scene: " + t);
            return null;
        } finally {
            // nothing of these was submitted to Minecraft yet
            if (composite != null) try { composite.free(); } catch (Throwable t) { closeFailures++; }
            if (scene != null) try { scene.free(); } catch (Throwable t) { closeFailures++; }
            if (target != null) try { target.free(); } catch (Throwable t) { closeFailures++; }
        }
    }

    private static void skip(long at, String stage, String reason, long capture, long previousCapture,
                             int atlasState) {
        if (at < 0) {
            FRAME_SKIPS.merge(reason, 1L, Long::sum);
            return;
        }
        var r = new Result(at, reason, stage, null, Float.NaN, Float.NaN, null, null, null, null, null,
            Double.NaN, 0, capture, 0, 0, 0, null, null, 0, 0, previousCapture, builds, atlasState, 0, null);
        synchronized (NOTES) {
            RESULTS.put(at, r);
        }
        Logger.info("[native-vk] hier load at draw " + at + " status=" + reason);
        logFrames(at);
        writeEvidence();
    }

    /**
     * With the every-frame flag: how many frames were composited before this handed sample was
     * decided (a judged sample logs it when its pass is recorded, before its readback returns).
     */
    private static void logFrames(long at) {
        if (!everyFrame()) return;
        Logger.info("[native-vk] hier frames before draw " + at + ": composited=" + framesComposited);
    }

    private static void requestReadback(GpuTextureView colour, int width, int height, long at) {
        long bytes = (long) width * height * 4;
        if (bytes > READBACK_BUDGET_BYTES) {
            fail("the colour image is over the readback budget");
            return;
        }
        GpuBuffer buffer = null;
        try {
            var gpu = RenderSystem.getDevice();
            buffer = gpu.createBuffer(() -> "voxy native hierarchical readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            final GpuBuffer readback = buffer;
            readbacksInFlight++;
            gpu.createCommandEncoder().copyTextureToBuffer(colour.texture(), readback, 0,
                () -> measure(readback, width, height, at), 0);
            buffer = null;
        } catch (Throwable t) {
            fail("the hierarchical-LOAD readback could not be requested: " + t);
        } finally {
            if (buffer != null) try { buffer.close(); } catch (Throwable t) { closeFailures++; }
        }
    }

    private static void measure(GpuBuffer buffer, int width, int height, long at) {
        try (var view = new GpuBufferSlice(buffer, 0, buffer.size()).map(true, false)) {
            var data = view.data();
            Pending pending;
            synchronized (NOTES) {
                pending = PENDING.remove(at);
            }
            var band = McNativeDepthLadder.takeBand(at);
            if (pending == null || band == null) {
                fail("hierarchical-LOAD readback at draw " + at + " has no reference or ladder band");
                return;
            }
            int[] q = band.sample().rect();
            int o = java.util.Arrays.equals(q, pending.rects()[0]) ? 0
                : java.util.Arrays.equals(q, pending.rects()[1]) ? 1 : -1;
            if (o < 0) {
                fail("hierarchical-LOAD at draw " + at + ": the ladder's rect is neither band rect");
                return;
            }
            int w = q[2] - q[0], h = q[3] - q[1];
            byte[] after = new byte[w * h * 3];
            int i = 0;
            for (int y = q[1]; y < q[3]; y++) {
                long rowBase = (long) y * width * 4;
                for (int x = q[0]; x < q[2]; x++, i++) {
                    long p = rowBase + (long) x * 4;
                    after[i * 3] = data.get((int) p);
                    after[i * 3 + 1] = data.get((int) p + 1);
                    after[i * 3 + 2] = data.get((int) p + 2);
                }
            }
            int[] refRgb = pending.colour()[o];
            float[] refDepth = pending.depth()[o];
            float minDepth = Float.NaN, maxDepth = Float.NaN;
            long refSet = 0;
            int clearRgb = McNativeTerrainScene.packRgb(CLEAR);
            for (int k = 0; k < refDepth.length; k++) {
                if (refRgb[k] != clearRgb) refSet++;
                float d = refDepth[k];
                if (d > VkDepth.CLEAR) {
                    minDepth = Float.isNaN(minDepth) ? d : Math.min(minDepth, d);
                    maxDepth = Float.isNaN(maxDepth) ? d : Math.max(maxDepth, d);
                }
            }
            long[] counts = McNativeTerrainLoad.judge(band.classes(), band.pixels(), after, refRgb, refDepth);
            String file = McNativeTerrainLoad.writeCrop(data, width, q, "native-hier-load-" + at + ".ppm.gz");
            String frameFile = McNativeTerrainLoad.writeFrame(data, width, height,
                "native-hier-load-frame-" + at + ".ppm.gz");
            String refName = "native-hier-load-reference-" + at + ".ppm.gz";
            String depthName = "native-hier-load-depth-" + at + ".f32.gz";
            boolean refWritten = writeReference(refRgb, refDepth, w, h, refName, depthName);
            var p = pending.partial();
            var result = new Result(at, JUDGED, p.stage(), counts, minDepth, maxDepth, file, frameFile,
                refWritten ? refName : null, refWritten ? depthName : null, p.projectionAdjusted(),
                p.farPlane(), refSet, p.cameraCapture(), p.engineId(), p.sceneBuild(),
                p.atlasGeneration(), p.mcProjection(), p.projection(), p.meshed(), p.iterations(),
                p.previousCapture(), p.buildsSoFar(), -1, p.meshedAtBuild(), p.visibility());
            synchronized (NOTES) {
                RESULTS.put(at, result);
            }
            if (McNativeTerrainLoad.violated(counts)) {
                problems++;
                String why = "hierarchical-LOAD at draw " + at + ": " + McNativeTerrainLoad.describe(counts);
                if (firstProblem == null) firstProblem = why;
                note(why);
            }
            Logger.info("[native-vk] hier load at draw " + at + " status=judged "
                + McNativeTerrainLoad.describe(counts) + " depth=[" + minDepth + " " + maxDepth + "]");
            writeEvidence();
        } catch (Throwable t) {
            fail("the hierarchical-LOAD measurement failed: " + t);
        } finally {
            readbacksInFlight--;
            try { buffer.close(); } catch (Throwable t) { closeFailures++; }
        }
    }

    private static boolean writeReference(int[] rgb, float[] depth, int w, int h, String refName,
                                          String depthName) {
        String dir = System.getProperty("voxy.harness.output");
        if (dir == null || dir.isBlank()) return false;
        try {
            byte[] body = new byte[w * h * 3];
            for (int i = 0; i < rgb.length; i++) {
                body[i * 3] = (byte) (rgb[i] & 0xFF);
                body[i * 3 + 1] = (byte) ((rgb[i] >> 8) & 0xFF);
                body[i * 3 + 2] = (byte) ((rgb[i] >> 16) & 0xFF);
            }
            McNativeVulkanProbe.writeGzipFileBytes(refName,
                ("P6\n" + w + " " + h + "\n255\n").getBytes(StandardCharsets.US_ASCII), body);
            var buf = java.nio.ByteBuffer.allocate(w * h * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (float d : depth) buf.putFloat(d);
            McNativeVulkanProbe.writeGzipFileBytes(depthName,
                ("VXF32\n" + w + " " + h + "\n").getBytes(StandardCharsets.US_ASCII), buf.array());
            return true;
        } catch (Throwable t) {
            note("could not retain the hierarchical-LOAD reference " + refName + ": " + t);
            return false;
        }
    }

    // ---------------- lifetime ----------------

    private static long adoptedDeviceHandle() {
        try { return VkContext.get().device.address(); } catch (Throwable t) { return 0; }
    }

    /** Retire through Minecraft's destroy queue: its submissions sampled the target and used the pipeline. */
    private static void retire(McNativeHierarchicalLoad probe) {
        if (instance == probe) instance = null;
        if (probe == null || probe.destroyed) return;
        try {
            McNativeVulkan.encoder(probe.device).queueForDestroy(probe);
            QUEUED.add(probe);
        } catch (Throwable t) {
            probe.destroyed = true;
            leakedScenes++;
            note("queueForDestroy refused the hierarchical scene (" + t + "); leaking it on purpose");
        }
    }

    @Override
    public void destroy() {
        QUEUED.remove(this);
        if (this.destroyed) return;
        this.destroyed = true;
        try { this.composite.free(); } catch (Throwable t) { closeFailures++; }
        try { this.scene.free(); } catch (Throwable t) { closeFailures++; }
        try { this.target.free(); } catch (Throwable t) { closeFailures++; }
    }

    public static void shutdownImmediate(org.lwjgl.vulkan.VkDevice waitedDevice) {
        var owned = new java.util.ArrayList<McNativeHierarchicalLoad>(QUEUED);
        QUEUED.clear();
        if (instance != null) owned.add(instance);
        instance = null;
        for (var probe : owned) {
            if (probe.destroyed) continue;
            if (waitedDevice == null || waitedDevice.address() != probe.ownerDevice) {
                probe.destroyed = true;
                leakedScenes++;
                note("not destroying the hierarchical scene: the idle wait was observed on another device;"
                    + " leaking on purpose");
                continue;
            }
            probe.destroy();
        }
        stopTracker();
        writeEvidence();
    }

    public static void shutdown() {
        var probe = instance;
        if (probe != null) retire(probe);
        writeEvidence();
    }

    private static void stopTracker() {
        if (!startedTracker) return;
        startedTracker = false;
        try { VkFrameTracker.shutdown(); } catch (Throwable t) { closeFailures++; }
    }

    // ---------------- evidence ----------------

    private static void writeEvidence() {
        McNativeVulkanProbe.writeFile("native-hier-load.json", json());
    }

    static String json() {
        List<Result> results = results();
        List<String> notes;
        synchronized (NOTES) {
            notes = List.copyOf(NOTES);
        }
        var sb = new StringBuilder("{\n");
        sb.append("  \"enabled\": ").append(enabled()).append(",\n");
        sb.append("  \"attempted\": ").append(attempted).append(",\n");
        sb.append("  \"drawsRecorded\": ").append(drawsRecorded).append(",\n");
        sb.append("  \"everyFrame\": ").append(everyFrame()).append(",\n");
        sb.append("  \"framesComposited\": ").append(framesComposited).append(",\n");
        sb.append("  \"frameSkips\": {");
        int skipIndex = 0;
        for (var e : FRAME_SKIPS.entrySet()) {
            if (skipIndex++ > 0) sb.append(", ");
            sb.append(McNativeVulkanProbe.quote(e.getKey())).append(": ").append(e.getValue());
        }
        sb.append("},\n");
        sb.append("  \"builds\": ").append(builds).append(",\n");
        sb.append("  \"buildBudget\": ").append(BUILD_BUDGET).append(",\n");
        sb.append("  \"iterations\": ").append(ITERATIONS).append(",\n");
        sb.append("  \"topRadius\": ").append(TOP_RADIUS).append(",\n");
        sb.append("  \"depth\": ").append(DEPTH).append(",\n");
        sb.append("  \"declaredDepthState\": [").append(DECLARED_DEPTH_STATE[0]).append(", ")
          .append(DECLARED_DEPTH_STATE[1]).append(", ").append(DECLARED_DEPTH_STATE[2]).append("],\n");
        sb.append("  \"depthStateReadBack\": false,\n");
        sb.append("  \"instanceMode\": ").append(VoxyClient.nativeInstanceMode()).append(",\n");
        sb.append("  \"results\": [");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            sb.append(i > 0 ? ",\n    {" : "\n    {");
            sb.append("\"at\": ").append(r.at());
            sb.append(", \"status\": ").append(McNativeVulkanProbe.quote(r.status()));
            sb.append(", \"stage\": ").append(McNativeVulkanProbe.quote(r.stage()));
            sb.append(", \"cameraCapture\": ").append(r.cameraCapture());
            sb.append(", \"previousCapture\": ").append(r.previousCapture());
            sb.append(", \"buildsSoFar\": ").append(r.buildsSoFar());
            if (r.counts() != null) {
                for (int k = 0; k < McNativeTerrainLoad.COUNTS; k++) {
                    sb.append(", \"").append(McNativeTerrainLoad.COUNT_NAMES[k]).append("\": ")
                      .append(r.counts()[k]);
                }
                sb.append(", \"minDepth\": ").append(Float.isNaN(r.minDepth()) ? "null" : Float.toString(r.minDepth()));
                sb.append(", \"maxDepth\": ").append(Float.isNaN(r.maxDepth()) ? "null" : Float.toString(r.maxDepth()));
                sb.append(", \"referenceSet\": ").append(r.referenceSet());
                sb.append(", \"file\": ").append(McNativeVulkanProbe.quote(r.file()));
                sb.append(", \"frameFile\": ").append(McNativeVulkanProbe.quote(r.frameFile()));
                sb.append(", \"referenceFile\": ").append(McNativeVulkanProbe.quote(r.referenceFile()));
                sb.append(", \"referenceDepthFile\": ").append(McNativeVulkanProbe.quote(r.referenceDepthFile()));
                sb.append(", \"projectionAdjusted\": ").append(r.projectionAdjusted());
                sb.append(", \"farPlane\": ").append(Double.isFinite(r.farPlane()) ? Double.toString(r.farPlane()) : "null");
                sb.append(", \"engineId\": ").append(r.engineId());
                sb.append(", \"sceneBuild\": ").append(r.sceneBuild());
                sb.append(", \"atlasGeneration\": ").append(r.atlasGeneration());
                sb.append(", \"mcProjection\": ").append(floats(r.mcProjection()));
                sb.append(", \"projection\": ").append(floats(r.projection()));
                sb.append(", \"meshed\": ").append(r.meshed());
                sb.append(", \"meshedAtBuild\": ").append(r.meshedAtBuild());
                sb.append(", \"visibility\": ").append(McNativeVulkanProbe.quote(r.visibility()));
                sb.append(", \"iterationsRun\": ").append(r.iterations());
            } else {
                sb.append(", \"atlasState\": ").append(r.atlasState());
            }
            sb.append('}');
        }
        sb.append(results.isEmpty() ? "],\n" : "\n  ],\n");
        sb.append("  \"problems\": ").append(problems).append(",\n");
        sb.append("  \"firstProblem\": ").append(McNativeVulkanProbe.quote(firstProblem)).append(",\n");
        sb.append("  \"closeFailures\": ").append(closeFailures).append(",\n");
        sb.append("  \"leakedScenes\": ").append(leakedScenes).append(",\n");
        sb.append("  \"deviceDiverged\": ").append(deviceDiverged).append(",\n");
        sb.append("  \"readbacksInFlight\": ").append(readbacksInFlight).append(",\n");
        sb.append("  \"atlasReads\": ").append(McNativeAtlas.reads()).append(",\n");
        sb.append("  \"device\": ").append(McNativeVulkanProbe.quote(
            ownerDeviceSeen == 0 ? null : "0x" + Long.toHexString(ownerDeviceSeen))).append(",\n");
        sb.append("  \"notes\": [");
        for (int i = 0; i < notes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(McNativeVulkanProbe.quote(notes.get(i)));
        }
        sb.append("]\n}\n");
        return sb.toString();
    }

    private static String floats(float[] values) {
        if (values == null) return "null";
        var sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }

    private static void fail(String why) {
        problems++;
        if (firstProblem == null) firstProblem = why;
        note(why);
        writeEvidence();
    }

    private static void note(String message) {
        synchronized (NOTES) {
            if (NOTES.contains(message) || NOTES.size() >= 32) return;
            NOTES.add(message);
        }
        Logger.warn("[native-vk] " + message);
    }
}
