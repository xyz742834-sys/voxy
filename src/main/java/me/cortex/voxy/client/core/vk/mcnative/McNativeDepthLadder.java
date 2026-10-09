package me.cortex.voxy.client.core.vk.mcnative;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.cortex.voxy.client.core.vk.shader.SpirvCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderType;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * <b>Minecraft のシーン深度を、読み戻しではなく<i>挙動</i>で、画素ごとに測る</b>診断。
 *
 * <h2>なぜこれが必要になったか</h2>
 * {@link McNativeDepthProbe} は MC の深度画像を転送コピーで読み戻せることを示したが、
 * <b>全画素が 0.0</b> だった。コピーが完了したことは中身がシーンの深度である保証にならない
 * (round-7 review R7-DEPTH-GATE)。そこで MC 自身の深度テストに答えさせる。
 *
 * <h2>測り方 (第 3 版): 同じ画素に全段を重ねる</h2>
 * MC のパスを<b>カラーも深度も LOAD</b> で開き、ひとつの矩形 (帯) に対して、
 * <b>深度書き込み無効</b>のまま次の順で描く:
 * <ol>
 *   <li>比較 ALWAYS、色 BASE (白) — 帯の全画素を必ず塗る。これが「ここに描いた」の証拠。</li>
 *   <li>比較 GREATER、深度 {@code z_0}、色 LOW (灰) — {@code d < z_0} の画素だけ残る。
 *       これが LESS 経路の<b>正の対照</b>: LESS が全段落ちる画素でも、深度テストが
 *       機能していれば GREATER が通る。どちらも通らなければ (d == z_0 を除き) 異常。</li>
 *   <li>比較 LESS、深度 {@code z_0 < z_1 < … < z_7} の<b>昇順</b>、段ごとに別の色 —
 *       画素に最後に残った色は {@code max { i : z_i < d }} を表す。</li>
 * </ol>
 * したがって最終画像の各画素の色が、<b>その画素の</b> MC の深度 {@code d} の区間
 * ({@code d < z_0}、{@code z_k < d <= z_{k+1}}、{@code d > z_7}) を符号化する。
 * 列ごとに別の画素を別の閾値で試した第 1/2 版と違い、閾値は<b>同じ画素</b>に全部かかる
 * (round-8 review R8-LADDER-MECHANISM / R8-LADDER-BOUND)。
 *
 * <p>帯の全画素はこの 10 色のどれかになる。それ以外の色 ("other") が 1 画素でもあれば、
 * 帯が描けていないか、向きの解釈が違うか、後から何かが上書きしたかであり、測定にならない。
 * BASE が残った画素 ("anomaly") は深度テストが LESS でも GREATER でも通らなかった画素で、
 * {@code d == z_0} ちょうどを除けば深度経路の故障を意味する。
 *
 * <h2>段の深さ</h2>
 * MC は frame graph の先頭で深度を 0.0 にクリアし ({@code LevelRenderer.render} の clear pass)、
 * 投影は {@code setPerspective(fov, aspect, zFar, zNear, ..)} と near/far を入れ替えて組む
 * ({@code Projection}、26.2 bytecode)。つまり逆Z で、距離 d の面の深度は 0.05/d 程度。
 * 段は {@code 2^-16 .. 2^-2} (約 3000 ブロックから 0.2 ブロック) に置く。
 * ⚠ これらは<b>ソースから読んだ事実</b>で、この probe が測ったものではない。
 *
 * <h2>⚠ この probe は MC の深度に<b>一切書かない</b></h2>
 * 三つのパイプラインすべてが {@code depthWriteEnable = false} ({@link #depthStencilState})。
 * カラーには書くが、深度は読むだけ。{@link McNativeTerrainProbe} と違い MC のシーンを
 * クリアもしない。だから terrain probe と同じ起動では測れない (あちらは深度をクリアする)。
 *
 * <h2>⚠ 規約 (逆Zか否か) は<b>述べない</b></h2>
 * 梯子は各画素の深度値の区間を測るだけである。どちらが手前かは、画面のどこが物理的に
 * 近いかを知らない限り決まらない — round 7 が帯の平均に対して指摘した穴と同じ。
 *
 * <h2>標本</h2>
 * 読み戻しは draw 2 から 240 draw ごとに要求し、成功した標本をすべて残す (上限あり)。
 * 第 2 版は draw 2 の 1 標本だけで、そのフレームにはまだ地形が無く雲だけが写っていた。
 * 向きは、帯の全画素がパレット内に収まる向きを採り、<b>棄却した向きの crop も残す</b>
 * (round 6 B4 と同じ規律)。
 *
 * <p>既定で無効。{@code -Dvoxy.native.depthladder=true} のときだけ動く。
 */
public final class McNativeDepthLadder implements Destroyable {
    public static final String FLAG = "voxy.native.depthladder";
    /**
     * 共存実験 (既定で無効): 梯子の標本と<b>同じフレーム・同じ画素</b>で、既知の深度 z* の
     * クアッドを Voxy の深度比較 ({@link VkDepth#COMPARE_OP}、GREATER_OR_EQUAL) で描き、
     * 2 回目の読み戻しで「MC の深度が z* 以下の画素にだけ現れた」ことを画素ごとに確かめる。
     * 梯子の 1 回目の読み戻しが各画素の MC 深度の区間を与えるので、z* を段の値そのものに
     * 置けば区間が z* を跨ぐ画素は無く、全画素で期待が決まる。深度は書かない。
     */
    public static final String COEXIST_FLAG = "voxy.native.coexist";
    /** z* = 段 {@link #COEXIST_RUNG} の深度。descend の帯は段 3/4 に跨るので混在する。 */
    public static final int COEXIST_RUNG = 4;
    /** 共存クアッドの色 (三値 1,2,1)。パレットの 10 色と重ならない。 */
    public static final float[] COEXIST_RGB = {0.5f, 1.0f, 0.5f};
    /** GPU が実際に書く値 (実測: 128, 255, 128)。照合は<b>厳密一致</b> (round-15 review R15-COEXIST-RGB)。 */
    public static final int[] COEXIST_RGB_EXACT = {128, 255, 128};

    /** 段の数。 */
    public static final int RUNGS = 8;

    /** 帯 (最終画像での NDC)。marker (x -0.98..-0.78) とは重ならない。 */
    private static final float BAND_X0 = -0.60f, BAND_X1 = 0.60f;
    private static final float BAND_Y0 = 0.36f, BAND_Y1 = 0.20f;

    /**
     * パレット。各チャネルは 0 / 0.5 / 1 の三値で、gate は同じ三値量子化で分類する。
     * 添字: 0 = BASE (ALWAYS、白)、1 = LOW (GREATER z_0、灰)、2.. = 段 0..7。
     * ⚠ gate は自分の定数と比べる。ここを変えたら {@code scripts/verify.py} も変える。
     */
    public static final float[][] PALETTE = {
        {1.0f, 1.0f, 1.0f},   // BASE
        {0.5f, 0.5f, 0.5f},   // LOW
        {1.0f, 0.0f, 1.0f},   // rung 0  magenta
        {0.0f, 1.0f, 1.0f},   // rung 1  cyan
        {1.0f, 1.0f, 0.0f},   // rung 2  yellow
        {1.0f, 0.0f, 0.0f},   // rung 3  red
        {0.0f, 1.0f, 0.0f},   // rung 4  green
        {0.0f, 0.0f, 1.0f},   // rung 5  blue
        {1.0f, 0.5f, 0.0f},   // rung 6  orange
        {0.5f, 0.0f, 1.0f},   // rung 7  violet
    };
    public static final int BASE = 0, LOW = 1, RUNG0 = 2;

    /**
     * 三つのパイプラインの比較演算、添字で引く。{@link #create} はこの配列からパイプラインを
     * 作り、{@link #record} は同じ添字で束縛する。テストはこの配列と添字を検査する
     * (round-9 review R9-TEST-COVERAGE: テストが別のリストを見ていては create の取り違えを
     * 見逃す)。
     */
    static final int[] PIPELINE_COMPARE_OPS = {VK_COMPARE_OP_LESS, VK_COMPARE_OP_ALWAYS,
        VK_COMPARE_OP_GREATER, me.cortex.voxy.client.core.vk.VkDepth.COMPARE_OP};
    static final int OP_LESS = 0, OP_ALWAYS = 1, OP_GREATER = 2, OP_COEXIST = 3;

    /** 全フレームのサムネイルの縮尺。4x4 ブロックの整数平均。 */
    public static final int FRAME_SCALE = 4;

    /**
     * 作成後に create-info から読み戻した深度状態、パイプラインごと {compareOp, testEnable,
     * writeEnable}。{@link #buildPipelines} が creator の<b>戻った後</b>に構造体から読む。
     * creator の中で構造体を書き換える変異 (round-11 review R10-CREATE-TEST) は作成関数には
     * 届き、戻った後も残っていればここに写り、buildPipelines は失敗を返し、証跡は書き込み有効を
     * 公開する。呼び出しの間だけ書き換えて戻す変異は写らない (明示した限界)。
     */
    private static int[][] pipelineStates = new int[0][];

    private static final long READBACK_BUDGET_BYTES = 40L << 20;
    private static final int FAILURE_BUDGET = 3;
    private static final long READBACK_INTERVAL = 240;
    /** 16 lifecycle stages, at least one sample each (the harness waits for it), most two. */
    private static final int SAMPLE_LIMIT = 40;
    private static long samplesRequested;

    /** How many samples the ladder has requested so far (the harness waits on it per stage). */
    public static long samplesRequested() { return samplesRequested; }

    /** Whether the ladder is on and can still take samples (so a stage may wait for one). */
    public static boolean sampling() {
        return Boolean.getBoolean(FLAG) && !deviceDiverged && problems < FAILURE_BUDGET
            && SAMPLES.size() < SAMPLE_LIMIT;
    }
    private static final int LEAK_BUDGET = 3;

    private static final List<String> NOTES = new ArrayList<>();
    private static final List<Sample> SAMPLES = new ArrayList<>();
    /** 共存実験の結果、標本の draw 本数 → 結果。 */
    private static final java.util.Map<Long, Coexist> COEXIST = new java.util.LinkedHashMap<>();
    /** 1 回目の読み戻しの帯の分類 (画素ごとのパレット添字)、2 回目が照合するまで保持。 */
    private static final java.util.Map<Long, byte[]> BAND_CLASSES = new java.util.HashMap<>();
    /** 同じ帯の生 RGB (画素ごと 3 バイト)。覆われなかった画素はこれと厳密一致でなければならない。 */
    private static final java.util.Map<Long, byte[]> BAND_PIXELS = new java.util.HashMap<>();
    private static McNativeDepthLadder instance;
    private static long drawsRecorded;
    private static long nextReadbackAt = 2;
    private static boolean attempted;
    private static boolean readbackInFlight;
    /**
     * このフレームで読み戻しを要求した標本の draw 本数、無ければ -1。{@code McNativeTerrainLoad}
     * が同じフレームの<b>後</b>で取り出す ({@link #takeSampleThisFrame})。梯子自身は MC の深度を
     * 決して書かない; 書く実験は別の probe と別のフラグである (handoff の規則 3)。
     */
    private static long sampleThisFrame = -1;
    /**
     * そのフレームの標本を渡す先。両方の LOAD 実験が有効なら<b>交互</b>に渡す: どちらも MC の
     * 深度に書くので、同じフレームで二つ目が見る深度はもう MC のものではない。標本ごとの
     * 渡し先は証跡 ({@code experiment}) に出し、gate は各実験の結果がちょうどその標本に
     * 対応することを要求する。
     */
    private static String consumerThisFrame = null;
    private static final java.util.Map<Long, String> SAMPLE_CONSUMERS = new java.util.HashMap<>();
    public static final String EXPERIMENT_TERRAIN_LOAD = "terrainLoad",
        EXPERIMENT_REAL_LOAD = "realLoad";
    private static int assigned;
    private static int problems;
    private static String firstProblem;
    private static int closeFailures;
    private static int leakedPipelines;
    private static boolean deviceDiverged;

    /**
     * ひとつの標本: 採用した向きで帯を数えた結果と、両向きの crop。
     *
     * @param at             読み戻しを登録した時点の draw 本数
     * @param flipped        採用した向き ({@code true} なら行順は最終画像と上下逆)
     * @param targetWidth    読み戻したフレームの幅
     * @param targetHeight   読み戻したフレームの高さ
     * @param rect           帯の矩形 (採用した向き、フレーム画素座標)
     * @param counts         帯の画素数: [BASE(anomaly), LOW, rung0..rung7, other]
     * @param rejectedFlipped 棄却した向き
     * @param rejectedRect   帯の矩形 (棄却した向き)
     * @param rejectedOther  棄却した向きで帯の画素のうちパレット外だった数
     * @param file           採用した向きの crop (PPM gzip)
     * @param rejectedFile   棄却した向きの crop (PPM gzip)
     * @param frameFile      読み戻した<b>全フレーム</b>の 1/4 サムネイル (PPM gzip)。gate は
     *                       両 crop をこのサムネイルの述べた位置に重ね、一致を要求する —
     *                       crop がどこから切り出されたかを producer の言葉以外で縛るため
     *                       (round-9 review R8-LADDER-GATE: 向きと矩形を揃って偽ると通った)
     * @param stage          読み戻しを要求した時点の harness の段階名 (system property)
     * @param camera         その時点のカメラ {x, y, z, pitch, yaw}。Z の向きの実験は
     *                       同じ地面を既知の二つの高さから真下に見る: gate は段階ごとの
     *                       カメラ高さを checkpoint の地面高さと照らし、区間の移動を読む
     */
    public record Sample(long at, boolean flipped, int targetWidth, int targetHeight, int[] rect,
                         long[] counts, boolean rejectedFlipped, int[] rejectedRect,
                         long rejectedOther, String file, String rejectedFile,
                         String frameFile, String stage, double[] camera) {}

    /**
     * 共存実験の 1 標本。1 回目の読み戻し (梯子) の画素ごとの区間から期待を決め、2 回目の
     * 読み戻し (クアッドの後) で現れた/現れなかったを数える。
     *
     * @param present        共存色だった画素数、{@code absent} それ以外でパレット内、
     *                       {@code other} どちらでもない (測定無効)
     * @param expectedPass   梯子の区間が z* 以下 (low または段 < COEXIST_RUNG) の画素数
     * @param expectedFail   段 >= COEXIST_RUNG の画素数
     * @param absentWherePass 期待は現れるのに現れなかった画素数 (違反)
     * @param presentWhereFail 期待は現れないのに現れた画素数 (違反)
     * @param unchangedElsewhere 現れなかった画素のうち梯子の色のままだった数 (違反は absent − これ)
     */
    public record Coexist(long at, long present, long absent, long other, long expectedPass,
                          long expectedFail, long absentWherePass, long presentWhereFail,
                          long unchangedElsewhere, String file, String frameFile) {}

    // ---------------- 保持するもの ----------------

    private final VulkanDevice device;
    private final long ownerDevice;
    private final int colourFormat, depthFormat;
    private final long vertexModule, fragmentModule, layout;
    /** {@link #PIPELINE_COMPARE_OPS} と同じ添字。すべて深度書き込み無効。 */
    private final long[] pipelines;

    public static java.util.List<Coexist> coexist() {
        synchronized (NOTES) {
            return List.copyOf(COEXIST.values());
        }
    }

    /** 帯の状態の引き渡し: 標本、1 回目の分類、<b>直近の読み戻し</b>の生 RGB (quad の後ならその後)。 */
    record Band(Sample sample, byte[] classes, byte[] pixels) {}

    /**
     * このフレームで梯子が読み戻しを要求した標本の draw 本数を返し、忘れる (無ければ -1)。
     * 描画スレッドで、梯子の {@link #renderIfEnabled} の後に呼ぶこと。
     */
    static long takeSampleThisFrame(String consumer) {
        if (sampleThisFrame < 0 || !consumer.equals(consumerThisFrame)) return -1;
        long at = sampleThisFrame;
        sampleThisFrame = -1;
        consumerThisFrame = null;
        return at;
    }

    /** 渡し先の決め方: 両方有効なら交互 (terrain-LOAD から)、片方ならそれ、無ければ無し。 */
    static String assignConsumer(boolean terrain, boolean real, int index) {
        if (terrain && real) return (index % 2 == 0) ? EXPERIMENT_TERRAIN_LOAD : EXPERIMENT_REAL_LOAD;
        if (terrain) return EXPERIMENT_TERRAIN_LOAD;
        if (real) return EXPERIMENT_REAL_LOAD;
        return null;
    }

    /** 標本 {@code at} を渡した先、渡していなければ {@code null}。 */
    static String consumerOf(long at) {
        synchronized (NOTES) {
            return SAMPLE_CONSUMERS.get(at);
        }
    }

    /**
     * 標本 {@code at} の帯 (分類と直近の生 RGB) を取り出して忘れる。{@code McNativeTerrainLoad}
     * の 3 回目の読み戻しが照合に使う。無ければ {@code null} (読み戻しの順序が崩れた)。
     */
    static Band takeBand(long at) {
        synchronized (NOTES) {
            Sample sample = null;
            synchronized (SAMPLES) {
                for (var s : SAMPLES) if (s.at() == at) sample = s;
            }
            byte[] classes = BAND_CLASSES.remove(at);
            byte[] pixels = BAND_PIXELS.remove(at);
            if (sample == null || classes == null || pixels == null) return null;
            return new Band(sample, classes, pixels);
        }
    }
    private boolean destroyed;

    private McNativeDepthLadder(VulkanDevice device, long ownerDevice, int colourFormat,
                                int depthFormat, long vertexModule, long fragmentModule,
                                long layout, long[] pipelines) {
        this.device = device;
        this.ownerDevice = ownerDevice;
        this.colourFormat = colourFormat;
        this.depthFormat = depthFormat;
        this.vertexModule = vertexModule;
        this.fragmentModule = fragmentModule;
        this.layout = layout;
        this.pipelines = pipelines;
    }

    // ---------------- 入口 ----------------

    /** 帯 (NDC、GL 規約の y) {@code {x0, y0, x1, y1}}。terrain-LOAD が足跡を合わせる先。 */
    public static float[] band() {
        return new float[] {BAND_X0, BAND_Y0, BAND_X1, BAND_Y1};
    }

    /** 各段の NDC 深度。{@code 2^-16, 2^-14, …, 2^-2} の昇順 (gate の定数と同じ式)。 */
    public static float[] depths() {
        float[] z = new float[RUNGS];
        for (int i = 0; i < RUNGS; i++) z[i] = Math.scalb(1.0f, -(16 - 2 * i));
        return z;
    }

    public static boolean attempted() { return attempted; }
    public static long drawsRecorded() { return drawsRecorded; }

    public static List<Sample> samples() {
        synchronized (SAMPLES) {
            return List.copyOf(SAMPLES);
        }
    }

    public static void renderIfEnabled() {
        if (!Boolean.getBoolean(FLAG)) return;
        try {
            render();
        } catch (Throwable t) {
            note("the native depth ladder failed: " + t);
            var trace = t.getStackTrace();
            for (int i = 0; i < Math.min(6, trace.length); i++) note("  at " + trace[i]);
        }
    }

    private static void render() {
        var notes = new ArrayList<String>();
        VulkanDevice device = McNativeVulkan.device(notes);
        if (device == null) {
            notes.forEach(McNativeDepthLadder::note);
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
            note("the main render target has no colour or depth view, so a depth ladder"
                + " cannot be tested against anything");
            return;
        }
        int format = VulkanConst.toVk(colour.texture().getFormat());
        int depthVk = VulkanConst.toVk(depth.texture().getFormat());
        int width = colour.getWidth(0), height = colour.getHeight(0);
        if (width <= 0 || height <= 0) return;

        long mcDevice = McNativeVulkan.vkDeviceHandle(device, notes);
        var ladder = instance;
        if (ladder != null && ladder.destroyed) {
            if (instance == ladder) instance = null;
            ladder = null;
        }
        if (ladder != null && ladder.ownerDevice != mcDevice && mcDevice != 0) {
            // ⚠ round-6 review R6-TERRAIN-DEVICE: ownership is judged against Minecraft's
            // CURRENT device, divergence stops the probe for the session, and the stale
            // instance is leaked on purpose and counted. Published before returning.
            deviceDiverged = true;
            note("Minecraft's VkDevice changed from 0x" + Long.toHexString(ladder.ownerDevice)
                + " to 0x" + Long.toHexString(mcDevice) + "; the ladder stops for this session"
                + " and leaks its pipelines on purpose");
            ladder.destroyed = true;
            leakedPipelines++;
            if (instance == ladder) instance = null;
            writeEvidence();
            return;
        }
        if (ladder != null && (ladder.device != device || ladder.colourFormat != format
                || ladder.depthFormat != depthVk)) {
            retire(ladder, device);
            if (instance == ladder) instance = null;
            ladder = null;
        }
        if (ladder == null) {
            if (mcDevice == 0) {
                note("Minecraft's current VkDevice handle could not be read, so the ladder"
                    + " cannot be bound to a device");
                return;
            }
            attempted = true;
            ladder = create(device, mcDevice, format, depthVk);
            if (ladder == null) return;
            instance = ladder;
        }

        // ⚠ カラーも深度も LOAD。MC のシーンと<b>その深度</b>をそのまま残す。
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "voxy native depth ladder", colour, Optional.empty(),
                depth, OptionalDouble.empty())) {
            VkCommandBuffer cmd = McNativeVulkan.commandBufferOf(pass, notes);
            if (cmd == null) {
                notes.forEach(McNativeDepthLadder::note);
                return;
            }
            ladder.record(cmd, width, height);
            drawsRecorded++;
        }

        if (drawsRecorded >= nextReadbackAt && !readbackInFlight && problems < FAILURE_BUDGET
                && SAMPLES.size() < SAMPLE_LIMIT) {
            nextReadbackAt = drawsRecorded + READBACK_INTERVAL;
            long at = drawsRecorded;
            requestReadback(colour, width, height);
            if (readbackInFlight) samplesRequested++;
            if (readbackInFlight) {
                String consumer = assignConsumer(McNativeTerrainLoad.enabled(),
                    McNativeRealLoad.enabled(), assigned);
                if (consumer != null) assigned++;
                sampleThisFrame = consumer == null ? -1 : at;
                consumerThisFrame = consumer;
                synchronized (NOTES) {
                    if (consumer != null) SAMPLE_CONSUMERS.put(at, consumer);
                }
            }
            if (Boolean.getBoolean(COEXIST_FLAG) && readbackInFlight) {
                // ⚠ Same frame, same pixels: a second LOAD pass draws the coexistence quad over
                // the band with Voxy's compare op and NO depth write, then a second readback.
                boolean drawn = false;
                try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        () -> "voxy native coexist", colour, Optional.empty(),
                        depth, OptionalDouble.empty())) {
                    VkCommandBuffer cmd = McNativeVulkan.commandBufferOf(pass, notes);
                    if (cmd != null) {
                        ladder.recordCoexist(cmd, width, height);
                        drawn = true;
                    } else {
                        notes.forEach(McNativeDepthLadder::note);
                    }
                }
                // ⚠ The copy must be recorded OUTSIDE the render pass instance (measured: inside
                // it, validation flagged VUID-vkCmdCopyImageToBuffer-renderpass and the second
                // readback held garbage). Request it only after the pass has closed.
                if (drawn) requestCoexistReadback(colour, width, height, at);
            }
        }
        if (drawsRecorded == 1 || drawsRecorded % 600 == 0) writeEvidence();
    }

    // ---------------- 記録 ----------------

    /** BASE (ALWAYS) → LOW (GREATER z_0) → 段 0..7 (LESS、昇順)。<b>深度には書かない</b>。 */
    private void record(VkCommandBuffer cmd, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            var viewport = org.lwjgl.vulkan.VkViewport.calloc(1, stack)
                .x(0).y(0).width(width).height(height).minDepth(0).maxDepth(1);
            var scissor = org.lwjgl.vulkan.VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(width, height);
            vkCmdSetViewport(cmd, 0, viewport);
            vkCmdSetScissor(cmd, 0, scissor);

            float[] z = depths();
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipelines[OP_ALWAYS]);
            push(cmd, stack, PALETTE[BASE], 0.5f);
            vkCmdDraw(cmd, 6, 1, 0, 0);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipelines[OP_GREATER]);
            push(cmd, stack, PALETTE[LOW], z[0]);
            vkCmdDraw(cmd, 6, 1, 0, 0);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipelines[OP_LESS]);
            for (int i = 0; i < RUNGS; i++) {
                push(cmd, stack, PALETTE[RUNG0 + i], z[i]);
                vkCmdDraw(cmd, 6, 1, 0, 0);
            }
        }
    }

    /** 共存クアッドの描き方: {pipeline 添字, 深度}。{@link #recordCoexist} はこれを使う。 */
    static Object[] coexistDrawPlan() {
        return new Object[] {OP_COEXIST, depths()[COEXIST_RUNG], COEXIST_RGB};
    }

    /** 共存クアッド: 帯全体、深度 z* = depths()[COEXIST_RUNG]、比較 GREATER_OR_EQUAL、書き込み無効。 */
    private void recordCoexist(VkCommandBuffer cmd, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            var viewport = org.lwjgl.vulkan.VkViewport.calloc(1, stack)
                .x(0).y(0).width(width).height(height).minDepth(0).maxDepth(1);
            var scissor = org.lwjgl.vulkan.VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(width, height);
            vkCmdSetViewport(cmd, 0, viewport);
            vkCmdSetScissor(cmd, 0, scissor);
            Object[] plan = coexistDrawPlan();
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipelines[(Integer) plan[0]]);
            push(cmd, stack, (float[]) plan[2], (Float) plan[1]);
            vkCmdDraw(cmd, 6, 1, 0, 0);
        }
    }

    private void push(VkCommandBuffer cmd, MemoryStack stack, float[] rgb, float depth) {
        ByteBuffer params = stack.malloc(36);
        params.putFloat(0, BAND_X0).putFloat(4, BAND_Y0).putFloat(8, BAND_X1).putFloat(12, BAND_Y1);
        params.putFloat(16, rgb[0]).putFloat(20, rgb[1]).putFloat(24, rgb[2]).putFloat(28, 1.0f);
        params.putFloat(32, depth);
        vkCmdPushConstants(cmd, this.layout,
            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, params);
    }

    // ---------------- 読み戻しと判定 ----------------

    private static void requestReadback(GpuTextureView colour, int width, int height) {
        long bytes = (long) width * height * 4;
        if (bytes > READBACK_BUDGET_BYTES) {
            fail("the colour image is " + bytes + " bytes, over the " + READBACK_BUDGET_BYTES
                + " byte budget; not measuring the ladder rather than allocating that much");
            return;
        }
        GpuBuffer buffer = null;
        try {
            var gpu = RenderSystem.getDevice();
            buffer = gpu.createBuffer(() -> "voxy native depth ladder readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            final GpuBuffer readback = buffer;
            final long at = drawsRecorded;
            final String stage = System.getProperty("voxy.harness.stage", "");
            final double[] camera = cameraNow();
            readbackInFlight = true;
            gpu.createCommandEncoder().copyTextureToBuffer(colour.texture(), readback, 0,
                () -> measure(readback, width, height, at, stage, camera), 0);
            buffer = null;
            // ⚠ The chronology line is written NOW, when the frame is captured, not when the GPU
            // callback runs: the callback can land after the harness has moved to the next
            // stage (measured: draw 1922 requested in `return`, its sample line logged under
            // `edit`), so the gate anchors the stage to this line and requires the sample line
            // to repeat it.
            Logger.info("[native-vk] depth ladder sample requested at draw " + at + " stage=" + stage
                + " camera=[" + camera[0] + " " + camera[1] + " " + camera[2] + " " + camera[3]
                + " " + camera[4] + "]");
        } catch (Throwable t) {
            fail("the ladder readback could not be requested: " + t);
        } finally {
            if (buffer != null) {
                try {
                    buffer.close();
                } catch (Throwable t) {
                    closeFailures++;
                    note("could not close the unregistered ladder buffer: " + t);
                }
            }
        }
    }

    /**
     * 帯を両向きで分類し、全画素がパレット内に収まる向きを採る。
     *
     * <p>⚠ 向きは仮定しない (round 6 B4)。両向きの計数と crop を残し、採用した向きでは
     * "other" が 0、棄却した向きでは "other" が多数であることを gate が要求する。
     */
    private static void requestCoexistReadback(GpuTextureView colour, int width, int height,
                                               long at) {
        long bytes = (long) width * height * 4;
        GpuBuffer buffer = null;
        try {
            var gpu = RenderSystem.getDevice();
            buffer = gpu.createBuffer(() -> "voxy native coexist readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            final GpuBuffer readback = buffer;
            gpu.createCommandEncoder().copyTextureToBuffer(colour.texture(), readback, 0,
                () -> measureCoexist(readback, width, height, at), 0);
            buffer = null;
        } catch (Throwable t) {
            fail("the coexist readback could not be requested: " + t);
        } finally {
            if (buffer != null) {
                try {
                    buffer.close();
                } catch (Throwable t) {
                    closeFailures++;
                    note("could not close the unregistered coexist buffer: " + t);
                }
            }
        }
    }

    /**
     * 2 回目の読み戻し: 1 回目の分類 (画素ごとの区間) から期待を決め、画素ごとに照合する。
     * 期待: low または段 < COEXIST_RUNG → 現れる (d ≤ z*)、段 ≥ COEXIST_RUNG → 現れない。
     * 現れなかった画素は 1 回目と同じ色のままでなければならない。
     */
    private static void measureCoexist(GpuBuffer buffer, int width, int height, long at) {
        try (var view = new GpuBufferSlice(buffer, 0, buffer.size()).map(true, false)) {
            var data = view.data();
            Sample sample = null;
            byte[] classes, pixels;
            // ⚠ terrain-LOAD (a separate probe) reads the same frame after the quad: leave the
            // classes in place and hand it the band AS THE QUAD LEFT IT, byte for byte.
            boolean keepForTerrain = consumerOf(at) != null;
            synchronized (NOTES) {
                for (var s : SAMPLES) if (s.at() == at) sample = s;
                classes = keepForTerrain ? BAND_CLASSES.get(at) : BAND_CLASSES.remove(at);
                pixels = keepForTerrain ? BAND_PIXELS.get(at) : BAND_PIXELS.remove(at);
            }
            if (sample == null || classes == null || pixels == null) {
                fail("coexist readback at draw " + at + " has no ladder sample to compare with"
                    + " (readback order)");
                return;
            }
            int[] q = sample.rect();
            int w = q[2] - q[0], h = q[3] - q[1];
            long present = 0, absent = 0, other = 0, expectedPass = 0, expectedFail = 0;
            long absentWherePass = 0, presentWhereFail = 0, unchanged = 0;
            byte[] afterQuad = keepForTerrain ? new byte[w * h * 3] : null;
            int i = 0;
            for (int y = q[1]; y < q[3]; y++) {
                long rowBase = (long) y * width * 4;
                for (int x = q[0]; x < q[2]; x++, i++) {
                    long p = rowBase + (long) x * 4;
                    int r = data.get((int) p) & 0xFF, g = data.get((int) p + 1) & 0xFF,
                        b = data.get((int) p + 2) & 0xFF;
                    if (afterQuad != null) {
                        afterQuad[i * 3] = (byte) r;
                        afterQuad[i * 3 + 1] = (byte) g;
                        afterQuad[i * 3 + 2] = (byte) b;
                    }
                    // ⚠ exact bytes, not classes: a quad of another green or an uncovered pixel
                    // of another shade must not pass (round-15 review R15-COEXIST-RGB)
                    boolean coexist = r == COEXIST_RGB_EXACT[0] && g == COEXIST_RGB_EXACT[1]
                        && b == COEXIST_RGB_EXACT[2];
                    int before = classes[i];
                    int i3 = i * 3;
                    boolean identical = pixels[i3] == (byte) r && pixels[i3 + 1] == (byte) g
                        && pixels[i3 + 2] == (byte) b;
                    boolean expectPass = before == LOW || (before >= RUNG0
                        && before - RUNG0 < COEXIST_RUNG);
                    boolean expectFail = before >= RUNG0 && before - RUNG0 >= COEXIST_RUNG;
                    if (expectPass) expectedPass++;
                    if (expectFail) expectedFail++;
                    if (coexist) {
                        present++;
                        if (expectFail) presentWhereFail++;
                    } else {
                        absent++;
                        if (identical) unchanged++;
                        else other++;
                        if (expectPass) absentWherePass++;
                    }
                }
            }
            String file = writeCrop(data, width, q, "native-depth-ladder-coexist-" + at + ".ppm.gz");
            // the whole frame the second readback saw, anchoring its crop like the first one's
            String frameFile = writeFrame(data, width, height,
                "native-depth-ladder-coexist-frame-" + at + ".ppm.gz");
            var result = new Coexist(at, present, absent, other, expectedPass, expectedFail,
                absentWherePass, presentWhereFail, unchanged, file, frameFile);
            synchronized (NOTES) {
                COEXIST.put(at, result);
                if (afterQuad != null) BAND_PIXELS.put(at, afterQuad);
            }
            if (other != 0 || absentWherePass != 0 || presentWhereFail != 0 || unchanged != absent) {
                problems++;
                String why = "coexist at draw " + at + ": present=" + present + " absent=" + absent
                    + " other=" + other + " absentWherePass=" + absentWherePass
                    + " presentWhereFail=" + presentWhereFail + " unchanged=" + unchanged;
                if (firstProblem == null) firstProblem = why;
                note(why);
            }
            Logger.info("[native-vk] depth ladder coexist at draw " + at + " present=" + present
                + " absent=" + absent + " other=" + other + " expectedPass=" + expectedPass
                + " expectedFail=" + expectedFail + " absentWherePass=" + absentWherePass
                + " presentWhereFail=" + presentWhereFail + " unchanged=" + unchanged);
            writeEvidence();
        } catch (Throwable t) {
            fail("the coexist measurement failed: " + t);
        } finally {
            try {
                buffer.close();
            } catch (Throwable t) {
                closeFailures++;
                note("could not close the coexist readback buffer: " + t);
            }
        }
    }

    /** {x, y, z, pitch, yaw} of Minecraft's main camera right now, or NaNs if unreachable. */
    private static double[] cameraNow() {
        try {
            var cam = Minecraft.getInstance().gameRenderer.mainCamera();
            var p = cam.position();
            return new double[] {p.x, p.y, p.z, cam.xRot(), cam.yRot()};
        } catch (Throwable t) {
            return new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        }
    }

    private static void measure(GpuBuffer buffer, int width, int height, long at, String stage,
                                double[] camera) {
        try (var view = new GpuBufferSlice(buffer, 0, buffer.size()).map(true, false)) {
            var data = view.data();
            long[][] counts = new long[2][];
            int[][] rects = new int[2][];
            for (int o = 0; o < 2; o++) {
                rects[o] = bandRect(width, height, o == 1);
                counts[o] = classify(data, width, rects[o]);
            }
            int other = PALETTE.length;
            int chosen = counts[0][other] <= counts[1][other] ? 0 : 1;
            boolean flipped = chosen == 1;
            long[] c = counts[chosen];
            String file = writeCrop(data, width, rects[chosen],
                "native-depth-ladder-" + at + ".ppm.gz");
            String rejectedFile = writeCrop(data, width, rects[1 - chosen],
                "native-depth-ladder-rejected-" + at + ".ppm.gz");
            String frameFile = writeFrame(data, width, height,
                "native-depth-ladder-frame-" + at + ".ppm.gz");
            var sample = new Sample(at, flipped, width, height, rects[chosen], c,
                !flipped, rects[1 - chosen], counts[1 - chosen][other], file, rejectedFile,
                frameFile, stage, camera);
            String why = null;
            if (c[other] != 0) {
                why = "sample at draw " + at + ": " + c[other] + " pixel(s) of the band are"
                    + " outside the palette in either orientation, so the band was not drawn"
                    + " or something overwrote it";
            } else if (c[BASE] != 0) {
                why = "sample at draw " + at + ": " + c[BASE] + " pixel(s) passed neither the"
                    + " LESS rungs nor the GREATER control (depth exactly z_0, or a depth test"
                    + " that did not decide); the sample is not a measurement";
            }
            synchronized (SAMPLES) {
                SAMPLES.add(sample);
            }
            if (Boolean.getBoolean(COEXIST_FLAG) || McNativeTerrainLoad.enabled()
                    || McNativeRealLoad.enabled()) {
                int[] q = rects[chosen];
                int n = (q[2] - q[0]) * (q[3] - q[1]);
                byte[] cls = new byte[n];
                byte[] raw = new byte[n * 3];
                int i = 0;
                for (int y = q[1]; y < q[3]; y++) {
                    long rowBase = (long) y * width * 4;
                    for (int x = q[0]; x < q[2]; x++, i++) {
                        long p = rowBase + (long) x * 4;
                        raw[i * 3] = data.get((int) p);
                        raw[i * 3 + 1] = data.get((int) p + 1);
                        raw[i * 3 + 2] = data.get((int) p + 2);
                        cls[i] = (byte) classify(raw[i * 3] & 0xFF, raw[i * 3 + 1] & 0xFF,
                            raw[i * 3 + 2] & 0xFF);
                    }
                }
                synchronized (NOTES) {
                    BAND_CLASSES.put(at, cls);
                    BAND_PIXELS.put(at, raw);
                }
            }
            if (why != null) {
                problems++;
                if (firstProblem == null) firstProblem = why;
                note(why);
            }
            // ⚠ round-13 review R13-Z-BINDING: the log is the retained chronology. Each sample
            // line carries the stage and camera too, so a report that relabels a sample
            // disagrees with the log; the gate reconciles both and the stage order.
            Logger.info("[native-vk] depth ladder sample at draw " + at + " flipped=" + flipped
                + " counts=" + describe(c) + " stage=" + stage + " camera=[" + camera[0] + " "
                + camera[1] + " " + camera[2] + " " + camera[3] + " " + camera[4] + "]"
                + (why == null ? "" : " PROBLEM: " + why));
            writeEvidence();
        } catch (Throwable t) {
            fail("the ladder measurement failed: " + t);
        } finally {
            readbackInFlight = false;
            try {
                buffer.close();
            } catch (Throwable t) {
                closeFailures++;
                problems++;
                if (firstProblem == null) firstProblem = "could not close the ladder buffer: " + t;
                note("could not close the ladder readback buffer: " + t);
                writeEvidence();
            }
        }
    }

    private static String describe(long[] c) {
        var sb = new StringBuilder("[anomaly=").append(c[BASE]).append(" low=").append(c[LOW]);
        for (int i = 0; i < RUNGS; i++) sb.append(" r").append(i).append('=').append(c[RUNG0 + i]);
        return sb.append(" other=").append(c[PALETTE.length]).append(']').toString();
    }

    /**
     * 帯の矩形 (フレーム画素座標、指定した向き)。gate と同じ式。
     *
     * <p>⚠ ラスタライズは<b>画素中心</b>で判定する: 画素 x が塗られるのは
     * {@code x + 0.5} が [左端, 右端) に入るときなので、端は {@code ceil(edge - 0.5)}。
     * 最初の版は切り捨てていて、中心が端より外の 1 列 (76 画素) が帯に含まれ、
     * 全標本が "other" で落ちた。gate は同じ式で矩形を解き、違えば失敗する。
     */
    static int[] bandRect(int width, int height, boolean flipped) {
        int x0 = covered((BAND_X0 + 1.0f) * 0.5f * width);
        int x1 = covered((BAND_X1 + 1.0f) * 0.5f * width);
        float top = Math.max(BAND_Y0, BAND_Y1), bottom = Math.min(BAND_Y0, BAND_Y1);
        int y0, y1;
        if (flipped) {
            y0 = covered((1.0f + bottom) * 0.5f * height);
            y1 = covered((1.0f + top) * 0.5f * height);
        } else {
            y0 = covered((1.0f - top) * 0.5f * height);
            y1 = covered((1.0f - bottom) * 0.5f * height);
        }
        return new int[] {Math.max(0, x0), Math.max(0, y0), Math.min(width, x1),
            Math.min(height, y1)};
    }

    /** 端 (画素座標) から、その端を境に塗られる最初の画素番号へ。 */
    static int covered(float edge) {
        return (int) Math.ceil(edge - 0.5f);
    }

    /** 三値量子化: 0..64 → 0、96..160 → 1、192..255 → 2、それ以外 → -1。 */
    static int level(int v) {
        if (v <= 64) return 0;
        if (v >= 96 && v <= 160) return 1;
        if (v >= 192) return 2;
        return -1;
    }

    /** 画素をパレット添字に分類する。該当なしは {@code PALETTE.length} ("other")。 */
    static int classify(int r, int g, int b) {
        int lr = level(r), lg = level(g), lb = level(b);
        if (lr < 0 || lg < 0 || lb < 0) return PALETTE.length;
        for (int i = 0; i < PALETTE.length; i++) {
            if (Math.round(PALETTE[i][0] * 2) == lr && Math.round(PALETTE[i][1] * 2) == lg
                    && Math.round(PALETTE[i][2] * 2) == lb) return i;
        }
        return PALETTE.length;
    }

    private static long[] classify(ByteBuffer data, int width, int[] q) {
        long[] counts = new long[PALETTE.length + 1];
        for (int y = q[1]; y < q[3]; y++) {
            long rowBase = (long) y * width * 4;
            for (int x = q[0]; x < q[2]; x++) {
                long at = rowBase + (long) x * 4;
                if (at + 2 >= data.limit()) {
                    counts[PALETTE.length]++;
                    continue;
                }
                counts[classify(data.get((int) at) & 0xFF, data.get((int) at + 1) & 0xFF,
                    data.get((int) at + 2) & 0xFF)]++;
            }
        }
        return counts;
    }

    private static String writeCrop(ByteBuffer data, int width, int[] q, String name) {
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
                    if (src + 2 >= data.limit()) break;
                    body[into++] = data.get((int) src);
                    body[into++] = data.get((int) src + 1);
                    body[into++] = data.get((int) src + 2);
                }
            }
            McNativeVulkanProbe.writeGzipFileBytes(name, header, body);
            return name;
        } catch (Throwable t) {
            note("could not retain the ladder crop " + name + ": " + t);
            return null;
        }
    }

    /**
     * 全フレームの 1/{@link #FRAME_SCALE} サムネイル。各出力画素は {@code FRAME_SCALE}² 画素の
     * チャネル別整数平均 (切り捨て)。端の端数ブロックは落とす。gate は crop から同じ平均を
     * 計算し、述べた矩形の位置で一致することを要求する。
     */
    private static String writeFrame(ByteBuffer data, int width, int height, String name) {
        String dir = System.getProperty("voxy.harness.output");
        if (dir == null || dir.isBlank()) return null;
        int w = width / FRAME_SCALE, h = height / FRAME_SCALE;
        if (w <= 0 || h <= 0) return null;
        try {
            var header = ("P6\n" + w + " " + h + "\n255\n").getBytes(StandardCharsets.US_ASCII);
            byte[] body = new byte[w * h * 3];
            int into = 0;
            for (int by = 0; by < h; by++) {
                for (int bx = 0; bx < w; bx++) {
                    int r = 0, g = 0, b = 0;
                    for (int y = by * FRAME_SCALE; y < (by + 1) * FRAME_SCALE; y++) {
                        long rowBase = (long) y * width * 4;
                        for (int x = bx * FRAME_SCALE; x < (bx + 1) * FRAME_SCALE; x++) {
                            long src = rowBase + (long) x * 4;
                            r += data.get((int) src) & 0xFF;
                            g += data.get((int) src + 1) & 0xFF;
                            b += data.get((int) src + 2) & 0xFF;
                        }
                    }
                    int n = FRAME_SCALE * FRAME_SCALE;
                    body[into++] = (byte) (r / n);
                    body[into++] = (byte) (g / n);
                    body[into++] = (byte) (b / n);
                }
            }
            McNativeVulkanProbe.writeGzipFileBytes(name, header, body);
            return name;
        } catch (Throwable t) {
            note("could not retain the ladder frame thumbnail " + name + ": " + t);
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

    private static McNativeDepthLadder create(VulkanDevice device, long mcDevice,
                                              int colourFormat, int depthFormat) {
        if (leakedPipelines >= LEAK_BUDGET) {
            note("already leaked " + leakedPipelines + " ladder pipeline(s); not building"
                + " another one");
            return null;
        }
        if (depthFormat == VK_FORMAT_UNDEFINED) {
            note("Minecraft's render target has no depth format, so a depth ladder would test"
                + " against nothing");
            return null;
        }
        var vk = device.vkDevice();
        long vertexModule = 0, fragmentModule = 0, layout = 0;
        long[] pipelines = new long[PIPELINE_COMPARE_OPS.length];
        try (MemoryStack stack = stackPush()) {
            // ⚠ シェーダは marker のものを<b>そのまま</b>使う。push constant の形が同じで、
            // 既に MC のデバイス上で動くことが測られているので、新しい未知を持ち込まない。
            vertexModule = module(vk, stack, VkShaderType.VERTEX,
                McNativeMarkerDraw.VERTEX_SOURCE, "depth-ladder.vert");
            fragmentModule = module(vk, stack, VkShaderType.FRAGMENT,
                McNativeMarkerDraw.FRAGMENT_SOURCE, "depth-ladder.frag");
            if (vertexModule == 0 || fragmentModule == 0) {
                destroy(vk, vertexModule, fragmentModule, 0, pipelines);
                return null;
            }
            var range = org.lwjgl.vulkan.VkPushConstantRange.calloc(1, stack)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .offset(0).size(36);
            var layoutInfo = org.lwjgl.vulkan.VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default().pPushConstantRanges(range);
            long[] handle = new long[1];
            if (vkCreatePipelineLayout(vk, layoutInfo, null, handle) != VK_SUCCESS) {
                note("vkCreatePipelineLayout failed for the depth ladder");
                destroy(vk, vertexModule, fragmentModule, 0, pipelines);
                return null;
            }
            layout = handle[0];
            // ⚠ round-10 review R10-CREATE-TEST: the test must exercise THIS path, not a copy
            // of its table. buildPipelines builds every create-info and hands it to the
            // creator; the test injects a creator that inspects the info instead of Vulkan.
            long[] built = buildPipelines(stack, vertexModule, fragmentModule, layout,
                colourFormat, depthFormat, vulkanCreator(vk));
            System.arraycopy(built, 0, pipelines, 0, built.length);
            boolean complete = true;
            for (long handle$ : pipelines) complete &= handle$ != 0;
            if (!complete) {
                destroy(vk, vertexModule, fragmentModule, layout, pipelines);
                return null;
            }
            Logger.info("[native-vk] created the depth-ladder pipelines on Minecraft's device"
                + " (colour " + colourFormat + ", depth " + depthFormat + ", depth writes OFF)");
            return new McNativeDepthLadder(device, mcDevice, colourFormat, depthFormat,
                vertexModule, fragmentModule, layout, pipelines);
        } catch (Throwable t) {
            note("could not build the depth ladder: " + t);
            destroy(vk, vertexModule, fragmentModule, layout, pipelines);
            return null;
        }
    }

    private static long module(org.lwjgl.vulkan.VkDevice vk, MemoryStack stack,
                               VkShaderType type, String source, String name) {
        var spirv = SpirvCompiler.compile(type, source, name);
        if (spirv == null) {
            note("could not compile " + name);
            return 0;
        }
        var info = org.lwjgl.vulkan.VkShaderModuleCreateInfo.calloc(stack).sType$Default()
            .pCode(spirv);
        long[] handle = new long[1];
        if (vkCreateShaderModule(vk, info, null, handle) != VK_SUCCESS) {
            note("vkCreateShaderModule failed for " + name);
            return 0;
        }
        return handle[0];
    }

    /**
     * <b>この probe の要点</b>: 深度テストは有効、<b>書き込みは無効</b>、比較だけが変わる。
     * {@link #pipeline} はこれをそのまま使い、テストはこれを直接検査する
     * (round-8 review: JSON の定数ではなくパイプライン状態そのものを守る)。
     */
    static VkPipelineDepthStencilStateCreateInfo depthStencilState(MemoryStack stack,
                                                                   int depthCompare) {
        return VkPipelineDepthStencilStateCreateInfo.calloc(stack)
            .sType$Default().depthTestEnable(true).depthWriteEnable(false)
            .depthCompareOp(depthCompare).depthBoundsTestEnable(false).stencilTestEnable(false);
    }

    /** {@link #PIPELINE_COMPARE_OPS} のコピー (梯子 LESS、対照 ALWAYS、補対照 GREATER)。 */
    static int[] compareOps() {
        return PIPELINE_COMPARE_OPS.clone();
    }

    /** 作成の注入点: 実運用では {@code vkCreateGraphicsPipelines}、テストでは検査。 */
    @FunctionalInterface
    interface PipelineCreator {
        long create(org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo.Buffer info);
    }

    /**
     * {@link #PIPELINE_COMPARE_OPS} の各演算について create-info を組み、{@code creator} に渡す。
     * 返り値は同じ添字のハンドル (0 = 失敗)。{@link #create} はこれを本物の作成関数で呼び、
     * テストは偽の creator で<b>同じ経路</b>が組んだ深度状態を検査する。
     */
    static long[] buildPipelines(MemoryStack stack, long vertexModule, long fragmentModule,
                                 long layout, int colourFormat, int depthFormat,
                                 PipelineCreator creator) {
        long[] handles = new long[PIPELINE_COMPARE_OPS.length];
        int[][] observed = new int[PIPELINE_COMPARE_OPS.length][];
        boolean intact = true;
        for (int i = 0; i < PIPELINE_COMPARE_OPS.length; i++) {
            var info = pipelineInfo(stack, vertexModule, fragmentModule, layout, colourFormat,
                depthFormat, PIPELINE_COMPARE_OPS[i]);
            handles[i] = creator.create(info);
            // ⚠ round-11 review R10-CREATE-TEST: the creator is the seam the test cannot
            // cross. Read the state back from the struct the creator was handed, AFTER it
            // returns: whatever it left in the struct is observed and published. (A creator that
            // rewrites the state for the call and restores it afterwards is not caught here —
            // a stated limit; nothing beyond this read-back is claimed.)
            var ds = info.get(0).pDepthStencilState();
            observed[i] = ds == null ? new int[] {-1, 0, 1}
                : new int[] {ds.depthCompareOp(), ds.depthTestEnable() ? 1 : 0,
                    ds.depthWriteEnable() ? 1 : 0};
            if (observed[i][0] != PIPELINE_COMPARE_OPS[i] || observed[i][1] != 1
                    || observed[i][2] != 0) {
                intact = false;
            }
        }
        synchronized (NOTES) {
            pipelineStates = observed;
        }
        if (!intact) {
            note("the depth-stencil state handed to pipeline creation is not the one the ladder"
                + " built (compare ops " + java.util.Arrays.deepToString(observed) + "); the"
                + " pipelines are not used");
            return new long[PIPELINE_COMPARE_OPS.length];
        }
        return handles;
    }

    /** 作成後に観測した深度状態のコピー ({compareOp, testEnable, writeEnable} × 3)。 */
    static int[][] pipelineStates() {
        synchronized (NOTES) {
            var out = new int[pipelineStates.length][];
            for (int i = 0; i < out.length; i++) out[i] = pipelineStates[i].clone();
            return out;
        }
    }

    /** 本番の creator: Vulkan を呼ぶだけ。これより先はテストでも読み戻しでも検査できない (明示した限界)。 */
    static PipelineCreator vulkanCreator(org.lwjgl.vulkan.VkDevice vk) {
        return info -> {
            long[] out = new long[1];
            if (vkCreateGraphicsPipelines(vk, VK_NULL_HANDLE, info, null, out) != VK_SUCCESS) {
                note("vkCreateGraphicsPipelines failed for the depth ladder (compare "
                    + info.get(0).pDepthStencilState().depthCompareOp() + ")");
                return 0;
            }
            return out[0];
        };
    }

    private static org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo.Buffer pipelineInfo(
            MemoryStack stack, long vertexModule, long fragmentModule, long layout,
            int colourFormat, int depthFormat, int depthCompare) {
        var stages = org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT)
            .module(vertexModule).pName(stack.UTF8("main"));
        stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT)
            .module(fragmentModule).pName(stack.UTF8("main"));
        var vertexInput = org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo.calloc(stack)
            .sType$Default();
        var assembly = org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
            .sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
            .primitiveRestartEnable(false);
        var viewportState = org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo.calloc(stack)
            .sType$Default().viewportCount(1).scissorCount(1);
        var raster = org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo.calloc(stack)
            .sType$Default().depthClampEnable(false).rasterizerDiscardEnable(false)
            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
            .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).depthBiasEnable(false).lineWidth(1.0f);
        var multisample = org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo.calloc(stack)
            .sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
            .sampleShadingEnable(false);
        var depthStencil = depthStencilState(stack, depthCompare);
        var blendAttachment = org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState
            .calloc(1, stack).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT).blendEnable(false);
        var blend = org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo.calloc(stack)
            .sType$Default().pAttachments(blendAttachment);
        var dynamic = org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo.calloc(stack)
            .sType$Default().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT,
                VK_DYNAMIC_STATE_SCISSOR));
        var rendering = org.lwjgl.vulkan.VkPipelineRenderingCreateInfo.calloc(stack)
            .sType$Default().pColorAttachmentFormats(stack.ints(colourFormat))
            .depthAttachmentFormat(depthFormat);
        return org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
            .pNext(rendering).pStages(stages).pVertexInputState(vertexInput)
            .pInputAssemblyState(assembly).pViewportState(viewportState)
            .pRasterizationState(raster).pMultisampleState(multisample)
            .pDepthStencilState(depthStencil).pColorBlendState(blend).pDynamicState(dynamic)
            .layout(layout);
    }

    private static void destroy(org.lwjgl.vulkan.VkDevice vk, long vertexModule,
                                long fragmentModule, long layout, long[] pipelines) {
        for (int i = pipelines.length - 1; i >= 0; i--) {
            if (pipelines[i] != 0) vkDestroyPipeline(vk, pipelines[i], null);
            pipelines[i] = 0;
        }
        if (layout != 0) vkDestroyPipelineLayout(vk, layout, null);
        if (fragmentModule != 0) vkDestroyShaderModule(vk, fragmentModule, null);
        if (vertexModule != 0) vkDestroyShaderModule(vk, vertexModule, null);
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        destroy(this.device.vkDevice(), this.vertexModule, this.fragmentModule, this.layout,
            this.pipelines);
    }

    /** 退役は MC の提出寿命に預ける。預けられなければ壊さずに漏らし、数える。 */
    private static void retire(McNativeDepthLadder ladder, VulkanDevice device) {
        if (ladder == null || ladder.destroyed) return;
        if (ladder.device != device) {
            note("the ladder pipelines belong to a device that is no longer current; leaking"
                + " them on purpose");
            ladder.destroyed = true;
            leakedPipelines++;
            return;
        }
        try {
            McNativeVulkan.encoder(device).queueForDestroy(ladder);
        } catch (Throwable t) {
            ladder.destroyed = true;
            leakedPipelines++;
            note("queueForDestroy refused the ladder pipelines (" + t + "); leaking them on"
                + " purpose rather than destroying something that may still be in use");
        }
    }

    public static void shutdown() {
        var ladder = instance;
        instance = null;
        if (ladder == null) return;
        var device = McNativeVulkan.device();
        if (device != null) {
            retire(ladder, device);
            writeEvidence();
            return;
        }
        leakedPipelines++;
        note("Minecraft's device is no longer reachable, so the ladder pipelines cannot be shown"
            + " to be unused; leaking them on purpose");
        ladder.destroyed = true;
        writeEvidence();
    }

    public static void shutdownImmediate(org.lwjgl.vulkan.VkDevice waitedDevice) {
        var ladder = instance;
        instance = null;
        if (ladder == null) return;
        if (waitedDevice == null || waitedDevice.address() != ladder.ownerDevice) {
            leakedPipelines++;
            note("the ladder belongs to a different device than the one whose idle was observed;"
                + " leaking it on purpose");
            ladder.destroyed = true;
            writeEvidence();
            return;
        }
        ladder.destroy();
        writeEvidence();
    }

    // ---------------- 証跡 ----------------

    private static void writeEvidence() {
        McNativeVulkanProbe.writeFile("native-depth-ladder.json", json());
    }

    static String json() {
        List<Sample> samples = samples();
        List<String> notes;
        synchronized (NOTES) {
            notes = List.copyOf(NOTES);
        }
        var sb = new StringBuilder("{\n");
        sb.append("  \"enabled\": ").append(Boolean.getBoolean(FLAG)).append(",\n");
        sb.append("  \"attempted\": ").append(attempted).append(",\n");
        sb.append("  \"completed\": ").append(!samples.isEmpty()).append(",\n");
        sb.append("  \"drawsRecorded\": ").append(drawsRecorded).append(",\n");
        sb.append("  \"rungs\": ").append(RUNGS).append(",\n");
        // ⚠ Not a literal: derived from the depth-stencil state read back after creation.
        int[][] states = pipelineStates();
        boolean writes = false;
        for (int[] st : states) writes |= st[2] != 0;
        sb.append("  \"depthWritesEnabled\": ").append(writes).append(",\n");
        sb.append("  \"pipelineStates\": [");
        for (int i = 0; i < states.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("[").append(states[i][0]).append(", ").append(states[i][1]).append(", ")
              .append(states[i][2]).append("]");
        }
        sb.append("],\n");
        sb.append("  \"zConventionMeasuredHere\": false,\n");
        sb.append("  \"rungDepths\": ").append(floats(depths())).append(",\n");
        sb.append("  \"band\": [").append(BAND_X0).append(", ").append(BAND_Y0).append(", ")
          .append(BAND_X1).append(", ").append(BAND_Y1).append("],\n");
        sb.append("  \"palette\": [");
        for (int i = 0; i < PALETTE.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(floats(PALETTE[i]));
        }
        sb.append("],\n");
        sb.append("  \"readbackInterval\": ").append(READBACK_INTERVAL).append(",\n");
        sb.append("  \"coexistEnabled\": ").append(Boolean.getBoolean(COEXIST_FLAG)).append(",\n");
        sb.append("  \"coexistRung\": ").append(COEXIST_RUNG).append(",\n");
        sb.append("  \"coexistRgb\": ").append(floats(COEXIST_RGB)).append(",\n");
        sb.append("  \"coexistRgbExact\": [").append(COEXIST_RGB_EXACT[0]).append(", ")
          .append(COEXIST_RGB_EXACT[1]).append(", ").append(COEXIST_RGB_EXACT[2]).append("],\n");
        sb.append("  \"coexist\": [");
        var coexist = coexist();
        for (int i = 0; i < coexist.size(); i++) {
            var c = coexist.get(i);
            sb.append(i > 0 ? ",\n    {" : "\n    {");
            sb.append("\"at\": ").append(c.at()).append(", \"present\": ").append(c.present())
              .append(", \"absent\": ").append(c.absent()).append(", \"other\": ").append(c.other())
              .append(", \"expectedPass\": ").append(c.expectedPass())
              .append(", \"expectedFail\": ").append(c.expectedFail())
              .append(", \"absentWherePass\": ").append(c.absentWherePass())
              .append(", \"presentWhereFail\": ").append(c.presentWhereFail())
              .append(", \"unchangedElsewhere\": ").append(c.unchangedElsewhere())
              .append(", \"file\": ").append(McNativeVulkanProbe.quote(c.file()))
              .append(", \"frameFile\": ").append(McNativeVulkanProbe.quote(c.frameFile())).append('}');
        }
        sb.append(coexist.isEmpty() ? "],\n" : "\n  ],\n");
        sb.append("  \"frameScale\": ").append(FRAME_SCALE).append(",\n");
        sb.append("  \"sampleLimit\": ").append(SAMPLE_LIMIT).append(",\n");
        sb.append("  \"samples\": [");
        for (int i = 0; i < samples.size(); i++) {
            var s = samples.get(i);
            sb.append(i > 0 ? ",\n    {" : "\n    {");
            sb.append("\"at\": ").append(s.at());
            sb.append(", \"flipped\": ").append(s.flipped());
            sb.append(", \"targetWidth\": ").append(s.targetWidth());
            sb.append(", \"targetHeight\": ").append(s.targetHeight());
            sb.append(", \"rect\": ").append(ints(s.rect()));
            sb.append(", \"counts\": {\"anomaly\": ").append(s.counts()[BASE]);
            sb.append(", \"low\": ").append(s.counts()[LOW]);
            sb.append(", \"rungs\": [");
            for (int r = 0; r < RUNGS; r++) sb.append(r > 0 ? ", " : "").append(s.counts()[RUNG0 + r]);
            sb.append("], \"other\": ").append(s.counts()[PALETTE.length]).append("}");
            sb.append(", \"rejectedFlipped\": ").append(s.rejectedFlipped());
            sb.append(", \"rejectedRect\": ").append(ints(s.rejectedRect()));
            sb.append(", \"rejectedOther\": ").append(s.rejectedOther());
            sb.append(", \"file\": ").append(McNativeVulkanProbe.quote(s.file()));
            sb.append(", \"rejectedFile\": ").append(McNativeVulkanProbe.quote(s.rejectedFile()));
            sb.append(", \"frameFile\": ").append(McNativeVulkanProbe.quote(s.frameFile()));
            sb.append(", \"stage\": ").append(McNativeVulkanProbe.quote(s.stage()));
            sb.append(", \"experiment\": ").append(McNativeVulkanProbe.quote(consumerOf(s.at())));
            sb.append(", \"camera\": [");
            for (int k = 0; k < 5; k++) {
                double v = s.camera()[k];
                sb.append(k > 0 ? ", " : "").append(Double.isFinite(v) ? Double.toString(v) : "null");
            }
            sb.append("]}");
        }
        sb.append(samples.isEmpty() ? "],\n" : "\n  ],\n");
        sb.append("  \"problems\": ").append(problems).append(",\n");
        sb.append("  \"firstProblem\": ").append(McNativeVulkanProbe.quote(firstProblem))
          .append(",\n");
        sb.append("  \"closeFailures\": ").append(closeFailures).append(",\n");
        sb.append("  \"leakedPipelines\": ").append(leakedPipelines).append(",\n");
        sb.append("  \"deviceDiverged\": ").append(deviceDiverged).append(",\n");
        // ⚠ 梯子は MC のシーン深度を測る。同じ実行で terrain probe (MC の深度をクリアする) や
        // marker (自分の箱に深度を書く) が動いていたら、測ったものは MC の深度ではない。
        // フラグと実際に記録した本数の両方を出す。
        sb.append("  \"terrainProbeEnabled\": ").append(Boolean.getBoolean(McNativeTerrainProbe.FLAG))
          .append(",\n");
        sb.append("  \"terrainDrawsRecorded\": ").append(McNativeTerrainProbe.status().drawsRecorded())
          .append(",\n");
        sb.append("  \"markerDrawEnabled\": ").append(Boolean.getBoolean(McNativeMarkerDraw.FLAG))
          .append(",\n");
        sb.append("  \"markerDrawsRecorded\": ").append(McNativeMarkerDraw.status().drawsRecorded())
          .append(",\n");
        // ⚠ terrain-LOAD writes Voxy's depth into MC's attachment, but only AFTER this probe's
        // readbacks of the same frame were requested, and only on sampled frames; MC clears
        // depth every frame, so the ladder's samples remain MC's own depth. Both are published
        // so the gate can reconcile the pass count with the sample count.
        sb.append("  \"terrainLoadEnabled\": ").append(McNativeTerrainLoad.enabled()).append(",\n");
        sb.append("  \"terrainLoadDrawsRecorded\": ").append(McNativeTerrainLoad.drawsRecorded())
          .append(",\n");
        sb.append("  \"realLoadEnabled\": ").append(McNativeRealLoad.enabled()).append(",\n");
        sb.append("  \"realLoadDrawsRecorded\": ").append(McNativeRealLoad.drawsRecorded())
          .append(",\n");
        sb.append("  \"device\": ").append(McNativeVulkanProbe.quote(deviceHandle()))
          .append(",\n");
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

    private static String ints(int[] values) {
        if (values == null) return "null";
        var sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }

    private static String deviceHandle() {
        var it = instance;
        if (it != null) return "0x" + Long.toHexString(it.ownerDevice);
        try {
            return "0x" + Long.toHexString(
                me.cortex.voxy.client.core.vk.VkContext.get().device.address());
        } catch (Throwable t) {
            return null;
        }
    }

    private static void note(String message) {
        synchronized (NOTES) {
            if (NOTES.contains(message) || NOTES.size() >= 32) return;
            NOTES.add(message);
        }
        Logger.warn("[native-vk] " + message);
    }
}
