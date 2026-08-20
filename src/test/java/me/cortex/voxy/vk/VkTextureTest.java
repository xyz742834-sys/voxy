package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/** VkTexture のレベル別レイアウト追跡。 */
public class VkTextureTest {
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

    private static VkTexture hiZLike() {
        return new VkTexture(VK_FORMAT_D32_SFLOAT, 4, 64, 64,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
    }

    @Test
    void createsPerLevelViews() {
        var tex = hiZLike();
        try {
            assertEquals(4, tex.levels);
            assertNotEquals(0L, tex.view(), "whole-image view");
            for (int i = 0; i < 4; i++) {
                assertNotEquals(0L, tex.view(i), "level " + i + " view");
            }
            // レベルごとに別のビューであること (GL の BASE_LEVEL/MAX_LEVEL 相当が無いため必須)
            assertNotEquals(tex.view(0), tex.view(1));
            assertThrows(IndexOutOfBoundsException.class, () -> tex.view(4));
        } finally {
            tex.free();
        }
    }

    @Test
    void startsUndefined() {
        var tex = hiZLike();
        try {
            for (int i = 0; i < tex.levels; i++) {
                assertEquals(VK_IMAGE_LAYOUT_UNDEFINED, tex.layout(i));
            }
        } finally {
            tex.free();
        }
    }

    /**
     * <b>本題</b>: HiZ の mip チェーン生成のように、同一画像の異なるレベルが
     * 異なるレイアウトを同時に持つ状態を表現できること。
     *
     * <p>level i を深度アタッチメントとして書きながら level i-1 をサンプリングする、
     * という GL 側の構造をそのままなぞる。
     */
    @Test
    void differentLevelsHoldDifferentLayouts() {
        var tex = hiZLike();
        var t = VkFrameTracker.get();
        try {
            var cmd = t.beginFrame();

            // level 0 を書き込み可能に
            tex.barrier(cmd, 0, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            assertEquals(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, tex.layout(0));
            assertEquals(VK_IMAGE_LAYOUT_UNDEFINED, tex.layout(1), "other levels are untouched");

            // mip チェーン: level i-1 を読みながら level i に書く
            for (int i = 1; i < tex.levels; i++) {
                // 直前に書いたレベルを読み取り可能へ
                tex.barrier(cmd, i - 1, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                    VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
                // 今回書くレベルをアタッチメントへ
                tex.barrier(cmd, i, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                    VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                    VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

                assertEquals(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, tex.layout(i - 1),
                    "level " + (i - 1) + " must be readable while level " + i + " is written");
                assertEquals(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, tex.layout(i));
            }

            // 終端: 3 レベルが READ_ONLY、最後の 1 レベルが ATTACHMENT
            assertEquals(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, tex.layout(0));
            assertEquals(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, tex.layout(1));
            assertEquals(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, tex.layout(2));
            assertEquals(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, tex.layout(3));

            t.endFrame();
            t.waitForFrame();
        } finally {
            tex.free();
        }
    }

    /**
     * レイアウト据え置きでもアクセスマスクがあればバリアを出す。
     * 完全な no-op のときだけ省略する。
     */
    @Test
    void sameLayoutStillEmitsWhenSyncIsNeeded() {
        var tex = hiZLike();
        var t = VkFrameTracker.get();
        try {
            var cmd = t.beginFrame();
            tex.barrier(cmd, 0, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
            assertEquals(VK_IMAGE_LAYOUT_GENERAL, tex.layout(0));

            // 同じレイアウトのまま write -> read の同期だけ必要なケース。
            // ここでバリアを省くと同期漏れになる (バリデーションが検出する)
            tex.barrier(cmd, 0, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            assertEquals(VK_IMAGE_LAYOUT_GENERAL, tex.layout(0));

            // 完全な no-op (レイアウト据え置き + アクセスマスク 0) は何も出さない
            tex.barrier(cmd, 0, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
            assertEquals(VK_IMAGE_LAYOUT_GENERAL, tex.layout(0));

            t.endFrame();
            t.waitForFrame();
        } finally {
            tex.free();
        }
    }

    /** 外部由来の画像はメモリを所有しない。 */
    @Test
    void externalTextureDoesNotOwnMemory() {
        var owned = hiZLike();
        try {
            var external = VkTexture.wrapExternal(owned.image, VK_FORMAT_D32_SFLOAT, 4, 64, 64);
            assertTrue(external.isExternal());
            assertEquals(VK_NULL_HANDLE, external.memory,
                "IOSurface backed images bind no memory of their own");
            assertFalse(owned.isExternal());
            // ビューだけ破棄され、画像本体は owned 側が持つ
            external.free();
        } finally {
            owned.free();
        }
    }

    @Test
    void samplersAreCached() {
        var a = VkSampler.nearestClamp();
        var b = VkSampler.nearestClamp();
        assertSame(a, b, "identical sampler settings must reuse one VkSampler");
        assertNotSame(a, VkSampler.linearClamp());
    }
}
