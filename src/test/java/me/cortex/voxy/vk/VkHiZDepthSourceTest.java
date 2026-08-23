package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkFrameTracker;
import me.cortex.voxy.client.core.vk.VkHiZ;
import me.cortex.voxy.client.core.vk.VkRenderTarget;
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
 * Phase 5c-5b1: <b>HiZ が「深度アタッチメント」を元にできること</b>。
 *
 * <h2>なぜ要るのか</h2>
 * 上流の HiZ は <b>Voxy 自身の深度テクスチャ</b>を読む
 * [確認済 — {@code NormalRenderPipeline.setup} が {@code fb.getDepthTex()} を返し、
 * {@code runPipeline} が {@code renderOpaque} の<b>後</b>に
 * {@code innerPrimaryWork(viewport, depthTexture)} を呼ぶ]。
 *
 * <p>5c-4c までの Vulkan 経路は <b>interop の解決済み深度</b> (R32_SFLOAT の色画像) を
 * 読んでいた。それは<b>再投影済みで MC の投影空間</b>にあり、
 * トラバーサルが使う MVP とは<b>別の空間</b>である。しかも 1 フレーム古い。
 *
 * <p>そこで元を {@code VkRenderTarget.depth} ({@code D32_SFLOAT}) に変えたが、
 * <b>{@code VkHiZ} が深度フォーマットを読むのはこれが初めて</b>である。
 * 読めなければ (アスペクトの取り違え等) <b>0 かゴミが返る</b>だけで、
 * 絵は出るので気付けない。
 *
 * <h2>⚠ ここで主張できること・できないこと</h2>
 * 元が<b>一様</b>なので、<b>縮約 (min/max) については何も主張しない</b> —
 * それは {@link VkHiZTest} の担当である。
 * ここが主張するのは<b>「深度の値が実際に届いているか」</b>だけである。
 *
 * <p>⚠ 「0 でない」では足りない。<b>2 つの異なる深度で試し、両方が届く</b>ことを
 * 要求する — 定数を返す実装を落とすため [規約 11]。
 */
public class VkHiZDepthSourceTest {
    private static final int W = 64, H = 32;

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
        VkFrameTracker.shutdown();
    }

    /**
     * 深度を {@code clearDepth} でクリアした的から HiZ を作り、レベル 0 を読み返す。
     */
    private static float[] hizLevelZeroFromDepthClearedTo(float clearDepth) {
        var target = new VkRenderTarget(W, H);
        VkHiZ hiz = null;
        VkBuffer readback = null;
        try {
            hiz = new VkHiZ(target.depth, W, H);
            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            // 深度アタッチメントとしてクリアする。⚠ 色は触らない
            target.beginRendering(cmd, null, clearDepth);
            target.endRendering(cmd);
            // ⚠ **これが検査の本体**: 深度画像をそのまま HiZ の元にする
            hiz.record(cmd);
            t.endFrame();
            t.waitForFrame();

            readback = new VkBuffer((long) W * H * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
            var c2 = t.beginFrame();
            download(c2, hiz.texture(), 0, hiz.widthOf(0), hiz.heightOf(0), readback);
            t.endFrame();
            t.waitForFrame();

            var out = new float[hiz.widthOf(0) * hiz.heightOf(0)];
            for (int i = 0; i < out.length; i++) {
                out[i] = MemoryUtil.memGetFloat(readback.addr() + (long) i * 4L);
            }
            return out;
        } finally {
            if (readback != null) readback.free();
            if (hiz != null) hiz.free();
            target.free();
        }
    }

    /** <b>深度アタッチメントの値が HiZ に届くこと。</b> */
    @Test
    void theHiZCanReadADepthAttachment() {
        float[] level0 = hizLevelZeroFromDepthClearedTo(0.25f);
        assertTrue(level0.length > 0, "the chain must have a level 0");
        for (int i = 0; i < level0.length; i++) {
            assertEquals(0.25f, level0[i], 1e-6f,
                "texel " + i + " of the HiZ does not carry the depth it was cleared to;"
                    + " sampling a D32_SFLOAT source is not working");
        }
    }

    /**
     * ⚠ <b>対照</b>: 別の深度なら別の値が届くこと。
     *
     * <p>1 つの値だけを見ると<b>定数を返す実装</b>が通る。
     * 「0 でない」でも同じ穴が残る [規約 11]。
     */
    @Test
    void adifferentDepthGivesADifferentHiZ() {
        float[] a = hizLevelZeroFromDepthClearedTo(0.25f);
        float[] b = hizLevelZeroFromDepthClearedTo(0.75f);
        assertEquals(a.length, b.length);
        for (int i = 0; i < b.length; i++) {
            assertEquals(0.75f, b[i], 1e-6f, "texel " + i + " did not follow the source");
        }
        assertNotEquals(a[0], b[0],
            "the HiZ must follow the depth it was given; if both runs agree,"
                + " the value is coming from somewhere other than the source");
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
