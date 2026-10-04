package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.interop.VkInteropImage;
import me.cortex.voxy.client.core.vk.shader.VkAutoBindingShader;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;

/**
 * Phase 5c-1c — <b>Minecraft の深度を Vulkan が受け取り、色に変換して返す</b>パス。
 *
 * <pre>
 * MC の深度 → (GL の取り込みパス) → IOSurface(R32F) → <b>ここ</b> → IOSurface(BGRA) → GL 合成
 * </pre>
 *
 * <p>{@link VkDepthResolve} の<b>逆向き</b>である。5b までは Vulkan が書いて GL が読んでいたが、
 * この段で初めて <b>GL が書いて Vulkan が読む</b>向きが本番経路に入る。
 *
 * <h2>この段で確かめられること</h2>
 * <ul>
 *   <li>深度が届いている (MC の地形の形が見える)</li>
 *   <li><b>深度側の経路の Y の向き</b> — 色経路 (5c-1b) とは別経路なので、
 *       片方の反転が他方を打ち消せない [docs/phase5c-plan.md §9]</li>
 *   <li>深度の値域・向き (手前が明るいか)</li>
 * </ul>
 *
 * <h2>⚠ 読み手側の落とし穴 — レイアウトの初期遷移</h2>
 * GL が書いた画像を Vulkan が読む場合、<b>{@code UNDEFINED} からの遷移は
 * GL の書き込みを捨てる</b>。{@link VkInteropImage#primeLayout()} を
 * <b>GL が書く前に</b>済ませておくこと [docs/phase5a-gl-to-vk-sync.md §6.1]。
 *
 * <h2>レイアウトは {@code GENERAL} で通す</h2>
 * 読み元は GL と共有しているので {@code GENERAL} に据え置く
 * ({@link VkInteropImage} の規約)。書き先は描画中だけ
 * {@code COLOR_ATTACHMENT_OPTIMAL} にし、最後に {@code GENERAL} へ戻して GL に渡す。
 *
 * <p><b>テストも本番と同じ {@link #record} を通る</b> — 素の {@link VkTexture} を
 * 渡せば interop 無しで同じ描画ができる。実装に変種を作らないので、
 * 対照が本番でない経路を守る事故が起きない [規約 5]。
 */
public class VkDepthVisualise {
    /**
     * グレー化の指数。<b>見やすさのために選んだ任意の定数である</b>
     * (根拠と値域の計算は {@code docs/phase5c1c-completion.md})。
     *
     * <p>逆Zの深度は概ね {@code near/distance} なので、生値のままでは
     * ほとんどの画素が 0 付近に潰れて<b>画面がほぼ真っ黒</b>になる。
     * {@code pow(z, 1/8)} だと距離が 2 倍になるごとに輝度が約 8.3% 下がり、
     * 1〜512 ブロックが 0.69〜0.32 に収まる。
     *
     * <p><b>物理量ではないので、これを使って距離を読み取ってはならない。</b>
     * 5c-1c の目的は「形と向きが見えること」であって測光ではない。
     */
    public static final float GAMMA = 1.0f / 8.0f;

    /**
     * 空 (= 深度がクリア値のまま = 何も描かれていない画素) を塗る色。マゼンタ。
     *
     * <p><b>グレースケールに現れない色</b>であることが重要である。
     * 灰色の濃淡と地続きの色にすると「暗い地形」と「空」が区別できず、
     * 目視の判定基準にならない。
     */
    public static final float[] SKY_RGB = {1.0f, 0.0f, 1.0f};

    /** {@link #SKY_RGB} をシェーダに渡す形。<b>2 箇所に数値を書かない</b>ため導出する。 */
    public static final String SKY_GLSL =
        "vec3(" + SKY_RGB[0] + ", " + SKY_RGB[1] + ", " + SKY_RGB[2] + ")";

    private final VkAutoBindingShader shader;
    private final VkGraphicsPipeline pipeline;
    private final int width, height;
    private boolean freed;

    /**
     * @param srcDepth 読む深度。R32F で {@code VK_IMAGE_USAGE_SAMPLED_BIT} が要る。
     *                 本番では {@link VkInteropImage#texture()} を渡す
     * @param colourFormat 書き先のフォーマット。本番は
     *                 {@link VkInteropImage.Kind#COLOR_BGRA8}
     */
    public VkDepthVisualise(VkTexture srcDepth, int width, int height, int colourFormat) {
        this.width = width;
        this.height = height;

        this.shader = VkDepth.defines(VkShader.makeAuto().name("vk-depth-visualise")
                .addSource(ShaderType.VERTEX, VkShaderLoader.parse("voxy:lod/vk/depth_visualise.vert"))
                .addSource(ShaderType.FRAGMENT, VkShaderLoader.parse("voxy:lod/vk/depth_visualise.frag"))
                // ⚠ 空の判定に使う FAR は VkDepth.defines が入れる USE_REVERSE_Z から
                // depthutils.glsl が決める。**シェーダに 0.0 を直書きしていない**ので、
                // 規約を変えれば可視化も一緒に変わる [規約 6]
                .define("DEPTH_VIS_GAMMA", GAMMA)
                .define("DEPTH_VIS_SKY", SKY_GLSL))
            .compile();

        this.shader.texture(0, srcDepth, VkSampler.nearestClamp(), VK_IMAGE_LAYOUT_GENERAL);
        var missing = this.shader.unboundBindings();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("depth visualise has unbound descriptors: " + missing);
        }

        this.pipeline = VkGraphicsPipeline.builder(this.shader)
            .colorFormat(colourFormat)
            // 深度アタッチメント無しのパス。宣言も UNDEFINED にしないと
            // VkRenderingInfo と食い違う
            .depthFormat(VK_FORMAT_UNDEFINED)
            .depthTest(false).depthWrite(false)
            .cullMode(VK_CULL_MODE_NONE)
            .build();
    }

    /**
     * 可視化を記録する。呼び出し後、{@code dst} は {@code GENERAL} にあり GL から読める。
     *
     * <p>⚠ <b>{@code src} に GL が書いた内容は、この時点で既に GL 側の同期が
     * 済んでいなければならない。</b> GL の書き込み順序は Vulkan のバリアでは表現できず、
     * CPU 側の同期でしか担保できない [docs/phase5a-gl-to-vk-sync.md §4]。
     *
     * @param src 深度 (R32F)。GL が書いたもの
     * @param dst 色の書き先
     */
    public void record(VkCommandBuffer cmd, VkTexture src, VkTexture dst) {
        this.record(cmd, src, dst, null);
    }

    /**
     * @param overlay 全面の可視化を描いた<b>直後、レンダリングを閉じる前</b>に走る。
     *                目印を重ねるのに使う (5c-1b の緑の四角)。
     *                同じレンダリングの中なので順序が保証され、
     *                バリアも追加のパスも要らない。{@code null} 可
     */
    public void record(VkCommandBuffer cmd, VkTexture src, VkTexture dst, Runnable overlay) {
        if (this.freed) throw new IllegalStateException("VkDepthVisualise was freed");
        if (dst.width != this.width || dst.height != this.height) {
            throw new IllegalArgumentException("destination is " + dst.width + "x" + dst.height
                + " but the pass was built for " + this.width + "x" + this.height);
        }
        if (src.width != this.width || src.height != this.height) {
            // 大きさが違うと texelFetch が範囲外を読む (未定義)。
            // 5c-1b で MC のテクスチャ実寸とターゲットの論理サイズが食い違う例を踏んでいる
            throw new IllegalArgumentException("source is " + src.width + "x" + src.height
                + " but the pass was built for " + this.width + "x" + this.height);
        }

        // 読み元。GL と共有しているので GENERAL 据え置き。
        // src 側のステージは「前フレームにこのパスが読んだこと」に対する WAR で、
        // GL の書き込みに対する可視性は CPU 同期が担保している
        src.barrier(cmd, 0, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);

        // 書き先。前フレームで GL が読んだかもしれないので実行依存だけ張る (WAR)
        dst.barrier(cmd, 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

        try (MemoryStack stack = stackPush()) {
            var att = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                .imageView(dst.view(0))
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                // 全面を書くので LOAD は要らない
                .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);

            var ri = VkRenderingInfo.calloc(stack).sType$Default()
                .layerCount(1).pColorAttachments(att);
            ri.renderArea().offset().set(0, 0);
            ri.renderArea().extent().set(this.width, this.height);
            vkCmdBeginRendering(cmd, ri);

            var vp = VkViewport.calloc(1, stack)
                .x(0).y(0).width(this.width).height(this.height).minDepth(0).maxDepth(1);
            var sc = VkRect2D.calloc(1, stack);
            sc.offset().set(0, 0);
            sc.extent().set(this.width, this.height);
            vkCmdSetViewport(cmd, 0, vp);
            vkCmdSetScissor(cmd, 0, sc);

            this.pipeline.bind(cmd);
            this.shader.bind(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS);
            vkCmdDraw(cmd, 3, 1, 0, 0);

            if (overlay != null) overlay.run();

            vkCmdEndRendering(cmd);
        }

        // GL に渡す
        dst.barrier(cmd, 0, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
    }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        this.pipeline.free();
        this.shader.free();
    }
}
