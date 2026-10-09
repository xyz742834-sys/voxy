package me.cortex.voxy.client.core.vk.mcnative;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.cortex.voxy.client.core.vk.SyntheticTerrain;
import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.VkDepth;
import me.cortex.voxy.client.core.vk.VkRenderTarget;
import me.cortex.voxy.client.core.vk.VkSceneUniform;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer;
import me.cortex.voxy.client.core.vk.VkTerrainResources;
import com.mojang.blaze3d.vulkan.VulkanConst;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * <b>Voxy の本物の地形パイプラインを、Minecraft 自身の Vulkan フレームに描く</b>診断。
 *
 * <h2>これが示すこと / 示さないこと</h2>
 * <table>
 *   <tr><th>示す</th><th>示さない</th></tr>
 *   <tr><td>Voxy の地形シェーダ・パイプライン・間接描画が
 *           <b>MC の device / colour 画像の上で</b>動く</td>
 *       <td>実ワールドの地形が正しく見えること</td></tr>
 *   <tr><td>その絵が、同じ device 上の<b>Voxy 自身の的に描いた絵と画素単位で一致</b>する</td>
 *       <td>MC のシーンと正しく合成/深度共存できること</td></tr>
 *   <tr><td>外部のパスに記録する継ぎ目 ({@link VkTerrainRenderer#recordDrawsInRenderPass})
 *           が実機で成立する</td>
 *       <td>性能、LoD、アトラス、実データ経路</td></tr>
 * </table>
 *
 * <h2>⚠ これは<b>実験</b>である</h2>
 * round-4 の独立レビューは「診断層はまだ地形作業の<b>受理された土台として使えるほど
 * 健全ではない</b>。地形の探索は実験として構わないが、独立に受理された診断層からの
 * 継続として扱ってはならない」と述べた。この probe はその許しの範囲にある。
 * 既定で無効であり、{@code -Dvoxy.native.terrain=true} のときだけ動く。
 *
 * <h2>⚠ 有効にすると MC のフレームを<b>上書きする</b></h2>
 * 比較を成立させるために、自分のパスで MC の colour と depth を<b>クリアする</b>。
 * つまり画面はこの合成地形になる (MC の GUI はこの後に描かれるので残る)。
 * 診断として意図的であり、フラグ無効時は一切行われない。
 *
 * <h2>合成データであること</h2>
 * 入力は {@link SyntheticTerrain#boundaryCases()} である。実データ (ワールド読み込み +
 * メッシュ生成 + アトラス) はこの経路では用意できない。問うているのは
 * 「同じ入力から、Voxy 自身の的と MC の画像に同じ絵が出るか」なので、この限定で足りる。
 */
public final class McNativeTerrainProbe implements Destroyable {
    public static final String FLAG = "voxy.native.terrain";

    /** 背景。地形が出す色と紛れないように選ぶ ({@code VkTerrainRenderTest} と同じ値)。 */
    private static final float[] CLEAR = {0.05f, 0.05f, 0.10f, 1.0f};

    /** 読み戻しの上限。これを超える画面では測らない ({@link McNativeMarkerDraw} と同じ予算)。 */
    private static final long READBACK_BUDGET_BYTES = 40L << 20;

    /** 比較の間隔 (draw 本数)。しきい値であって差ではない。 */
    private static final long COMPARE_INTERVAL = 240;

    /** 比較が問題を見つけた回数の上限。超えたら要求をやめる。 */
    private static final int COMPARE_FAILURE_BUDGET = 3;

    private static final List<String> NOTES = new ArrayList<>();
    private static McNativeTerrainProbe instance;
    private static long drawsRecorded;
    private static long nextCompareAt = 2;
    private static boolean compareInFlight;
    private static int compareOk;
    private static int compareProblems;
    private static String firstCompareProblem;
    private static int closeFailures;
    private static Comparison comparison;
    private static long evidenceWrittenAt = -1;
    private static boolean attempted;
    /**
     * 壊さずに漏らした probe の数。
     *
     * <p>⚠ round-5 review R4-L1: marker 側は「退役を預けられなければ漏らす」を
     * <b>数えていなかった</b>ので、resize を繰り返すと静かに積み上がり得た。
     * 数えて証跡に出し、予算を超えたら<b>組み立てをやめる</b>。
     */
    private static int leakedProbes;

    /** 直近の標本書き込みが失敗したか。比較を清浄として数えないために使う。 */
    private static boolean sampleWriteFailed;

    /**
     * MC の device が Voxy の採用した device から離れたか。
     *
     * <p>一度離れたら<b>この session では二度と記録しない</b>。採用はやり直せないので、
     * 作り直しても古い context の資源を使うことになる。
     */
    private static boolean deviceDiverged;

    /** 漏らしてよい probe の数。超えたら新しく作らない。 */
    private static final int LEAK_BUDGET = 3;

    /**
     * 比較の結果。
     *
     * @param attempted     読み戻しを要求したか
     * @param completed     読み戻しが完了して比較できたか
     * @param referenceSet  Voxy 自身の的の、背景でない画素数。0 なら何も描けていない
     * @param nativeSet     MC の画像の、背景でない画素数
     * @param mismatches    採用した向きでの RGB 不一致画素数
     * @param flipped       採用した向き (true なら MC の画像は上下反転して一致した)
     * @param note          問題。{@code null} なら期待どおり
     * @param sampleAtDraw  この標本を取った時点の draw 本数
     * @param sampleFile    生標本のファイル名 (PPM)。{@code null} なら残せていない
     */
    public record Comparison(boolean attempted, boolean completed, long referenceSet,
                             long nativeSet, long mismatches, Boolean flipped, String note,
                             long sampleAtDraw, String sampleFile) {}

    /** 証跡に出す状態。 */
    public record Result(boolean enabled, boolean attempted, boolean built, long drawsRecorded,
                         int width, int height, int colourFormat, String device,
                         Comparison comparison, int timesClean, int timesWithAProblem,
                         String firstProblem, int closeFailures, int failureBudget,
                         List<String> notes) {}

    // ---------------- 保持するもの ----------------

    private final VulkanDevice device;
    private final int colourFormat;
    private final int width, height;
    private final VkTerrainResources res;
    private final VkTerrainRenderer renderer;
    private final int drawCount;
    /** Voxy 自身の的に描いた参照画像の RGB (行 0 = Vulkan の行 0)。 */
    private final int[] reference;
    private final long referenceSet;
    /** 組み立て時の {@code VkDevice} ハンドル。破棄の持ち主判定に使う。 */
    private final long ownerDevice;
    private boolean destroyed;

    private McNativeTerrainProbe(VulkanDevice device, int colourFormat, int width, int height,
                                 VkTerrainResources res, VkTerrainRenderer renderer,
                                 int drawCount, int[] reference, long referenceSet,
                                 long ownerDevice) {
        this.device = device;
        this.ownerDevice = ownerDevice;
        this.colourFormat = colourFormat;
        this.width = width;
        this.height = height;
        this.res = res;
        this.renderer = renderer;
        this.drawCount = drawCount;
        this.reference = reference;
        this.referenceSet = referenceSet;
    }

    // ---------------- 入口 ----------------

    public static Result status() {
        synchronized (NOTES) {
            var it = instance;
            return new Result(Boolean.getBoolean(FLAG), attempted, it != null, drawsRecorded,
                it == null ? 0 : it.width, it == null ? 0 : it.height,
                it == null ? 0 : it.colourFormat,
                it == null ? null : deviceHandle(),
                comparison, compareOk, compareProblems, firstCompareProblem, closeFailures,
                COMPARE_FAILURE_BUDGET, List.copyOf(NOTES));
        }
    }

    /**
     * レベル描画の末尾で呼ぶ。フラグが無効なら<b>何もしない</b> —
     * MC の device も触らず、確保もせず、ファイルも書かない。
     */
    public static void renderIfEnabled() {
        if (!Boolean.getBoolean(FLAG)) return;
        try {
            render();
        } catch (Throwable t) {
            note("the native terrain probe failed: " + t);
            var trace = t.getStackTrace();
            for (int i = 0; i < Math.min(6, trace.length); i++) note("  at " + trace[i]);
        }
    }

    private static void render() {
        var notes = new ArrayList<String>();
        VulkanDevice device = McNativeVulkan.device(notes);
        if (device == null) {
            notes.forEach(McNativeTerrainProbe::note);
            return;
        }
        if (!adoptedContextReady()) {
            noteOnce("Voxy's context has not adopted Minecraft's device, so there is nothing to"
                + " record the terrain pipeline onto (set -Dvoxy.native.adopt=true)");
            return;
        }
        // ⚠ round-6 review R6-TERRAIN-DEVICE: 採用は一度しか起きないので、MC が device を
        // 差し替えても Voxy の context は<b>古い device を持ったまま</b>になる。以前の
        // 所有判定は「自分の context の device」同士を比べていたので常に真で、
        // 結果として<b>A の資源で B のコマンドバッファに記録する</b>ことになった。
        // 基準を MC のいまに置き、食い違うなら<b>何もしない</b>。
        long mcDevice = McNativeVulkan.vkDeviceHandle(device, notes);
        long ourDevice = adoptedDeviceHandle();
        if (mcDevice == 0 || ourDevice == 0 || mcDevice != ourDevice) {
            notes.forEach(McNativeTerrainProbe::note);
            noteOnce("Voxy's adopted context holds device 0x" + Long.toHexString(ourDevice)
                + " but Minecraft is now using 0x" + Long.toHexString(mcDevice)
                + "; refusing to record Voxy's resources into another device's command buffer."
                + " The terrain probe stops for the rest of this session.");
            deviceDiverged = true;
            var stale = instance;
            instance = null;
            if (stale != null) {
                // 古い device のものなので壊さない。意図的に漏らして数える。
                stale.destroyed = true;
                leakedProbes++;
            }
            // ⚠ round-7 review R6-TERRAIN-DEVICE の残り: 記録は安全に止まるが、
            // <b>ディスク上の前の清浄なレポートがそのまま残って</b>いた。
            // 記録をやめた事実をいますぐ書く (B4 と同じ規律)。
            evidenceWrittenAt = -1;
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
        if (colour == null) {
            noteOnce("the main render target has no colour view");
            return;
        }
        if (depth == null) {
            // 地形パイプラインは深度アタッチメントを宣言している。無ければ記録できない。
            noteOnce("the main render target has no depth view, so the terrain pipeline's"
                + " declared depth attachment cannot be satisfied");
            return;
        }
        int format = VulkanConst.toVk(colour.texture().getFormat());
        int width = colour.getWidth(0);
        int height = colour.getHeight(0);
        if (width <= 0 || height <= 0) return;

        var probe = instance;
        if (probe != null && (probe.device != device || probe.colourFormat != format
                || probe.width != width || probe.height != height)) {
            retire(probe);
            probe = null;
        }
        if (probe == null) {
            attempted = true;
            probe = build(device, format, width, height);
            if (probe == null) return;
            instance = probe;
            // ⚠ 測ったのは<b>前の寸法の</b>画像である。作り直したらその標本は参照できない
            // (実測: resize 後も 1708x960 の標本を 1920x1080 の報告が指していて、
            // ゲートが "the retained samples are 1708x960 but the probe drew to 1920x1080"
            // で落ちた)。標本と比較の状態を捨て、新しい寸法で測り直させる。
            comparison = null;
            nextCompareAt = drawsRecorded + 2;
        }

        // ⚠ パスは MC の API で開き、colour と depth を<b>クリアする</b>。
        // クリアするのは比較を成立させるためである (LOAD では MC のシーンの上に重なり、
        // Voxy 自身の的に描いた絵と比べようがない)。レイアウト遷移は MC が持ったまま。
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "voxy native terrain", colour,
                Optional.of(new Vector4f(CLEAR[0], CLEAR[1], CLEAR[2], CLEAR[3])),
                depth, OptionalDouble.of(VkDepth.CLEAR))) {
            VkCommandBuffer cmd = McNativeVulkan.commandBufferOf(pass, notes);
            if (cmd == null) {
                notes.forEach(McNativeTerrainProbe::note);
                return;
            }
            // 的を受け取らない経路なので、照合は明示的に行う。
            probe.renderer.assertColourFormatMatches(format);
            probe.renderer.recordDrawsInRenderPass(cmd, probe.drawCount);
            drawsRecorded++;
        }

        if (drawsRecorded >= nextCompareAt && !compareInFlight
                && compareProblems < COMPARE_FAILURE_BUDGET) {
            nextCompareAt = drawsRecorded + COMPARE_INTERVAL;
            requestComparison(probe, colour);
        }
        writeEvidenceIfDue();
    }

    // ---------------- 組み立て ----------------

    /**
     * 合成地形と、それを描くパイプライン、そして<b>参照画像</b>を作る。
     *
     * <p>参照は Voxy 自身の {@link VkRenderTarget} に {@link VkTerrainRenderer#record} で
     * 描いたものである。これを作る過程で
     * {@link VkTerrainRenderer#recordBeforeRenderPass} 相当 (アトラスのアップロードと
     * 深度境界のクリア) が<b>フェンスで待たれた状態で</b>済む。だから MC のパスの中では
     * 描画コマンドだけを積めばよく、パスの外に積まなければならないものが残らない。
     */
    private static McNativeTerrainProbe build(VulkanDevice device, int format,
                                              int width, int height) {
        if (leakedProbes >= LEAK_BUDGET) {
            note("already leaked " + leakedProbes + " terrain probe(s) because their retirement"
                + " could not be handed to Minecraft; not building another one");
            return null;
        }
        long bytes = (long) width * height * 4;
        if (bytes > READBACK_BUDGET_BYTES) {
            note("the colour image is " + bytes + " bytes, over the " + READBACK_BUDGET_BYTES
                + " byte budget; not building the terrain probe rather than allocating that much");
            return null;
        }
        if (format != VkRenderTarget.FORMAT_COLOR) {
            note("Minecraft's colour format is 0x" + Integer.toHexString(format)
                + " but Voxy's own target is 0x" + Integer.toHexString(VkRenderTarget.FORMAT_COLOR)
                + "; a pixel comparison between the two would not mean anything");
            return null;
        }
        // ⚠ round-6 review R6-TERRAIN-WAIT の規律 (提出したが待ちを観測できなければ漏らす) は
        // {@link McNativeTerrainScene#build} にある。ここは budget と結果の扱いだけ。
        var scene = McNativeTerrainScene.build(format, width, height,
            SyntheticTerrain.boundaryCases(), closeUpMvp(width, height), SyntheticTerrain.ORIGIN,
            false, CLEAR, McNativeTerrainProbe::note, () -> leakedProbes++);
        if (scene == null) return null;
        if (scene.set == 0) {
            note("Voxy's own target drew nothing, so there is no reference to compare"
                + " Minecraft's frame against");
            scene.free();
            return null;
        }
        return new McNativeTerrainProbe(device, format, width, height, scene.res, scene.renderer,
            scene.drawCount, scene.colour, scene.set, VkContext.get().device.address());
    }

    /**
     * 7 面区分すべてを使うセクションを至近距離から見る視点
     * ({@code VkTerrainRenderTest.closeUpMvp} と同じ)。
     */
    private static float[] closeUpMvp(int width, int height) {
        return VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(60), (float) width / height,
                0.1f, 2000f),
            VkSceneUniform.lookAt(new float[]{14.0f, 7.0f, 13.0f}, new float[]{8.5f, 0.5f, 0.5f},
                new float[]{0, 1, 0}));
    }

    /** 背景色を RGBA8_UNORM の RGB に詰めたもの。 */
    private static int packClear() {
        return McNativeTerrainScene.packRgb(CLEAR);
    }

    // ---------------- 比較 ----------------

    private static void requestComparison(McNativeTerrainProbe probe, GpuTextureView colour) {
        com.mojang.blaze3d.buffers.GpuBuffer buffer = null;
        try {
            long bytes = (long) probe.width * probe.height * 4;
            var device = RenderSystem.getDevice();
            buffer = device.createBuffer(() -> "voxy native terrain readback",
                com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ
                    | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST, bytes);
            final var readback = buffer;
            compareInFlight = true;
            // ⚠ round-5 review B4: 取得時点の情報を<b>登録時に束ねる</b>。
            // コールバックの中でグローバルの draw 本数を読むと、ファイル名と報告が食い違う
            // (実測: sampleAtDraw 3363 に対しファイル名は -3 だった)。
            final long at = drawsRecorded;
            device.createCommandEncoder().copyTextureToBuffer(colour.texture(), readback, 0,
                () -> compare(probe, readback, at), 0);
            buffer = null;   // 以後の解放は callback 側の責務
        } catch (Throwable t) {
            failComparison("the terrain readback could not be requested: " + t, drawsRecorded);
        } finally {
            if (buffer != null) {
                try { buffer.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * MC の画像を参照と<b>画素単位で</b>比べる。
     *
     * <p>⚠ 向きを仮定しない。MC の画像の行順が Vulkan の行順と反転していることは
     * {@link McNativeMarkerDraw} で実測されている。両方の向きで数え、
     * <b>不一致が少ない方を採用</b>し、採用した向きで<b>不一致 0</b> を要求する。
     * アルファは面/LoD の符号化なので比較から外す
     * ({@link VkRenderTarget#compareColor} と同じ扱い)。
     */
    private static void compare(McNativeTerrainProbe probe,
                                com.mojang.blaze3d.buffers.GpuBuffer buffer, long at) {
        try (var view = new com.mojang.blaze3d.buffers.GpuBufferSlice(buffer, 0, buffer.size())
                .map(true, false)) {
            var data = view.data();
            int clearRgb = packClear();
            long bestMismatches = Long.MAX_VALUE;
            boolean bestFlipped = false;
            long bestSet = 0;
            for (boolean flipped : new boolean[]{false, true}) {
                long mismatches = 0, set = 0;
                for (int y = 0; y < probe.height; y++) {
                    int srcRow = flipped ? probe.height - 1 - y : y;
                    for (int x = 0; x < probe.width; x++) {
                        int src = (srcRow * probe.width + x) * 4;
                        if (src + 2 >= data.limit()) { mismatches++; continue; }
                        int rgb = (data.get(src) & 0xFF)
                            | ((data.get(src + 1) & 0xFF) << 8)
                            | ((data.get(src + 2) & 0xFF) << 16);
                        if (rgb != clearRgb) set++;
                        if (rgb != probe.reference[y * probe.width + x]) mismatches++;
                    }
                }
                if (mismatches < bestMismatches) {
                    bestMismatches = mismatches;
                    bestFlipped = flipped;
                    bestSet = set;
                }
                if (mismatches == 0) break;
            }
            String note = null;
            if (bestSet == 0) {
                note = "Minecraft's colour image holds nothing but the background, so the"
                    + " terrain draws never reached it";
            } else if (bestMismatches != 0) {
                note = bestMismatches + " of " + ((long) probe.width * probe.height)
                    + " pixels differ from the same scene drawn to Voxy's own target"
                    + (bestFlipped ? " (flipped)" : "");
            }
            // ⚠ <b>毎回</b>その取得ぶんの組を残す。最初は「清浄な組は 1 つだけ残す」
            // 設計だったが、それは<b>公開する比較が別の取得の画素を指す</b>ことを意味した
            // (実測: sampleAtDraw 3391 が draw 2911 の標本を指し、新しい結合検査が落とした)。
            // gzip 後は 1 枚 8.5 KB なので、取得ごとに残しても run 全体で 250 KB ほどである。
            sampleWriteFailed = false;
            String sample = writeSample(probe, data, bestFlipped, at);
            // ⚠ 証跡ディレクトリが指定されていない (= ハーネス外の素の診断) のは失敗ではない。
            // 失敗なのは<b>指定されているのに書けなかった</b>場合である。ゲートは
            // sampleFile を必須にしているので、前者がそのまま受理されることはない。
            if (sampleWriteFailed && note == null) {
                note = "the comparison agreed but its raw samples could not be retained, so"
                    + " nothing can check it";
            }
            comparison = new Comparison(true, true, probe.referenceSet, bestSet, bestMismatches,
                bestFlipped, note, at, sample);
            if (note == null) {
                compareOk++;
            } else {
                compareProblems++;
                if (firstCompareProblem == null) firstCompareProblem = note;
                evidenceWrittenAt = -1;
                writeEvidence();
            }
            Logger.info("[native-vk] Voxy's terrain pipeline on Minecraft's frame: set="
                + bestSet + " reference=" + probe.referenceSet + " mismatches=" + bestMismatches
                + (bestFlipped ? " (flipped)" : "")
                + (note == null ? " (pixel-identical to Voxy's own target)" : " PROBLEM: " + note));
        } catch (Throwable t) {
            failComparison("the terrain comparison failed: " + t, at);
        } finally {
            compareInFlight = false;
            try {
                buffer.close();
            } catch (Throwable t) {
                // ⚠ round-5 review R4-L1: marker 側はこれを独立カウンタに入れるだけで
                // <b>放棄の判断には使っていなかった</b>ので、閉じられないまま毎間隔
                // 1 枚ぶん積み上がり得た。問題として数え、予算に参加させる。
                closeFailures++;
                compareProblems++;
                if (firstCompareProblem == null) {
                    firstCompareProblem = "could not close the terrain readback buffer: " + t;
                }
                note("could not close the terrain readback buffer: " + t);
                evidenceWrittenAt = -1;
                writeEvidence();
            }
        }
    }

    /**
     * 失敗した比較を記録する。
     *
     * <p>⚠ {@link McNativeMarkerDraw} と同じ規律: 例外経路も<b>問題を数え、即座に書く</b>。
     * そうしないとディスクには前回の清浄な標本が残り、壊れた回が消える。
     */
    private static void failComparison(String why, long at) {
        comparison = new Comparison(true, false, 0, 0, 0, null, why, at, null);
        compareProblems++;
        if (firstCompareProblem == null) firstCompareProblem = why;
        note(why);
        evidenceWrittenAt = -1;
        writeEvidence();
    }

    /**
     * 採用した向きの MC の画像を PPM (P6) で残す。
     *
     * <p>⚠ 集計だけ残すと、ゲートが実装の出した数値を検算することになる (循環)。
     * 生画素を残し、Python 側が参照と独立に比べられるようにする。
     * 参照画像も同じ向きで併記する ({@code -reference.ppm})。
     */
    private static String writeSample(McNativeTerrainProbe probe, ByteBuffer data, boolean flipped,
                                      long at) {
        String dir = System.getProperty("voxy.harness.output");
        if (dir == null || dir.isBlank()) return null;
        try {
            String name = "native-terrain-sample-" + at + ".ppm.gz";
            var header = ("P6\n" + probe.width + " " + probe.height + "\n255\n")
                .getBytes(StandardCharsets.US_ASCII);
            byte[] body = new byte[probe.width * probe.height * 3];
            int into = 0;
            for (int y = 0; y < probe.height; y++) {
                int srcRow = flipped ? probe.height - 1 - y : y;
                for (int x = 0; x < probe.width; x++) {
                    int src = (srcRow * probe.width + x) * 4;
                    if (src + 2 >= data.limit()) break;
                    body[into++] = data.get(src);
                    body[into++] = data.get(src + 1);
                    body[into++] = data.get(src + 2);
                }
            }
            McNativeVulkanProbe.writeGzipFileBytes(name, header, body);

            byte[] ref = new byte[probe.width * probe.height * 3];
            for (int i = 0, j = 0; i < probe.reference.length; i++) {
                int rgb = probe.reference[i];
                ref[j++] = (byte) (rgb & 0xFF);
                ref[j++] = (byte) ((rgb >> 8) & 0xFF);
                ref[j++] = (byte) ((rgb >> 16) & 0xFF);
            }
            McNativeVulkanProbe.writeGzipFileBytes(
                "native-terrain-reference-" + at + ".ppm.gz", header, ref);
            return name;
        } catch (Throwable t) {
            // ⚠ round-5 review B4: marker 側は標本の書き込み失敗で<b>元の成功した結果を
            // そのまま返して</b>いたので、清浄カウンタが進み、失敗は公開されなかった。
            // 書けなかったことは問題として数える (呼び出し側が note==null を上書きする)。
            sampleWriteFailed = true;
            note("could not retain the terrain sample: " + t);
            return null;
        }
    }

    // ---------------- 寿命 ----------------

    /**
     * 画面のフォーマット/寸法が変わった、あるいはデバイスが差し替わった。
     *
     * <p>⚠ <b>いまこの瞬間 MC が使っている可能性がある</b>ので、ここでは壊さない。
     * Voxy のフレームトラッカーに任せ、提出が完了してから解放させる。
     */
    private static void retire(McNativeTerrainProbe probe) {
        // ⚠ 参照を先に切る。round-5 review R5-LIFETIME が marker で指摘した形
        // (退役させた後も static に残り、条件が戻ると再び record に使われる) を
        // 構造的に起こさない。
        if (instance == probe) instance = null;
        if (probe == null || probe.destroyed) return;
        if (!probe.ownedByCurrentDevice()) {
            note("the terrain probe belongs to a device that is no longer current; leaking it"
                + " on purpose rather than destroying it against the wrong device");
            probe.destroyed = true;
            leakedProbes++;
            return;
        }
        try {
            // ⚠ 寿命は<b>MC の提出</b>である。これらの資源を参照しているのは MC の
            // コマンドバッファなので、Voxy のフレームトラッカーではなく MC の破棄待ち行列に
            // 預ける。前の版は VkFrameTracker を使っていたが、トラッカーは参照の寿命とは
            // 無関係であり、実測では resize の時点で既に落ちていた
            // ("VkFrameTracker not initialised" — ゲートが捕まえた)。
            McNativeVulkan.encoder(probe.device).queueForDestroy(probe);
        } catch (Throwable t) {
            // 預けられなかった時点で「もう使われていない」根拠が無いので、壊さずに漏らす。
            probe.destroyed = true;
            leakedProbes++;
            note("queueForDestroy refused the terrain probe (" + t + "); leaking it on purpose"
                + " rather than destroying something that may still be in use");
        }
    }

    /**
     * この probe の資源が<b>いまも有効な device に属しているか</b>。
     *
     * <p>⚠ round-6 review R6-TERRAIN-DEVICE: 以前は
     * {@code VkContext.get().device.address() == this.ownerDevice} だけを見ていた。
     * 採用は一度きりなので両者は常に一致し、<b>MC が device を差し替えても真</b>になった。
     * 自分の context と、<b>MC のいまの device</b>の両方に一致することを要求する。
     */
    private boolean ownedByCurrentDevice() {
        try {
            if (VkContext.get().device.address() != this.ownerDevice) return false;
            long mc = McNativeVulkan.vkDeviceHandle(McNativeVulkan.device(), new ArrayList<>());
            return mc != 0 && mc == this.ownerDevice;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Voxy が採用した {@code VkDevice} のハンドル、取れなければ 0。 */
    private static long adoptedDeviceHandle() {
        try {
            return VkContext.get().device.address();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * MC の破棄待ち行列から呼ばれる。<b>ここで初めて</b>資源を解放する —
     * この時点で、預けたときに実行中だった MC の提出は完了している。
     */
    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        try {
            this.renderer.free();
        } catch (Throwable t) {
            note("could not free the terrain renderer: " + t);
        }
        try {
            this.res.free();
        } catch (Throwable t) {
            note("could not free the terrain resources: " + t);
        }
    }

    /**
     * MC のデバイスが {@code waitedDevice} で idle を観測できたときだけ、即座に壊す。
     *
     * <p>⚠ 別のデバイスのものは壊さない ({@link McNativeMarkerDraw#shutdownImmediate} と同じ理由)。
     */
    public static void shutdownImmediate(org.lwjgl.vulkan.VkDevice waitedDevice) {
        var probe = instance;
        if (probe == null) return;
        instance = null;
        if (waitedDevice == null || waitedDevice.address() != probe.ownerDevice) {
            note("not destroying the terrain probe: its device is 0x"
                + Long.toHexString(probe.ownerDevice) + " but the idle wait was observed on "
                + (waitedDevice == null ? "no device"
                    : "0x" + Long.toHexString(waitedDevice.address()))
                + "; leaking on purpose");
            leakedProbes++;
            return;
        }
        probe.destroy();
    }

    /** MC のレベル描画が閉じるとき。提出の完了を観測していないので<b>ここでは壊さない</b>。 */
    public static void shutdown() {
        var probe = instance;
        if (probe == null) return;
        retire(probe);
    }

    // ---------------- 証跡 ----------------

    private static void writeEvidenceIfDue() {
        if (drawsRecorded != 1 && drawsRecorded % 600 != 0) return;
        if (evidenceWrittenAt == drawsRecorded) return;
        evidenceWrittenAt = drawsRecorded;
        writeEvidence();
    }

    private static void writeEvidence() {
        String dir = System.getProperty("voxy.harness.output");
        if (dir == null || dir.isBlank()) return;
        McNativeVulkanProbe.writeFile("native-terrain-probe.json", evidenceJson());
    }

    private static String evidenceJson() {
        var s = status();
        var sb = new StringBuilder("{\n");
        sb.append("  \"enabled\": ").append(s.enabled()).append(",\n");
        sb.append("  \"attempted\": ").append(s.attempted()).append(",\n");
        sb.append("  \"built\": ").append(s.built()).append(",\n");
        sb.append("  \"drawsRecorded\": ").append(s.drawsRecorded()).append(",\n");
        sb.append("  \"targetWidth\": ").append(s.width()).append(",\n");
        sb.append("  \"targetHeight\": ").append(s.height()).append(",\n");
        sb.append("  \"colourVkFormat\": ").append(s.colourFormat()).append(",\n");
        sb.append("  \"clearRgb\": ").append(packClear()).append(",\n");
        sb.append("  \"device\": ").append(deviceJson()).append(",\n");
        sb.append("  \"timesClean\": ").append(s.timesClean()).append(",\n");
        sb.append("  \"timesWithAProblem\": ").append(s.timesWithAProblem()).append(",\n");
        sb.append("  \"firstProblem\": ").append(McNativeVulkanProbe.quote(s.firstProblem())).append(",\n");
        sb.append("  \"closeFailures\": ").append(s.closeFailures()).append(",\n");
        sb.append("  \"leakedProbes\": ").append(leakedProbes).append(",\n");
        sb.append("  \"leakBudget\": ").append(LEAK_BUDGET).append(",\n");
        sb.append("  \"deviceDiverged\": ").append(deviceDiverged).append(",\n");
        sb.append("  \"failureBudget\": ").append(s.failureBudget()).append(",\n");
        var c = s.comparison();
        if (c == null) {
            sb.append("  \"comparison\": null,\n");
        } else {
            sb.append("  \"comparison\": {")
              .append("\"attempted\": ").append(c.attempted())
              .append(", \"completed\": ").append(c.completed())
              .append(", \"referenceSet\": ").append(c.referenceSet())
              .append(", \"nativeSet\": ").append(c.nativeSet())
              .append(", \"mismatches\": ").append(c.mismatches())
              .append(", \"flipped\": ").append(c.flipped() == null ? "null" : c.flipped())
              .append(", \"note\": ").append(McNativeVulkanProbe.quote(c.note()))
              .append(", \"sampleAtDraw\": ").append(c.sampleAtDraw())
              .append(", \"sampleFile\": ").append(McNativeVulkanProbe.quote(c.sampleFile()))
              .append("},\n");
        }
        sb.append("  \"notes\": [");
        var notes = s.notes();
        for (int i = 0; i < notes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(McNativeVulkanProbe.quote(notes.get(i)));
        }
        sb.append("]\n}\n");
        return sb.toString();
    }

    private static String deviceJson() {
        return McNativeVulkanProbe.quote(deviceHandle());
    }

    /** 走らせている {@code VkDevice} のハンドル。採用した context のものになる。 */
    private static String deviceHandle() {
        try {
            return "0x" + Long.toHexString(VkContext.get().device.address());
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 小物 ----------------

    private static boolean adoptedContextReady() {
        try {
            return VkContext.get().isAdopted();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void note(String message) {
        synchronized (NOTES) {
            if (NOTES.contains(message) || NOTES.size() >= 64) return;
            NOTES.add(message);
        }
        Logger.warn("[native-vk] " + message);
    }

    /** 毎フレーム来る条件のための note。同じ文は 1 回しか残らない ({@link #note} と同じ)。 */
    private static void noteOnce(String message) {
        note(message);
    }
}
