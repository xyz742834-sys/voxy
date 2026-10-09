package me.cortex.voxy.client.core.vk.mcnative;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.cortex.voxy.client.core.vk.SyntheticTerrain;
import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.VkDepth;
import me.cortex.voxy.client.core.vk.VkRenderTarget;
import me.cortex.voxy.client.core.vk.VkSceneUniform;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * <b>Voxy の本物の地形パイプラインを、Minecraft の colour と depth を LOAD したパスに描く</b>
 * 実験 — 梯子 ({@link McNativeDepthLadder}) が同じフレームで読み戻しを要求した<b>後</b>に、
 * 梯子の帯へ合成地形 ({@link SyntheticTerrain#depthSweep()}) を Voxy 自身の深度状態
 * ({@link VkDepth#COMPARE_OP} = GREATER_OR_EQUAL、<b>深度書き込み有効</b>) で描き、3 回目の
 * 読み戻しを画素ごとに照合する。
 *
 * <h2>何を測るか</h2>
 * 帯の各画素について、互いに独立な二つの測定から期待を決める:
 * <ul>
 *   <li>梯子の 1 回目の読み戻しが与える、MC の深度 d の区間 (lo, hi] (パレットの分類)</li>
 *   <li>同じシーン・同じ視点・同じ寸法を Voxy 自身の的に描いた参照の深度 d_V (D32 の読み戻し)</li>
 * </ul>
 * Voxy の断片は d_V ≥ d のとき通る。d_V ≥ hi なら必ず<b>現れる</b> (その画素は参照と同じ
 * RGB)、d_V ≤ lo なら必ず<b>現れない</b> (直前の読み戻しと同じ RGB)、間なら決まらない
 * (数えるだけ)。参照に幾何の無い画素は変わってはならない。違反は 0 でなければならず、
 * 両方の決まる種類の画素を持つ標本が少なくとも 1 つ要る。
 *
 * <h2>⚠ 梯子とは別の probe である (handoff の規則 3)</h2>
 * 梯子は MC の深度を決して書かない。このパスは書く — ただし同じフレームで梯子の読み戻しが
 * 要求された後だけなので、梯子の標本は MC 自身の深度のままである (MC は毎フレーム深度を
 * クリアする)。別フラグ {@code -Dvoxy.native.terrainload=true}、既定で無効、梯子が無ければ
 * 何もしない。
 *
 * <h2>何を言わないか</h2>
 * 実データの地形ではない (合成)。深度の<b>目盛り</b>は「参照の深度値が MC の同じ深度値と
 * 同じ側に落ちる」以上は言わない。GPU が比較を実行したことの認証はしない (他の証跡と同じ)。
 */
public final class McNativeTerrainLoad implements Destroyable {
    /**
     * round-24 R24-RETIRED-CONTEXT: scenes queued on Minecraft's destroy queue and not destroyed
     * yet. Minecraft may drain that queue after Voxy's context is released; the immediate shutdown
     * (after its device-idle wait) destroys these itself, so Minecraft's later destroy() is a no-op.
     */
    private static final java.util.List<McNativeTerrainLoad> QUEUED = new java.util.ArrayList<>();

    public static final String FLAG = "voxy.native.terrainload";
    /** シーンの名前 (gate が固定する)。 */
    public static final String SCENE = "depthSweep";
    /** 視点: z ≈ 0、y = 8 から +Z を少し見下ろす。パネル (z = 32·k) は重ならない階段になる。 */
    static final float[] EYE = {80f, 8f, 0f};
    static final float[] CENTRE = {80f, 2f, 300f};
    static final float[] UP = {0f, 1f, 0f};
    static final float FOV_DEGREES = 60f, NEAR = 0.1f, FAR = 2000f;
    /** 帯の中へ足跡を収めるときの余白 (帯の幅/高さに対する比)。 */
    static final float FIT_MARGIN = 0.05f;
    /** 背景 (terrain probe と同じ)。 */
    static final float[] CLEAR = {0.05f, 0.05f, 0.10f, 1.0f};
    /**
     * 宣言した深度状態 {compareOp, testEnable, writeEnable}: {@link VkTerrainRenderer} のパイプ
     * ラインは {@code depthTest(true).depthWrite(true).depthCompare(VkDepth.COMPARE_OP)} で作る。
     * ⚠ 梯子の {@code pipelineStates} と違い、作成後に構造体から読み戻してはいない (宣言)。
     */
    static final int[] DECLARED_DEPTH_STATE = {VkDepth.COMPARE_OP, 1, 1};

    private static final long READBACK_BUDGET_BYTES = 40L << 20;
    private static final int FAILURE_BUDGET = 3;
    private static final int LEAK_BUDGET = 3;

    private static final List<String> NOTES = new ArrayList<>();
    private static final java.util.Map<Long, Result> RESULTS = new java.util.LinkedHashMap<>();
    private static final java.util.Set<String> REFERENCE_FILES = new java.util.HashSet<>();
    private static McNativeTerrainLoad instance;
    /**
     * 最後に組み立てたシーンの事実 (寸法、フォーマット、行列、描画数、参照画素数、device)。
     * 証跡はこれを出す: 退役 (resize、終了) で {@link #instance} が無くなっても、組み立てた
     * 事実は消えない (実測: 終了後に書いた報告が built=false, referenceSet=0 になった)。
     */
    private static boolean builtOnce;
    private static int builtWidth, builtHeight, builtColourFormat, builtDepthFormat, builtDrawCount;
    private static long builtReferenceSet, builtDevice;
    private static float[] builtMvp;
    private static long drawsRecorded;
    private static boolean attempted;
    private static int problems;
    private static String firstProblem;
    private static int closeFailures;
    private static int leakedScenes;
    private static boolean deviceDiverged;
    private static int readbacksInFlight;

    /** 判定の計数の添字 ({@link #judge})。 */
    static final int GEOMETRY = 0, NO_GEOMETRY = 1, EXPECT_VISIBLE = 2, EXPECT_HIDDEN = 3,
        UNDETERMINED = 4, VISIBLE = 5, HIDDEN = 6, AMBIGUOUS = 7, OTHER = 8,
        VISIBLE_WHERE_HIDDEN = 9, HIDDEN_WHERE_VISIBLE = 10, CHANGED_WHERE_NO_GEOMETRY = 11,
        COUNTS = 12;
    static final String[] COUNT_NAMES = {"geometry", "noGeometry", "expectVisible",
        "expectHidden", "undetermined", "visible", "hidden", "ambiguous", "other",
        "visibleWhereHidden", "hiddenWhereVisible", "changedWhereNoGeometry"};

    /**
     * 1 標本の結果。
     *
     * @param at                 梯子の標本の draw 本数 (3 つの読み戻しを結ぶ)
     * @param counts             {@link #COUNT_NAMES} の順の画素数
     * @param minDepth           帯の中の参照深度の最小 (幾何のある画素)、無ければ NaN
     * @param maxDepth           同、最大
     * @param file               3 回目の読み戻しの帯の crop (PPM gzip)
     * @param frameFile          3 回目の読み戻しの全フレームの 1/4 サムネイル
     * @param referenceFile      参照の色の同じ帯の crop (寸法と矩形ごとに 1 つ)
     * @param referenceDepthFile 参照の深度の同じ帯 (float32 LE、ヘッダ付き、gzip)
     */
    public record Result(long at, long[] counts, float minDepth, float maxDepth, String file,
                         String frameFile, String referenceFile, String referenceDepthFile) {}

    // ---------------- 保持するもの ----------------

    private final VulkanDevice device;
    private final long ownerDevice;
    private final int colourFormat, depthFormat, width, height;
    private final McNativeTerrainScene scene;
    private boolean destroyed;

    private McNativeTerrainLoad(VulkanDevice device, long ownerDevice, int colourFormat,
                                int depthFormat, int width, int height, McNativeTerrainScene scene) {
        this.device = device;
        this.ownerDevice = ownerDevice;
        this.colourFormat = colourFormat;
        this.depthFormat = depthFormat;
        this.width = width;
        this.height = height;
        this.scene = scene;
    }

    // ---------------- 入口 ----------------

    public static boolean enabled() { return Boolean.getBoolean(FLAG); }
    public static boolean attempted() { return attempted; }
    public static long drawsRecorded() { return drawsRecorded; }

    public static List<Result> results() {
        synchronized (NOTES) {
            return List.copyOf(RESULTS.values());
        }
    }

    /** レベル描画の末尾、梯子の後に呼ぶ。フラグが無効なら<b>何もしない</b>。 */
    public static void renderIfEnabled() {
        if (!enabled()) return;
        try {
            render();
        } catch (Throwable t) {
            note("the terrain-LOAD experiment failed: " + t);
            var trace = t.getStackTrace();
            for (int i = 0; i < Math.min(6, trace.length); i++) note("  at " + trace[i]);
        }
    }

    private static void render() {
        // 梯子がこのフレームで標本を取ったときだけ動く。取っていなければ何も触らない。
        long at = McNativeDepthLadder.takeSampleThisFrame(McNativeDepthLadder.EXPERIMENT_TERRAIN_LOAD);
        if (at < 0) return;
        var notes = new ArrayList<String>();
        VulkanDevice device = McNativeVulkan.device(notes);
        if (device == null) {
            notes.forEach(McNativeTerrainLoad::note);
            return;
        }
        if (!adoptedContextReady()) {
            note("Voxy's context has not adopted Minecraft's device, so there is nothing to"
                + " record the terrain pipeline onto (set -Dvoxy.native.adopt=true)");
            return;
        }
        // ⚠ round-6 review R6-TERRAIN-DEVICE: 基準は MC のいまの device。食い違えば何もしない。
        long mcDevice = McNativeVulkan.vkDeviceHandle(device, notes);
        long ourDevice = adoptedDeviceHandle();
        if (mcDevice == 0 || ourDevice == 0 || mcDevice != ourDevice) {
            notes.forEach(McNativeTerrainLoad::note);
            note("Voxy's adopted context holds device 0x" + Long.toHexString(ourDevice)
                + " but Minecraft is now using 0x" + Long.toHexString(mcDevice)
                + "; refusing to record Voxy's resources into another device's command buffer."
                + " The terrain-LOAD experiment stops for the rest of this session.");
            deviceDiverged = true;
            var stale = instance;
            instance = null;
            if (stale != null) {
                stale.destroyed = true;
                leakedScenes++;
            }
            writeEvidence();
            return;
        }
        if (deviceDiverged) return;
        var mc = Minecraft.getInstance();
        if (mc == null || mc.gameRenderer == null) return;
        var target = mc.gameRenderer.mainRenderTarget();
        if (target == null) return;
        GpuTextureView colour = target.getColorTextureView();
        GpuTextureView depth = target.getDepthTextureView();
        if (colour == null || depth == null) {
            note("the main render target has no colour or depth view, so the terrain pipeline"
                + " cannot be recorded against Minecraft's depth");
            return;
        }
        int format = VulkanConst.toVk(colour.texture().getFormat());
        int depthVk = VulkanConst.toVk(depth.texture().getFormat());
        int width = colour.getWidth(0), height = colour.getHeight(0);
        if (width <= 0 || height <= 0) return;
        if (format != VkRenderTarget.FORMAT_COLOR) {
            note("Minecraft's colour format is " + format + " but Voxy's terrain pipeline and"
                + " reference use " + VkRenderTarget.FORMAT_COLOR + "; the pixels could not be"
                + " compared");
            return;
        }
        // ⚠ 地形パイプラインは深度アタッチメントのフォーマットを宣言して作られている
        // (VkRenderTarget.FORMAT_DEPTH = D32_SFLOAT)。MC のものと違えば描けない。
        if (depthVk != VkRenderTarget.FORMAT_DEPTH) {
            note("Minecraft's depth format is " + depthVk + " but Voxy's terrain pipeline declares "
                + VkRenderTarget.FORMAT_DEPTH + "; the pipeline cannot be used in Minecraft's pass");
            return;
        }

        var probe = instance;
        if (probe != null && probe.destroyed) {
            if (instance == probe) instance = null;
            probe = null;
        }
        if (probe != null && (probe.device != device || probe.colourFormat != format
                || probe.depthFormat != depthVk || probe.width != width || probe.height != height)) {
            retire(probe);
            probe = null;
        }
        if (probe == null) {
            if (leakedScenes >= LEAK_BUDGET) {
                note("already leaked " + leakedScenes + " terrain scene(s); not building another");
                return;
            }
            attempted = true;
            var scene = McNativeTerrainScene.build(format, width, height,
                SyntheticTerrain.depthSweep(), mvp(width, height), cameraSection(), true, CLEAR,
                McNativeTerrainLoad::note, () -> leakedScenes++);
            if (scene == null) return;
            if (scene.set == 0 || scene.depth == null) {
                note("Voxy's own target drew nothing (or no depth was read), so there is no"
                    + " reference to judge Minecraft's frame against");
                scene.free();
                return;
            }
            probe = new McNativeTerrainLoad(device, mcDevice, format, depthVk, width, height, scene);
            instance = probe;
            synchronized (NOTES) {
                builtOnce = true;
                builtWidth = width;
                builtHeight = height;
                builtColourFormat = format;
                builtDepthFormat = depthVk;
                builtDrawCount = scene.drawCount;
                builtReferenceSet = scene.set;
                builtDevice = mcDevice;
                builtMvp = scene.mvp.clone();
            }
            Logger.info("[native-vk] terrain-LOAD scene built: " + scene.set + " reference pixels,"
                + " " + scene.drawCount + " draw(s), depth state declared "
                + java.util.Arrays.toString(DECLARED_DEPTH_STATE));
        }
        if (problems >= FAILURE_BUDGET) return;

        // ⚠ colour も depth も LOAD。MC のシーンと深度の上に、Voxy の深度状態で描く (書く)。
        boolean drawn = false;
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "voxy native terrain load", colour, Optional.empty(),
                depth, OptionalDouble.empty())) {
            VkCommandBuffer cmd = McNativeVulkan.commandBufferOf(pass, notes);
            if (cmd == null) {
                notes.forEach(McNativeTerrainLoad::note);
                return;
            }
            probe.scene.renderer.assertColourFormatMatches(format);
            probe.scene.renderer.recordDrawsInRenderPass(cmd, probe.scene.drawCount);
            drawsRecorded++;
            drawn = true;
        }
        // ⚠ コピーはパスの<b>外</b>で要求する (梯子の実測: 中では VUID と中身のごみ)。
        if (drawn) requestReadback(probe, colour, width, height, at);
        if (drawsRecorded == 1) writeEvidence();
    }

    // ---------------- 視点 ----------------

    /** 透視投影 × 視点、まだ帯に合わせていない。 */
    static float[] view(int width, int height) {
        return VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(FOV_DEGREES), (float) width / height,
                NEAR, FAR),
            VkSceneUniform.lookAt(EYE, CENTRE, UP));
    }

    /** カメラのいるセクション (面マスクの基準)。 */
    static int[] cameraSection() {
        return new int[] {Math.floorDiv((int) Math.floor(EYE[0]), 32),
            Math.floorDiv((int) Math.floor(EYE[1]), 32), Math.floorDiv((int) Math.floor(EYE[2]), 32)};
    }

    /**
     * セルの 8 隅を {@code m} で投影した NDC の包含矩形と深度の範囲:
     * {@code {x0, y0, x1, y1, zMin, zMax}}。
     */
    static float[] footprint(float[] m, List<float[]> cells) {
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY,
            x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY,
            z0 = Float.POSITIVE_INFINITY, z1 = Float.NEGATIVE_INFINITY;
        for (float[] c : cells) {
            for (int i = 0; i < 8; i++) {
                float[] p = VkSceneUniform.project(m, c[(i & 1) == 0 ? 0 : 3],
                    c[(i & 2) == 0 ? 1 : 4], c[(i & 4) == 0 ? 2 : 5]);
                x0 = Math.min(x0, p[0]); x1 = Math.max(x1, p[0]);
                y0 = Math.min(y0, p[1]); y1 = Math.max(y1, p[1]);
                z0 = Math.min(z0, p[2]); z1 = Math.max(z1, p[2]);
            }
        }
        return new float[] {x0, y0, x1, y1, z0, z1};
    }

    /**
     * NDC のアフィン変換 (クリップ空間では {@code x' = sx·x + tx·w}) で、投影した足跡を梯子の帯
     * {@link McNativeDepthLadder#band()} の内側 (余白 {@link #FIT_MARGIN}) に写す。深度は触らない。
     */
    static float[] fit(float[] pv, List<float[]> cells) {
        float[] f = footprint(pv, cells);
        float[] band = McNativeDepthLadder.band();   // {x0, y0, x1, y1} (NDC、GL 規約の y)
        float bx0 = Math.min(band[0], band[2]), bx1 = Math.max(band[0], band[2]);
        float by0 = Math.min(band[1], band[3]), by1 = Math.max(band[1], band[3]);
        float mx = (bx1 - bx0) * FIT_MARGIN, my = (by1 - by0) * FIT_MARGIN;
        float sx = (bx1 - bx0 - 2 * mx) / (f[2] - f[0]);
        float sy = (by1 - by0 - 2 * my) / (f[3] - f[1]);
        float tx = (bx0 + mx) - sx * f[0];
        float ty = (by0 + my) - sy * f[1];
        float[] F = new float[16];
        F[0] = sx;
        F[5] = sy;
        F[10] = 1f;
        F[15] = 1f;
        F[12] = tx;
        F[13] = ty;
        return VkSceneUniform.mul(F, pv);
    }

    /** 固定する視点: {@link #depthSweepCells()} の足跡を帯に合わせた行列。 */
    static float[] mvp(int width, int height) {
        return fit(view(width, height), depthSweepCells());
    }

    static List<float[]> depthSweepCells() {
        return SyntheticTerrain.depthSweep().opaqueQuadCells();
    }

    // ---------------- 判定 ----------------

    /**
     * 梯子の分類と Voxy の深度から期待を決める: 1 = 現れる、-1 = 現れない、0 = 決まらない。
     *
     * <p>梯子の分類が与える MC の深度 d の区間: LOW は d < z₀、BASE は d = z₀、段 i は
     * z_i < d ≤ z_{i+1} (最後の段は z₇ < d ≤ 1)。Voxy の断片は d_V ≥ d で通るので、
     * d_V が区間の上端以上なら必ず通り、下端以下なら必ず落ちる。
     */
    static int expectation(int ladderClass, float voxyDepth) {
        float[] z = McNativeDepthLadder.depths();
        // Minecraft's depth is exactly the clear value 0: any Voxy depth passes GREATER_OR_EQUAL
        if (ladderClass == McNativeDepthLadder.CLEAR) return 1;
        if (ladderClass == McNativeDepthLadder.LOW) return voxyDepth >= z[0] ? 1 : 0;
        if (ladderClass == McNativeDepthLadder.BASE) return voxyDepth >= z[0] ? 1 : -1;
        int rung = ladderClass - McNativeDepthLadder.RUNG0;
        if (rung < 0 || rung >= McNativeDepthLadder.RUNGS) return 0;
        if (voxyDepth <= z[rung]) return -1;
        float hi = rung + 1 < McNativeDepthLadder.RUNGS ? z[rung + 1] : 1.0f;
        return voxyDepth >= hi ? 1 : 0;
    }

    /**
     * 帯の全画素を判定して {@link #COUNT_NAMES} の順に数える。
     *
     * @param classes  梯子の分類 (画素ごと 1 バイト)
     * @param before   直前の読み戻しの RGB (画素ごと 3 バイト)
     * @param after    3 回目の読み戻しの RGB (画素ごと 3 バイト)
     * @param refRgb   参照の RGB ({@code r | g << 8 | b << 16})、帯の順
     * @param refDepth 参照の深度、帯の順 ({@link VkDepth#CLEAR} = 幾何なし)
     */
    static long[] judge(byte[] classes, byte[] before, byte[] after, int[] refRgb, float[] refDepth) {
        return judge(classes, before, after, refRgb, refDepth, false);
    }

    /**
     * Voxy's GL composition rule (2026-10-10): Voxy's terrain appears only where Minecraft drew
     * nothing — the GL path builds a stencil from Minecraft's depth and draws only where it is the
     * clear value. A composite under that rule must show Voxy exactly on CLEAR pixels and leave
     * every other pixel as it was, whatever Voxy's depth.
     */
    static int expectationClearOnly(int ladderClass) {
        return ladderClass == McNativeDepthLadder.CLEAR ? 1 : -1;
    }

    /** @param clearOnly judge by {@link #expectationClearOnly} instead of the depth-test rule */
    static long[] judge(byte[] classes, byte[] before, byte[] after, int[] refRgb, float[] refDepth,
                        boolean clearOnly) {
        long[] c = new long[COUNTS];
        int n = classes.length;
        for (int i = 0; i < n; i++) {
            int i3 = i * 3;
            boolean sameAsBefore = after[i3] == before[i3] && after[i3 + 1] == before[i3 + 1]
                && after[i3 + 2] == before[i3 + 2];
            if (!(refDepth[i] > VkDepth.CLEAR)) {
                c[NO_GEOMETRY]++;
                if (!sameAsBefore) c[CHANGED_WHERE_NO_GEOMETRY]++;
                continue;
            }
            c[GEOMETRY]++;
            int rgb = refRgb[i];
            boolean sameAsRef = (after[i3] & 0xFF) == (rgb & 0xFF)
                && (after[i3 + 1] & 0xFF) == ((rgb >> 8) & 0xFF)
                && (after[i3 + 2] & 0xFF) == ((rgb >> 16) & 0xFF);
            int expect = clearOnly ? expectationClearOnly(classes[i]) : expectation(classes[i], refDepth[i]);
            if (expect > 0) c[EXPECT_VISIBLE]++;
            else if (expect < 0) c[EXPECT_HIDDEN]++;
            else c[UNDETERMINED]++;
            if (sameAsRef && sameAsBefore) {
                c[AMBIGUOUS]++;
            } else if (sameAsRef) {
                c[VISIBLE]++;
                if (expect < 0) c[VISIBLE_WHERE_HIDDEN]++;
            } else if (sameAsBefore) {
                c[HIDDEN]++;
                if (expect > 0) c[HIDDEN_WHERE_VISIBLE]++;
            } else {
                c[OTHER]++;
            }
        }
        return c;
    }

    /** 違反があるか。 */
    static boolean violated(long[] c) {
        return c[OTHER] != 0 || c[VISIBLE_WHERE_HIDDEN] != 0 || c[HIDDEN_WHERE_VISIBLE] != 0
            || c[CHANGED_WHERE_NO_GEOMETRY] != 0;
    }

    // ---------------- 読み戻し ----------------

    private static void requestReadback(McNativeTerrainLoad probe, GpuTextureView colour,
                                        int width, int height, long at) {
        long bytes = (long) width * height * 4;
        if (bytes > READBACK_BUDGET_BYTES) {
            fail("the colour image is " + bytes + " bytes, over the " + READBACK_BUDGET_BYTES
                + " byte budget; not measuring rather than allocating that much");
            return;
        }
        GpuBuffer buffer = null;
        try {
            var gpu = RenderSystem.getDevice();
            buffer = gpu.createBuffer(() -> "voxy native terrain load readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            final GpuBuffer readback = buffer;
            readbacksInFlight++;
            gpu.createCommandEncoder().copyTextureToBuffer(colour.texture(), readback, 0,
                () -> measure(probe, readback, width, height, at), 0);
            buffer = null;
        } catch (Throwable t) {
            fail("the terrain-LOAD readback could not be requested: " + t);
        } finally {
            if (buffer != null) {
                try {
                    buffer.close();
                } catch (Throwable t) {
                    closeFailures++;
                    note("could not close the unregistered terrain-LOAD buffer: " + t);
                }
            }
        }
    }

    private static void measure(McNativeTerrainLoad probe, GpuBuffer buffer, int width, int height,
                                long at) {
        try (var view = new GpuBufferSlice(buffer, 0, buffer.size()).map(true, false)) {
            var data = view.data();
            var band = McNativeDepthLadder.takeBand(at);
            if (band == null) {
                fail("terrain-LOAD readback at draw " + at + " has no ladder band to compare with"
                    + " (readback order)");
                return;
            }
            var sample = band.sample();
            if (sample.targetWidth() != width || sample.targetHeight() != height
                    || probe.width != width || probe.height != height) {
                fail("terrain-LOAD readback at draw " + at + " is " + width + "x" + height
                    + " but the ladder sample is " + sample.targetWidth() + "x"
                    + sample.targetHeight() + " and the scene " + probe.width + "x" + probe.height);
                return;
            }
            int[] q = sample.rect();
            int w = q[2] - q[0], h = q[3] - q[1];
            int n = w * h;
            byte[] after = new byte[n * 3];
            int[] refRgb = new int[n];
            float[] refDepth = new float[n];
            float minDepth = Float.NaN, maxDepth = Float.NaN;
            int i = 0;
            for (int y = q[1]; y < q[3]; y++) {
                long rowBase = (long) y * width * 4;
                for (int x = q[0]; x < q[2]; x++, i++) {
                    long p = rowBase + (long) x * 4;
                    after[i * 3] = data.get((int) p);
                    after[i * 3 + 1] = data.get((int) p + 1);
                    after[i * 3 + 2] = data.get((int) p + 2);
                    refRgb[i] = probe.scene.colour[y * width + x];
                    float d = probe.scene.depth[y * width + x];
                    refDepth[i] = d;
                    if (d > VkDepth.CLEAR) {
                        minDepth = Float.isNaN(minDepth) ? d : Math.min(minDepth, d);
                        maxDepth = Float.isNaN(maxDepth) ? d : Math.max(maxDepth, d);
                    }
                }
            }
            long[] counts = judge(band.classes(), band.pixels(), after, refRgb, refDepth);
            String file = writeCrop(data, width, q, "native-terrain-load-" + at + ".ppm.gz");
            String frameFile = writeFrame(data, width, height,
                "native-terrain-load-frame-" + at + ".ppm.gz");
            String refName = "native-terrain-load-reference-" + width + "x" + height + "-"
                + q[0] + "-" + q[1] + "-" + q[2] + "-" + q[3] + ".ppm.gz";
            String depthName = "native-terrain-load-depth-" + width + "x" + height + "-"
                + q[0] + "-" + q[1] + "-" + q[2] + "-" + q[3] + ".f32.gz";
            String referenceFile = writeReference(probe.scene, q, refName, depthName);
            String referenceDepthFile = referenceFile == null ? null : depthName;
            var result = new Result(at, counts, minDepth, maxDepth, file, frameFile,
                referenceFile, referenceDepthFile);
            synchronized (NOTES) {
                RESULTS.put(at, result);
            }
            if (violated(counts)) {
                problems++;
                String why = "terrain-LOAD at draw " + at + ": " + describe(counts);
                if (firstProblem == null) firstProblem = why;
                note(why);
            }
            Logger.info("[native-vk] terrain load at draw " + at + " " + describe(counts)
                + " depth=[" + minDepth + " " + maxDepth + "]");
            writeEvidence();
        } catch (Throwable t) {
            fail("the terrain-LOAD measurement failed: " + t);
        } finally {
            readbacksInFlight--;
            try {
                buffer.close();
            } catch (Throwable t) {
                closeFailures++;
                note("could not close the terrain-LOAD readback buffer: " + t);
            }
        }
    }

    static String describe(long[] c) {
        var sb = new StringBuilder();
        for (int k = 0; k < COUNTS; k++) {
            if (k > 0) sb.append(' ');
            sb.append(COUNT_NAMES[k]).append('=').append(c[k]);
        }
        return sb.toString();
    }

    // ---------------- 証跡ファイル ----------------

    static String writeCrop(ByteBuffer data, int width, int[] q, String name) {
        String dir = System.getProperty("voxy.harness.output");
        if (dir == null || dir.isBlank()) return null;
        int w = q[2] - q[0], h = q[3] - q[1];
        if (w <= 0 || h <= 0) return null;
        try {
            var header = ("P6\n" + w + " " + h + "\n255\n").getBytes(StandardCharsets.US_ASCII);
            byte[] body = new byte[w * h * 3];
            int into = 0;
            for (int y = q[1]; y < q[3]; y++) {
                long rowBase = (long) y * width * 4;
                for (int x = q[0]; x < q[2]; x++) {
                    long src = rowBase + (long) x * 4;
                    body[into++] = data.get((int) src);
                    body[into++] = data.get((int) src + 1);
                    body[into++] = data.get((int) src + 2);
                }
            }
            McNativeVulkanProbe.writeGzipFileBytes(name, header, body);
            return name;
        } catch (Throwable t) {
            note("could not retain the terrain-LOAD crop " + name + ": " + t);
            return null;
        }
    }

    /** 梯子と同じ縮尺・同じ平均 ({@link McNativeDepthLadder#FRAME_SCALE})。gate は同じ式で照合する。 */
    static String writeFrame(ByteBuffer data, int width, int height, String name) {
        String dir = System.getProperty("voxy.harness.output");
        if (dir == null || dir.isBlank()) return null;
        int scale = McNativeDepthLadder.FRAME_SCALE;
        int w = width / scale, h = height / scale;
        if (w <= 0 || h <= 0) return null;
        try {
            var header = ("P6\n" + w + " " + h + "\n255\n").getBytes(StandardCharsets.US_ASCII);
            byte[] body = new byte[w * h * 3];
            int into = 0;
            for (int by = 0; by < h; by++) {
                for (int bx = 0; bx < w; bx++) {
                    int r = 0, g = 0, b = 0;
                    for (int y = by * scale; y < (by + 1) * scale; y++) {
                        long rowBase = (long) y * width * 4;
                        for (int x = bx * scale; x < (bx + 1) * scale; x++) {
                            long src = rowBase + (long) x * 4;
                            r += data.get((int) src) & 0xFF;
                            g += data.get((int) src + 1) & 0xFF;
                            b += data.get((int) src + 2) & 0xFF;
                        }
                    }
                    int count = scale * scale;
                    body[into++] = (byte) (r / count);
                    body[into++] = (byte) (g / count);
                    body[into++] = (byte) (b / count);
                }
            }
            McNativeVulkanProbe.writeGzipFileBytes(name, header, body);
            return name;
        } catch (Throwable t) {
            note("could not retain the terrain-LOAD frame thumbnail " + name + ": " + t);
            return null;
        }
    }

    /**
     * 参照の色 (PPM) と深度 (ヘッダ {@code VXF32\n<w> <h>\n} + float32 LE、行ごと) の帯の
     * crop を、寸法と矩形ごとに 1 度だけ書く。
     */
    private static String writeReference(McNativeTerrainScene scene, int[] q, String refName,
                                         String depthName) {
        String dir = System.getProperty("voxy.harness.output");
        if (dir == null || dir.isBlank()) return null;
        int w = q[2] - q[0], h = q[3] - q[1];
        if (w <= 0 || h <= 0) return null;
        synchronized (NOTES) {
            if (REFERENCE_FILES.contains(refName)) return refName;
        }
        try {
            McNativeVulkanProbe.writeGzipFileBytes(refName,
                ("P6\n" + w + " " + h + "\n255\n").getBytes(StandardCharsets.US_ASCII),
                scene.colourBytes(q));
            McNativeVulkanProbe.writeGzipFileBytes(depthName,
                ("VXF32\n" + w + " " + h + "\n").getBytes(StandardCharsets.US_ASCII),
                scene.depthBytes(q));
            synchronized (NOTES) {
                REFERENCE_FILES.add(refName);
            }
            return refName;
        } catch (Throwable t) {
            note("could not retain the terrain-LOAD reference " + refName + ": " + t);
            return null;
        }
    }

    private static void fail(String why) {
        problems++;
        if (firstProblem == null) firstProblem = why;
        note(why);
        writeEvidence();
    }

    // ---------------- 寿命 ----------------

    private static boolean adoptedContextReady() {
        try {
            return VkContext.isAdopted();
        } catch (Throwable t) {
            return false;
        }
    }

    private static long adoptedDeviceHandle() {
        try {
            return VkContext.get().device.address();
        } catch (Throwable t) {
            return 0;
        }
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
    private static void retire(McNativeTerrainLoad probe) {
        if (instance == probe) instance = null;
        if (probe == null || probe.destroyed) return;
        if (!probe.ownedByCurrentDevice()) {
            note("the terrain-LOAD scene belongs to a device that is no longer current; leaking"
                + " it on purpose rather than destroying it against the wrong device");
            probe.destroyed = true;
            leakedScenes++;
            return;
        }
        try {
            McNativeVulkan.encoder(probe.device).queueForDestroy(probe);
            QUEUED.add(probe);
        } catch (Throwable t) {
            probe.destroyed = true;
            leakedScenes++;
            note("queueForDestroy refused the terrain-LOAD scene (" + t + "); leaking it on"
                + " purpose rather than destroying something that may still be in use");
        }
    }

    /** MC の破棄待ち行列から呼ばれる。<b>ここで初めて</b>資源を解放する。 */
    @Override
    public void destroy() {
        QUEUED.remove(this);
        if (this.destroyed) return;
        this.destroyed = true;
        this.scene.free();
    }

    public static void shutdownImmediate(org.lwjgl.vulkan.VkDevice waitedDevice) {
        var owned = new java.util.ArrayList<McNativeTerrainLoad>(QUEUED);
        QUEUED.clear();
        if (instance != null) owned.add(instance);
        instance = null;
        for (var probe : owned) {
            if (probe.destroyed) continue;
            if (waitedDevice == null || waitedDevice.address() != probe.ownerDevice) {
                probe.destroyed = true;
                leakedScenes++;
                note("not destroying the terrain-LOAD scene: the idle wait was observed on another device;"
                    + " leaking on purpose");
                continue;
            }
            probe.destroy();
        }
        if (!owned.isEmpty()) writeEvidence();   // as before: nothing owned, nothing to report
    }

    /** MC のレベル描画が閉じるとき。提出の完了を観測していないので<b>ここでは壊さない</b>。 */
    public static void shutdown() {
        var probe = instance;
        if (probe == null) return;
        retire(probe);
        writeEvidence();
    }

    // ---------------- 証跡 ----------------

    private static void writeEvidence() {
        McNativeVulkanProbe.writeFile("native-terrain-load.json", json());
    }

    static String json() {
        List<Result> results = results();
        List<String> notes;
        synchronized (NOTES) {
            notes = List.copyOf(NOTES);
        }
        var it = instance;
        boolean built;
        int width, height, colourFormat, depthFormat, drawCount;
        long referenceSet, device;
        float[] mvp;
        synchronized (NOTES) {
            built = builtOnce;
            width = builtWidth;
            height = builtHeight;
            colourFormat = builtColourFormat;
            depthFormat = builtDepthFormat;
            drawCount = builtDrawCount;
            referenceSet = builtReferenceSet;
            device = builtDevice;
            mvp = builtMvp == null ? null : builtMvp.clone();
        }
        var sb = new StringBuilder("{\n");
        sb.append("  \"enabled\": ").append(enabled()).append(",\n");
        sb.append("  \"attempted\": ").append(attempted).append(",\n");
        // ⚠ "built" = a scene was built in this session; "live" = one is held right now.
        sb.append("  \"built\": ").append(built).append(",\n");
        sb.append("  \"live\": ").append(it != null && !it.destroyed).append(",\n");
        sb.append("  \"drawsRecorded\": ").append(drawsRecorded).append(",\n");
        sb.append("  \"width\": ").append(width).append(",\n");
        sb.append("  \"height\": ").append(height).append(",\n");
        sb.append("  \"colourFormat\": ").append(colourFormat).append(",\n");
        sb.append("  \"depthFormat\": ").append(depthFormat).append(",\n");
        sb.append("  \"scene\": ").append(McNativeVulkanProbe.quote(SCENE)).append(",\n");
        sb.append("  \"eye\": ").append(floats(EYE)).append(",\n");
        sb.append("  \"centre\": ").append(floats(CENTRE)).append(",\n");
        sb.append("  \"fovDegrees\": ").append(FOV_DEGREES).append(",\n");
        sb.append("  \"near\": ").append(NEAR).append(",\n");
        sb.append("  \"far\": ").append(FAR).append(",\n");
        sb.append("  \"fitMargin\": ").append(FIT_MARGIN).append(",\n");
        sb.append("  \"mvp\": ").append(mvp == null ? "null" : floats(mvp)).append(",\n");
        sb.append("  \"drawCount\": ").append(drawCount).append(",\n");
        sb.append("  \"referenceSet\": ").append(referenceSet).append(",\n");
        sb.append("  \"declaredDepthState\": [").append(DECLARED_DEPTH_STATE[0]).append(", ")
          .append(DECLARED_DEPTH_STATE[1]).append(", ").append(DECLARED_DEPTH_STATE[2]).append("],\n");
        sb.append("  \"depthStateReadBack\": false,\n");
        sb.append("  \"ladderEnabled\": ").append(Boolean.getBoolean(McNativeDepthLadder.FLAG))
          .append(",\n");
        sb.append("  \"results\": [");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            sb.append(i > 0 ? ",\n    {" : "\n    {");
            sb.append("\"at\": ").append(r.at());
            for (int k = 0; k < COUNTS; k++) {
                sb.append(", \"").append(COUNT_NAMES[k]).append("\": ").append(r.counts()[k]);
            }
            sb.append(", \"minDepth\": ").append(Float.isNaN(r.minDepth()) ? "null" : Float.toString(r.minDepth()));
            sb.append(", \"maxDepth\": ").append(Float.isNaN(r.maxDepth()) ? "null" : Float.toString(r.maxDepth()));
            sb.append(", \"file\": ").append(McNativeVulkanProbe.quote(r.file()));
            sb.append(", \"frameFile\": ").append(McNativeVulkanProbe.quote(r.frameFile()));
            sb.append(", \"referenceFile\": ").append(McNativeVulkanProbe.quote(r.referenceFile()));
            sb.append(", \"referenceDepthFile\": ")
              .append(McNativeVulkanProbe.quote(r.referenceDepthFile())).append('}');
        }
        sb.append(results.isEmpty() ? "],\n" : "\n  ],\n");
        sb.append("  \"problems\": ").append(problems).append(",\n");
        sb.append("  \"firstProblem\": ").append(McNativeVulkanProbe.quote(firstProblem)).append(",\n");
        sb.append("  \"closeFailures\": ").append(closeFailures).append(",\n");
        sb.append("  \"leakedScenes\": ").append(leakedScenes).append(",\n");
        sb.append("  \"leakBudget\": ").append(LEAK_BUDGET).append(",\n");
        sb.append("  \"deviceDiverged\": ").append(deviceDiverged).append(",\n");
        sb.append("  \"readbacksInFlight\": ").append(readbacksInFlight).append(",\n");
        sb.append("  \"device\": ").append(McNativeVulkanProbe.quote(
            built ? "0x" + Long.toHexString(device) : null)).append(",\n");
        sb.append("  \"notes\": [");
        for (int i = 0; i < notes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(McNativeVulkanProbe.quote(notes.get(i)));
        }
        sb.append("]\n}\n");
        return sb.toString();
    }

    private static String floats(float[] values) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }

    private static void note(String message) {
        synchronized (NOTES) {
            if (NOTES.contains(message) || NOTES.size() >= 32) return;
            NOTES.add(message);
        }
        Logger.warn("[native-vk] " + message);
    }
}
