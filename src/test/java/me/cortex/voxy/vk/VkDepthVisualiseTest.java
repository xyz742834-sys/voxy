package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.interop.VkInteropImage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * <b>深度の可視化 (5c-1c) が逆Zの約束事に結び付いていること。</b>
 *
 * <h2>なぜ JUnit にも置くのか</h2>
 * 同じ内容は {@code interopCompositeCheck} の C11 でも見ているが、
 * <b>あちらは GL コンテキストが要るので手で走らせる検査</b>である
 * (macOS の GLFW が main スレッドを要求するため JUnit では動かない
 * [docs/phase5a-gl-to-vk-sync.md §7])。
 *
 * <p>ここで守りたいのは<b>規約の結び付き</b>である —
 * 「空 = 何も描かれていない画素」の判定は
 * {@link VkDepth#FAR} から {@code util/depthutils.glsl} の {@code FAR} を経由して
 * シェーダに届いている。逆Zを外せば <b>FAR は 1.0 になり、この検査は落ちる</b>。
 * <b>常時走る網に置いておかないと、規約を変えたときに可視化だけが取り残される</b> [規約 6]。
 *
 * <h2>この検査を空虚に満たす方法と、その塞ぎ方</h2>
 * <ol>
 *   <li><b>全部が空の色になっている</b> → 「空が空の色」は自明に通る。
 *       {@link #onlyTheFarTexelsBecomeTheSkyColour} が
 *       <b>FAR でない画素は空の色にならないこと</b>も同時に要求する</li>
 *   <li><b>出力が一様</b> → 深度を運んでいなくても通る。
 *       {@link #nearerDepthIsBrighter} が<b>値の順序</b>を要求する</li>
 *   <li><b>行がずれている</b> → 値の集合だけ見ると通る。
 *       {@link #rowsAreNotShuffled} が<b>どの行にどの値が来たか</b>を見る</li>
 * </ol>
 *
 * <p>⚠ <b>「行 N が画面の上か下か」はここでは言えない。</b>
 * 生成も検証も同じ「framebuffer の行」を参照しているので、
 * 規約が反転すれば<b>両方が反転して一致する</b> [規約 4]。
 * 物理的な上下は MC 上で「空が画面の上に出るか」を見て確かめる。
 */
public class VkDepthVisualiseTest {
    private static final int W = 64, H = 64;
    /** 空にする行の開始。絵の上 1/4 (GL 規約なので行が大きい側)。 */
    private static final int SKY_START = H * 3 / 4;

    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
        VkFrameTracker.init();
    }

    @AfterAll
    static void teardown() {
        if (VkFrameTracker.isRecordingFrame()) VkFrameTracker.get().endFrame();
        VkFrameTracker.get().waitIdle();
        VkSampler.shutdown();
        VkQuadIndexBuffer.shutdown();
        VkFrameTracker.shutdown();
    }

    /**
     * 行ごとに異なる深度。<b>一様な帯では行のずれを見逃す。</b>
     * 実際の MC と同じ形にしてある — 上 1/4 が空、行 0 (絵の下端) が最も手前。
     */
    private static float[] pattern() {
        float[] d = new float[W * H];
        for (int y = 0; y < H; y++) {
            float v = y >= SKY_START
                ? VkDepth.CLEAR
                : 0.999f - 0.998f * (y / (float) (SKY_START - 1));
            for (int x = 0; x < W; x++) d[y * W + x] = v;
        }
        return d;
    }

    /** 可視化を 1 回走らせて BGRA8 の結果を返す。 */
    private static int[] visualise(float[] depth) {
        VkTexture src = new VkTexture(VK_FORMAT_R32_SFLOAT, 1, W, H,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        VkTexture dst = new VkTexture(VkInteropImage.Kind.COLOR_BGRA8.vkFormat, 1, W, H,
            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        VkBuffer staging = new VkBuffer((long) W * H * 4,
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        VkBuffer readback = new VkBuffer((long) W * H * 4,
            VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        VkDepthVisualise vis = null;
        try {
            long addr = staging.addr();
            for (int i = 0; i < depth.length; i++) MemoryUtil.memPutFloat(addr + (long) i * 4, depth[i]);

            vis = new VkDepthVisualise(src, W, H, VkInteropImage.Kind.COLOR_BGRA8.vkFormat);

            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            upload(cmd, staging, src);
            // 本番と同じ record を通す [規約 5]。interop かどうかは
            // 周りのバリアの違いでしかなく、描画そのものは同一である
            vis.record(cmd, src, dst);
            download(cmd, dst, readback);
            t.endFrame();
            t.waitForFrame();

            int[] out = new int[W * H];
            long base = readback.addr();
            for (int i = 0; i < out.length; i++) out[i] = MemoryUtil.memGetInt(base + (long) i * 4);
            return out;
        } finally {
            if (vis != null) vis.free();
            readback.free();
            staging.free();
            dst.free();
            src.free();
        }
    }

    private static void upload(VkCommandBuffer cmd, VkBuffer staging, VkTexture tex) {
        tex.barrier(cmd, 0, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            var region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(W, H, 1);
            vkCmdCopyBufferToImage(cmd, staging.handle, tex.image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
        // 可視化パスは GENERAL 据え置きで読む (interop と同じ扱い)
        tex.barrier(cmd, 0, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    private static void download(VkCommandBuffer cmd, VkTexture tex, VkBuffer dst) {
        tex.barrier(cmd, 0, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        try (MemoryStack stack = stackPush()) {
            var region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(W, H, 1);
            vkCmdCopyImageToBuffer(cmd, tex.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                dst.handle, region);
        }
    }

    /** BGRA8 の 1 テクセルから R / G / B を取り出す。 */
    private static int b(int texel) { return texel & 0xFF; }
    private static int g(int texel) { return (texel >>> 8) & 0xFF; }
    private static int r(int texel) { return (texel >>> 16) & 0xFF; }

    private static boolean isSky(int texel) {
        return r(texel) == Math.round(VkDepthVisualise.SKY_RGB[0] * 255)
            && g(texel) == Math.round(VkDepthVisualise.SKY_RGB[1] * 255)
            && b(texel) == Math.round(VkDepthVisualise.SKY_RGB[2] * 255);
    }

    /**
     * <b>空の判定が {@link VkDepth#FAR} に結び付いていること。</b>
     *
     * <p>逆Zを外すと {@code FAR} は 1.0 になり、
     * <b>空だった画素が最も明るいグレーになる</b>のでここが落ちる。
     * これが<b>規約とシェーダを繋ぎ止めている 1 本</b>である。
     */
    @Test
    void onlyTheFarTexelsBecomeTheSkyColour() {
        int[] px = visualise(pattern());
        for (int y = SKY_START; y < H; y++) {
            assertTrue(isSky(px[y * W + W / 2]),
                "row " + y + " has depth FAR, so it must be the sky colour");
        }
        // ⚠ 対照: 全部が空の色なら上の主張は自明になる
        for (int y = 0; y < SKY_START; y++) {
            assertFalse(isSky(px[y * W + W / 2]),
                "row " + y + " has geometry, so it must NOT be the sky colour");
        }
    }

    /** 描かれた画素は<b>グレー</b>であること (色相を持たない = 深度以外の情報を混ぜていない)。 */
    @Test
    void drawnTexelsAreGrey() {
        int[] px = visualise(pattern());
        for (int y = 0; y < SKY_START; y++) {
            int v = px[y * W + W / 2];
            assertEquals(r(v), g(v), "row " + y + " must be grey (r == g)");
            assertEquals(g(v), b(v), "row " + y + " must be grey (g == b)");
        }
    }

    /**
     * <b>手前ほど明るいこと。</b> 5c-1c の目視判定「手前が明るいか」を機械的に固定する。
     *
     * <p>逆Zでは深度値が大きいほど手前なので、
     * パターン (行 0 が最も手前) では<b>行が増えるほど暗く</b>なる。
     */
    @Test
    void nearerDepthIsBrighter() {
        int[] px = visualise(pattern());
        int previous = 256;
        for (int y = 0; y < SKY_START; y++) {
            int v = r(px[y * W + W / 2]);
            assertTrue(v <= previous,
                "brightness must fall with distance, but row " + y + " (" + v
                    + ") is brighter than row " + (y - 1) + " (" + previous + ")");
            previous = v;
        }
        // 対照: 一様な出力なら上の <= は自明に通る。使える幅があることを要求する
        int nearest = r(px[0 * W + W / 2]);
        int furthest = r(px[(SKY_START - 1) * W + W / 2]);
        assertTrue(nearest - furthest > 32,
            "the grey range is too narrow to read (nearest=" + nearest
                + " furthest=" + furthest + "); the gamma no longer spreads the values");
    }

    /**
     * <b>D2: descriptor の宣言レイアウトと描画時の実レイアウトが一致していること。</b>
     *
     * <p>読み元は interop の規約どおり {@code GENERAL} に据え置かれるので、
     * descriptor も {@code GENERAL} を宣言していなければならない。食い違うと
     * バリデーション有効時に {@code VUID-vkCmdDraw-imageLayout-00344} が出る。
     * この検査はバリデーションを有効にして走らせたときに意味がある
     * (無効時はメッセージが無いので空虚に通る — docs/ai/testing.md level 3)。
     */
    @Test
    void drawsWithoutALayoutMismatchDiagnostic() {
        VkContext.clearValidationMessages();
        visualise(pattern());
        var all = VkContext.validationMessages();
        System.out.println("[vk] VkDepthVisualiseTest: validation=" + VkContext.get().validationEnabled
            + " messages=" + all.size());
        assertEquals(java.util.List.of(),
            all.stream().filter(m -> m.contains("VUID-vkCmdDraw-imageLayout-00344")).toList(),
            "D2: the depth source is declared in a layout other than the one it is in at draw time");
        assertEquals(java.util.List.of(), all, "the depth-visualise pass must be validation-clean");
    }

    /**
     * <b>行が入れ替わっていないこと。</b>
     *
     * <p>値の集合だけを見ると、行を並べ替えても通ってしまう。
     * ここでは<b>1 行だけ他と違う深度</b>を置き、その行に印が出ることを見る。
     */
    @Test
    void rowsAreNotShuffled() {
        float[] d = pattern();
        int marked = SKY_START / 3;
        // 印の行だけ「最も手前」にする。周囲より必ず明るくなる
        for (int x = 0; x < W; x++) d[marked * W + x] = 1.0f;

        int[] px = visualise(d);
        int markedValue = r(px[marked * W + W / 2]);
        assertEquals(255, markedValue, "the marked row should be the brightest possible");
        assertTrue(markedValue > r(px[(marked - 1) * W + W / 2]),
            "the mark landed on a different row than it was written to");
        assertTrue(markedValue > r(px[(marked + 1) * W + W / 2]),
            "the mark landed on a different row than it was written to");
    }
}
