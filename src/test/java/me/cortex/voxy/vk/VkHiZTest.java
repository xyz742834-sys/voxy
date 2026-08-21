package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkDepth;
import me.cortex.voxy.client.core.vk.VkFrameTracker;
import me.cortex.voxy.client.core.vk.VkHiZ;
import me.cortex.voxy.client.core.vk.VkSampler;
import me.cortex.voxy.client.core.vk.VkTexture;
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
 * <b>HiZ のミップ連鎖が、縮約の定義どおりであること</b> (Phase 5c-4a)。
 *
 * <h2>ここは厳密に言える</h2>
 * レベル 0 は<b>元の深度の再標本化</b>なので、元と大きさが違えば一致は言えない。
 * しかし<b>レベル i (i&ge;1) は レベル i-1 のちょうど 2 倍の大きさ</b>なので、
 * {@code textureGather} が取る 4 テクセルは <b>2x2 のブロックそのもの</b>である。
 * したがって <b>ビット単位で厳密に</b> 一致を要求できる。
 *
 * <h2>この検査を空虚に満たす方法と、その塞ぎ方</h2>
 * <ol>
 *   <li><b>元データが平坦</b> → min も max も同じ値になり、
 *       <b>縮約が何であっても通る</b>。
 *       {@link #theSourcePatternActuallyDistinguishesMinFromMax} が対照になる</li>
 *   <li><b>min と max を取り違えている</b> → 1 の対照があれば落ちる。
 *       逆Zでは <b>min = 最も奥</b>が正しい (遮蔽判定を保守的にするため)</li>
 * </ol>
 */
public class VkHiZTest {
    /** ⚠ 2 の冪でない大きさを渡して、切り下げが効くことも見る。 */
    private static final int SRC_W = 100, SRC_H = 40;
    private static final int W = 64, H = 32;    // 期待される HiZ の大きさ

    private static VkTexture source;
    private static VkHiZ hiz;
    private static float[][] levels;

    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
        VkFrameTracker.init();
        source = new VkTexture(VkHiZ.FORMAT, 1, SRC_W, SRC_H,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT).name("hizSrc");
        hiz = new VkHiZ(source, SRC_W, SRC_H);

        var staging = new VkBuffer((long) SRC_W * SRC_H * 4,
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        // ⚠ **非対称で、隣どうしが必ず違う**模様。平坦だと縮約が何でも通ってしまう。
        // 値は逆Zの範囲 (0..1) に収める
        for (int y = 0; y < SRC_H; y++) {
            for (int x = 0; x < SRC_W; x++) {
                // ⚠ **0.0 (= FAR) を避ける。** 偶然 FAR になると
                // 「空だから 0」と「模様として 0」が区別できなくなる [規約 18]
                float v = (float) ((((x * 37 + y * 101) % 251) + 1) / 252.0);
                MemoryUtil.memPutFloat(staging.addr() + ((long) y * SRC_W + x) * 4L, v);
            }
        }

        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        upload(cmd, staging, source);
        hiz.record(cmd);
        t.endFrame();
        t.waitForFrame();

        levels = new float[hiz.levels()][];
        var readback = new VkBuffer((long) W * H * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        for (int l = 0; l < hiz.levels(); l++) {
            int lw = hiz.widthOf(l), lh = hiz.heightOf(l);
            var c2 = t.beginFrame();
            download(c2, hiz.texture(), l, lw, lh, readback);
            t.endFrame();
            t.waitForFrame();
            levels[l] = new float[lw * lh];
            for (int i = 0; i < levels[l].length; i++) {
                levels[l][i] = MemoryUtil.memGetFloat(readback.addr() + (long) i * 4L);
            }
        }
        readback.free();
        staging.free();
    }

    @AfterAll
    static void teardown() {
        if (VkFrameTracker.isRecordingFrame()) VkFrameTracker.get().endFrame();
        VkFrameTracker.get().waitIdle();
        if (hiz != null) hiz.free();
        if (source != null) source.free();
        VkSampler.shutdown();
        VkFrameTracker.shutdown();
    }

    /** 大きさとレベル数が GL 版の決め方と一致すること。 */
    @Test
    void theChainIsSizedTheWayTheReferenceSizesIt() {
        assertEquals(W, hiz.width(), "the HiZ width must be the source rounded DOWN to a power of two");
        assertEquals(H, hiz.height(), "... and the same for height");
        // ceil(log2(64)) = 6 -> レベル 0..5、いちばん小さいのは 2x1
        assertEquals(6, hiz.levels(), "levels must be ceil(log2(max(w,h)))");
        assertEquals(2, hiz.widthOf(hiz.levels() - 1), "the smallest level is not 1x1");
        assertEquals(1, hiz.heightOf(hiz.levels() - 1));
        assertEquals((W << 16) | H, hiz.packedSize(), "packedHizSize must be w<<16 | h");
    }

    /**
     * <b>対照</b>: 元の模様が min と max を実際に区別すること。
     *
     * <p>平坦なら「min を取った」も「max を取った」も同じ結果になり、
     * 下の検査は<b>何も主張しなくなる</b>。
     */
    @Test
    void theSourcePatternActuallyDistinguishesMinFromMax() {
        int differing = 0;
        for (int l = 1; l < hiz.levels(); l++) {
            int pw = hiz.widthOf(l - 1);
            for (int y = 0; y < hiz.heightOf(l); y++) {
                for (int x = 0; x < hiz.widthOf(l); x++) {
                    float[] q = block(levels[l - 1], pw, x, y);
                    float mn = Math.min(Math.min(q[0], q[1]), Math.min(q[2], q[3]));
                    float mx = Math.max(Math.max(q[0], q[1]), Math.max(q[2], q[3]));
                    if (mn != mx) differing++;
                }
            }
        }
        assertTrue(differing > 100,
            "only " + differing + " 2x2 blocks have a min different from their max;"
                + " the reduction is not being distinguished from any other reduction");
    }

    /**
     * <b>レベル i は レベル i-1 の 2x2 縮約 (逆Zなので min) と厳密に一致すること。</b>
     *
     * <p>期待値は<b>縮約の定義</b>であって、シェーダの式を再実行したものではない [規約 4]。
     */
    @Test
    void eachLevelIsTheExactReductionOfTheOneAbove() {
        for (int l = 1; l < hiz.levels(); l++) {
            int pw = hiz.widthOf(l - 1);
            for (int y = 0; y < hiz.heightOf(l); y++) {
                for (int x = 0; x < hiz.widthOf(l); x++) {
                    float[] q = block(levels[l - 1], pw, x, y);
                    float expected = Math.min(Math.min(q[0], q[1]), Math.min(q[2], q[3]));
                    float got = levels[l][y * hiz.widthOf(l) + x];
                    assertEquals(expected, got, 0.0f,
                        "level " + l + " at (" + x + "," + y + "): expected the minimum of "
                            + java.util.Arrays.toString(q) + " (reverse-Z keeps the FARTHEST)"
                            + " but got " + got);
                }
            }
        }
    }

    /**
     * <b>取り違えの対照</b>: max を取っていたら落ちること。
     *
     * <p>逆Zで max を取ると「最も手前」を残すことになり、
     * <b>見えているノードを隠れていると判定して落とす</b> — 絵に穴が開く。
     */
    @Test
    void takingTheMaximumInsteadWouldBeDetected() {
        int mismatches = 0;
        for (int l = 1; l < hiz.levels(); l++) {
            int pw = hiz.widthOf(l - 1);
            for (int y = 0; y < hiz.heightOf(l); y++) {
                for (int x = 0; x < hiz.widthOf(l); x++) {
                    float[] q = block(levels[l - 1], pw, x, y);
                    float mx = Math.max(Math.max(q[0], q[1]), Math.max(q[2], q[3]));
                    if (levels[l][y * hiz.widthOf(l) + x] != mx) mismatches++;
                }
            }
        }
        assertTrue(mismatches > 100,
            "the chain agreed with a MAX reduction in all but " + mismatches
                + " texels; this test cannot tell min from max");
    }

    /** 元の深度が実際に読まれていること (レベル 0 が平坦でない)。 */
    @Test
    void levelZeroActuallySampledTheSource() {
        float first = levels[0][0];
        int different = 0;
        for (float v : levels[0]) if (v != first) different++;
        assertTrue(different > levels[0].length / 4,
            "level 0 is nearly constant (" + different + " of " + levels[0].length
                + " differ from the first texel) — the source may not have been sampled");
        for (float v : levels[0]) {
            assertTrue(v >= 0.0f && v <= 1.0f, "depth outside 0..1: " + v);
        }
        assertNotEquals(VkDepth.FAR, first, "level 0 starts at FAR; the source may be empty");
    }

    private static float[] block(float[] prev, int prevWidth, int x, int y) {
        return new float[]{
            prev[(y * 2)     * prevWidth + (x * 2)],
            prev[(y * 2)     * prevWidth + (x * 2 + 1)],
            prev[(y * 2 + 1) * prevWidth + (x * 2)],
            prev[(y * 2 + 1) * prevWidth + (x * 2 + 1)],
        };
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
            region.imageExtent().set(SRC_W, SRC_H, 1);
            vkCmdCopyBufferToImage(cmd, staging.handle, tex.image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
    }

    private static void download(VkCommandBuffer cmd, VkTexture tex, int level,
                                 int w, int h, VkBuffer dst) {
        tex.barrier(cmd, level, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        try (MemoryStack stack = stackPush()) {
            var region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(level).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(w, h, 1);
            vkCmdCopyImageToBuffer(cmd, tex.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                dst.handle, region);
        }
    }
}
