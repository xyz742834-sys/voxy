package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.Arrays;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GL 側 {@code GlTexture} の Vulkan 版。{@code VkImage} + メモリ + ビュー。
 *
 * <h2>レイアウト追跡 — レベル別</h2>
 * Vulkan の画像には「現在のレイアウト」があり、遷移を明示しなければならない。
 * <b>本クラスはレベル (mip) ごとに現在のレイアウトを保持する。</b>
 *
 * <p>レベル別が必須なのは HiZ の mip チェーン生成が
 * <b>同一画像の level i-1 を読みながら level i に書く</b>ためである [確認済 —
 * `HiZBuffer.buildMipChain`]。GL は {@code GL_TEXTURE_BASE_LEVEL}/{@code MAX_LEVEL} で
 * 読めるレベルを絞ってこれを実現しているが、Vulkan に同等の仕組みは無く、
 * <b>レベルごとに別の {@code VkImageView}</b> が要る。
 *
 * <h2>⚠ 記録順の前提</h2>
 * <b>ここで保持しているのは「コマンドバッファに記録された時点」のレイアウトであって、
 * GPU が実行している時点の状態ではない。</b>
 * したがって <b>transition を記録順と異なる順序で呼ぶと追跡が破綻する</b>。
 *
 * <p>現状は in-flight = 1 かつコマンドバッファ 1 本を線形に記録するため、
 * 記録順 == 実行順が成り立ち安全である [確認済 — `VkFrameTracker`]。
 * <b>多重化するか複数のコマンドバッファを併用する場合、この前提は崩れる。</b>
 *
 * <h2>外部メモリ (IOSurface)</h2>
 * interop 用の画像は IOSurface backed になる (Phase 5)。
 * {@link #wrapExternal} で既存の {@code VkImage} を受け取れる。
 * <b>所有権を持たないので {@code vkFreeMemory} は呼ばない</b> —
 * IOSurface backed の場合そもそもメモリをバインドしていない。
 * 参照実装は {@code ~/dev/mdi-bench} の {@code Interop.java}。
 * 外部画像も同じレイアウト追跡に載るので、GL へ渡す前の遷移もこの仕組みで書ける。
 */
public class VkTexture extends TrackedObject {
    public final long image;
    public final long memory;          // 外部由来なら VK_NULL_HANDLE
    public final int format;
    public final int width;
    public final int height;
    public final int levels;
    private final boolean ownsImage;

    /** レベルごとの現在レイアウト (記録時点)。 */
    private final int[] layouts;
    /** 全レベルを覆うビュー (サンプリング用)。 */
    private final long viewAll;
    /** レベルごとのビュー (アタッチメント用 / レベルを絞ったサンプリング用)。 */
    private final long[] viewPerLevel;

    private static int COUNT;

    public VkTexture(int format, int levels, int width, int height, int usage) {
        var ctx = VkContext.get();
        this.format = format;
        this.width = width;
        this.height = height;
        this.levels = levels;
        this.ownsImage = true;

        try (MemoryStack stack = stackPush()) {
            var ici = VkImageCreateInfo.calloc(stack).sType$Default()
                .imageType(VK_IMAGE_TYPE_2D)
                .format(format)
                .mipLevels(levels)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ici.extent().width(width).height(height).depth(1);

            long[] p = new long[1];
            VkContext.check(vkCreateImage(ctx.device, ici, null, p), "vkCreateImage");
            this.image = p[0];

            var req = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(ctx.device, this.image, req);
            var mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(req.size())
                .memoryTypeIndex(ctx.findMemoryType(req.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            VkContext.check(vkAllocateMemory(ctx.device, mai, null, p), "vkAllocateMemory");
            this.memory = p[0];
            VkContext.check(vkBindImageMemory(ctx.device, this.image, this.memory, 0),
                "vkBindImageMemory");
        }

        this.layouts = new int[levels];
        Arrays.fill(this.layouts, VK_IMAGE_LAYOUT_UNDEFINED);
        this.viewAll = createView(this.image, format, 0, levels);
        this.viewPerLevel = new long[levels];
        for (int i = 0; i < levels; i++) this.viewPerLevel[i] = createView(this.image, format, i, 1);
        COUNT++;
    }

    private VkTexture(long image, int format, int levels, int width, int height) {
        this.image = image;
        this.memory = VK_NULL_HANDLE;
        this.format = format;
        this.levels = levels;
        this.width = width;
        this.height = height;
        this.ownsImage = false;
        this.layouts = new int[levels];
        Arrays.fill(this.layouts, VK_IMAGE_LAYOUT_UNDEFINED);
        this.viewAll = createView(image, format, 0, levels);
        this.viewPerLevel = new long[levels];
        for (int i = 0; i < levels; i++) this.viewPerLevel[i] = createView(image, format, i, 1);
        COUNT++;
    }

    /**
     * 外部で作られた {@code VkImage} (IOSurface backed など) を包む。
     * <b>メモリの所有権は持たない</b>ので {@link #free()} で {@code vkFreeMemory} は呼ばない。
     */
    public static VkTexture wrapExternal(long image, int format, int levels, int width, int height) {
        return new VkTexture(image, format, levels, width, height);
    }

    private static long createView(long image, int format, int baseLevel, int levelCount) {
        try (MemoryStack stack = stackPush()) {
            var ci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format);
            ci.subresourceRange()
                .aspectMask(aspectOf(format))
                .baseMipLevel(baseLevel).levelCount(levelCount)
                .baseArrayLayer(0).layerCount(1);
            long[] p = new long[1];
            VkContext.check(vkCreateImageView(VkContext.get().device, ci, null, p),
                "vkCreateImageView");
            return p[0];
        }
    }

    public static int aspectOf(int format) {
        return switch (format) {
            case VK_FORMAT_D16_UNORM, VK_FORMAT_D32_SFLOAT, VK_FORMAT_X8_D24_UNORM_PACK32
                -> VK_IMAGE_ASPECT_DEPTH_BIT;
            case VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT_S8_UINT
                -> VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
            default -> VK_IMAGE_ASPECT_COLOR_BIT;
        };
    }

    // ---------------- views ----------------

    /** 全レベルを覆うビュー。通常のサンプリングに使う。 */
    public long view() { return this.viewAll; }

    /** 指定レベルだけのビュー。アタッチメント、またはレベルを絞った読み出しに使う。 */
    public long view(int level) {
        this.checkLevel(level);
        return this.viewPerLevel[level];
    }

    // ---------------- layout ----------------

    /** 記録時点での指定レベルのレイアウト。 */
    public int layout(int level) {
        this.checkLevel(level);
        return this.layouts[level];
    }

    /**
     * レイアウト遷移とメモリ同期をまとめて記録する。
     *
     * <p><b>レイアウト遷移とメモリ同期を同じ API にしている理由</b>:
     * Vulkan では両者が {@code VkImageMemoryBarrier} 1 つに同居しており、分離できない。
     * また「レイアウトは変わらないが同期だけ必要」なケースが実在する
     * (例: {@code GENERAL} のまま書き込み → 読み出し)。
     * そのため<b>レイアウトが同じでもアクセスマスクが指定されていればバリアを発行する</b>。
     * 何もしないのは「レイアウト据え置き かつ アクセスマスク 0」の完全な no-op のときだけ。
     *
     * @param level 対象 mip レベル
     */
    public void barrier(VkCommandBuffer cmd, int level, int newLayout,
                        int srcStage, int srcAccess, int dstStage, int dstAccess) {
        this.checkLevel(level);
        int old = this.layouts[level];
        if (old == newLayout && srcAccess == 0 && dstAccess == 0) {
            return; // 完全な no-op
        }
        try (MemoryStack stack = stackPush()) {
            var b = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                .oldLayout(old).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(this.image);
            b.subresourceRange()
                .aspectMask(aspectOf(this.format))
                .baseMipLevel(level).levelCount(1)
                .baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, b);
        }
        this.layouts[level] = newLayout;
    }

    /** 全レベルをまとめて遷移する。レベルごとに違うレイアウトを持つ場合は 1 枚ずつ発行される。 */
    public void barrierAll(VkCommandBuffer cmd, int newLayout,
                           int srcStage, int srcAccess, int dstStage, int dstAccess) {
        for (int i = 0; i < this.levels; i++) {
            this.barrier(cmd, i, newLayout, srcStage, srcAccess, dstStage, dstAccess);
        }
    }

    private void checkLevel(int level) {
        if (level < 0 || level >= this.levels) {
            throw new IndexOutOfBoundsException("mip level " + level + " of " + this.levels);
        }
    }

    // ---------------- lifecycle ----------------

    @Override
    public void free() {
        this.free0();
        var dev = VkContext.get().device;
        for (long v : this.viewPerLevel) vkDestroyImageView(dev, v, null);
        vkDestroyImageView(dev, this.viewAll, null);
        // 外部由来の画像は破棄する側の責任。メモリも所有していない
        if (this.ownsImage) {
            vkDestroyImage(dev, this.image, null);
            if (this.memory != VK_NULL_HANDLE) vkFreeMemory(dev, this.memory, null);
        }
        COUNT--;
    }

    public boolean isExternal() { return !this.ownsImage; }

    public VkTexture name(String name) {
        //TODO: VK_EXT_debug_utils による命名
        return this;
    }

    public static int getCount() { return COUNT; }
}
