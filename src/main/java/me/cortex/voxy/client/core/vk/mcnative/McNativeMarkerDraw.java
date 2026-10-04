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
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
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
    public static final String VERTEX_SOURCE = """
        #version 460
        // 頂点バッファ無し。小さな四角を 2 三角形で出す。
        //
        // ⚠ y の符号は**実測で決めた**。素の Vulkan NDC は y が下向きなので
        // y が負なら画面上端のはずだが、MC が合成した最終フレーム
        // (スクリーンショット) では上下が反転して現れた — y=-0.98 で描いた矩形が
        // 960px 画像の y≈854、つまり下端に出た。したがって最終画像の左上に出すには
        // y を正にする。MC の最終合成は Vulkan の NDC をそのまま画像行に写さない
        // [docs/ai/vulkan-native-integration-survey.md で測定]。
        void main() {
            const vec2 corners[6] = vec2[6](
                vec2(-0.98, 0.98), vec2(-0.78, 0.98), vec2(-0.98, 0.78),
                vec2(-0.78, 0.98), vec2(-0.78, 0.78), vec2(-0.98, 0.78));
            gl_Position = vec4(corners[gl_VertexIndex], 0.0, 1.0);
        }
        """;

    /** @see #VERTEX_SOURCE */
    public static final String FRAGMENT_SOURCE = """
        #version 460
        layout(location = 0) out vec4 outColour;
        void main() {
            // 不透明マゼンタ。Minecraft の実シーンにはほぼ出ない色なので、
            // スクリーンショットから機械的に検出できる。
            outColour = vec4(1.0, 0.0, 1.0, 1.0);
        }
        """;

    /**
     * 描くときの色 (RGB)。
     *
     * <p>⚠ <b>最終画像ではこの値そのままにはならない</b>。実測では
     * {@code (255, 0, 255)} で描いた画素がスクリーンショットでは
     * {@code (235, 0, 235)} になった — MC の最終合成が約 8% 暗くする。
     * ピクセル検証は許容幅を持たせ、実際に出た値を証跡に残すこと。
     */
    public static final int MARKER_R = 255, MARKER_G = 0, MARKER_B = 255;

    private static McNativeMarkerDraw instance;
    private static long drawsRecorded;
    private static long evidenceWrittenAt = -1;
    private static final List<String> NOTES = new ArrayList<>();
    private static boolean complained;

    private final VulkanDevice device;
    private final int colourFormat;
    private final long vertexModule;
    private final long fragmentModule;
    private final long pipelineLayout;
    private final long pipeline;
    private boolean destroyed;

    private McNativeMarkerDraw(VulkanDevice device, int colourFormat, long vertexModule,
                               long fragmentModule, long pipelineLayout, long pipeline) {
        this.device = device;
        this.colourFormat = colourFormat;
        this.vertexModule = vertexModule;
        this.fragmentModule = fragmentModule;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
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
        int format = VulkanConst.toVk(colour.texture().getFormat());
        int width = colour.getWidth(0);
        int height = colour.getHeight(0);
        if (width <= 0 || height <= 0) return;

        McNativeMarkerDraw draw = instance;
        if (draw != null && (draw.device != device || draw.colourFormat != format)) {
            // 画面のフォーマットが変わった / デバイスが差し替わった。
            // 破棄は MC の提出寿命に合わせる — 今まさに使われている可能性があるため。
            retire(draw, device);
            draw = null;
        }
        if (draw == null) {
            draw = create(device, format);
            if (draw == null) return;
            instance = draw;
        }

        // ⚠ パスは MC の API で開く。色アタッチメントのレイアウト遷移とバリアは
        // MC が持ったままになり、こちらは MC のレイアウト規約を仮定しない。
        // LOAD で開く (クリアしない) ので、この時点までに描かれた絵は残る。
        try (var pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "voxy native marker", colour, Optional.empty())) {
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

    private void record(VkCommandBuffer cmd, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipeline);
            var viewport = VkViewport.calloc(1, stack)
                .x(0).y(0).width(width).height(height).minDepth(0).maxDepth(1);
            var scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(width, height);
            vkCmdSetViewport(cmd, 0, viewport);
            vkCmdSetScissor(cmd, 0, scissor);
            vkCmdDraw(cmd, 6, 1, 0, 0);
        }
    }

    private static McNativeMarkerDraw create(VulkanDevice device, int colourFormat) {
        var vk = device.vkDevice();
        long vertexModule = 0, fragmentModule = 0, layout = 0, pipeline = 0;
        try (MemoryStack stack = stackPush()) {
            vertexModule = shaderModule(device, VkShaderType.VERTEX, VERTEX_SOURCE, "voxy-native-marker.vert");
            fragmentModule = shaderModule(device, VkShaderType.FRAGMENT, FRAGMENT_SOURCE, "voxy-native-marker.frag");
            if (vertexModule == 0 || fragmentModule == 0) {
                destroy(device, vertexModule, fragmentModule, 0, 0);
                return null;
            }

            // ディスクリプタもプッシュ定数も無い。入力が無いこと自体が「有界」の担保になる。
            var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            long[] handle = new long[1];
            if (vkCreatePipelineLayout(vk, layoutInfo, null, handle) != VK_SUCCESS) {
                note("vkCreatePipelineLayout failed on Minecraft's device");
                destroy(device, vertexModule, fragmentModule, 0, 0);
                return null;
            }
            layout = handle[0];

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
            // 深度は付けない。深度規約 (reverse-Z か否か、MC が残すレイアウト) は
            // この一歩の範囲外であり、混ぜると「描けたか」が判定できなくなる。
            var blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                    | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                .blendEnable(false);
            var blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                .logicOpEnable(false).pAttachments(blendAttachment);
            var dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            // MC のバックエンドは dynamic rendering を使う。パイプラインにも
            // 同じ色フォーマットを宣言する (MC から読んだ実フォーマットをそのまま)。
            var rendering = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                .pColorAttachmentFormats(stack.ints(colourFormat))
                .depthAttachmentFormat(VK_FORMAT_UNDEFINED);

            var createInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
                .pNext(rendering.address())
                .pStages(stages)
                .pVertexInputState(vertexInput)
                .pInputAssemblyState(assembly)
                .pViewportState(viewportState)
                .pRasterizationState(raster)
                .pMultisampleState(multisample)
                .pColorBlendState(blend)
                .pDynamicState(dynamic)
                .layout(layout)
                .renderPass(VK_NULL_HANDLE);
            if (vkCreateGraphicsPipelines(vk, VK_NULL_HANDLE, createInfo, null, handle) != VK_SUCCESS) {
                note("vkCreateGraphicsPipelines failed on Minecraft's device");
                destroy(device, vertexModule, fragmentModule, layout, 0);
                return null;
            }
            pipeline = handle[0];
            Logger.info("[native-vk] created the marker pipeline on Minecraft's device"
                + " (colour format " + colourFormat + ")");
            return new McNativeMarkerDraw(device, colourFormat, vertexModule, fragmentModule, layout, pipeline);
        } catch (Throwable t) {
            note("could not build the marker pipeline: " + t);
            destroy(device, vertexModule, fragmentModule, layout, pipeline);
            return null;
        }
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
        destroy(this.device, this.vertexModule, this.fragmentModule, this.pipelineLayout, this.pipeline);
    }

    private static void destroy(VulkanDevice device, long vertexModule, long fragmentModule,
                                long layout, long pipeline) {
        var vk = device.vkDevice();
        if (pipeline != 0) vkDestroyPipeline(vk, pipeline, null);
        if (layout != 0) vkDestroyPipelineLayout(vk, layout, null);
        if (fragmentModule != 0) vkDestroyShaderModule(vk, fragmentModule, null);
        if (vertexModule != 0) vkDestroyShaderModule(vk, vertexModule, null);
    }

    /** ワールドを離れるときなど、明示的に手放したいとき。 */
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
