package me.cortex.voxy.client.core.vk.interop;

import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.VkFrameTracker;
import me.cortex.voxy.client.core.vk.VkTexture;
import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImportMetalIOSurfaceInfoEXT;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.lang.foreign.MemorySegment;

import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTMetalObjects.VK_STRUCTURE_TYPE_IMPORT_METAL_IO_SURFACE_INFO_EXT;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 1 枚の IOSurface を <b>{@code VkImage} と GL テクスチャの両方</b>として見せるオブジェクト。
 *
 * <pre>
 * IOSurface  ←─ VkImage              (VkImportMetalIOSurfaceInfoEXT)
 *            ←─ GL_TEXTURE_RECTANGLE (CGLTexImageIOSurface2D)
 * </pre>
 *
 * <p>Phase 0 の {@code Interop.java} を Voxy に取り込んだもの。
 * Phase 0 版との違いは <b>所有権を 1 箇所に集めたこと</b>で、
 * 解放順が 3 者に跨る問題 [docs/phase5-proposal.md §1.3] をここで閉じ込める。
 *
 * <h2>⚠ レイアウトを {@code UNDEFINED} から遷移させてはならない (2 回目以降)</h2>
 * Vulkan の {@code oldLayout = VK_IMAGE_LAYOUT_UNDEFINED} は
 * <b>「内容を捨ててよい」という宣言</b>である。interop 画像に対してこれをやると
 * <b>GL が書いた内容が消える</b>。Phase 0 の {@code Step8} は Vulkan が書き手だったので
 * 毎フレーム {@code UNDEFINED} から始めていたが、<b>GL → Vulkan 方向ではそれが使えない</b>。
 *
 * <p>そのため本クラスは {@link #toGeneral} を用意し、
 * <b>初回だけ {@code UNDEFINED} → {@code GENERAL}、以降は {@code GENERAL} 据え置きで
 * メモリ同期のみ</b>を発行する。{@link VkTexture} のレイアウト追跡がそのまま使える
 * (レイアウトが同じでもアクセスマスクがあればバリアを出す作りになっている)。
 *
 * <h2>GL 側の見え方</h2>
 * {@code CGLTexImageIOSurface2D} は {@code GL_TEXTURE_RECTANGLE} 専用なので、
 * GL のシェーダからは {@code sampler2DRect} + 非正規化座標で読むことになる。
 */
public final class VkInteropImage extends TrackedObject {
    /** interop で使えるフォーマットの組。Phase 0 §8.2 で確認できた 2 つだけ。 */
    public enum Kind {
        /** 色。{@code 'BGRA'} / {@code VK_FORMAT_B8G8R8A8_UNORM}。 */
        COLOR_BGRA8(VK_FORMAT_B8G8R8A8_UNORM, IOSurf.FOURCC_BGRA, 4,
            Cgl.GL_RGBA, Cgl.GL_BGRA, Cgl.GL_UNSIGNED_INT_8_8_8_8_REV),
        /** 深度。{@code 'r00f'} / {@code VK_FORMAT_R32_SFLOAT}。深度 aspect ではなく色 aspect である。 */
        DEPTH_R32F(VK_FORMAT_R32_SFLOAT, IOSurf.FOURCC_R32F, 4,
            Cgl.GL_R32F, Cgl.GL_RED, Cgl.GL_FLOAT);

        public final int vkFormat;
        public final int fourcc;
        public final int bytesPerElement;
        public final int glInternalFormat;
        public final int glFormat;
        public final int glType;

        Kind(int vkFormat, int fourcc, int bytesPerElement,
             int glInternalFormat, int glFormat, int glType) {
            this.vkFormat = vkFormat;
            this.fourcc = fourcc;
            this.bytesPerElement = bytesPerElement;
            this.glInternalFormat = glInternalFormat;
            this.glFormat = glFormat;
            this.glType = glType;
        }
    }

    public final Kind kind;
    public final int width;
    public final int height;

    private final MemorySegment iosurface;
    private final long image;
    private final VkTexture texture;

    /**
     * {@code vkGetImageMemoryRequirements} が返したサイズ。
     * <b>IOSurface の {@code allocSize} と一致することがゼロコピーの根拠</b>
     * [確認済 — Phase 0 §8.3 の 7e]。
     */
    public final long memoryRequirementSize;
    /** IOSurface 側の確保サイズ。 */
    public final long allocSize;

    private int glTexture = 0;

    /**
     * @param usage {@code VkImageCreateInfo.usage}。読む側なら
     *              {@code SAMPLED | TRANSFER_SRC}、書く側なら {@code COLOR_ATTACHMENT} など。
     */
    public VkInteropImage(int width, int height, Kind kind, int usage) {
        this.width = width;
        this.height = height;
        this.kind = kind;
        this.iosurface = IOSurf.create(width, height, kind.fourcc, kind.bytesPerElement);
        this.allocSize = IOSurf.allocSize(this.iosurface);

        VkContext ctx = VkContext.get();
        try (MemoryStack stack = stackPush()) {
            VkImportMetalIOSurfaceInfoEXT importInfo = VkImportMetalIOSurfaceInfoEXT.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMPORT_METAL_IO_SURFACE_INFO_EXT)
                .ioSurface(this.iosurface.address());

            VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack)
                .sType$Default()
                .pNext(importInfo.address())
                .imageType(VK_IMAGE_TYPE_2D)
                .format(kind.vkFormat)
                .mipLevels(1).arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ici.extent().set(width, height, 1);

            long[] p = new long[1];
            VkContext.check(vkCreateImage(ctx.device, ici, null, p), "vkCreateImage(IOSurface)");
            this.image = p[0];

            // IOSurface backed の場合メモリは既に紐づいており vkBindImageMemory は不要
            // (MoltenVK が内部で処理する)。要求サイズだけ記録しておく。
            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(ctx.device, this.image, req);
            this.memoryRequirementSize = req.size();
        }

        // ビューを作る前に登録する。VkImageView の生成自体が
        // VUID-VkImageViewCreateInfo-image-01020 を出すため
        // [docs/phase5b-composite.md §3]。
        VkContext.registerInteropImage(this.image);
        this.texture = VkTexture.wrapExternal(this.image, kind.vkFormat, 1, width, height);
    }

    /** Vulkan 側の見え方。レイアウト追跡もこちらが持つ。 */
    public VkTexture texture() { return this.texture; }

    public long imageHandle() { return this.image; }

    public MemorySegment ioSurface() { return this.iosurface; }

    /**
     * GL テクスチャ ID。初回呼び出し時に生成する。
     * <b>GL コンテキストが current なスレッドから呼ぶこと。</b>
     */
    public int glTexture() {
        if (this.glTexture == 0) {
            MemorySegment ctx = Cgl.currentContext();
            if (ctx.address() == 0) {
                throw new IllegalStateException("no current CGL context; "
                    + "VkInteropImage.glTexture() must be called on the GL thread");
            }
            int tex = glGenTextures();
            glBindTexture(Cgl.GL_TEXTURE_RECTANGLE, tex);
            int err = Cgl.texImageIOSurface2D(ctx, this.kind.glInternalFormat,
                this.width, this.height, this.kind.glFormat, this.kind.glType,
                this.iosurface, 0);
            glBindTexture(Cgl.GL_TEXTURE_RECTANGLE, 0);
            if (err != Cgl.CGL_NO_ERROR) {
                glDeleteTextures(tex);
                throw new IllegalStateException("CGLTexImageIOSurface2D -> CGLError " + err);
            }
            this.glTexture = tex;
        }
        return this.glTexture;
    }

    /**
     * <b>{@code UNDEFINED} → {@code GENERAL} を、GL が書く前に一度だけ済ませる。</b>
     *
     * <h2>なぜ要るのか — GL が書き手のときだけの問題</h2>
     * {@code oldLayout = VK_IMAGE_LAYOUT_UNDEFINED} は
     * <b>「内容を捨ててよい」という宣言</b>である。レイアウト追跡は生成直後
     * {@code UNDEFINED} から始まるので、<b>GL が先に書いた画像に対して
     * 最初の {@link #toGeneral} を発行すると、その書き込みが捨てられる</b>
     * [docs/phase5a-gl-to-vk-sync.md §6.1]。
     *
     * <p>Vulkan が書き手の画像 (5b の色・深度) ではこれが問題にならない —
     * 初回に捨てられて困る内容がまだ無いからである。
     * <b>GL → Vulkan 方向を通す 5c-1c で初めて効いてくる。</b>
     *
     * <p>症状は「<b>最初の 1 フレームだけ深度が化ける</b>」なので、
     * 目視では見逃しやすく、静止画の比較でも 2 フレーム目以降を見ていれば通ってしまう。
     * だから<b>生成時に必ず済ませる</b>ことにして、呼び忘れの余地を消す。
     *
     * <p>Phase 5a のベンチは同じことを外側で行っていた ({@code primeLayout})。
     * <b>制約が分かっているならクラス側に持たせる</b> —
     * doc に書いただけでは後の変更が条件を通り越す [規約 6]。
     *
     * <p><b>フレームの記録中に呼んではならない。</b> 生成直後に呼ぶこと。
     */
    public void primeLayout() {
        var tracker = VkFrameTracker.get();
        if (tracker.isRecording()) {
            throw new IllegalStateException("primeLayout() must be called outside a frame; "
                + "it submits and waits on its own");
        }
        var cmd = tracker.beginFrame();
        // 誰も待たせる相手がいない (この画像への最初のアクセス)。
        // dst は「この後 GL が書き、それを Vulkan が読む」ので広く取る
        this.toGeneral(cmd,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_SHADER_READ_BIT);
        tracker.endFrame();
        tracker.waitForFrame();
    }

    /**
     * {@code VK_IMAGE_LAYOUT_GENERAL} に置き、指定のアクセス同期を張る。
     *
     * <p>初回は {@code UNDEFINED} からの遷移になるが、それ以降は
     * <b>{@code GENERAL} 据え置きのメモリバリア</b>になる。
     * <b>interop 画像に対して二度と {@code UNDEFINED} を使ってはならない</b> —
     * GL が書いた内容が捨てられる。
     */
    public void toGeneral(VkCommandBuffer cmd,
                          int srcStage, int srcAccess, int dstStage, int dstAccess) {
        this.texture.barrier(cmd, 0, VK_IMAGE_LAYOUT_GENERAL,
            srcStage, srcAccess, dstStage, dstAccess);
    }

    /**
     * 解放順は IOSurface が最後 [docs/phase5-proposal.md §1.3]。
     * <b>GL テクスチャの削除には GL コンテキストが要る</b>ので、
     * GL テクスチャを作ったのと同じスレッドから呼ぶこと。
     */
    @Override
    public void free() {
        this.free0();
        this.texture.free();                                  // 1. VkImageView
        vkDestroyImage(VkContext.get().device, this.image, null); // 1. VkImage
        // ハンドルは再利用されうるので、破棄したら必ず外す。
        // 残したままにすると別の資源に対する本物の指摘が黙る
        VkContext.unregisterInteropImage(this.image);
        if (this.glTexture != 0) {                            // 2. GL テクスチャ
            glDeleteTextures(this.glTexture);
            this.glTexture = 0;
        }
        IOSurf.release(this.iosurface);                       // 3. IOSurface
    }
}
