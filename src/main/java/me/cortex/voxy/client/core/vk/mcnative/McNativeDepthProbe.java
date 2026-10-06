package me.cortex.voxy.client.core.vk.mcnative;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;

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
     * @param reversedZ    逆Zと述べられるか。判断できないときは {@code null}
     * @param basis        その判断の根拠、または判断できない理由
     * @param device       測った device のハンドル
     * @param notes        問題
     */
    public record Result(boolean attempted, boolean completed, boolean uniform, int vkFormat,
                         int width, int height, float min, float max, float topMean,
                         float bottomMean, float clearedValue, float clearedShare,
                         long[] histogram, Boolean reversedZ, String basis, String device,
                         List<String> notes) {}

    public static Result status() {
        synchronized (NOTES) {
            var r = result;
            if (r != null) return r;
            return new Result(attempted, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new long[BINS], null, "not measured", deviceHandle(), List.copyOf(NOTES));
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

            // ⚠ 向きは<b>十分に分離しているときだけ</b>述べる。上端が空だとは限らない。
            Boolean reversed = null;
            String basis;
            float separation = Math.abs(topMean - bottomMean);
            if (uniform) {
                // ⚠ 実測 (2026-10-06): コピーは<b>完了する</b>が、1708x960 の全画素が
                // 0.0 だった。何も書いていない隔離実行でも同じだった。
                // 「コピーが成功した」は「中身がシーンの深度である」を意味しない。
                // 一様な画像は深度規約について何も語らないので、そう記録する。
                basis = "the copy completed but every one of " + counted + " pixels is " + min
                    + "; a uniform image cannot be Minecraft's scene depth, so this path does"
                    + " not observe it (measured with nothing else writing to the image)";
            } else if (Float.isNaN(topMean) || Float.isNaN(bottomMean)) {
                basis = "the top or bottom band held no readable depth";
            } else if (separation < 0.05f) {
                basis = "the top band (" + topMean + ") and the bottom band (" + bottomMean
                    + ") are within " + separation + " of each other, which does not separate"
                    + " near from far; the convention cannot be read off this frame";
            } else if (bottomMean < topMean) {
                reversed = Boolean.FALSE;
                basis = "the bottom band (" + bottomMean + ", expected to be the ground close to"
                    + " the camera) is NEARER ZERO than the top band (" + topMean + ", expected"
                    + " to be sky), so smaller means closer: not reverse-Z";
            } else {
                reversed = Boolean.TRUE;
                basis = "the bottom band (" + bottomMean + ", expected to be the ground close to"
                    + " the camera) is NEARER ONE than the top band (" + topMean + ", expected"
                    + " to be sky), so larger means closer: reverse-Z";
            }

            result = new Result(true, true, uniform, format, width, height, min, max, topMean,
                bottomMean, clearedValue, clearedShare, histogram, reversed, basis,
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

    /** 測れなかったことも結果である。推測で埋めず、そう記録する。 */
    private static void fail(String why, int format, int width, int height) {
        note(why);
        result = new Result(true, false, false, format, width, height, 0, 0, 0, 0, 0, 0,
            new long[BINS], null, why, deviceHandle(), List.copyOf(NOTES));
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
        sb.append("  \"reversedZ\": ").append(r.reversedZ() == null ? "null" : r.reversedZ())
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
