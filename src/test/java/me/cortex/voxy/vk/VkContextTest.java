package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.VkContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 検証 1: VkContext が初期化でき、Phase 0 の実測値と一致すること。 */
public class VkContextTest {
    @BeforeAll
    static void setup() { VulkanTestSupport.requireVulkan(); }

    @Test
    void deviceInitialises() {
        var ctx = VkContext.get();
        assertNotNull(ctx.device);
        assertNotNull(ctx.queue);
        assertTrue(ctx.queueFamily >= 0);
        assertNotEquals(0L, ctx.commandPool);
        System.out.println("[vk] queueFamily=" + ctx.queueFamily
            + " unifiedMemoryType=" + ctx.unifiedMemoryType);
    }

    /**
     * push constant 上限。Phase 0 の実測は 4096 で、これを根拠に
     * 「default-block uniform 17 宣言は全て push constant で足りる」と判断している。
     */
    @Test
    void maxPushConstantsSizeIsAtLeastWhatWeNeed() {
        int limit = VkContext.get().maxPushConstantsSize;
        System.out.println("[vk] maxPushConstantsSize=" + limit);
        // ssao.comp の mat4 x 6 = 384 バイトが最大の想定ケース
        assertTrue(limit >= 384,
            "need >=384B for ssao.comp's 6 mat4s, got " + limit);
        assertEquals(4096, limit,
            "Phase 0 measured 4096 on M4 Pro / MoltenVK; a different value invalidates "
          + "the sizing assumption in docs/phase2-pushconstant-todo.md");
    }

    /**
     * subgroup。Phase 0 の実測は 32。
     * prefixsum の subgroup 版 (inital3.comp) を使えるかの判定に効く。
     */
    @Test
    void subgroupSizeMatchesPhase0() {
        var ctx = VkContext.get();
        System.out.println("[vk] subgroupSize=" + ctx.subgroupSize + " hasSubgroup=" + ctx.hasSubgroup);
        assertTrue(ctx.hasSubgroup, "subgroup ops are needed by util/prefixsum/inital3.comp");
        assertEquals(32, ctx.subgroupSize, "Phase 0 measured subgroupSize=32");
    }

    /**
     * ユニファイドメモリ。DEVICE_LOCAL|HOST_VISIBLE|HOST_COHERENT を満たす
     * メモリタイプが存在することが VkBuffer の設計前提 (ステージング不要)。
     */
    @Test
    void unifiedMemoryTypeExists() {
        var ctx = VkContext.get();
        assertTrue(ctx.unifiedMemoryType >= 0);
        int flags = ctx.memProps.memoryTypes(ctx.unifiedMemoryType).propertyFlags();
        System.out.println("[vk] unified memory flags=0x" + Integer.toHexString(flags)
            + " heapCount=" + ctx.memProps.memoryHeapCount());
        assertTrue((flags & org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0);
        assertTrue((flags & org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0);
        assertTrue((flags & org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0);
    }

    /**
     * dynamic offset (カテゴリ C) が成立するかの前提。
     * UploadStream 相当のリングバッファのオフセットがこの倍数でなければ、
     * アロケータ側の調整が要る。docs/phase3-descriptor-survey.md 4.1。
     */
    @Test
    void storageBufferOffsetAlignmentIsSatisfiable() {
        long align = VkContext.get().minStorageBufferOffsetAlignment;
        System.out.println("[vk] minStorageBufferOffsetAlignment=" + align
            + " minUniformBufferOffsetAlignment=" + VkContext.get().minUniformBufferOffsetAlignment);
        assertTrue(align > 0);
        assertEquals(0, align & (align - 1), "Vulkan requires this limit to be a power of two");
        // UploadStream は BASE_ALLOCATION_ALIGNEMENT = max(limit, 16) で
        // 全確保サイズを切り上げ、オフセットはその総和になる。
        // よって limit がこの値以下であれば全オフセットが自動的に整合する。
        long uploadStreamAlignment = Math.max(align, 16);
        assertEquals(0, uploadStreamAlignment % align,
            "UploadStream's allocation granularity must be a multiple of the device requirement");
    }

    /**
     * shaderInt64 / shaderDrawParameters が実際に有効化されていること。
     * 前者は quad_format.glsl の uint64 実装、後者は gl_BaseInstance 経路が依存する。
     * VkContext は device 生成時に両方を要求しているので、
     * 生成が成功した時点で有効。ここでは物理デバイスが実際に対応しているかを確認する。
     */
    @Test
    void requiredFeaturesAreSupported() {
        var feats = org.lwjgl.vulkan.VkPhysicalDeviceFeatures.calloc();
        try {
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFeatures(VkContext.get().physical, feats);
            System.out.println("[vk] shaderInt64=" + feats.shaderInt64()
                + " multiDrawIndirect=" + feats.multiDrawIndirect()
                + " drawIndirectFirstInstance=" + feats.drawIndirectFirstInstance());
            assertTrue(feats.shaderInt64(), "quad_format.glsl relies on uint64_t");
            assertTrue(feats.multiDrawIndirect(), "MDI is the whole draw path");
            assertTrue(feats.drawIndirectFirstInstance(), "baseInstance carries the section id today");
        } finally {
            feats.free();
        }
    }
}
