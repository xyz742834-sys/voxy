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
import me.cortex.voxy.client.core.vk.VkHostViewport;
import me.cortex.voxy.client.core.vk.VkRenderTarget;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import org.joml.Vector3f;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * <b>実セクションを、Minecraft 自身のカメラ行列で、Minecraft の colour と depth を LOAD した
 * パスに描く</b>実験 (native instance mode の上で)。
 *
 * <h2>何を測るか</h2>
 * {@link McNativeTerrainLoad} と同じ規則を、合成地形の代わりに<b>ワールドエンジンが取り込んだ
 * 実セクション</b> (レベル {@link #LEVEL}、半径 {@link #RADIUS}) で、<b>MC の投影とモデルビュー</b>
 * ({@link McNativeCamera}) を使って問う。画素ごとに、梯子の区間 (MC の深度) と、同じフレームに
 * 同じ行列で Voxy 自身の的に描いた参照の深度から期待を決める: 参照深度が区間の上端以上なら
 * Voxy の色が<b>そのまま</b>現れ、下端以下なら直前の読み戻しのまま、間なら決まらない、幾何の
 * 無い画素は変わらない。違反 0 を要求する。
 *
 * <h2>答える統合の問い</h2>
 * Voxy の実メッシュを MC の行列で描いたとき、その深度は MC の深度と<b>同じ空間</b>にあるか
 * (MC の描画距離の外の Voxy の地形は MC の空の上に現れ、MC の地形より遠いものは隠れるか)。
 * 描画距離の外の地形は {@code horizon} 段階 (x = 768 付近、Voxy だけが持つ) が帯に入れる。
 *
 * <h2>境界</h2>
 * 別フラグ {@code -Dvoxy.native.realload=true}、既定で無効。native instance mode が要る。
 * 梯子が標本を<b>この実験に渡した</b>フレームだけで動く (梯子は terrain-LOAD と交互に渡す)。
 * 渡された標本ごとに結果を 1 つ残す: 判定したか、なぜ判定しなかったか (理由は固定の集合)。
 * LoD の切り替え、トラバーサル、カリング、半透明は扱わない。
 */
public final class McNativeRealLoad implements Destroyable {
    public static final String FLAG = "voxy.native.realload";
    /** メッシュ化するレベルと半径: レベル 3 のセクションは 256 ブロック、半径 4 で ±1024 ブロック。 */
    static final int LEVEL = 3, RADIUS = 4;
    static final int MAX_QUADS = 2_000_000;
    static final int BUILD_BUDGET = 10;
    static final float[] CLEAR = {0.05f, 0.05f, 0.10f, 1.0f};
    static final int[] DECLARED_DEPTH_STATE = McNativeTerrainLoad.DECLARED_DEPTH_STATE;
    /** 判定しなかった理由 (gate はこの集合だけを受け付ける)。 */
    static final String JUDGED = "judged", NO_ENGINE = "no-world-engine",
        NO_CAMERA = "no-camera-this-frame", EXTENT = "camera-extent-mismatch",
        NOTHING_MESHED = "nothing-meshed", BUILD_BUDGET_SPENT = "build-budget-spent",
        ATLAS_PENDING = "atlas-pending";

    private static final long READBACK_BUDGET_BYTES = 40L << 20;
    private static final int FAILURE_BUDGET = 3;
    private static final int LEAK_BUDGET = 3;

    private static final List<String> NOTES = new ArrayList<>();
    private static final java.util.Map<Long, Result> RESULTS = new java.util.LinkedHashMap<>();
    private static final java.util.Map<Long, Pending> PENDING = new java.util.HashMap<>();
    private static McNativeRealLoad instance;
    private static long drawsRecorded, lastCaptureSeen = -1;
    private static int builds, problems, closeFailures, leakedScenes, readbacksInFlight;
    private static boolean attempted, deviceDiverged;
    private static String firstProblem;
    private static long ownerDeviceSeen;

    /**
     * 1 標本の結果。{@code status} が {@link #JUDGED} 以外なら計数とファイルは無い。
     *
     * @param projectionAdjusted MC の投影を 0..1 に直したか ({@link VkHostViewport#projectionForVulkan})
     * @param farPlane           使った投影の far 平面 (ブロック)
     * @param referenceSet       帯の中の参照の非背景画素数
     */
    public record Result(long at, String status, String stage, long[] counts, float minDepth,
                         float maxDepth, String file, String frameFile, String referenceFile,
                         String referenceDepthFile, Boolean projectionAdjusted, double farPlane,
                         int sceneLevel, int[] sceneCentre, int sceneSections, int sceneQuads,
                         int sceneDraws, long referenceSet, long cameraCapture,
                         // round-20 review R20-REAL-METADATA / -ENGINE-IDENTITY / -SKIP-PROVENANCE:
                         int engineId, int sceneBuild, int atlasGeneration, float[] mcProjection,
                         float[] projection, Skip skip, int excludedNear, int cutBlocks) {}

    /** What a skipped sample saw, so the gate can corroborate the reason. */
    public record Skip(long previousCapture, int[] cameraExtent, int[] frameExtent, int buildsSoFar,
                       int atlasState) {}

    /** 記録時に切り出しておく参照の帯 (両方の向き)。測定のコールバックが向きを選ぶ。 */
    private record Pending(int[][] rects, int[][] colour, float[][] depth, Result partial) {}

    private final VulkanDevice device;
    private final long ownerDevice;
    private final int colourFormat, depthFormat;
    private final McNativeRealScene scene;
    /** The engine the scene was meshed from (round-20 R20-REAL-ENGINE-IDENTITY) and the atlas it was baked with. */
    private final java.lang.ref.WeakReference<me.cortex.voxy.common.world.WorldEngine> engine;
    private final int atlasGeneration;
    private boolean destroyed;

    private McNativeRealLoad(VulkanDevice device, long ownerDevice, int colourFormat, int depthFormat,
                             McNativeRealScene scene, me.cortex.voxy.common.world.WorldEngine engine,
                             int atlasGeneration) {
        this.device = device;
        this.ownerDevice = ownerDevice;
        this.colourFormat = colourFormat;
        this.depthFormat = depthFormat;
        this.scene = scene;
        this.engine = new java.lang.ref.WeakReference<>(engine);
        this.atlasGeneration = atlasGeneration;
    }

    public static boolean enabled() { return Boolean.getBoolean(FLAG); }
    public static long drawsRecorded() { return drawsRecorded; }

    public static List<Result> results() {
        synchronized (NOTES) {
            return List.copyOf(RESULTS.values());
        }
    }

    /** レベル描画の末尾、梯子の後に<b>毎フレーム</b>呼ぶ。フラグが無効なら何もしない。 */
    public static void renderIfEnabled() {
        if (!enabled()) return;
        // the capture count must have advanced since the previous tail: the matrix is this frame's
        long capture = McNativeCamera.frame();
        long previousCapture = lastCaptureSeen;
        boolean fresh = capture != previousCapture;
        lastCaptureSeen = capture;
        try {
            render(fresh, capture, previousCapture);
        } catch (Throwable t) {
            fail("the real-LOAD experiment failed: " + t);
        }
    }

    private static void render(boolean freshCamera, long capture, long previousCapture) {
        long at = McNativeDepthLadder.takeSampleThisFrame(McNativeDepthLadder.EXPERIMENT_REAL_LOAD);
        if (at < 0) return;
        attempted = true;
        String stage = System.getProperty("voxy.harness.stage", "");
        if (!VoxyClient.nativeInstanceMode()) {
            fail("real-LOAD needs native instance mode (-Dvoxy.native.instance=true)");
            return;
        }
        var notes = new ArrayList<String>();
        VulkanDevice device = McNativeVulkan.device(notes);
        if (device == null) {
            notes.forEach(McNativeRealLoad::note);
            return;
        }
        long mcDevice = McNativeVulkan.vkDeviceHandle(device, notes);
        long ourDevice = adoptedDeviceHandle();
        if (mcDevice == 0 || ourDevice == 0 || mcDevice != ourDevice) {
            deviceDiverged = true;
            var stale = instance;
            instance = null;
            if (stale != null) { stale.destroyed = true; leakedScenes++; }
            fail("Minecraft's device 0x" + Long.toHexString(mcDevice) + " is not Voxy's adopted 0x"
                + Long.toHexString(ourDevice) + "; real-LOAD stops for the session");
            return;
        }
        if (deviceDiverged || problems >= FAILURE_BUDGET) return;
        ownerDeviceSeen = mcDevice;
        var mc = Minecraft.getInstance();
        var target = mc.gameRenderer.mainRenderTarget();
        GpuTextureView colour = target.getColorTextureView();
        GpuTextureView depth = target.getDepthTextureView();
        if (colour == null || depth == null) {
            fail("the main render target has no colour or depth view");
            return;
        }
        int format = VulkanConst.toVk(colour.texture().getFormat());
        int depthVk = VulkanConst.toVk(depth.texture().getFormat());
        int width = colour.getWidth(0), height = colour.getHeight(0);
        if (format != VkRenderTarget.FORMAT_COLOR || depthVk != VkRenderTarget.FORMAT_DEPTH) {
            fail("Minecraft's formats are colour " + format + " depth " + depthVk + ", not the "
                + VkRenderTarget.FORMAT_COLOR + "/" + VkRenderTarget.FORMAT_DEPTH + " Voxy's pipeline declares");
            return;
        }
        // ---- the reasons a sample is not judged (published, from a fixed set) ----
        var view = McNativeCamera.latest();
        if (view == null || !freshCamera) {
            skip(at, stage, NO_CAMERA, capture, new Skip(previousCapture, null, null, builds, -1));
            return;
        }
        if (view.width() != width || view.height() != height) {
            skip(at, stage, EXTENT, capture, new Skip(previousCapture, new int[] {view.width(), view.height()},
                new int[] {width, height}, builds, -1));
            return;
        }
        var world = mc.level == null ? null : WorldIdentifier.ofEngineNullable(mc.level);
        if (world == null || !world.isLive()) {
            skip(at, stage, NO_ENGINE, capture, new Skip(previousCapture, null, null, builds, -1));
            return;
        }
        // the model bakery samples Minecraft's block atlas; on Vulkan it is read through Blaze3D
        // (McNativeAtlas) and arrives in a later frame. Round-20 R20-ATLAS-RELOAD: if Minecraft
        // replaced the atlas texture (resource reload), read it again and rebake.
        McNativeAtlas.refreshIfReplaced();
        McNativeAtlas.requestOnce();
        if (McNativeAtlas.state() == McNativeAtlas.State.FAILED) {
            fail(McNativeAtlas.failure());
            return;
        }
        if (McNativeAtlas.state() != McNativeAtlas.State.READY) {
            skip(at, stage, ATLAS_PENDING, capture, new Skip(previousCapture, null, null, builds,
                McNativeAtlas.state().ordinal()));
            return;
        }

        int[] anchor = {VkHostViewport.sectionOf(view.x()), VkHostViewport.sectionOf(view.y()),
            VkHostViewport.sectionOf(view.z())};
        int[] centre = {anchor[0] >> LEVEL, anchor[1] >> LEVEL, anchor[2] >> LEVEL};
        var probe = instance;
        if (probe != null && (probe.destroyed || probe.scene.poisoned
                || !java.util.Arrays.equals(probe.scene.centre, centre)
                || probe.scene.width != width || probe.scene.height != height
                || probe.engine.get() != world
                || probe.atlasGeneration != McNativeAtlas.generation())) {
            retire(probe);
            probe = null;
        }
        if (probe == null) {
            if (builds >= BUILD_BUDGET || leakedScenes >= LEAK_BUDGET) {
                skip(at, stage, BUILD_BUDGET_SPENT, capture, new Skip(previousCapture, null, null, builds, -1));
                return;
            }
            builds++;
            int cut = mc.options.getEffectiveRenderDistance() * 16;
            var scene = McNativeRealScene.build(world, LEVEL, centre, RADIUS, MAX_QUADS, width, height,
                builds, new double[] {view.x(), view.y(), view.z()}, cut,
                McNativeRealLoad::logOnly, () -> closeFailures++);
            if (scene == null) {
                skip(at, stage, NOTHING_MESHED, capture, new Skip(previousCapture, null, null, builds, -1));
                return;
            }
            probe = new McNativeRealLoad(device, mcDevice, format, depthVk, scene, world,
                McNativeAtlas.generation());
            instance = probe;
        }

        // ---- Minecraft's own matrix, in Vulkan's 0..1 depth ----
        float[] sub = VkHostViewport.cameraSubPos(view.x(), view.y(), view.z(), anchor);
        var forward = VkHostViewport.forward(view.modelView(), new Vector3f());
        int[] ahead = {Math.round(forward.x * 4), Math.round(forward.y * 4), Math.round(forward.z * 4)};
        var vkProjection = VkHostViewport.projectionForVulkan(view.projection(), view.modelView(), sub, ahead);
        boolean adjusted = !vkProjection.equals(view.projection());
        if (VkHostViewport.depthStillOutOfRange(vkProjection, view.modelView(), sub, ahead)) {
            fail("Minecraft's projection does not put a point ahead of the camera into 0..1 depth"
                + " either as given or halved; its depth convention is not one this probe knows");
            return;
        }
        double farPlane = VkHostViewport.farPlaneDistance(vkProjection);
        float[] mvp = VkHostViewport.mvp(vkProjection, view.modelView(), sub);

        var ref = probe.scene.reference(mvp, anchor, sub, (int) (at & 0x7fffffff), CLEAR,
            McNativeRealLoad::fail, () -> leakedScenes++);
        if (ref == null) return;   // fail() already counted the problem

        // ⚠ LOAD both attachments; Voxy's own depth state (GREATER_OR_EQUAL, writes on)
        boolean drawn = false;
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "voxy native real load", colour, Optional.empty(), depth, OptionalDouble.empty())) {
            VkCommandBuffer cmd = McNativeVulkan.commandBufferOf(pass, notes);
            if (cmd == null) {
                notes.forEach(McNativeRealLoad::note);
                return;
            }
            probe.scene.renderer.assertColourFormatMatches(format);
            probe.scene.renderer.recordDrawsInRenderPass(cmd, probe.scene.drawCount);
            drawsRecorded++;
            drawn = true;
        }
        if (!drawn) return;
        // the reference's band, both orientations; the ladder's callback picks one
        int[][] rects = {McNativeDepthLadder.bandRect(width, height, false),
            McNativeDepthLadder.bandRect(width, height, true)};
        int[][] colours = new int[2][];
        float[][] depths = new float[2][];
        for (int o = 0; o < 2; o++) {
            int[] q = rects[o];
            int w = q[2] - q[0], h = q[3] - q[1];
            colours[o] = new int[w * h];
            depths[o] = new float[w * h];
            int i = 0;
            for (int y = q[1]; y < q[3]; y++) {
                for (int x = q[0]; x < q[2]; x++, i++) {
                    colours[o][i] = ref.colour()[y * width + x];
                    depths[o][i] = ref.depth()[y * width + x];
                }
            }
        }
        var s = probe.scene;
        float[] mcProjection = new float[16], usedProjection = new float[16];
        view.projection().get(mcProjection);
        vkProjection.get(usedProjection);
        var partial = new Result(at, JUDGED, stage, null, Float.NaN, Float.NaN, null, null, null, null,
            adjusted, farPlane, s.level, s.centre.clone(), s.sectionCount, s.totalQuads, s.drawCount,
            0, capture, s.engineId, s.buildOrdinal, probe.atlasGeneration, mcProjection,
            usedProjection, null, s.excludedNear, s.cutBlocks);
        synchronized (NOTES) {
            PENDING.put(at, new Pending(rects, colours, depths, partial));
        }
        requestReadback(colour, width, height, at);
    }

    private static void skip(long at, String stage, String reason, long capture, Skip why) {
        var r = new Result(at, reason, stage, null, Float.NaN, Float.NaN, null, null, null, null,
            null, Double.NaN, LEVEL, null, 0, 0, 0, 0, capture, 0, 0, 0, null, null, why, 0, 0);
        synchronized (NOTES) {
            RESULTS.put(at, r);
        }
        Logger.info("[native-vk] real load at draw " + at + " status=" + reason);
        writeEvidence();
    }

    private static void requestReadback(GpuTextureView colour, int width, int height, long at) {
        long bytes = (long) width * height * 4;
        if (bytes > READBACK_BUDGET_BYTES) {
            fail("the colour image is " + bytes + " bytes, over the readback budget");
            return;
        }
        GpuBuffer buffer = null;
        try {
            var gpu = RenderSystem.getDevice();
            buffer = gpu.createBuffer(() -> "voxy native real load readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            final GpuBuffer readback = buffer;
            readbacksInFlight++;
            gpu.createCommandEncoder().copyTextureToBuffer(colour.texture(), readback, 0,
                () -> measure(readback, width, height, at), 0);
            buffer = null;
        } catch (Throwable t) {
            fail("the real-LOAD readback could not be requested: " + t);
        } finally {
            if (buffer != null) {
                try { buffer.close(); } catch (Throwable t) { closeFailures++; }
            }
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
                fail("real-LOAD readback at draw " + at + " has no reference or ladder band (readback order)");
                return;
            }
            int[] q = band.sample().rect();
            int o = java.util.Arrays.equals(q, pending.rects()[0]) ? 0
                : java.util.Arrays.equals(q, pending.rects()[1]) ? 1 : -1;
            if (o < 0) {
                fail("real-LOAD at draw " + at + ": the ladder's rect " + java.util.Arrays.toString(q)
                    + " is neither orientation's band rect");
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
            String file = McNativeTerrainLoad.writeCrop(data, width, q, "native-real-load-" + at + ".ppm.gz");
            String frameFile = McNativeTerrainLoad.writeFrame(data, width, height,
                "native-real-load-frame-" + at + ".ppm.gz");
            String refName = "native-real-load-reference-" + at + ".ppm.gz";
            String depthName = "native-real-load-depth-" + at + ".f32.gz";
            boolean refWritten = writeReference(refRgb, refDepth, w, h, refName, depthName);
            var p = pending.partial();
            var result = new Result(at, JUDGED, p.stage(), counts, minDepth, maxDepth, file, frameFile,
                refWritten ? refName : null, refWritten ? depthName : null, p.projectionAdjusted(),
                p.farPlane(), p.sceneLevel(), p.sceneCentre(), p.sceneSections(), p.sceneQuads(),
                p.sceneDraws(), refSet, p.cameraCapture(), p.engineId(), p.sceneBuild(),
                p.atlasGeneration(), p.mcProjection(), p.projection(), null, p.excludedNear(),
                p.cutBlocks());
            synchronized (NOTES) {
                RESULTS.put(at, result);
            }
            if (McNativeTerrainLoad.violated(counts)) {
                problems++;
                String why = "real-LOAD at draw " + at + ": " + McNativeTerrainLoad.describe(counts);
                if (firstProblem == null) firstProblem = why;
                note(why);
            }
            Logger.info("[native-vk] real load at draw " + at + " status=judged "
                + McNativeTerrainLoad.describe(counts) + " depth=[" + minDepth + " " + maxDepth + "]");
            writeEvidence();
        } catch (Throwable t) {
            fail("the real-LOAD measurement failed: " + t);
        } finally {
            readbacksInFlight--;
            try { buffer.close(); } catch (Throwable t) {
                closeFailures++;
                note("could not close the real-LOAD readback buffer: " + t);
            }
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
            note("could not retain the real-LOAD reference " + refName + ": " + t);
            return false;
        }
    }

    // ---------------- lifetime (same discipline as the terrain probes) ----------------

    private static long adoptedDeviceHandle() {
        try { return VkContext.get().device.address(); } catch (Throwable t) { return 0; }
    }

    private boolean ownedByCurrentDevice() {
        try {
            if (VkContext.get().device.address() != this.ownerDevice) return false;
            long mc = McNativeVulkan.vkDeviceHandle(McNativeVulkan.device(), new ArrayList<>());
            return mc != 0 && mc == this.ownerDevice;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 退役は MC の提出寿命に預ける。預けられなければ壊さずに漏らし、数える。 */
    private static void retire(McNativeRealLoad probe) {
        if (instance == probe) instance = null;
        if (probe == null || probe.destroyed) return;
        if (probe.scene.poisoned || !probe.ownedByCurrentDevice()) {
            probe.destroyed = true;
            leakedScenes++;
            note("the real-LOAD scene cannot be shown unused on the current device; leaking it on purpose");
            return;
        }
        try {
            McNativeVulkan.encoder(probe.device).queueForDestroy(probe);
        } catch (Throwable t) {
            probe.destroyed = true;
            leakedScenes++;
            note("queueForDestroy refused the real-LOAD scene (" + t + "); leaking it on purpose");
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        // round-20 R20-DESTROY-ACCOUNTING: a child that could not be freed is a leak; count it
        closeFailures += this.scene.free();
    }

    public static void shutdownImmediate(org.lwjgl.vulkan.VkDevice waitedDevice) {
        McNativeAtlas.reset();
        var probe = instance;
        if (probe == null) return;
        instance = null;
        if (waitedDevice == null || waitedDevice.address() != probe.ownerDevice) {
            leakedScenes++;
            note("not destroying the real-LOAD scene: the idle wait was observed on another device;"
                + " leaking on purpose");
            writeEvidence();
            return;
        }
        probe.destroy();
        writeEvidence();
    }

    public static void shutdown() {
        var probe = instance;
        if (probe != null) retire(probe);
        // the 16 MiB atlas copy is dropped when the level renderer closes, the session ends
        // (ClientSessionEvents) or the device is released; a copy still in flight is discarded
        McNativeAtlas.reset();
        writeEvidence();
    }

    // ---------------- evidence ----------------

    private static void writeEvidence() {
        McNativeVulkanProbe.writeFile("native-real-load.json", json());
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
        sb.append("  \"builds\": ").append(builds).append(",\n");
        sb.append("  \"buildBudget\": ").append(BUILD_BUDGET).append(",\n");
        sb.append("  \"level\": ").append(LEVEL).append(",\n");
        sb.append("  \"radius\": ").append(RADIUS).append(",\n");
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
                sb.append(", \"sceneLevel\": ").append(r.sceneLevel());
                sb.append(", \"sceneCentre\": [").append(r.sceneCentre()[0]).append(", ")
                  .append(r.sceneCentre()[1]).append(", ").append(r.sceneCentre()[2]).append("]");
                sb.append(", \"sceneSections\": ").append(r.sceneSections());
                sb.append(", \"sceneQuads\": ").append(r.sceneQuads());
                sb.append(", \"sceneDraws\": ").append(r.sceneDraws());
                sb.append(", \"engineId\": ").append(r.engineId());
                sb.append(", \"sceneBuild\": ").append(r.sceneBuild());
                sb.append(", \"atlasGeneration\": ").append(r.atlasGeneration());
                sb.append(", \"mcProjection\": ").append(floats(r.mcProjection()));
                sb.append(", \"projection\": ").append(floats(r.projection()));
                sb.append(", \"excludedNear\": ").append(r.excludedNear());
                sb.append(", \"cutBlocks\": ").append(r.cutBlocks());
            }
            if (r.skip() != null) {
                var k = r.skip();
                sb.append(", \"previousCapture\": ").append(k.previousCapture());
                sb.append(", \"buildsSoFar\": ").append(k.buildsSoFar());
                sb.append(", \"atlasState\": ").append(k.atlasState());
                if (k.cameraExtent() != null) {
                    sb.append(", \"cameraExtent\": [").append(k.cameraExtent()[0]).append(", ")
                      .append(k.cameraExtent()[1]).append("]");
                    sb.append(", \"frameExtent\": [").append(k.frameExtent()[0]).append(", ")
                      .append(k.frameExtent()[1]).append("]");
                }
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
        sb.append("  \"atlasCloseFailures\": ").append(McNativeAtlas.closeFailures()).append(",\n");
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

    /** Build progress (not a problem): logged only. */
    private static void logOnly(String message) {
        Logger.info("[native-vk] real-LOAD: " + message);
    }

    private static void note(String message) {
        synchronized (NOTES) {
            if (NOTES.contains(message) || NOTES.size() >= 32) return;
            NOTES.add(message);
        }
        Logger.warn("[native-vk] " + message);
    }
}
