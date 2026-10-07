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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * <b>Minecraft のシーン深度を、読み戻しではなく<i>挙動</i>で測る</b>診断。
 *
 * <h2>なぜこれが必要になったか</h2>
 * {@link McNativeDepthProbe} は MC の深度画像を転送コピーで読み戻せることを示したが、
 * <b>全画素が 0.0</b> だった。コピーが完了したことは中身がシーンの深度である保証にならないので、
 * あれは「この経路では観測できない」という<b>否定的な結果</b>にとどまる
 * (round-7 review R7-DEPTH-GATE はこれを正しく指摘した)。
 *
 * <p>共存 — Voxy の LoD 地形が MC 自身の地形に正しく隠される — には、MC のシーン深度に対する
 * 深度テストが要る。だから深度の値は<b>どうしても</b>知る必要がある。
 *
 * <h2>測り方: 梯子</h2>
 * MC のパスを<b>カラーも深度も LOAD</b> で開き (つまり MC のシーンをそのまま残し)、
 * 既知の NDC 深度 z をもつ小さなクアッドを<b>横に並べた列ごとに 1 本ずつ</b>、
 * <b>比較 LESS・深度書き込み無効</b>で描く。比較 LESS なので、列 i のクアッドは
 * {@code z_i < d_mc} のときだけ残る。
 *
 * <p>したがって<b>どの列が残ったか</b>が、その帯における MC の深度値を挟み込む。
 * これは転送コピーを一切使わず、深度テストそのものに答えさせる測り方である。
 *
 * <h2>⚠ この probe は MC の深度に<b>一切書かない</b></h2>
 * {@code depthWriteEnable = false}。カラーには書く (梯子が見える) が、深度は読むだけである。
 * {@link McNativeTerrainProbe} と違い MC のシーンをクリアもしない。
 *
 * <h2>段の置き方 — 逆Z の深度が実際に住む場所に置く</h2>
 * 最初の版は段を {@code (i+0.5)/8} と<b>線形</b>に置き、地形の帯で全段が落ちた。それは
 * 「深度がクリアされている」と「正しい逆Z のシーン深度」を<b>区別しない</b>結果だった:
 * MC は frame graph の先頭で深度を 0.0 にクリアし ({@code LevelRenderer.render} の clear pass、
 * 26.2 bytecode)、投影は {@code setPerspective(fov, aspect, zFar, zNear, ..)} と near/far を
 * 入れ替えて組む (同 {@code Projection})。つまり逆Z で、距離 d の面の深度は near/d 程度
 * (near 0.05 なら 1 ブロック先で 0.05、30 ブロック先で 0.0017) — 線形の段は全部
 * 「カメラから 1 ブロック以内」を試していた。
 *
 * <p>段はいま {@code 2^-16 .. 2^-2} (昇順) に置く。near 0.05 なら約 3000 ブロックから
 * 0.2 ブロックまでを覆う。残った接頭辞が途中で途切れれば、その帯のシーン深度が
 * <b>本当に残っている</b>ことと、その値の範囲が分かる。{@code 2^-16} すら残らなければ、
 * この hook の深度は本当に ~0 である。
 *
 * <p>⚠ 上の near/far 入れ替えとクリア値は<b>ソースから読んだ事実</b>で、この probe が測った
 * ものではない。この probe は規約を主張しない (下)。
 *
 * <h2>⚠ 規約 (逆Zか否か) は<b>まだ述べない</b></h2>
 * 梯子は「その帯の深度値」を測るだけである。0.3 が残って 0.7 が落ちたとして、それが
 * 「0.3 の方が手前」なのか「0.3 の方が奥」なのかは、<b>画面のどこが物理的に近いかを
 * 知らない限り決まらない</b> — round 7 が帯の平均に対して指摘したのと同じ穴である。
 * 規約は「カメラを既知量だけ対象に近づけて、同じ帯の測定値が動く向きを見る」で決める。
 * それはこの probe の次の増分であり、ここでは<b>測定値だけ</b>を出す。
 *
 * <p>既定で無効。{@code -Dvoxy.native.depthladder=true} のときだけ動く。
 */
public final class McNativeDepthLadder implements Destroyable {
    public static final String FLAG = "voxy.native.depthladder";

    /** 段の数。多いほど深度値を細かく挟み込めるが、列が細くなる。 */
    public static final int RUNGS = 8;

    /** 梯子の帯 (最終画像での NDC)。marker とは重ならない位置に置く。 */
    private static final float BAND_X0 = -0.60f, BAND_X1 = 0.60f;
    private static final float BAND_Y0 = 0.36f, BAND_Y1 = 0.20f;
    /**
     * <b>梯子帯に併置した</b>対照の細帯。比較 ALWAYS なので必ず残る。
     *
     * <p>⚠ 最初の版では対照を離れた位置 (y 0.16..0.06) に置いていた。それだと
     * 「梯子帯だけが何かに覆われた」場合と「深度テストで落ちた」場合を区別できない。
     * <b>同じ列の真上</b>に ALWAYS の帯を置けば、その交絡が消える:
     * 併置対照が残っていて段が残らないなら、違いは深度比較だけである。
     */
    private static final float INLINE_CONTROL_Y0 = 0.40f, INLINE_CONTROL_Y1 = 0.37f;
    /** 離れた位置の対照帯。記録がフレームに届いているかの大域的な確認。 */
    private static final float CONTROL_Y0 = 0.16f, CONTROL_Y1 = 0.06f;

    /** 段の色 (マゼンタ) と対照帯の色 (シアン)。marker と同じ値を使う。 */
    private static final float[] RUNG_RGBA = {1.0f, 0.0f, 1.0f, 1.0f};
    private static final float[] CONTROL_RGBA = {0.0f, 1.0f, 1.0f, 1.0f};

    private static final long READBACK_BUDGET_BYTES = 40L << 20;
    private static final int FAILURE_BUDGET = 3;

    private static final List<String> NOTES = new ArrayList<>();
    private static McNativeDepthLadder instance;
    private static long drawsRecorded;
    private static boolean attempted;
    private static boolean readbackInFlight;
    private static boolean measured;
    private static int problems;
    private static String firstProblem;
    private static int closeFailures;
    private static int leakedPipelines;
    private static final int LEAK_BUDGET = 3;
    private static Result result;
    private static boolean deviceDiverged;

    /**
     * 測定結果。
     *
     * @param attempted     読み戻しを要求したか
     * @param completed     読み戻せて数えられたか
     * @param rungDepths    各段の NDC 深度
     * @param rungSurvived  各段が残ったか (比較 LESS を通ったか)
     * @param rungFill      各段の列が自分の色で埋まっていた割合 (0..1)
     * @param controlFill   対照帯の充填率。低ければ<b>記録が届いていない</b>
     * @param lowerBound    残った段の最大 z。{@code d_mc} の下限 (これより大きい)
     * @param upperBound    落ちた段の最小 z。{@code d_mc} の上限 (これ以下)
     * @param note          問題。{@code null} なら期待どおり
     * @param sampleFile    生標本のファイル名
     * @param sampleRect    標本の矩形 (採用した向きでの、フレーム画素座標)
     * @param sampleAtDraw  取得時点の draw 本数
     * @param flipped       採用した向き。{@code true} なら読み戻した行順は最終画像と上下逆
     * @param targetWidth   読み戻したフレームの幅。矩形と帯を同じ式で解くために公開する
     * @param targetHeight  読み戻したフレームの高さ
     */
    public record Result(boolean attempted, boolean completed, float[] rungDepths,
                         boolean[] rungSurvived, float[] rungFill, float[] inlineControlFill,
                         float controlFill, Float lowerBound, Float upperBound, String note,
                         String sampleFile, int[] sampleRect, long sampleAtDraw,
                         boolean flipped, int targetWidth, int targetHeight) {}

    // ---------------- 保持するもの ----------------

    private final VulkanDevice device;
    private final long ownerDevice;
    private final int colourFormat, depthFormat;
    private final long vertexModule, fragmentModule, layout;
    /** 比較 LESS・深度書き込み無効。 */
    private final long testPipeline;
    /** 比較 ALWAYS・深度書き込み無効 (対照)。 */
    private final long alwaysPipeline;
    private boolean destroyed;

    private McNativeDepthLadder(VulkanDevice device, long ownerDevice, int colourFormat,
                                int depthFormat, long vertexModule, long fragmentModule,
                                long layout, long testPipeline, long alwaysPipeline) {
        this.device = device;
        this.ownerDevice = ownerDevice;
        this.colourFormat = colourFormat;
        this.depthFormat = depthFormat;
        this.vertexModule = vertexModule;
        this.fragmentModule = fragmentModule;
        this.layout = layout;
        this.testPipeline = testPipeline;
        this.alwaysPipeline = alwaysPipeline;
    }

    // ---------------- 入口 ----------------

    public static Result status() {
        synchronized (NOTES) {
            var r = result;
            if (r != null) return r;
            return new Result(attempted, false, depths(), new boolean[RUNGS], new float[RUNGS],
                new float[RUNGS], 0f, null, null, measured ? null : "not measured", null, null,
                0, false, 0, 0);
        }
    }

    /**
     * 各段の NDC 深度。{@code 2^-16, 2^-14, …, 2^-2} の昇順。
     *
     * <p>昇順でなければならない: 比較 LESS では残る集合が接頭辞になる、という gate の
     * 規則がそれに依る。間隔は {@code scripts/verify.py} の {@code EXPECTED_LADDER_DEPTHS} と
     * <b>同じ式</b>で固定されている。
     */
    public static float[] depths() {
        float[] z = new float[RUNGS];
        for (int i = 0; i < RUNGS; i++) z[i] = Math.scalb(1.0f, -(16 - 2 * i));
        return z;
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
            noteOnce("the main render target has no colour or depth view, so a depth ladder"
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
        if (ladder != null && (ladder.device != device || ladder.colourFormat != format
                || ladder.depthFormat != depthVk || ladder.ownerDevice != mcDevice)) {
            retire(ladder, device);
            if (instance == ladder) instance = null;
            ladder = null;
        }
        if (ladder == null) {
            if (mcDevice == 0) {
                noteOnce("Minecraft's current VkDevice handle could not be read, so the ladder"
                    + " cannot be bound to a device");
                return;
            }
            attempted = true;
            ladder = create(device, mcDevice, format, depthVk);
            if (ladder == null) return;
            instance = ladder;
        }

        // ⚠ カラーも深度も LOAD。MC のシーンと<b>その深度</b>をそのまま残す。
        // ここが McNativeTerrainProbe との決定的な違いで、測りたいものが MC の深度だから。
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

        if (!measured && !readbackInFlight && problems < FAILURE_BUDGET && drawsRecorded >= 2) {
            requestReadback(colour, width, height);
        }
        writeEvidence();
    }

    // ---------------- 記録 ----------------

    /**
     * 梯子と対照帯を記録する。<b>深度には書かない</b>。
     *
     * <p>段 i は帯を {@link #RUNGS} 等分した列 i を占め、深度 {@code (i+0.5)/RUNGS} で
     * 比較 LESS。残った列が {@code d_mc} を挟み込む。
     */
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
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.testPipeline);
            for (int i = 0; i < RUNGS; i++) {
                float x0 = BAND_X0 + (BAND_X1 - BAND_X0) * i / RUNGS;
                float x1 = BAND_X0 + (BAND_X1 - BAND_X0) * (i + 1) / RUNGS;
                push(cmd, stack, x0, BAND_Y0, x1, BAND_Y1, RUNG_RGBA, z[i]);
                vkCmdDraw(cmd, 6, 1, 0, 0);
            }
            // 対照: ALWAYS なので必ず残る。残らなければ記録が MC のフレームに届いていない。
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.alwaysPipeline);
            // 併置対照を<b>段と同じ列に</b>置く。段が落ちた理由が深度だけであることを示す。
            for (int i = 0; i < RUNGS; i++) {
                float x0 = BAND_X0 + (BAND_X1 - BAND_X0) * i / RUNGS;
                float x1 = BAND_X0 + (BAND_X1 - BAND_X0) * (i + 1) / RUNGS;
                push(cmd, stack, x0, INLINE_CONTROL_Y0, x1, INLINE_CONTROL_Y1, CONTROL_RGBA,
                    z[i]);
                vkCmdDraw(cmd, 6, 1, 0, 0);
            }
            push(cmd, stack, BAND_X0, CONTROL_Y0, BAND_X1, CONTROL_Y1, CONTROL_RGBA, 0.5f);
            vkCmdDraw(cmd, 6, 1, 0, 0);
        }
    }

    private void push(VkCommandBuffer cmd, MemoryStack stack, float x0, float y0, float x1,
                      float y1, float[] rgba, float depth) {
        ByteBuffer params = stack.malloc(36);
        params.putFloat(0, x0).putFloat(4, y0).putFloat(8, x1).putFloat(12, y1);
        params.putFloat(16, rgba[0]).putFloat(20, rgba[1]).putFloat(24, rgba[2])
              .putFloat(28, rgba[3]);
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
            readbackInFlight = true;
            gpu.createCommandEncoder().copyTextureToBuffer(colour.texture(), readback, 0,
                () -> measure(readback, width, height, at), 0);
            buffer = null;
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
     * どの段が残ったかを数える。
     *
     * <p>⚠ 向きは仮定しない。marker で測ったとおり MC の画像の行順は最終画像と反転しうるので、
     * <b>両方の向きで対照帯を探し、対照帯が埋まっている向きを採る</b>。
     * 対照帯は ALWAYS で描いていて必ず存在するので、向きの判定に使える基準になる。
     */
    private static void measure(GpuBuffer buffer, int width, int height, long at) {
        try (var view = new GpuBufferSlice(buffer, 0, buffer.size()).map(true, false)) {
            var data = view.data();
            Boolean chosen = null;
            float bestControl = -1f;
            for (boolean flipped : new boolean[] {false, true}) {
                float fill = fillOf(data, width, height, flipped, BAND_X0, CONTROL_Y0,
                    BAND_X1, CONTROL_Y1, CONTROL_RGBA);
                if (fill > bestControl) {
                    bestControl = fill;
                    chosen = flipped;
                }
            }
            if (bestControl < 0.8f) {
                fail("the always-pass control band is only " + bestControl + " filled in either"
                    + " orientation, so the ladder's draws did not reach Minecraft's frame and"
                    + " nothing can be concluded about its depth");
                return;
            }
            boolean flipped = chosen;
            float[] z = depths();
            boolean[] survived = new boolean[RUNGS];
            float[] fill = new float[RUNGS];
            float[] inline = new float[RUNGS];
            for (int i = 0; i < RUNGS; i++) {
                float x0 = BAND_X0 + (BAND_X1 - BAND_X0) * i / RUNGS;
                float x1 = BAND_X0 + (BAND_X1 - BAND_X0) * (i + 1) / RUNGS;
                fill[i] = fillOf(data, width, height, flipped, x0, BAND_Y0, x1, BAND_Y1,
                    RUNG_RGBA);
                inline[i] = fillOf(data, width, height, flipped, x0, INLINE_CONTROL_Y0, x1,
                    INLINE_CONTROL_Y1, CONTROL_RGBA);
                survived[i] = fill[i] >= 0.8f;
            }
            // ⚠ 併置対照が埋まっていない列は、深度の話ではなく「そこに描けていない」話。
            // その列の結果は使えないので、測定全体を問題として扱う。
            for (int i = 0; i < RUNGS; i++) {
                if (inline[i] < 0.8f) {
                    fail("the inline always-pass control above rung " + i + " is only "
                        + inline[i] + " filled, so that column was not drawn at all and its"
                        + " empty rung says nothing about depth");
                    return;
                }
            }
            Float lower = null, upper = null;
            for (int i = 0; i < RUNGS; i++) {
                if (survived[i]) lower = z[i];
                else if (upper == null) upper = z[i];
            }
            // ⚠ 段は z の昇順なので、比較 LESS なら残る集合は<b>前半の連続した塊</b>に
            // なるはずである。穴が空いていたら、深度以外の何か (覆い被さり、別の描画) が
            // 効いている。そのときは測定として扱わない。
            String note = null;
            boolean seenFailure = false;
            for (int i = 0; i < RUNGS; i++) {
                if (!survived[i]) seenFailure = true;
                else if (seenFailure) {
                    note = "the surviving rungs are not a prefix of the ladder (rung " + i
                        + " at z=" + z[i] + " survived after an earlier rung failed), so"
                        + " something other than the depth test decided this frame";
                    break;
                }
            }
            result = new Result(true, true, z, survived, fill, inline, bestControl, lower, upper,
                note, null, null, at, flipped, width, height);
            if (note == null) {
                measured = true;
            } else {
                problems++;
                if (firstProblem == null) firstProblem = note;
            }
            String sample = writeSample(data, width, height, flipped, at);
            result = new Result(true, true, z, survived, fill, inline, bestControl, lower, upper,
                note, sample, sampleRect(width, height, flipped), at, flipped, width, height);
            Logger.info("[native-vk] depth ladder on Minecraft's loaded depth: control="
                + bestControl + " survived=" + describe(survived, z)
                + " => Minecraft's depth in that band is"
                + (lower == null ? " <= " + upper : (upper == null ? " > " + lower
                    : " in (" + lower + ", " + upper + "]"))
                + (note == null ? "" : " PROBLEM: " + note));
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

    private static String describe(boolean[] survived, float[] z) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < survived.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(survived[i] ? "z" + z[i] : "-");
        }
        return sb.append(']').toString();
    }

    /** 指定した NDC 矩形が、指定した色でどれだけ埋まっているか (0..1)。 */
    private static float fillOf(ByteBuffer data, int width, int height, boolean flipped,
                                float ax, float ay, float bx, float by, float[] rgba) {
        int x0 = (int) ((Math.min(ax, bx) + 1.0f) * 0.5f * width);
        int x1 = (int) ((Math.max(ax, bx) + 1.0f) * 0.5f * width);
        float top = Math.max(ay, by), bottom = Math.min(ay, by);
        int y0, y1;
        if (flipped) {
            y0 = (int) ((1.0f + bottom) * 0.5f * height);
            y1 = (int) ((1.0f + top) * 0.5f * height);
        } else {
            y0 = (int) ((1.0f - top) * 0.5f * height);
            y1 = (int) ((1.0f - bottom) * 0.5f * height);
        }
        x0 = Math.max(0, x0); y0 = Math.max(0, y0);
        x1 = Math.min(width, x1); y1 = Math.min(height, y1);
        long area = (long) Math.max(0, x1 - x0) * Math.max(0, y1 - y0);
        if (area == 0) return 0f;
        int wantR = Math.round(rgba[0] * 255), wantG = Math.round(rgba[1] * 255);
        int wantB = Math.round(rgba[2] * 255);
        long hits = 0;
        for (int y = y0; y < y1; y++) {
            long rowBase = (long) y * width * 4;
            for (int x = x0; x < x1; x++) {
                long at = rowBase + (long) x * 4;
                if (at + 2 >= data.limit()) break;
                int r = data.get((int) at) & 0xFF;
                int g = data.get((int) at + 1) & 0xFF;
                int b = data.get((int) at + 2) & 0xFF;
                if (near(r, wantR) && near(g, wantG) && near(b, wantB)) hits++;
            }
        }
        return (float) ((double) hits / area);
    }

    private static boolean near(int got, int want) {
        return want >= 200 ? got >= 200 : got <= 60;
    }

    private static int[] sampleRect(int width, int height, boolean flipped) {
        int x0 = (int) ((BAND_X0 + 1.0f) * 0.5f * width);
        int x1 = (int) ((BAND_X1 + 1.0f) * 0.5f * width);
        float top = INLINE_CONTROL_Y0, bottom = CONTROL_Y1;
        int y0, y1;
        if (flipped) {
            y0 = (int) ((1.0f + bottom) * 0.5f * height);
            y1 = (int) ((1.0f + top) * 0.5f * height);
        } else {
            y0 = (int) ((1.0f - top) * 0.5f * height);
            y1 = (int) ((1.0f - bottom) * 0.5f * height);
        }
        return new int[] {Math.max(0, x0), Math.max(0, y0), Math.min(width, x1),
            Math.min(height, y1)};
    }

    /** 採用した向きで、梯子と対照帯を含む矩形を PPM (gzip) で残す。 */
    private static String writeSample(ByteBuffer data, int width, int height, boolean flipped,
                                      long at) {
        String dir = System.getProperty("voxy.harness.output");
        if (dir == null || dir.isBlank()) return null;
        int[] q = sampleRect(width, height, flipped);
        int w = q[2] - q[0], h = q[3] - q[1];
        if (w <= 0 || h <= 0) return null;
        String name = "native-depth-ladder-" + at + ".ppm.gz";
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
            note("could not retain the ladder sample: " + t);
            return null;
        }
    }

    private static void fail(String why) {
        problems++;
        if (firstProblem == null) firstProblem = why;
        note(why);
        result = new Result(true, false, depths(), new boolean[RUNGS], new float[RUNGS],
            new float[RUNGS], 0f, null, null, why, null, null, drawsRecorded, false, 0, 0);
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
        long vertexModule = 0, fragmentModule = 0, layout = 0, test = 0, always = 0;
        try (MemoryStack stack = stackPush()) {
            // ⚠ シェーダは marker のものを<b>そのまま</b>使う。push constant の形が同じで、
            // 既に MC のデバイス上で動くことが測られているので、新しい未知を持ち込まない。
            vertexModule = module(vk, stack, VkShaderType.VERTEX,
                McNativeMarkerDraw.VERTEX_SOURCE, "depth-ladder.vert");
            fragmentModule = module(vk, stack, VkShaderType.FRAGMENT,
                McNativeMarkerDraw.FRAGMENT_SOURCE, "depth-ladder.frag");
            if (vertexModule == 0 || fragmentModule == 0) {
                destroy(vk, vertexModule, fragmentModule, 0, 0, 0);
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
                destroy(vk, vertexModule, fragmentModule, 0, 0, 0);
                return null;
            }
            layout = handle[0];
            test = pipeline(vk, stack, vertexModule, fragmentModule, layout, colourFormat,
                depthFormat, VK_COMPARE_OP_LESS);
            always = pipeline(vk, stack, vertexModule, fragmentModule, layout, colourFormat,
                depthFormat, VK_COMPARE_OP_ALWAYS);
            if (test == 0 || always == 0) {
                destroy(vk, vertexModule, fragmentModule, layout, test, always);
                return null;
            }
            Logger.info("[native-vk] created the depth-ladder pipelines on Minecraft's device"
                + " (colour " + colourFormat + ", depth " + depthFormat + ", depth writes OFF)");
            return new McNativeDepthLadder(device, mcDevice, colourFormat, depthFormat,
                vertexModule, fragmentModule, layout, test, always);
        } catch (Throwable t) {
            note("could not build the depth ladder: " + t);
            destroy(vk, vertexModule, fragmentModule, layout, test, always);
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
     * @param depthCompare 比較。{@code LESS} が梯子、{@code ALWAYS} が対照。
     *                     <b>どちらも深度を書かない</b> — MC のシーン深度を壊さないため。
     */
    private static long pipeline(org.lwjgl.vulkan.VkDevice vk, MemoryStack stack,
                                 long vertexModule, long fragmentModule, long layout,
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
        // ⚠ depthWriteEnable(false) がこの probe の要点。MC の深度は読むだけ。
        var depthStencil = org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo.calloc(stack)
            .sType$Default().depthTestEnable(true).depthWriteEnable(false)
            .depthCompareOp(depthCompare).depthBoundsTestEnable(false).stencilTestEnable(false);
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
        var info = org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
            .pNext(rendering).pStages(stages).pVertexInputState(vertexInput)
            .pInputAssemblyState(assembly).pViewportState(viewportState)
            .pRasterizationState(raster).pMultisampleState(multisample)
            .pDepthStencilState(depthStencil).pColorBlendState(blend).pDynamicState(dynamic)
            .layout(layout);
        long[] handle = new long[1];
        if (vkCreateGraphicsPipelines(vk, VK_NULL_HANDLE, info, null, handle) != VK_SUCCESS) {
            note("vkCreateGraphicsPipelines failed for the depth ladder (compare "
                + depthCompare + ")");
            return 0;
        }
        return handle[0];
    }

    private static void destroy(org.lwjgl.vulkan.VkDevice vk, long vertexModule,
                                long fragmentModule, long layout, long test, long always) {
        if (always != 0) vkDestroyPipeline(vk, always, null);
        if (test != 0) vkDestroyPipeline(vk, test, null);
        if (layout != 0) vkDestroyPipelineLayout(vk, layout, null);
        if (fragmentModule != 0) vkDestroyShaderModule(vk, fragmentModule, null);
        if (vertexModule != 0) vkDestroyShaderModule(vk, vertexModule, null);
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        destroy(this.device.vkDevice(), this.vertexModule, this.fragmentModule, this.layout,
            this.testPipeline, this.alwaysPipeline);
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
            return;
        }
        leakedPipelines++;
        note("Minecraft's device is no longer reachable, so the ladder pipelines cannot be shown"
            + " to be unused; leaking them on purpose");
        ladder.destroyed = true;
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
            return;
        }
        ladder.destroy();
    }

    // ---------------- 証跡 ----------------

    private static void writeEvidence() {
        McNativeVulkanProbe.writeFile("native-depth-ladder.json", json());
    }

    static String json() {
        var r = status();
        var sb = new StringBuilder("{\n");
        sb.append("  \"enabled\": ").append(Boolean.getBoolean(FLAG)).append(",\n");
        sb.append("  \"attempted\": ").append(r.attempted()).append(",\n");
        sb.append("  \"completed\": ").append(r.completed()).append(",\n");
        sb.append("  \"drawsRecorded\": ").append(drawsRecorded).append(",\n");
        sb.append("  \"rungs\": ").append(RUNGS).append(",\n");
        sb.append("  \"depthWritesEnabled\": false,\n");
        sb.append("  \"zConventionMeasuredHere\": false,\n");
        sb.append("  \"rungDepths\": ").append(floats(r.rungDepths())).append(",\n");
        sb.append("  \"rungSurvived\": ").append(bools(r.rungSurvived())).append(",\n");
        sb.append("  \"rungFill\": ").append(floats(r.rungFill())).append(",\n");
        sb.append("  \"inlineControlFill\": ").append(floats(r.inlineControlFill()))
          .append(",\n");
        sb.append("  \"inlineControlBand\": [").append(BAND_X0).append(", ")
          .append(INLINE_CONTROL_Y0).append(", ").append(BAND_X1).append(", ")
          .append(INLINE_CONTROL_Y1).append("],\n");
        sb.append("  \"controlFill\": ").append(r.controlFill()).append(",\n");
        sb.append("  \"lowerBound\": ").append(r.lowerBound() == null ? "null" : r.lowerBound())
          .append(",\n");
        sb.append("  \"upperBound\": ").append(r.upperBound() == null ? "null" : r.upperBound())
          .append(",\n");
        sb.append("  \"note\": ").append(McNativeVulkanProbe.quote(r.note())).append(",\n");
        sb.append("  \"problems\": ").append(problems).append(",\n");
        sb.append("  \"firstProblem\": ").append(McNativeVulkanProbe.quote(firstProblem))
          .append(",\n");
        sb.append("  \"closeFailures\": ").append(closeFailures).append(",\n");
        sb.append("  \"leakedPipelines\": ").append(leakedPipelines).append(",\n");
        sb.append("  \"deviceDiverged\": ").append(deviceDiverged).append(",\n");
        sb.append("  \"sampleFile\": ").append(McNativeVulkanProbe.quote(r.sampleFile()))
          .append(",\n");
        sb.append("  \"sampleRect\": ").append(r.sampleRect() == null ? "null"
            : "[" + r.sampleRect()[0] + ", " + r.sampleRect()[1] + ", " + r.sampleRect()[2]
              + ", " + r.sampleRect()[3] + "]").append(",\n");
        sb.append("  \"sampleAtDraw\": ").append(r.sampleAtDraw()).append(",\n");
        // ⚠ 向きとフレーム寸法は、gate が矩形と帯を<b>この実装と同じ式</b>で解くために要る。
        // 公開しなければ、再集計は向きを推測することになる (round 6 B4 と同じ穴)。
        sb.append("  \"flipped\": ").append(r.flipped()).append(",\n");
        sb.append("  \"targetWidth\": ").append(r.targetWidth()).append(",\n");
        sb.append("  \"targetHeight\": ").append(r.targetHeight()).append(",\n");
        // ⚠ 梯子は MC のシーン深度を測る。同じ実行で terrain probe (MC の深度をクリアする) や
        // marker (自分の箱に深度を書く) が動いていたら、測ったものは MC の深度ではない。
        // フラグと実際に記録した本数の両方を出す: フラグだけでは「有効だが描かなかった」と
        // 「無効」を区別できないし、本数だけでは有効化の事実を隠せる。
        sb.append("  \"terrainProbeEnabled\": ").append(Boolean.getBoolean(McNativeTerrainProbe.FLAG))
          .append(",\n");
        sb.append("  \"terrainDrawsRecorded\": ").append(McNativeTerrainProbe.status().drawsRecorded())
          .append(",\n");
        sb.append("  \"markerDrawEnabled\": ").append(Boolean.getBoolean(McNativeMarkerDraw.FLAG))
          .append(",\n");
        sb.append("  \"markerDrawsRecorded\": ").append(McNativeMarkerDraw.status().drawsRecorded())
          .append(",\n");
        sb.append("  \"band\": [").append(BAND_X0).append(", ").append(BAND_Y0).append(", ")
          .append(BAND_X1).append(", ").append(BAND_Y1).append("],\n");
        sb.append("  \"controlBand\": [").append(BAND_X0).append(", ").append(CONTROL_Y0)
          .append(", ").append(BAND_X1).append(", ").append(CONTROL_Y1).append("],\n");
        sb.append("  \"device\": ").append(McNativeVulkanProbe.quote(deviceHandle()))
          .append(",\n");
        sb.append("  \"notes\": [");
        List<String> notes;
        synchronized (NOTES) {
            notes = List.copyOf(NOTES);
        }
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

    private static String bools(boolean[] values) {
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

    private static void noteOnce(String message) {
        note(message);
    }
}
