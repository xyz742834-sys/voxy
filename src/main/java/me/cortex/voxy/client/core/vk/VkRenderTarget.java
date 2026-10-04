package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * オフスクリーンのカラー + 深度ターゲット。
 *
 * <h2>用途</h2>
 * <ol>
 *   <li><b>検証の土台</b>: 描いた結果を読み戻して比較する。
 *       draw 統合 (Stage 2b) の出力を統合前 (Stage 1) と突き合わせるのに使う
 *       (docs/phase4-proposal.md §4)。</li>
 *   <li><b>Phase 5 の interop へ直結</b>: IOSurface backed の画像に描いて
 *       GL に渡す経路はオフスクリーン描画そのもの。
 *       カラー画像を {@link VkTexture#wrapExternal} 由来に差し替えれば流用できる [推測]。</li>
 * </ol>
 *
 * <h2>dynamic_rendering を使う</h2>
 * {@code VkRenderPass} / {@code VkFramebuffer} を作らない。
 * 描画開始時に {@link #beginRendering} でアタッチメントを直接渡す。
 * 参照実装 ({@code ~/dev/mdi-bench} の {@code Offscreen.java}) は
 * 旧来の RenderPass 方式なので、画像生成と読み戻しの部分だけを踏襲している。
 */
public class VkRenderTarget {
    public static final int FORMAT_COLOR = VK_FORMAT_R8G8B8A8_UNORM;
    public static final int FORMAT_DEPTH = VK_FORMAT_D32_SFLOAT;

    public final int width, height;
    public final VkTexture color;
    public final VkTexture depth;
    /** カラーの実フォーマット。interop 先は BGRA なので {@link #FORMAT_COLOR} とは限らない。 */
    public final int colorFormat;
    /** カラーを自分で作ったか。外から渡された場合は {@link #free()} で解放しない。 */
    private final boolean ownsColor;
    /** 読み戻し先。ユニファイドメモリなので常時マップされている。 */
    private VkBuffer readback;

    public VkRenderTarget(int width, int height) {
        this(width, height, null, FORMAT_COLOR);
    }

    /**
     * カラーを外から与える版。<b>Phase 5 の interop で使う</b> —
     * {@code VkInteropImage.texture()} を渡すと、地形描画がそのまま
     * IOSurface に書き込まれる。
     *
     * <p>深度は常に自前で持つ。IOSurface に depth aspect は無いので
     * 深度アタッチメントを interop にはできない [確認済 — Phase 0 §8.2]。
     * GL に渡す深度は別途 R32F へ解決する ({@code VkDepthResolve})。
     *
     * @param externalColor null なら自前で確保する
     * @param colorFormat   {@code externalColor} のフォーマット。
     *                      <b>パイプライン生成時と一致していなければならない</b>
     */
    public VkRenderTarget(int width, int height, VkTexture externalColor, int colorFormat) {
        this.width = width;
        this.height = height;
        this.colorFormat = colorFormat;
        this.ownsColor = externalColor == null;
        if (externalColor == null) {
            this.color = new VkTexture(colorFormat, 1, width, height,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
              | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
              | VK_IMAGE_USAGE_SAMPLED_BIT).name("VkRenderTarget.color");
        } else {
            if (externalColor.width != width || externalColor.height != height) {
                throw new IllegalArgumentException("external colour is "
                    + externalColor.width + "x" + externalColor.height
                    + " but the target is " + width + "x" + height);
            }
            if (externalColor.format != colorFormat) {
                throw new IllegalArgumentException("external colour format 0x"
                    + Integer.toHexString(externalColor.format)
                    + " does not match the declared 0x" + Integer.toHexString(colorFormat));
            }
            this.color = externalColor;
        }
        this.depth = new VkTexture(FORMAT_DEPTH, 1, width, height,
            VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
          | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
          | VK_IMAGE_USAGE_SAMPLED_BIT).name("VkRenderTarget.depth");
    }

    // ---------------- rendering ----------------

    /**
     * 描画を開始する。必要なレイアウト遷移もここで行う。
     *
     * @param clearColor RGBA (0..1)。null ならクリアしない (LOAD)
     */
    public void beginRendering(VkCommandBuffer cmd, float[] clearColor, Float clearDepth) {
        // アタッチメントとして書ける状態へ。
        //
        // ⚠ src が ALL_COMMANDS なのは WAR のため。同じコマンドバッファ内で
        // 「描画 -> recordReadback (TRANSFER_READ) -> もう一度描画」と積むと、
        // 2 回目の描画は前の転送読みを追い越してはならない。
        // TOP_OF_PIPE を src に置くと「何も待たない」意味になり、この依存が張られない。
        // WAR は実行依存だけで足りるので srcAccess は 0 のままでよい
        // (可視化は不要。キャッシュフラッシュを増やさない) [確認済 — 仕様ベース]。
        // LOAD additionally reads prior attachment contents (RAW), so it must make
        // previous writes visible. Depth testing and color blending also read attachments.
        this.color.barrier(cmd, 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, clearColor == null ? VK_ACCESS_MEMORY_WRITE_BIT : 0,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
            VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        this.depth.barrier(cmd, 0, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, clearDepth == null ? VK_ACCESS_MEMORY_WRITE_BIT : 0,
            VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
            VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

        try (MemoryStack stack = stackPush()) {
            var colorAtt = VkRenderingAttachmentInfo.calloc(1, stack)
                .sType$Default()
                .imageView(this.color.view(0))
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(clearColor == null ? VK_ATTACHMENT_LOAD_OP_LOAD : VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            if (clearColor != null) {
                colorAtt.get(0).clearValue().color()
                    .float32(0, clearColor[0]).float32(1, clearColor[1])
                    .float32(2, clearColor[2]).float32(3, clearColor[3]);
            }

            var depthAtt = VkRenderingAttachmentInfo.calloc(stack)
                .sType$Default()
                .imageView(this.depth.view(0))
                .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                .loadOp(clearDepth == null ? VK_ATTACHMENT_LOAD_OP_LOAD : VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            if (clearDepth != null) {
                depthAtt.clearValue().depthStencil().depth(clearDepth).stencil(0);
            }

            var ri = VkRenderingInfo.calloc(stack).sType$Default()
                .layerCount(1)
                .pColorAttachments(colorAtt)
                .pDepthAttachment(depthAtt);
            ri.renderArea().offset().set(0, 0);
            ri.renderArea().extent().set(this.width, this.height);

            vkCmdBeginRendering(cmd, ri);

            // dynamic_rendering ではビューポート/シザーは動的に指定する
            var vp = VkViewport.calloc(1, stack)
                .x(0).y(0).width(this.width).height(this.height).minDepth(0).maxDepth(1);
            var sc = VkRect2D.calloc(1, stack);
            sc.offset().set(0, 0);
            sc.extent().set(this.width, this.height);
            vkCmdSetViewport(cmd, 0, vp);
            vkCmdSetScissor(cmd, 0, sc);
        }
    }

    /**
     * <b>深度だけを付けて描画を開始する。</b> カラーには一切触らない。
     * 遮蔽カリングが「不透明パスが書いた深度」に対してテストするのに使う。
     *
     * <p>深度は {@code LOAD} で、直前のパスが書いた内容をそのまま読む。
     * src が {@code ALL_COMMANDS} なのは、直前の深度書き込みとの
     * 実行依存を張るため (beginRendering と同じ理由)。
     */
    public void beginRenderingDepthOnly(VkCommandBuffer cmd) {
        this.depth.barrier(cmd, 0, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
            VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT);

        try (MemoryStack stack = stackPush()) {
            var depthAtt = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                .imageView(this.depth.view(0))
                .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);

            var ri = VkRenderingInfo.calloc(stack).sType$Default()
                .layerCount(1)
                .pDepthAttachment(depthAtt);
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
        }
    }

    public void endRendering(VkCommandBuffer cmd) {
        vkCmdEndRendering(cmd);
    }

    // ---------------- readback ----------------

    /** 読み戻しバッファ (RGBA8)。初回呼び出しで確保する。 */
    public VkBuffer readbackBuffer() {
        if (this.readback == null) {
            this.readback = new VkBuffer((long) this.width * this.height * 4)
                .name("VkRenderTarget.readback");
        }
        return this.readback;
    }

    /**
     * カラーを読み戻しバッファへコピーする記録を積む。
     * 結果は {@link VkFrameTracker#waitForFrame()} 後に
     * {@code readbackBuffer().addr()} から直接読める (ユニファイドメモリ)。
     */
    public void recordReadback(VkCommandBuffer cmd) {
        var dst = this.readbackBuffer();

        this.color.barrier(cmd, 0, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);

        try (MemoryStack stack = stackPush()) {
            var region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(this.width, this.height, 1);
            vkCmdCopyImageToBuffer(cmd, this.color.image,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.handle, region);

            // ホストが読むので HOST_READ まで可視化する
            var b = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(dst.handle).offset(0).size(dst.size());
            vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                0, null, b, null);
        }
    }

    /** 深度の読み戻し先。色とは別に持つ (両方同時に読みたい場面があるため)。 */
    private VkBuffer depthReadback;

    public VkBuffer depthReadbackBuffer() {
        if (this.depthReadback == null) {
            this.depthReadback = new VkBuffer((long) this.width * this.height * 4)
                .name("VkRenderTarget.depthReadback");
        }
        return this.depthReadback;
    }

    /**
     * 深度を読み戻す記録を積む。
     *
     * <p><b>深度の約束事 ({@link VkDepth}) が実際に効いているかは、
     * 深度そのものを見ないと分からない。</b> 色だけ見ても
     * 逆Z / 非逆Zの取り違えは<b>絵が同じになる</b>ため気づけない
     * — 逆Zは「同じ絵を、深度の分解能だけ変えて出す」ものだからである。
     * これは 5b の上下反転と同じ型の落とし穴で、
     * <b>端点 (色) の一致は途中 (深度) の正しさを保証しない</b>。
     */
    public void recordDepthReadback(VkCommandBuffer cmd) {
        var dst = this.depthReadbackBuffer();

        this.depth.barrier(cmd, 0, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);

        try (MemoryStack stack = stackPush()) {
            var region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource()
                .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(this.width, this.height, 1);
            vkCmdCopyImageToBuffer(cmd, this.depth.image,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.handle, region);

            var b = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(dst.handle).offset(0).size(dst.size());
            vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                0, null, b, null);
        }
    }

    /** 読み戻し済みの深度。{@code waitForFrame()} 後に呼ぶこと。 */
    public float depthAt(int x, int y) {
        return org.lwjgl.system.MemoryUtil.memGetFloat(
            this.depthReadbackBuffer().addr() + ((long) y * this.width + x) * 4L);
    }

    /** 読み戻し済み深度のうち<b>最も手前</b>の値 ({@link VkDepth} の約束に従う)。 */
    public float closestDepth() {
        long base = this.depthReadbackBuffer().addr();
        long n = (long) this.width * this.height;
        float best = VkDepth.FAR;
        for (long i = 0; i < n; i++) {
            float d = org.lwjgl.system.MemoryUtil.memGetFloat(base + i * 4);
            // 逆Zでは「手前ほど大きい」
            if (d > best) best = d;
        }
        return best;
    }

    /** クリア値と異なる深度を持つテクセル数 = 何かが深度を書いた画素数。 */
    public long depthCoverage() {
        long base = this.depthReadbackBuffer().addr();
        long n = (long) this.width * this.height;
        long count = 0;
        for (long i = 0; i < n; i++) {
            if (org.lwjgl.system.MemoryUtil.memGetFloat(base + i * 4) != VkDepth.CLEAR) count++;
        }
        return count;
    }

    /** 読み戻し済みピクセルを RGBA で取り出す。{@code waitForFrame()} 後に呼ぶこと。 */
    public int[] pixelAt(int x, int y) {
        long addr = this.readbackBuffer().addr() + ((long) y * this.width + x) * 4L;
        return new int[] {
            org.lwjgl.system.MemoryUtil.memGetByte(addr) & 0xFF,
            org.lwjgl.system.MemoryUtil.memGetByte(addr + 1) & 0xFF,
            org.lwjgl.system.MemoryUtil.memGetByte(addr + 2) & 0xFF,
            org.lwjgl.system.MemoryUtil.memGetByte(addr + 3) & 0xFF,
        };
    }

    /**
     * 2 つのターゲットのカラーを突き合わせ、異なるピクセル数を返す。
     * <b>Stage 1 と Stage 2b の比較に使う</b> (docs/phase4-proposal.md §4.1)。
     */
    public static long compareColor(VkRenderTarget a, VkRenderTarget b) {
        if (a.width != b.width || a.height != b.height) {
            throw new IllegalArgumentException("size mismatch: "
                + a.width + "x" + a.height + " vs " + b.width + "x" + b.height);
        }
        long pa = a.readbackBuffer().addr();
        long pb = b.readbackBuffer().addr();
        long n = (long) a.width * a.height;
        long diff = 0;
        for (long i = 0; i < n; i++) {
            if (org.lwjgl.system.MemoryUtil.memGetInt(pa + i * 4)
             != org.lwjgl.system.MemoryUtil.memGetInt(pb + i * 4)) diff++;
        }
        return diff;
    }

    /**
     * 読み戻し済みのカラーを PNG に書く。<b>人間が見て判断するための手段。</b>
     * 差分ゼロ比較 (§{@link #compareColor}) は機械的な検証だが、
     * 「そもそも意味のある絵になっているか」は目で見ないと分からない。
     *
     * <p>{@code javax.imageio} を使うので依存追加は不要。
     * {@code waitForFrame()} の後に呼ぶこと。
     */
    public void writePng(java.nio.file.Path path) throws java.io.IOException {
        this.writePng(path, false);
    }

    /**
     * @param forceOpaque アルファを 255 に潰して書く。
     *
     * <p><b>地形シェーダの出力アルファは不透明度ではない。</b>
     * {@code quads.frag} は {@code colour.a} に
     * {@code face | lodLevel<<3 | hasAO<<6} を {@code /255} して詰めている
     * [確認済 — `quad_util.glsl` の {@code addin} と `computeColour` の最終行]。
     * 値は 0..5 程度なので、そのまま PNG にすると<b>ほぼ完全に透明な画像</b>になり
     * 目視確認に使えない。人が見るための PNG ではこれを潰す。
     */
    public void writePng(java.nio.file.Path path, boolean forceOpaque) throws java.io.IOException {
        var img = new java.awt.image.BufferedImage(
            this.width, this.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        long base = this.readbackBuffer().addr();
        for (int y = 0; y < this.height; y++) {
            for (int x = 0; x < this.width; x++) {
                // 読み戻しの 0 行目は**絵の下端** (GL 規約の Y を通しているため)。
                // PNG は 0 行目が上端なので入れ替える。**表示のための反転**であって
                // 経路上の変換ではない [VkSceneUniform.perspective]
                long o = base + ((long) (this.height - 1 - y) * this.width + x) * 4L;
                int r = org.lwjgl.system.MemoryUtil.memGetByte(o) & 0xFF;
                int g = org.lwjgl.system.MemoryUtil.memGetByte(o + 1) & 0xFF;
                int b = org.lwjgl.system.MemoryUtil.memGetByte(o + 2) & 0xFF;
                int a = forceOpaque ? 255 : (org.lwjgl.system.MemoryUtil.memGetByte(o + 3) & 0xFF);
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        java.nio.file.Files.createDirectories(path.toAbsolutePath().getParent());
        javax.imageio.ImageIO.write(img, "PNG", path.toFile());
    }

    /**
     * 読み戻し済みカラーの相異なる RGBA 値の数。
     * <b>索引がずれたら色の集合が変わる</b>という前提の検査に使う。
     */
    public java.util.Set<Integer> distinctColours() {
        var out = new java.util.HashSet<Integer>();
        long base = this.readbackBuffer().addr();
        long n = (long) this.width * this.height;
        for (long i = 0; i < n; i++) {
            out.add(org.lwjgl.system.MemoryUtil.memGetInt(base + i * 4));
        }
        return out;
    }

    /**
     * 差分を可視化した PNG を書く。異なるピクセルを赤、同じところを暗くする。
     * Stage 2b で差分が出たときに<b>どこがずれたか</b>を見るため。
     */
    public static void writeDiffPng(VkRenderTarget a, VkRenderTarget b, java.nio.file.Path path)
            throws java.io.IOException {
        var img = new java.awt.image.BufferedImage(
            a.width, a.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        long pa = a.readbackBuffer().addr();
        long pb = b.readbackBuffer().addr();
        for (int y = 0; y < a.height; y++) {
            for (int x = 0; x < a.width; x++) {
                long i = ((long) (a.height - 1 - y) * a.width + x) * 4L;   // PNG は上端が 0 行目
                int va = org.lwjgl.system.MemoryUtil.memGetInt(pa + i);
                int vb = org.lwjgl.system.MemoryUtil.memGetInt(pb + i);
                if (va != vb) {
                    img.setRGB(x, y, 0xFFFF0000);
                } else {
                    int g = (va >>> 8) & 0xFF;
                    img.setRGB(x, y, 0xFF000000 | (g / 4 << 16) | (g / 4 << 8) | (g / 4));
                }
            }
        }
        java.nio.file.Files.createDirectories(path.toAbsolutePath().getParent());
        javax.imageio.ImageIO.write(img, "PNG", path.toFile());
    }

    public void free() {
        // 外から渡されたカラーは所有者が解放する (interop なら VkInteropImage)
        if (this.ownsColor) this.color.free();
        this.depth.free();
        if (this.readback != null) { this.readback.free(); this.readback = null; }
        if (this.depthReadback != null) { this.depthReadback.free(); this.depthReadback = null; }
    }
}
