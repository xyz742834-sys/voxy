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
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkViewport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * <b>Minecraft 自身の Vulkan バックエンドに、Voxy のパイプラインで有界な draw を 1 本記録する</b>
 * (docs/ai/project-goal.md の次の優先項目 — 最小の実接続)。
 *
 * <p>これは<b>地形描画ではない</b>。左上隅にマゼンタの小さな四角を 1 つ描くだけの
 * 診断である。狙いは絵ではなく、次の 4 点を実機で確かめること:
 *
 * <ol>
 *   <li><b>デバイス採用</b> — Voxy 自前の {@code VkContext} ではなく、MC の
 *       {@link VulkanDevice} 上にシェーダ・レイアウト・パイプラインを作れるか</li>
 *   <li><b>記録点</b> — MC のコマンドバッファに raw な {@code vkCmd*} を積めるか</li>
 *   <li><b>イメージ状態の所有権</b> — レンダーパスは<b>MC の API で開く</b>
 *       ({@code CommandEncoder.createRenderPass})。したがって色アタッチメントの
 *       レイアウト遷移とバリアは MC が持ち、こちらは一切張らない。
 *       MC のレイアウト規約を推測しないための設計である</li>
 *   <li><b>寿命</b> — 破棄は {@code VulkanCommandEncoder.queueForDestroy} に委ねる。
 *       Voxy 既存の明示 fence 待ちは MC が提供する契約ではない</li>
 * </ol>
 *
 * <p>MC 自身の検証レイヤが有効な状態 ({@code --vulkanValidation}) で動かすこと。
 * 記録が誤っていれば {@code scripts/verify.py} の native ステージが
 * 検証メッセージを拾って落ちる — それがこのクラスの合否判定である。
 *
 * <p>⚠ <b>既定で無効</b>。{@code -Dvoxy.native.marker=true} のときだけ描く。
 * 通常プレイの画面に診断を出さないため。
 */
public final class McNativeMarkerDraw implements Destroyable {
    /** これを true にしたときだけ描く。 */
    public static final String FLAG = "voxy.native.marker";

    /** 公開しているのはテストが同じ GLSL を独立にコンパイルして検証できるようにするため。 */
    /** 公開しているのはテストが同じ GLSL を独立にコンパイルして検証できるようにするため。 */
    public static final String VERTEX_SOURCE = """
        #version 460
        // 頂点バッファ無し。押し込んだ矩形 (NDC) を 2 三角形で出す。
        //
        // ⚠ y の符号は**実測で決めた**。素の Vulkan NDC は y が下向きだが、
        // MC が合成した最終フレーム (スクリーンショット) では上下が反転して現れた —
        // y=-0.98 で描いた矩形が 960px 画像の y≈854、つまり下端に出た。
        // そのため呼び出し側は「最終画像での上下」で矩形を渡し、ここで符号を合わせる
        // [docs/ai/vulkan-native-integration-survey.md で測定]。
        layout(push_constant) uniform Params {
            vec4 rect;      // x0, y0, x1, y1 (NDC, 画像の上が +y)
            vec4 colour;
            float depth;    // 0..1 (MC は isZZeroToOne=true)
        } params;
        void main() {
            const vec2 pick[6] = vec2[6](
                vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(0.0, 1.0),
                vec2(1.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0));
            vec2 t = pick[gl_VertexIndex];
            vec2 ndc = mix(params.rect.xy, params.rect.zw, t);
            gl_Position = vec4(ndc, params.depth, 1.0);
        }
        """;

    /** @see #VERTEX_SOURCE */
    public static final String FRAGMENT_SOURCE = """
        #version 460
        layout(push_constant) uniform Params {
            vec4 rect;
            vec4 colour;
            float depth;
        } params;
        layout(location = 0) out vec4 outColour;
        void main() {
            outColour = params.colour;
        }
        """;

    /**
     * 描くときの色 (RGB)。
     *
     * <p>⚠ <b>最終画像ではこの値そのままにはならない</b>。実測では
     * {@code (255, 0, 255)} で描いた画素がスクリーンショットでは
     * {@code (235, 0, 235)} になった — MC の最終合成が場面によって数 % 暗くする。
     * ピクセル検証は許容幅を持たせ、実際に出た値を証跡に残すこと。
     */
    public static final int MARKER_R = 255, MARKER_G = 0, MARKER_B = 255;

    /** 深度テストで「奥」として描く色。これが前面に出たら深度テストが効いていない。 */
    public static final int FAR_R = 0, FAR_G = 255, FAR_B = 255;

    /**
     * <b>深度に落とされるべき色</b>。近い矩形より後に、より奥の深度で描く。
     * 最終画像にこれが<b>1 画素でも出たら深度テストが効いていない</b>。
     */
    public static final int REJECTED_R = 255, REJECTED_G = 255, REJECTED_B = 0;

    // 画像の左上隅、幅/高さの 1%..11% の矩形 (最終画像での向き)。
    private static final float BOX_X0 = -0.98f, BOX_X1 = -0.78f;
    private static final float BOX_Y0 = 0.98f,  BOX_Y1 = 0.78f;
    /** 近い矩形は箱の左 60% を覆う。残りは奥の色が残る。 */
    private static final float NEAR_X1 = BOX_X0 + (BOX_X1 - BOX_X0) * 0.6f;

    private static final float DEPTH_FAR = 0.6f, DEPTH_NEAR = 0.3f, DEPTH_REJECTED = 0.9f;

    /**
     * <b>棄却色の対照帯</b>。深度証明の箱のすぐ下に、同じ色を<b>必ず通る設定</b>
     * (比較 ALWAYS) で置く。
     *
     * <p>⚠ round-1 review B4: 「箱に棄却色が無い」だけでは<b>3 枚目を描いたこと自体</b>を
     * 示せない — 描かなければ同じ絵になる。対照帯に色が出ていれば「描いた・描ける」が立ち、
     * そのうえで箱に無いことが初めて「深度に落とされた」証拠になる。
     */
    private static final float CONTROL_Y0 = 0.76f, CONTROL_Y1 = 0.72f;

    /**
     * <b>深度テスト済みの棄却色が「通る」セル</b>。箱の下、対照帯の下に置く。
     *
     * <p>⚠ round-2 review B4: 対照帯 (比較 ALWAYS) は「その色が描ける」ことしか示さず、
     * <b>深度テストを通した棄却色の draw が発行されたか</b>は示せない。そこで
     * <b>同じパイプライン・同じ色</b>で、ここには<b>手前</b>の深度 (必ず通る) を、
     * 箱には<b>奥</b>の深度 (必ず落ちる) を書く。結果が二つに分かれることが、
     * 「draw は出ていて、箱では深度に落ちた」の証明になる。
     */
    private static final float CELL_Y0 = 0.70f, CELL_Y1 = 0.66f;

    private static McNativeMarkerDraw instance;
    private static long drawsRecorded;
    /** 読み戻しで数えた画素。<b>最終画像ではなく MC のカラー画像そのもの</b>を見た結果。 */
    private static Readback readback;
    /**
     * 次に読み戻す draw 本数と、これまでの成功/問題の数。
     *
     * <p>⚠ 「前回からの差」で判定すると初期値 {@code Long.MIN_VALUE} との減算が
     * <b>オーバーフロー</b>して条件が永久に偽になった (読み戻しが一度も走らなかった)。
     * 閾値そのものを持つ形にして減算をやめる。
     */
    private static long nextReadbackAt = 2;
    private static int readbackOk, readbackProblems;
    private static String firstReadbackProblem;
    private static final long READBACK_INTERVAL = 240;
    private static long evidenceWrittenAt = -1;
    private static final List<String> NOTES = new ArrayList<>();
    private static boolean complained;

    private final VulkanDevice device;
    private final int colourFormat;
    private final int depthFormat;
    private final long vertexModule;
    private final long fragmentModule;
    private final long pipelineLayout;
    /** 深度比較 ALWAYS + 深度書き込み。MC のシーン深度に依存せず基準面を置くため。 */
    private final long writePipeline;
    /** 深度比較 LESS + 深度書き込み。基準面に対して前後関係を試すため。 */
    private final long testPipeline;
    private boolean destroyed;

    private McNativeMarkerDraw(VulkanDevice device, int colourFormat, int depthFormat,
                               long vertexModule, long fragmentModule, long pipelineLayout,
                               long writePipeline, long testPipeline) {
        this.device = device;
        this.colourFormat = colourFormat;
        this.depthFormat = depthFormat;
        this.vertexModule = vertexModule;
        this.fragmentModule = fragmentModule;
        this.pipelineLayout = pipelineLayout;
        this.writePipeline = writePipeline;
        this.testPipeline = testPipeline;
    }

    /**
     * 描画スレッドから毎フレーム 1 回呼ぶ。フラグが無効なら何もしない。
     * <b>例外は投げない</b> — 失敗は {@link #status()} の notes に残して諦める。
     */
    public static void renderIfEnabled() {
        if (!Boolean.getBoolean(FLAG)) return;
        try {
            render();
        } catch (Throwable t) {
            note("the native marker draw failed: " + t);
        }
    }

    private static void render() {
        var notes = new ArrayList<String>();
        VulkanDevice device = McNativeVulkan.device(notes);
        if (device == null) {
            notes.forEach(McNativeMarkerDraw::note);
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc == null || mc.gameRenderer == null) return;
        var target = mc.gameRenderer.mainRenderTarget();
        if (target == null) return;
        GpuTextureView colour = target.getColorTextureView();
        if (colour == null) {
            note("the main render target has no colour view");
            return;
        }
        GpuTextureView depth = target.getDepthTextureView();
        int format = VulkanConst.toVk(colour.texture().getFormat());
        int depthVkFormat = depth == null ? VK_FORMAT_UNDEFINED
            : VulkanConst.toVk(depth.texture().getFormat());
        int width = colour.getWidth(0);
        int height = colour.getHeight(0);
        if (width <= 0 || height <= 0) return;

        McNativeMarkerDraw draw = instance;
        if (draw != null && (draw.device != device || draw.colourFormat != format
                || draw.depthFormat != depthVkFormat)) {
            // 画面のフォーマットが変わった / デバイスが差し替わった。
            // 破棄は MC の提出寿命に合わせる — 今まさに使われている可能性があるため。
            retire(draw, device);
            draw = null;
        }
        if (draw == null) {
            draw = create(device, format, depthVkFormat);
            if (draw == null) return;
            instance = draw;
        }

        // ⚠ パスは MC の API で開く。色と深度のレイアウト遷移とバリアは MC が持ったままになり、
        // こちらは MC のレイアウト規約を仮定しない。どちらも LOAD (クリアしない)。
        try (var pass = depth == null
                ? RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "voxy native marker", colour, Optional.empty())
                : RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "voxy native marker", colour, Optional.empty(),
                        depth, OptionalDouble.empty())) {
            VkCommandBuffer cmd = McNativeVulkan.commandBufferOf(pass, notes);
            if (cmd == null) {
                notes.forEach(McNativeMarkerDraw::note);
                return;
            }
            draw.record(cmd, width, height);
            drawsRecorded++;
        }
        // パスを閉じた後に読み戻す。MC の API を使うのでレイアウトは MC の持ち物のまま。
        // ⚠ round-2 review B4: 1 回だけだと以後のライフサイクル全体に対して陳腐化する。
        // 定期的に取り直し、成功回数と最後の結果を証跡に残す。
        if (drawsRecorded >= nextReadbackAt) {
            nextReadbackAt = drawsRecorded + READBACK_INTERVAL;
            requestReadback(colour, width, height);
        }
        writeEvidenceIfDue(device, format, width, height);
    }

    /**
     * <b>深度が効いていることを、MC のシーン深度に依存せず示す 3 回の draw</b>。
     *
     * <p>MC が reverse-Z を使うかどうかはここでは仮定できないので、
     * まず <b>比較 ALWAYS + 書き込み</b>で基準面 (奥) を置き、その上で
     * <b>比較 LESS</b> の 2 枚を試す。期待される最終像は決定的である:
     *
     * <ol>
     *   <li>箱全体に<b>奥の色</b> (シアン, z=0.6) — ALWAYS なので必ず書ける</li>
     *   <li>箱の左 60% に<b>近い色</b> (マゼンタ, z=0.3) — LESS なので通る</li>
     *   <li>箱全体に<b>落とされるべき色</b> (黄, z=0.9) — LESS なので<b>どこにも出ない</b></li>
     * </ol>
     *
     * <p>⚠ 深度バッファ (MC のもの) の隅 10% に書き込む。診断としては意図的だが、
     * フラグ無効時は一切行われない。
     */
    /**
     * MC のカラー画像を<b>丸ごと</b>読み戻し、3 色の個数と外接矩形を出す。
     *
     * <p>⚠ <b>向きを仮定しない</b>。最初の実装は「最終画像での座標」で部分矩形を読み戻し、
     * 全カウントが 0 になった — テクスチャの行順は最終画像と反転していた。
     * どちらが上かを当てる代わりに、全面を読んで<b>色の位置関係</b>で判定する:
     * 近/遠の矩形が横に並んでいること、対照帯がその行範囲の外にあること、
     * そして<b>近/遠の行範囲に棄却色が無いこと</b>。これなら上下どちらでも同じ結論になる。
     */
    /** 読み戻しの上限。これを超える画面では測らない (round-2 review R2-N2: 8K で 126 MiB)。 */
    private static final long READBACK_BUDGET_BYTES = 40L << 20;

    private static void requestReadback(GpuTextureView colour, int width, int height) {
        long bytes = (long) width * height * 4;
        if (bytes > READBACK_BUDGET_BYTES) {
            readback = new Readback(true, false, 0, 0, 0, 0, 0, 0,
                "the colour image is " + bytes + " bytes, over the " + READBACK_BUDGET_BYTES
                + " byte readback budget; not measuring rather than allocating that much");
            note("the colour image is too large to read back (" + bytes + " bytes)");
            return;
        }
        GpuBuffer buffer = null;
        try {
            var device = RenderSystem.getDevice();
            buffer = device.createBuffer(() -> "voxy native marker readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            final GpuBuffer target = buffer;
            device.createCommandEncoder().copyTextureToBuffer(colour.texture(), target, 0,
                () -> classifyReadback(target, width, height), 0);
            buffer = null;   // 以後の解放は callback 側の責務
        } catch (Throwable t) {
            readback = new Readback(true, false, 0, 0, 0, 0, 0, 0, "readback request failed: " + t);
            note("could not read Minecraft's colour image back: " + t);
        } finally {
            // ⚠ round-2 review R2-N1: コピーの登録前に投げた場合、バッファが迷子になっていた。
            if (buffer != null) {
                try { buffer.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * 読み戻しの判定結果。<b>位置を決めて、その矩形が埋まっているか</b>を見る。
     *
     * <p>⚠ round-2 review B4: 以前は「色ごとの全画面外接矩形」の関係を見ていたので、
     * <b>離れた無関係の斑点 3 つで条件が成立</b>した (実測: 500 画素の斑点 3 つで
     * 問題なしと判定された)。向きの曖昧さは「両方の向きを試して、
     * 期待矩形が<b>密に</b>埋まっている方を採る」ことで解く。どちらでも埋まらなければ不合格。
     */
    private record Rect(int x0, int y0, int x1, int y1) {
        int area() { return Math.max(0, x1 - x0) * Math.max(0, y1 - y0); }
    }

    private static void classifyReadback(GpuBuffer buffer, int width, int height) {
        try (var view = new GpuBufferSlice(buffer, 0, buffer.size()).map(true, false)) {
            var data = view.data();
            // 期待する矩形を「最終画像の向き」と「その上下反転」の両方で作り、
            // 密度が条件を満たす向きを採用する。
            Readback best = null;
            for (boolean flipped : new boolean[] {false, true}) {
                var result = measure(data, width, height, flipped);
                if (result.note() == null) { best = result; break; }
                if (best == null) best = result;
            }
            readback = best;
            if (best.note() == null) {
                readbackOk++;
            } else {
                readbackProblems++;
                if (firstReadbackProblem == null) firstReadbackProblem = best.note();
            }
            Logger.info("[native-vk] read Minecraft's colour image back: near=" + best.near()
                + " far=" + best.far() + " rejectedInBox=" + best.rejectedInBox()
                + " control=" + best.control()
                + (best.note() == null ? " (as expected)" : " PROBLEM: " + best.note()));
        } catch (Throwable t) {
            readback = new Readback(true, false, 0, 0, 0, 0, 0, 0, "readback classify failed: " + t);
            note("could not classify the colour readback: " + t);
        } finally {
            try { buffer.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * ひとつの向きについて、期待矩形ごとの充填率を測る。
     *
     * <p>要求するのは<b>場所と密度</b>である: 近は箱の左 60% を 8 割以上、
     * 遠は残りを 8 割以上、棄却色は「通るセル」を 8 割以上埋め、
     * <b>箱の中には 1 画素も無い</b>こと。離れた斑点ではこれを満たせない。
     */
    private static Readback measure(java.nio.ByteBuffer data, int width, int height, boolean flipped) {
        Rect box = rect(BOX_X0, BOX_Y0, BOX_X1, BOX_Y1, width, height, flipped);
        Rect nearRect = rect(BOX_X0, BOX_Y0, NEAR_X1, BOX_Y1, width, height, flipped);
        Rect farRect = rect(NEAR_X1, BOX_Y0, BOX_X1, BOX_Y1, width, height, flipped);
        Rect cell = rect(BOX_X0, CELL_Y0, BOX_X1, CELL_Y1, width, height, flipped);
        Rect control = rect(BOX_X0, CONTROL_Y0, BOX_X1, CONTROL_Y1, width, height, flipped);

        int near = count(data, width, height, nearRect, 0);
        int far = count(data, width, height, farRect, 1);
        int rejectedInBox = count(data, width, height, box, 2);
        int cellHits = count(data, width, height, cell, 2);
        int controlHits = count(data, width, height, control, 2);

        String note = null;
        if (near * 10 < nearRect.area() * 8) {
            note = "the near quad fills " + near + " of " + nearRect.area()
                + " pixels of its part of the box" + (flipped ? " (flipped)" : "");
        } else if (far * 10 < farRect.area() * 8) {
            note = "the farther base quad fills " + far + " of " + farRect.area()
                + " pixels of its part of the box" + (flipped ? " (flipped)" : "");
        } else if (cellHits * 10 < cell.area() * 8) {
            note = "the depth-tested rejected colour fills only " + cellHits + " of " + cell.area()
                + " pixels of the cell where it must pass, so that draw was never issued";
        } else if (controlHits * 10 < control.area() * 8) {
            note = "the always-pass control strip holds only " + controlHits + " of "
                + control.area() + " rejected-colour pixels";
        } else if (rejectedInBox > 0) {
            note = rejectedInBox + " rejected-colour pixels are inside the depth-tested box,"
                + " so depth is not working";
        }
        return new Readback(true, true, near, far, rejectedInBox, cellHits,
            box.area(), cell.area(), note);
    }

    /** NDC の矩形を画素矩形へ。{@code flipped} なら行を反転する。 */
    private static Rect rect(float ax, float ay, float bx, float by,
                             int width, int height, boolean flipped) {
        int x0 = clamp((int) ((Math.min(ax, bx) + 1.0f) * 0.5f * width), width);
        int x1 = clamp((int) ((Math.max(ax, bx) + 1.0f) * 0.5f * width), width);
        float top = Math.max(ay, by), bottom = Math.min(ay, by);
        int y0, y1;
        if (flipped) {
            y0 = clamp((int) ((1.0f + bottom) * 0.5f * height), height);
            y1 = clamp((int) ((1.0f + top) * 0.5f * height), height);
        } else {
            y0 = clamp((int) ((1.0f - top) * 0.5f * height), height);
            y1 = clamp((int) ((1.0f - bottom) * 0.5f * height), height);
        }
        return new Rect(x0, y0, x1, y1);
    }

    private static int clamp(int v, int limit) { return Math.max(0, Math.min(v, limit)); }

    /** 矩形内で色 {@code which} (0=near, 1=far, 2=rejected) の画素を数える。 */
    private static int count(java.nio.ByteBuffer data, int width, int height, Rect r, int which) {
        int hits = 0;
        for (int y = r.y0(); y < r.y1() && y < height; y++) {
            int rowBase = y * width * 4;
            for (int x = r.x0(); x < r.x1() && x < width; x++) {
                int at = rowBase + x * 4;
                if (at + 2 >= data.limit()) break;
                int cr = data.get(at) & 0xFF, cg = data.get(at + 1) & 0xFF, cb = data.get(at + 2) & 0xFF;
                boolean match = switch (which) {
                    case 0 -> isNear(cr, cg, cb);
                    case 1 -> isFar(cr, cg, cb);
                    default -> isRejected(cr, cg, cb);
                };
                if (match) hits++;
            }
        }
        return hits;
    }

    private static boolean isNear(int r, int g, int b) { return r >= 200 && g <= 60 && b >= 200; }
    private static boolean isFar(int r, int g, int b) { return r <= 60 && g >= 200 && b >= 200; }
    private static boolean isRejected(int r, int g, int b) { return r >= 200 && g >= 200 && b <= 60; }

    /** NDC (最終画像の向き) から画素座標へ。 */
    private static int pixelX(float ndc, int width) { return (int) ((ndc + 1.0f) * 0.5f * width); }
    private static int pixelY(float ndc, int height) { return (int) ((1.0f - ndc) * 0.5f * height); }

    private void record(VkCommandBuffer cmd, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            var viewport = VkViewport.calloc(1, stack)
                .x(0).y(0).width(width).height(height).minDepth(0).maxDepth(1);
            var scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(width, height);
            vkCmdSetViewport(cmd, 0, viewport);
            vkCmdSetScissor(cmd, 0, scissor);

            boolean hasDepth = this.depthFormat != VK_FORMAT_UNDEFINED;
            if (!hasDepth) {
                // 深度が取れない状況では色だけ置く (旧来の証明に相当)。
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.writePipeline);
                quad(cmd, stack, BOX_X0, BOX_Y0, BOX_X1, BOX_Y1,
                    MARKER_R, MARKER_G, MARKER_B, DEPTH_NEAR);
                return;
            }
            // 基準面は箱と「通るセル」の両方を覆う。セルの深度状態を既知にするため。
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.writePipeline);
            quad(cmd, stack, BOX_X0, BOX_Y0, BOX_X1, BOX_Y1, FAR_R, FAR_G, FAR_B, DEPTH_FAR);
            quad(cmd, stack, BOX_X0, CELL_Y0, BOX_X1, CELL_Y1, FAR_R, FAR_G, FAR_B, DEPTH_FAR);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.testPipeline);
            quad(cmd, stack, BOX_X0, BOX_Y0, NEAR_X1, BOX_Y1,
                MARKER_R, MARKER_G, MARKER_B, DEPTH_NEAR);
            // 深度に落とされるべき 3 枚目。箱のどこにも出てはいけない。
            quad(cmd, stack, BOX_X0, BOX_Y0, BOX_X1, BOX_Y1,
                REJECTED_R, REJECTED_G, REJECTED_B, DEPTH_REJECTED);
            // ⚠ 同じ深度テスト付きパイプラインで、セルには<b>手前</b>の深度を書く。
            // ここに色が出ることが「棄却色の draw は発行された」の証明で、
            // 箱に出ないことが「深度に落とされた」の証明になる (round-2 review B4)。
            quad(cmd, stack, BOX_X0, CELL_Y0, BOX_X1, CELL_Y1,
                REJECTED_R, REJECTED_G, REJECTED_B, DEPTH_NEAR);
            // 対照: 比較 ALWAYS でも描けること (色そのものの可視性の対照)。
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.writePipeline);
            quad(cmd, stack, BOX_X0, CONTROL_Y0, BOX_X1, CONTROL_Y1,
                REJECTED_R, REJECTED_G, REJECTED_B, DEPTH_REJECTED);
        }
    }

    private void quad(VkCommandBuffer cmd, MemoryStack stack,
                      float x0, float y0, float x1, float y1,
                      int r, int g, int b, float depth) {
        // ⚠ y はすでに「最終画像での向き」に合わせた値 (BOX_Y0/BOX_Y1 の符号が実測の結果)。
        // ここで更に反転すると、測って直したはずの上下がまた戻る — 一度それで
        // 箱が画像の下端に出た。だからここでは触らない。
        var push = stack.floats(x0, y0, x1, y1,
            r / 255.0f, g / 255.0f, b / 255.0f, 1.0f,
            depth);
        vkCmdPushConstants(cmd, this.pipelineLayout,
            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, push);
        vkCmdDraw(cmd, 6, 1, 0, 0);
    }

    private static McNativeMarkerDraw create(VulkanDevice device, int colourFormat, int depthFormat) {
        var vk = device.vkDevice();
        long vertexModule = 0, fragmentModule = 0, layout = 0, writePipeline = 0, testPipeline = 0;
        try (MemoryStack stack = stackPush()) {
            vertexModule = shaderModule(device, VkShaderType.VERTEX, VERTEX_SOURCE, "voxy-native-marker.vert");
            fragmentModule = shaderModule(device, VkShaderType.FRAGMENT, FRAGMENT_SOURCE, "voxy-native-marker.frag");
            if (vertexModule == 0 || fragmentModule == 0) {
                destroy(device, vertexModule, fragmentModule, 0, 0, 0);
                return null;
            }

            // ディスクリプタは無し。矩形・色・深度はプッシュ定数だけで渡す
            // (入力が無いこと自体が「有界」の担保になる)。
            var range = VkPushConstantRange.calloc(1, stack)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .offset(0).size(9 * 4);
            var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pPushConstantRanges(range);
            long[] handle = new long[1];
            if (vkCreatePipelineLayout(vk, layoutInfo, null, handle) != VK_SUCCESS) {
                note("vkCreatePipelineLayout failed on Minecraft's device");
                destroy(device, vertexModule, fragmentModule, 0, 0, 0);
                return null;
            }
            layout = handle[0];

            writePipeline = pipeline(vk, stack, vertexModule, fragmentModule, layout,
                colourFormat, depthFormat, VK_COMPARE_OP_ALWAYS);
            testPipeline = depthFormat == VK_FORMAT_UNDEFINED ? writePipeline
                : pipeline(vk, stack, vertexModule, fragmentModule, layout,
                    colourFormat, depthFormat, VK_COMPARE_OP_LESS);
            if (writePipeline == 0 || testPipeline == 0) {
                destroy(device, vertexModule, fragmentModule, layout, writePipeline, testPipeline);
                return null;
            }
            Logger.info("[native-vk] created the marker pipelines on Minecraft's device"
                + " (colour " + colourFormat + ", depth " + depthFormat + ")");
            return new McNativeMarkerDraw(device, colourFormat, depthFormat, vertexModule,
                fragmentModule, layout, writePipeline, testPipeline);
        } catch (Throwable t) {
            note("could not build the marker pipeline: " + t);
            destroy(device, vertexModule, fragmentModule, layout, writePipeline, testPipeline);
            return null;
        }
    }

    /**
     * @param depthCompare 深度比較。{@code ALWAYS} は基準面を置くため、
     *                     {@code LESS} は前後関係を試すため。どちらも深度を書く。
     */
    private static long pipeline(org.lwjgl.vulkan.VkDevice vk, MemoryStack stack,
                                 long vertexModule, long fragmentModule, long layout,
                                 int colourFormat, int depthFormat, int depthCompare) {
        var stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT)
            .module(vertexModule).pName(stack.UTF8("main"));
        stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT)
            .module(fragmentModule).pName(stack.UTF8("main"));

        var vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
        var assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
            .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false);
        var viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
            .viewportCount(1).scissorCount(1);
        var raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
            .depthClampEnable(false).rasterizerDiscardEnable(false)
            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
            .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).depthBiasEnable(false).lineWidth(1.0f);
        var multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT).sampleShadingEnable(false);
        boolean hasDepth = depthFormat != VK_FORMAT_UNDEFINED;
        var depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
            .depthTestEnable(hasDepth)
            .depthWriteEnable(hasDepth)
            .depthCompareOp(depthCompare)
            .depthBoundsTestEnable(false)
            .stencilTestEnable(false);
        var blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
            .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
            .blendEnable(false);
        var blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
            .logicOpEnable(false).pAttachments(blendAttachment);
        var dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
            .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
        // MC のバックエンドは dynamic rendering を使う。パイプラインにも
        // MC から読んだ実フォーマットをそのまま宣言する。
        var rendering = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
            .pColorAttachmentFormats(stack.ints(colourFormat))
            .depthAttachmentFormat(depthFormat);

        var createInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
            .pNext(rendering.address())
            .pStages(stages)
            .pVertexInputState(vertexInput)
            .pInputAssemblyState(assembly)
            .pViewportState(viewportState)
            .pRasterizationState(raster)
            .pMultisampleState(multisample)
            .pDepthStencilState(depthStencil)
            .pColorBlendState(blend)
            .pDynamicState(dynamic)
            .layout(layout)
            .renderPass(VK_NULL_HANDLE);
        long[] handle = new long[1];
        if (vkCreateGraphicsPipelines(vk, VK_NULL_HANDLE, createInfo, null, handle) != VK_SUCCESS) {
            note("vkCreateGraphicsPipelines failed on Minecraft's device (compare " + depthCompare + ")");
            return 0;
        }
        return handle[0];
    }

    private static long shaderModule(VulkanDevice device, VkShaderType type, String source, String name) {
        try (MemoryStack stack = stackPush()) {
            var spirv = SpirvCompiler.compile(type, source, name);
            var info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            long[] handle = new long[1];
            if (vkCreateShaderModule(device.vkDevice(), info, null, handle) != VK_SUCCESS) {
                note("vkCreateShaderModule failed for " + name);
                return 0;
            }
            return handle[0];
        } catch (Throwable t) {
            note("could not compile " + name + ": " + t);
            return 0;
        }
    }

    /**
     * ⚠ 破棄は<b>MC の提出寿命に合わせる</b>。今のフレームがまだ実行中でも安全なように、
     * {@code queueForDestroy} に預ける。これが MC のバックエンドが提供する契約で、
     * Voxy 側の明示 fence 待ちはここでは使わない
     * [docs/ai/vulkan-native-integration-survey.md]。
     */
    private static void retire(McNativeMarkerDraw draw, VulkanDevice device) {
        if (draw == null || draw.destroyed) return;
        // ⚠ round-2 review B5: device が入れ替わっている場合、この draw は<b>古い device</b>の
        // ものなので、新しい device の encoder に退役を預けても意味がない。壊さずに漏らす。
        if (draw.device != device) {
            note("the marker pipeline belongs to a device that is no longer current;"
                + " leaking it on purpose rather than destroying it against the wrong device");
            draw.destroyed = true;
            return;
        }
        try {
            McNativeVulkan.encoder(device).queueForDestroy(draw);
        } catch (Throwable t) {
            // ⚠ round-1 review B5: ここで即破棄していた。退役を Minecraft に預けられなかった
            // 時点で「もう使われていない」根拠が無いので、<b>壊さずに漏らす</b>。
            // 診断 1 個分のパイプラインであり、壊して実行中参照になる方が遥かに悪い。
            note("queueForDestroy refused the marker pipeline (" + t + "); leaking it on purpose"
                + " rather than destroying something that may still be in use");
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        destroy(this.device, this.vertexModule, this.fragmentModule, this.pipelineLayout,
            this.writePipeline, this.testPipeline);
    }

    private static void destroy(VulkanDevice device, long vertexModule, long fragmentModule,
                                long layout, long writePipeline, long testPipeline) {
        var vk = device.vkDevice();
        if (testPipeline != 0 && testPipeline != writePipeline) vkDestroyPipeline(vk, testPipeline, null);
        if (writePipeline != 0) vkDestroyPipeline(vk, writePipeline, null);
        if (layout != 0) vkDestroyPipelineLayout(vk, layout, null);
        if (fragmentModule != 0) vkDestroyShaderModule(vk, fragmentModule, null);
        if (vertexModule != 0) vkDestroyShaderModule(vk, vertexModule, null);
    }

    /**
     * ワールドを離れるときなど、明示的に手放したいとき。
     * ⚠ Minecraft が device を壊す前に呼ぶこと — 残っていると
     * 「device 破棄前に子オブジェクトを全て破棄せよ」と検証レイヤに指摘される。
     */
    public static void shutdown() {
        var draw = instance;
        instance = null;
        if (draw == null) return;
        var device = McNativeVulkan.device();
        if (device != null) {
            retire(draw, device);
            return;
        }
        // ⚠ round-2 review B5: ここで即破棄していた。device が取れないということは
        // 「提出が終わった」ことも確かめられないということなので、漏らす方を選ぶ。
        note("Minecraft's device is no longer reachable, so the marker pipeline cannot be shown"
            + " to be unused; leaking it on purpose");
        draw.destroyed = true;
    }

    /**
     * <b>いま壊す</b>。クライアント終了時はこれ以上提出が無いので、
     * {@code queueForDestroy} に預けても<b>誰も処理しない</b> — 預けたままだと
     * Minecraft が device を壊すときに「子オブジェクトが残っている」と正しく指摘される。
     */
    public static void shutdownImmediate() {
        var draw = instance;
        instance = null;
        if (draw != null) draw.destroy();
    }

    /** {@link #shutdownImmediate()} の呼び出し元が device のアイドルを確認済みであること。 */
    public static boolean hasLiveDraw() { return instance != null && !instance.destroyed; }

    private static void note(String note) {
        synchronized (NOTES) {
            if (NOTES.size() < 32 && !NOTES.contains(note)) NOTES.add(note);
        }
        if (!complained) {
            complained = true;
            Logger.warn("[native-vk] " + note);
        }
    }

    /**
     * <b>MC のカラー画像を描画直後に読み戻して数えた画素</b>。
     *
     * <p>⚠ round-1 review B4 の本質的な弱点への答え。最終スクリーンショットは
     * MC がこの後に描く GUI / オーバーレイ / ポスト処理に左右されるので、
     * 「マーカーが見えない」が「描けていない」を意味しない (実測: 次元切替の
     * フレームでは左上が暗いだけでマーカーが消えた)。読み戻しは
     * <b>こちらの draw が MC のカラー画像に届いたか</b>そのものを測る。
     *
     * <p>コピーは MC の {@code CommandEncoder.copyTextureToBuffer} で行う —
     * 画像のレイアウト遷移は MC が持ったままになる。
     */
    public record Readback(boolean attempted, boolean completed, int near, int far,
                           int rejectedInBox, int control, int boxArea, int controlArea,
                           String note) {}

    public static Readback readback() { return readback; }

    /** これまでに記録した draw の本数と、諦めた理由。 */
    public record Status(boolean enabled, long drawsRecorded, boolean pipelineLive,
                         int colourFormat, List<String> notes) {}

    public static Status status() {
        var draw = instance;
        synchronized (NOTES) {
            return new Status(Boolean.getBoolean(FLAG), drawsRecorded,
                draw != null && !draw.destroyed, draw == null ? 0 : draw.colourFormat,
                List.copyOf(NOTES));
        }
    }

    /**
     * 証跡を書く。最初の draw の直後と、以後 600 本ごと
     * ({@code -Dvoxy.harness.output} が指定されているときだけ)。
     */
    private static void writeEvidenceIfDue(VulkanDevice device, int format, int width, int height) {
        if (drawsRecorded != 1 && drawsRecorded % 600 != 0) return;
        if (evidenceWrittenAt == drawsRecorded) return;
        String dir = System.getProperty("voxy.harness.output");
        if (dir == null || dir.isBlank()) return;
        evidenceWrittenAt = drawsRecorded;
        var status = status();
        var sb = new StringBuilder("{\n");
        sb.append("  \"enabled\": ").append(status.enabled()).append(",\n");
        sb.append("  \"drawsRecorded\": ").append(status.drawsRecorded()).append(",\n");
        sb.append("  \"pipelineLive\": ").append(status.pipelineLive()).append(",\n");
        sb.append("  \"colourVkFormat\": ").append(format).append(",\n");
        sb.append("  \"targetWidth\": ").append(width).append(",\n");
        sb.append("  \"targetHeight\": ").append(height).append(",\n");
        sb.append("  \"markerRgb\": [").append(MARKER_R).append(", ").append(MARKER_G)
            .append(", ").append(MARKER_B).append("],\n");
        sb.append("  \"farRgb\": [").append(FAR_R).append(", ").append(FAR_G)
            .append(", ").append(FAR_B).append("],\n");
        sb.append("  \"rejectedRgb\": [").append(REJECTED_R).append(", ").append(REJECTED_G)
            .append(", ").append(REJECTED_B).append("],\n");
        var draw = instance;
        sb.append("  \"depthAttached\": ")
            .append(draw != null && draw.depthFormat != VK_FORMAT_UNDEFINED).append(",\n");
        // ゲートが「どこに何色が出ているべきか」を推測しないよう、幾何をそのまま渡す
        // (NDC, 最終画像の向き)。round-1 review B4。
        sb.append("  \"geometry\": {")
            .append("\"box\": [").append(BOX_X0).append(", ").append(BOX_Y0).append(", ")
            .append(BOX_X1).append(", ").append(BOX_Y1).append("], ")
            .append("\"nearSplitX\": ").append(NEAR_X1).append(", ")
            .append("\"controlStrip\": [").append(BOX_X0).append(", ").append(CONTROL_Y0)
            .append(", ").append(BOX_X1).append(", ").append(CONTROL_Y1).append("], ")
            .append("\"depthTestedPassCell\": [").append(BOX_X0).append(", ").append(CELL_Y0)
            .append(", ").append(BOX_X1).append(", ").append(CELL_Y1).append("]},\n");
        sb.append("  \"depthVkFormat\": ").append(draw == null ? 0 : draw.depthFormat).append(",\n");
        sb.append("  \"device\": \"0x").append(Long.toHexString(device.vkDevice().address())).append("\",\n");
        var rb = readback;
        sb.append("  \"readback\": ");
        if (rb == null) {
            sb.append("null,\n");
        } else {
            sb.append("{\"attempted\": ").append(rb.attempted())
              .append(", \"completed\": ").append(rb.completed())
              .append(", \"near\": ").append(rb.near())
              .append(", \"far\": ").append(rb.far())
              .append(", \"rejectedInBox\": ").append(rb.rejectedInBox())
              .append(", \"control\": ").append(rb.control())
              .append(", \"boxArea\": ").append(rb.boxArea())
              .append(", \"controlArea\": ").append(rb.controlArea())
              .append(", \"note\": ").append(McNativeVulkanProbe.quote(rb.note()))
              .append(", \"timesClean\": ").append(readbackOk)
              .append(", \"timesWithAProblem\": ").append(readbackProblems)
              .append(", \"firstProblem\": ").append(McNativeVulkanProbe.quote(firstReadbackProblem))
              .append("},\n");
        }
        sb.append("  \"notes\": [");
        var notes = status.notes();
        for (int i = 0; i < notes.size(); i++) {
            sb.append(i == 0 ? "\n    " : ",\n    ").append(McNativeVulkanProbe.quote(notes.get(i)));
        }
        sb.append(notes.isEmpty() ? "]\n}" : "\n  ]\n}");
        try {
            Path out = Path.of(dir);
            Files.createDirectories(out);
            Files.writeString(out.resolve("native-marker-draw.json"), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            note("could not write the marker evidence: " + t);
        }
    }
}
