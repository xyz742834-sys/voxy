package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.interop.IOSurf;
import me.cortex.voxy.client.core.vk.interop.VkInteropImage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * <b>D6 (interop のメモリバインド半分) の検査。</b>
 *
 * <p>{@link VkInteropImage} は IOSurface backed の {@code VkImage} に、バリデーションレイヤを
 * 満足させるための {@code VkDeviceMemory} をバインドする。ここで守りたいのは 2 つ:
 * <ol>
 *   <li><b>IOSurface が裏付けのままであること</b> — バインドしたメモリに絵が行ってしまえば
 *       GL との共有が静かに壊れる。GL 無しで確かめるため、<b>IOSurface そのものを
 *       第三の窓として直接読み書きする</b> ({@link IOSurf#lock})。
 *       Vulkan → IOSurface と IOSurface → Vulkan の両方向を見る。</li>
 *   <li><b>本番の descriptor 経路 ({@link VkDepthVisualise} on interop) が、バリデーション有効時に
 *       「メモリ未バインド」系の指摘を 1 件も出さず、interop 用の 3 VUID 抑制が 1 回も
 *       発火しないこと。</b></li>
 * </ol>
 *
 * <p>バリデーション無効時は 2. のメッセージ検査は空虚に通るが、画素の検査は常に効く。
 * 有効時 ({@code -PvkValidation=true}) にこそ意味がある検査である
 * (docs/ai/testing.md level 3)。
 */
public class VkInteropImageTest {
    private static final int W = 64, H = 64;
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

    @BeforeEach
    void clearMessages() {
        // この検査の間に出た指摘だけを見る
        VkContext.clearValidationMessages();
        VkContext.clearSuppressedValidationMessages();
    }

    // ---------------- helpers ----------------

    /**
     * 行ごとに違う値 (地形の行は列ごとにも僅かに違う)。一様だと行のずれも
     * 「別のメモリに行った」も見逃す。空の行は<b>正確に</b> {@link VkDepth#CLEAR} —
     * 可視化の空判定は FAR との等値なので、ここに揺らぎを足してはならない。
     */
    private static float[] depthPattern() {
        float[] d = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                d[y * W + x] = y >= SKY_START
                    ? VkDepth.CLEAR
                    : 0.999f - 0.998f * (y / (float) (SKY_START - 1)) + x * 1e-6f;
            }
        }
        return d;
    }

    /** IOSurface を CPU から直接書く (GL が書くのと同じ窓)。 */
    private static void hostWrite(VkInteropImage img, float[] src) {
        MemorySegment s = img.ioSurface();
        long stride = IOSurf.bytesPerRow(s);
        IOSurf.lock(s, 0);
        try {
            MemorySegment base = IOSurf.baseAddress(s);
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    base.set(ValueLayout.JAVA_FLOAT_UNALIGNED, y * stride + (long) x * 4, src[y * W + x]);
                }
            }
        } finally {
            IOSurf.unlock(s, 0);
        }
    }

    /** IOSurface を CPU から直接読む。 */
    private static int[] hostReadInts(VkInteropImage img) {
        MemorySegment s = img.ioSurface();
        long stride = IOSurf.bytesPerRow(s);
        int[] out = new int[W * H];
        IOSurf.lock(s, IOSurf.LOCK_READ_ONLY);
        try {
            MemorySegment base = IOSurf.baseAddress(s);
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    out[y * W + x] = base.get(ValueLayout.JAVA_INT_UNALIGNED, y * stride + (long) x * 4);
                }
            }
        } finally {
            IOSurf.unlock(s, IOSurf.LOCK_READ_ONLY);
        }
        return out;
    }

    private static void copyToBuffer(VkCommandBuffer cmd, long image, VkBuffer dst) {
        try (MemoryStack stack = stackPush()) {
            var region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(W, H, 1);
            vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_GENERAL, dst.handle, region);
        }
    }

    private static void copyFromBuffer(VkCommandBuffer cmd, VkBuffer src, long image) {
        try (MemoryStack stack = stackPush()) {
            var region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(W, H, 1);
            vkCmdCopyBufferToImage(cmd, src.handle, image, VK_IMAGE_LAYOUT_GENERAL, region);
        }
    }

    /** interop 画像を Vulkan のコピーで読み戻す (interopCompositeCheck の readGlWrittenInterop と同じ形)。 */
    private static int[] vulkanRead(VkInteropImage img, VkBuffer readback) {
        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        img.toGeneral(cmd,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        copyToBuffer(cmd, img.imageHandle(), readback);
        t.endFrame();
        t.waitForFrame();
        int[] out = new int[W * H];
        long base = readback.addr();
        for (int i = 0; i < out.length; i++) out[i] = MemoryUtil.memGetInt(base + (long) i * 4);
        return out;
    }

    private static int[] bits(float[] f) {
        int[] out = new int[f.length];
        for (int i = 0; i < f.length; i++) out[i] = Float.floatToRawIntBits(f[i]);
        return out;
    }

    private static String firstDiff(int[] a, int[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return "row " + (i / W) + " col " + (i % W) + ": expected 0x" + Integer.toHexString(a[i])
                    + " got 0x" + Integer.toHexString(b[i]);
            }
        }
        return "(identical)";
    }

    private static List<String> messagesMentioning(String needle) {
        return VkContext.validationMessages().stream().filter(m -> m.contains(needle)).toList();
    }

    // ---------------- tests ----------------

    /** メモリはバインドされていて、{@code DEVICE_LOCAL} かつ host-visible でないこと (クラス doc の条件 1)。 */
    @Test
    void bindsPrivateDeviceLocalMemory() {
        var img = new VkInteropImage(W, H, VkInteropImage.Kind.DEPTH_R32F,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        try {
            assertNotEquals(VK_NULL_HANDLE, img.memoryHandle(), "a VkDeviceMemory must be bound");
            assertTrue(img.memoryRequirementSize > 0, "vkGetImageMemoryRequirements reported no size");
            int flags = img.memoryPropertyFlags;
            assertTrue((flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0,
                "memory must be DEVICE_LOCAL, got flags 0x" + Integer.toHexString(flags));
            assertEquals(0, flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                "memory must NOT be HOST_VISIBLE (MoltenVK would flush host memory over the IOSurface),"
                    + " got flags 0x" + Integer.toHexString(flags));
            assertEquals(0, flags & VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT,
                "memory must not be lazily allocated, got flags 0x" + Integer.toHexString(flags));
            assertEquals(flags, VkContext.get().memProps.memoryTypes(img.memoryTypeIndex).propertyFlags(),
                "memoryTypeIndex and memoryPropertyFlags disagree");
        } finally {
            img.free();
        }
        assertEquals(List.of(), VkContext.validationMessages(),
            "creating, binding and freeing an interop image must be validation-clean");
        assertEquals(0, VkContext.suppressedValidationCount(),
            () -> "the interop-noise suppression fired: " + VkContext.suppressedValidationMessages());
    }

    /**
     * <b>Vulkan が書いた内容が IOSurface に届くこと。</b>
     * バインドしたメモリに絵が行っていれば IOSurface は 0 のままになる。
     */
    @Test
    void vulkanWritesLandInTheIOSurface() {
        float[] src = depthPattern();
        // SAMPLED は VkImageView を作れる usage を 1 つ入れるため (VUID-VkImageViewCreateInfo-image-04441);
        // 本番の interop 画像は常に SAMPLED か COLOR_ATTACHMENT を持つ
        var img = new VkInteropImage(W, H, VkInteropImage.Kind.DEPTH_R32F,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        VkBuffer staging = new VkBuffer((long) W * H * 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        try {
            long addr = staging.addr();
            for (int i = 0; i < src.length; i++) MemoryUtil.memPutFloat(addr + (long) i * 4, src[i]);

            // 対照: 書く前の IOSurface はパターンと一致しない
            assertNotEquals(firstDiff(bits(src), hostReadInts(img)), "(identical)",
                "the fresh IOSurface already holds the pattern; the check is vacuous");

            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            // 初回だけ UNDEFINED -> GENERAL (Vulkan が書き手なので捨てられて困る内容は無い)
            img.toGeneral(cmd,
                VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
            copyFromBuffer(cmd, staging, img.imageHandle());
            t.endFrame();
            t.waitForFrame();

            int[] got = hostReadInts(img);
            assertEquals("(identical)", firstDiff(bits(src), got),
                "Vulkan's write did not reach the IOSurface: the bound VkDeviceMemory took over as backing");
        } finally {
            staging.free();
            img.free();
        }
        assertEquals(List.of(), VkContext.validationMessages());
        assertEquals(0, VkContext.suppressedValidationCount(),
            () -> "the interop-noise suppression fired: " + VkContext.suppressedValidationMessages());
    }

    /**
     * <b>IOSurface に (GL のように) 外から書いた内容を Vulkan が読めること。</b>
     * GL → Vulkan 方向の C10 と同じ形を、GL 無しで IOSurface 直書きにより再現する。
     */
    @Test
    void hostWritesToTheIOSurfaceAreReadByVulkan() {
        float[] src = depthPattern();
        var img = new VkInteropImage(W, H, VkInteropImage.Kind.DEPTH_R32F,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        VkBuffer readback = new VkBuffer((long) W * H * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        try {
            // ⚠ 外部が書く前に UNDEFINED -> GENERAL を済ませる [Phase 5a §6.1]
            img.primeLayout();
            hostWrite(img, src);

            int[] got = vulkanRead(img, readback);
            assertEquals("(identical)", firstDiff(bits(src), got),
                "Vulkan read something other than the IOSurface contents");
        } finally {
            readback.free();
            img.free();
        }
        assertEquals(List.of(), VkContext.validationMessages());
        assertEquals(0, VkContext.suppressedValidationCount(),
            () -> "the interop-noise suppression fired: " + VkContext.suppressedValidationMessages());
    }

    /**
     * <b>本番の descriptor 経路 — interop 画像の上で {@link VkDepthVisualise} を走らせる</b>
     * (interopCompositeCheck C11 と同じ形、GL 抜き)。
     *
     * <p>要求するもの:
     * <ul>
     *   <li>絵が正しい (空はマゼンタ、地形はグレーで手前ほど明るい) — IOSurface を通して
     *       書いた深度が descriptor 経由で読めている</li>
     *   <li>{@code UNASSIGNED-VkDescriptorImageInfo-BoundResourceFreedMemoryAccess} も
     *       「used with no memory bound」も出ない (D6)</li>
     *   <li>{@code VUID-vkCmdDraw-imageLayout-00344} が出ない (D2)</li>
     *   <li>interop 用の 3 VUID 抑制が 1 回も発火しない</li>
     * </ul>
     */
    @Test
    void depthVisualiseOnInteropImagesIsValidationClean() {
        float[] src = depthPattern();
        VkInteropImage depthIn = null, colourOut = null;
        VkDepthVisualise vis = null;
        VkBuffer readback = new VkBuffer((long) W * H * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        try {
            depthIn = new VkInteropImage(W, H, VkInteropImage.Kind.DEPTH_R32F,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            depthIn.primeLayout();
            colourOut = new VkInteropImage(W, H, VkInteropImage.Kind.COLOR_BGRA8,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            vis = new VkDepthVisualise(depthIn.texture(), W, H,
                VkInteropImage.Kind.COLOR_BGRA8.vkFormat);

            hostWrite(depthIn, src);                 // 「GL が書いた」深度

            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            vis.record(cmd, depthIn.texture(), colourOut.texture());   // 本番と同じ record
            t.endFrame();
            t.waitForFrame();

            int[] px = vulkanRead(colourOut, readback);
            int skyR = Math.round(VkDepthVisualise.SKY_RGB[0] * 255);
            int skyG = Math.round(VkDepthVisualise.SKY_RGB[1] * 255);
            int skyB = Math.round(VkDepthVisualise.SKY_RGB[2] * 255);
            for (int y = SKY_START; y < H; y++) {
                int v = px[y * W + W / 2];
                assertTrue(r(v) == skyR && g(v) == skyG && b(v) == skyB,
                    "row " + y + " has depth FAR, so it must be the sky colour; got 0x" + Integer.toHexString(v));
            }
            int previous = 256;
            for (int y = 0; y < SKY_START; y++) {
                int v = px[y * W + W / 2];
                assertFalse(r(v) == skyR && g(v) == skyG && b(v) == skyB,
                    "row " + y + " has geometry, so it must NOT be the sky colour");
                assertTrue(r(v) == g(v) && g(v) == b(v), "row " + y + " must be grey");
                assertTrue(r(v) <= previous, "brightness must fall with distance at row " + y);
                previous = r(v);
            }
            assertTrue(r(px[W / 2]) - r(px[(SKY_START - 1) * W + W / 2]) > 32,
                "the grey range is too narrow; the visualiser did not read the IOSurface depth");

            // --- IOSurface も同じ絵を持っていること (書き先側の裏付けが IOSurface のまま) ---
            assertEquals("(identical)", firstDiff(px, hostReadInts(colourOut)),
                "the colour the IOSurface holds differs from what Vulkan reads back");
        } finally {
            if (vis != null) vis.free();
            if (colourOut != null) colourOut.free();
            if (depthIn != null) depthIn.free();
            readback.free();
        }

        var all = VkContext.validationMessages();
        System.out.println("[vk] VkInteropImageTest depth-visualise: validation="
            + VkContext.get().validationEnabled + " messages=" + all.size()
            + " suppressed=" + VkContext.suppressedValidationCount());
        assertEquals(List.of(), messagesMentioning("BoundResourceFreedMemoryAccess"),
            "D6: the descriptor write complained about unbound memory");
        assertEquals(List.of(), messagesMentioning("used with no memory bound"),
            "D6: some command saw the interop image as unbound");
        assertEquals(List.of(), messagesMentioning("VUID-vkCmdDraw-imageLayout-00344"),
            "D2: the descriptor's declared layout does not match the image's layout at draw time");
        assertEquals(List.of(), all, "the production depth-visualise path on interop images must be validation-clean");
        assertEquals(0, VkContext.suppressedValidationCount(),
            () -> "the interop-noise suppression fired " + VkContext.suppressedValidationCount()
                + " times; with memory bound it must never fire: " + VkContext.suppressedValidationMessages());
    }

    private static int b(int texel) { return texel & 0xFF; }
    private static int g(int texel) { return (texel >>> 8) & 0xFF; }
    private static int r(int texel) { return (texel >>> 16) & 0xFF; }
}
