package me.cortex.voxy.client.core.vk.mcnative;

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

    private static McNativeMarkerDraw instance;
    private static long drawsRecorded;
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
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.writePipeline);
            quad(cmd, stack, BOX_X0, BOX_Y0, BOX_X1, BOX_Y1, FAR_R, FAR_G, FAR_B, DEPTH_FAR);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.testPipeline);
            quad(cmd, stack, BOX_X0, BOX_Y0, NEAR_X1, BOX_Y1,
                MARKER_R, MARKER_G, MARKER_B, DEPTH_NEAR);
            quad(cmd, stack, BOX_X0, BOX_Y0, BOX_X1, BOX_Y1,
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
        try {
            McNativeVulkan.encoder(device).queueForDestroy(draw);
        } catch (Throwable t) {
            note("queueForDestroy refused the marker pipeline (" + t + "); destroying immediately");
            draw.destroy();
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
        if (device != null) retire(draw, device); else draw.destroy();
    }

    private static void note(String note) {
        synchronized (NOTES) {
            if (NOTES.size() < 32 && !NOTES.contains(note)) NOTES.add(note);
        }
        if (!complained) {
            complained = true;
            Logger.warn("[native-vk] " + note);
        }
    }

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
        sb.append("  \"depthVkFormat\": ").append(draw == null ? 0 : draw.depthFormat).append(",\n");
        sb.append("  \"device\": \"0x").append(Long.toHexString(device.vkDevice().address())).append("\",\n");
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
