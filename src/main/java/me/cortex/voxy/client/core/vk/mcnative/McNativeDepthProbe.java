package me.cortex.voxy.client.core.vk.mcnative;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;

/**
 * <b>Minecraft のシーン深度を読み戻して、その規約を測る</b>診断。
 *
 * <h2>なぜこれが要るのか</h2>
 * Voxy の LoD 地形を MC のフレームに<b>共存させる</b>には、MC が描いたシーン深度に対して
 * 深度テストしなければならない。そのためには少なくとも次の 2 つを知る必要がある:
 *
 * <ol>
 *   <li>MC の深度画像が<b>そもそも読み戻せるか</b> (読めなければ、共存の正しさを
 *       測る手段が無い。MC の encoder は {@code VulkanConst.formatAspectMask} で
 *       アスペクトをフォーマットから導くので読めるはずだが、<b>はずだ</b>では足りない)</li>
 *   <li>MC の深度が<b>逆Zか否か</b> — 近いほど値が大きいのか小さいのか。
 *       Voxy 側は {@link me.cortex.voxy.client.core.vk.VkDepth} の規約で描くので、
 *       向きが逆なら共存は「全部通る」か「全部落ちる」のどちらかになる</li>
 * </ol>
 *
 * <h2>どちらも推測しない</h2>
 * (2) は<b>画面の上端と下端の深度を比べて</b>決める。上端は空 (無限遠) になりやすく、
 * 下端は足元の地面 (至近) になりやすい。この 2 つの大小関係が規約を直接に示す。
 * ⚠ ただし「上端が必ず空」ではない (洞窟、壁の前、GUI)。だから<b>断定はせず</b>、
 * 観測した分布をそのまま証跡に出し、十分に分離しているときだけ向きを述べる。
 *
 * <p>既定で無効。{@code -Dvoxy.native.depth=true} のときだけ動く。
 * 読み戻すだけで、MC の画像には<b>一切書かない</b>。
 */
public final class McNativeDepthProbe {
    public static final String FLAG = "voxy.native.depth";

    /** 読み戻しの上限 (深度は 4 バイト/画素)。 */
    private static final long BUDGET_BYTES = 40L << 20;

    /** 分布を出すビンの数。 */
    private static final int BINS = 16;

    private static final List<String> NOTES = new ArrayList<>();
    private static boolean attempted;
    private static boolean inFlight;
    private static boolean done;
    private static Result result;
    private static int closeFailures;

    /**
     * 測定結果。
     *
     * @param attempted    読み戻しを要求したか
     * @param completed    読み戻せて測れたか
     * @param vkFormat     MC の深度フォーマット (実測。D32_SFLOAT は 126)
     * @param width        画像の幅
     * @param height       画像の高さ
     * @param min          観測した最小値
     * @param max          観測した最大値
     * @param topMean      画像の上端 2% 行の平均
     * @param bottomMean   画像の下端 2% 行の平均
     * @param clearedValue 最も多く出た値 (ほぼ確実にクリア値 = 最遠面)
     * @param clearedShare その値が占める割合
     * @param histogram    [0,1] を {@link #BINS} 等分したヒストグラム
     * @param basis        観測の記述。<b>規約は述べない</b> (R7-DEPTH-GATE)
     * @param sampleFile   生の深度を残したファイル名。ゲートが数え直せるようにする
     * @param device       測った device のハンドル
     * @param notes        問題
     */
    public record Result(boolean attempted, boolean completed, boolean uniform, int vkFormat,
                         int width, int height, float min, float max, float topMean,
                         float bottomMean, float clearedValue, float clearedShare,
                         long[] histogram, String basis, String sampleFile, String device,
                         List<String> notes) {}

    public static Result status() {
        synchronized (NOTES) {
            var r = result;
            if (r != null) return r;
            return new Result(attempted, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new long[BINS], "not measured", null, deviceHandle(), List.copyOf(NOTES));
        }
    }

    /**
     * 一度だけ測る。フラグが無効なら<b>何もしない</b> —
     * MC の device も触らず、確保もせず、ファイルも書かない。
     */
    public static void probeOnce() {
        if (!Boolean.getBoolean(FLAG)) return;
        if (done || inFlight) return;
        try {
            request();
        } catch (Throwable t) {
            note("the native depth probe failed: " + t);
            done = true;
        }
    }

    private static void request() {
        var notes = new ArrayList<String>();
        VulkanDevice device = McNativeVulkan.device(notes);
        if (device == null) {
            notes.forEach(McNativeDepthProbe::note);
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc == null || mc.gameRenderer == null) return;
        var target = mc.gameRenderer.mainRenderTarget();
        if (target == null) return;
        GpuTextureView depth = target.getDepthTextureView();
        if (depth == null) {
            note("the main render target has no depth view");
            done = true;
            return;
        }
        int format = VulkanConst.toVk(depth.texture().getFormat());
        int width = depth.getWidth(0);
        int height = depth.getHeight(0);
        if (width <= 0 || height <= 0) return;
        if (format != VK_FORMAT_D32_SFLOAT) {
            // ⚠ 他のフォーマットだと 1 画素のバイト数も解釈も変わる。推測しない。
            note("Minecraft's depth format is VkFormat " + format + ", not D32_SFLOAT (126);"
                + " this probe only knows how to read 32-bit float depth");
            done = true;
            return;
        }
        long bytes = (long) width * height * 4;
        if (bytes > BUDGET_BYTES) {
            note("Minecraft's depth image is " + bytes + " bytes, over the " + BUDGET_BYTES
                + " byte budget; not reading it rather than allocating that much");
            done = true;
            return;
        }

        attempted = true;
        GpuBuffer buffer = null;
        try {
            var gpu = RenderSystem.getDevice();
            buffer = gpu.createBuffer(() -> "voxy native depth readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            final GpuBuffer readback = buffer;
            inFlight = true;
            gpu.createCommandEncoder().copyTextureToBuffer(depth.texture(), readback, 0,
                () -> measure(readback, format, width, height), 0);
            buffer = null;   // 以後の解放は callback 側の責務
        } catch (Throwable t) {
            // ⚠ これは<b>答えである</b>: MC の深度は読み戻せない。推測の代わりに記録する。
            fail("Minecraft's depth image could not be copied to a buffer: " + t, format,
                width, height);
        } finally {
            if (buffer != null) {
                try {
                    buffer.close();
                } catch (Throwable t) {
                    closeFailures++;
                    note("could not close the unregistered depth buffer: " + t);
                }
            }
        }
    }

    private static void measure(GpuBuffer buffer, int format, int width, int height) {
        try (var view = new GpuBufferSlice(buffer, 0, buffer.size()).map(true, false)) {
            var data = view.data();
            long[] histogram = new long[BINS];
            float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
            // 最も多いビンの代表値を「クリア値」として扱う。全画面の最遠面である。
            double topSum = 0, bottomSum = 0;
            long topCount = 0, bottomCount = 0;
            int band = Math.max(1, height / 50);   // 上下 2%
            long counted = 0;
            for (int y = 0; y < height; y++) {
                long rowBase = (long) y * width * 4;
                for (int x = 0; x < width; x++) {
                    long at = rowBase + (long) x * 4;
                    if (at + 3 >= data.limit()) break;
                    float z = data.getFloat((int) at);
                    if (Float.isNaN(z)) continue;
                    counted++;
                    if (z < min) min = z;
                    if (z > max) max = z;
                    int bin = (int) Math.floor(Math.max(0, Math.min(0.999999, z)) * BINS);
                    histogram[bin]++;
                    if (y < band) { topSum += z; topCount++; }
                    if (y >= height - band) { bottomSum += z; bottomCount++; }
                }
            }
            if (counted == 0) {
                fail("the depth readback contained no readable values", format, width, height);
                return;
            }
            boolean uniform = min == max;
            int fullest = 0;
            for (int i = 1; i < BINS; i++) if (histogram[i] > histogram[fullest]) fullest = i;
            float clearedValue = (fullest + 0.5f) / BINS;
            float clearedShare = (float) ((double) histogram[fullest] / counted);
            float topMean = topCount == 0 ? Float.NaN : (float) (topSum / topCount);
            float bottomMean = bottomCount == 0 ? Float.NaN : (float) (bottomSum / bottomCount);

            // ⚠ round-7 review R7-DEPTH-GATE: ここには「下端は足元の地面 (近)、
            // 上端は空 (遠)」という<b>前提</b>に基づいて逆Zを述べるコードがあった。
            // レビュアの指摘は正しい: <b>帯の平均が離れていることは、どちらが近いかを
            // 特定しない</b>。壁・洞窟・見上げ/見下ろし・行順の反転のいずれでも逆になる。
            // しかもゲートは「帯が離れているなら unknown を認めない」としていたので、
            // 証明されていない前提から確信を強制していた。
            //
            // だから<b>この probe は規約を述べない</b>。観測した値だけを出す。
            // 規約は、既知の深度で描いて<b>何が残るか</b>を見る実験
            // (= McNativeMarkerDraw が既に成立させている手法) でしか決まらない。
            String basis = uniform
                ? "the copy completed but every one of " + counted + " pixels is " + min
                    + "; a uniform image cannot be Minecraft's scene depth, so this path does"
                    + " not observe it"
                : "observed min " + min + ", max " + max + ", top band " + topMean
                    + ", bottom band " + bottomMean + " over " + counted + " pixels."
                    + " THE Z CONVENTION IS NOT INFERRED FROM THIS: which screen region is"
                    + " nearer is not known, so band order says nothing about the direction"
                    + " of depth. It must be measured by drawing at known depths against a"
                    + " loaded attachment.";
            String sample = writeSample(data, width, height, counted);

            result = new Result(true, true, uniform, format, width, height, min, max, topMean,
                bottomMean, clearedValue, clearedShare, histogram, basis, sample,
                deviceHandle(), List.copyOf(NOTES));
            done = true;
            Logger.info("[native-vk] read Minecraft's depth image back: " + width + "x" + height
                + " min=" + min + " max=" + max + " top=" + topMean + " bottom=" + bottomMean
                + " cleared~" + clearedValue + " (" + Math.round(clearedShare * 100) + "% of the"
                + " frame); " + basis);
            write();
        } catch (Throwable t) {
            fail("the depth readback could not be measured: " + t, format, width, height);
        } finally {
            inFlight = false;
            try {
                buffer.close();
            } catch (Throwable t) {
                closeFailures++;
                note("could not close the depth readback buffer: " + t);
                write();
            }
        }
    }

    /**
     * 生の深度を gzip した P6 相当で残す。
     *
     * <p>⚠ round-7 review R7-DEPTH-GATE: 集計 (ヒストグラム・最小最大・帯の平均) しか
     * 残していなかったので、<b>互いに矛盾する集計が通った</b> — 実測ゼロのヒストグラムの
     * まま min=0/max=1/bottomMean=0.8 と書けば「読めた」ことになった。
     * 生の値を残し、Python 側が数え直して集計と突き合わせられるようにする。
     *
     * <p>深度は 32bit float なので、[0,1] を 0..65535 に量子化した 16bit grey の
     * PGM (P5) で残す。<b>可逆ではない</b>が、ヒストグラム・一様性・最小最大・帯の平均を
     * 検算するには十分で、1708x960 なら gzip 後は数十 KB に収まる。
     */
    private static String writeSample(java.nio.ByteBuffer data, int width, int height,
                                      long counted) {
        String dir = System.getProperty("voxy.harness.output");
        if (dir == null || dir.isBlank()) return null;
        String name = "native-depth-sample.pgm.gz";
        try {
            var header = ("P5\n" + width + " " + height + "\n65535\n")
                .getBytes(StandardCharsets.US_ASCII);
            byte[] body = new byte[width * height * 2];
            int into = 0;
            for (int y = 0; y < height; y++) {
                long rowBase = (long) y * width * 4;
                for (int x = 0; x < width; x++) {
                    long at = rowBase + (long) x * 4;
                    int q = 0;
                    if (at + 3 < data.limit()) {
                        float z = data.getFloat((int) at);
                        if (!Float.isNaN(z)) {
                            q = (int) Math.round(Math.max(0, Math.min(1, z)) * 65535.0);
                        }
                    }
                    body[into++] = (byte) ((q >>> 8) & 0xFF);   // PGM は big-endian
                    body[into++] = (byte) (q & 0xFF);
                }
            }
            McNativeVulkanProbe.writeGzipFileBytes(name, header, body);
            return name;
        } catch (Throwable t) {
            note("could not retain the raw depth sample: " + t);
            return null;
        }
    }

    /** 測れなかったことも結果である。推測で埋めず、そう記録する。 */
    private static void fail(String why, int format, int width, int height) {
        note(why);
        result = new Result(true, false, false, format, width, height, 0, 0, 0, 0, 0, 0,
            new long[BINS], why, null, deviceHandle(), List.copyOf(NOTES));
        done = true;
        write();
    }

    private static void write() {
        McNativeVulkanProbe.writeFile("native-depth-probe.json", json());
    }

    static String json() {
        var r = status();
        var sb = new StringBuilder("{\n");
        sb.append("  \"enabled\": ").append(Boolean.getBoolean(FLAG)).append(",\n");
        sb.append("  \"attempted\": ").append(r.attempted()).append(",\n");
        sb.append("  \"completed\": ").append(r.completed()).append(",\n");
        sb.append("  \"uniform\": ").append(r.uniform()).append(",\n");
        sb.append("  \"depthVkFormat\": ").append(r.vkFormat()).append(",\n");
        sb.append("  \"width\": ").append(r.width()).append(",\n");
        sb.append("  \"height\": ").append(r.height()).append(",\n");
        sb.append("  \"min\": ").append(r.min()).append(",\n");
        sb.append("  \"max\": ").append(r.max()).append(",\n");
        sb.append("  \"topMean\": ").append(r.topMean()).append(",\n");
        sb.append("  \"bottomMean\": ").append(r.bottomMean()).append(",\n");
        sb.append("  \"clearedValue\": ").append(r.clearedValue()).append(",\n");
        sb.append("  \"clearedShare\": ").append(r.clearedShare()).append(",\n");
        sb.append("  \"bins\": ").append(BINS).append(",\n");
        sb.append("  \"histogram\": [");
        for (int i = 0; i < r.histogram().length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(r.histogram()[i]);
        }
        sb.append("],\n");
        // ⚠ round-7 review R7-DEPTH-GATE: ここには reversedZ があった。帯の平均からは
        // 決まらないので<b>出さない</b>。規約は別の実験で測る。
        sb.append("  \"zConventionMeasuredHere\": false,\n");
        sb.append("  \"sampleFile\": ").append(McNativeVulkanProbe.quote(r.sampleFile()))
          .append(",\n");
        sb.append("  \"basis\": ").append(McNativeVulkanProbe.quote(r.basis())).append(",\n");
        sb.append("  \"closeFailures\": ").append(closeFailures).append(",\n");
        sb.append("  \"device\": ").append(McNativeVulkanProbe.quote(r.device())).append(",\n");
        sb.append("  \"notes\": [");
        for (int i = 0; i < r.notes().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(McNativeVulkanProbe.quote(r.notes().get(i)));
        }
        sb.append("]\n}\n");
        return sb.toString();
    }

    private static String deviceHandle() {
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

    private McNativeDepthProbe() {
        throw new AssertionError();
    }
}
