package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import org.junit.jupiter.api.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** 地形リソース一式とアトラス転送 (`vkCmdCopyBufferToImage`) の検証。 */
public class VkTerrainResourcesTest {
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

    @Test
    void allocatesEveryBuffer() {
        var r = new VkTerrainResources(64, 1024, 512);
        try {
            for (var b : new VkBuffer[]{r.uniform, r.geometry, r.sectionMetadata, r.model,
                    r.modelColour, r.positionScratch, r.drawCall, r.drawCount,
                    r.indirectLookup, r.visibility}) {
                assertNotEquals(0L, b.handle);
                assertNotEquals(0L, b.addr(), "unified memory buffers are always mapped");
            }
            assertEquals(VkTerrainResources.ATLAS_W, r.atlas.width);
            assertEquals(VkTerrainResources.ATLAS_H, r.atlas.height);
            assertEquals(1, r.atlas.levels);
        } finally {
            r.free();
        }
    }

    /**
     * <b>転送が実際に効いているか</b>を GPU 側から読み戻して確認する。
     * アトラスを転送 → 別バッファへ読み戻し → パターンが一致するか。
     */
    @Test
    void atlasTransferLandsOnTheGpu() {
        // ⚠ モデル数は<b>検査する id を覆う</b>こと。
        // アトラスは maxModels ぶんしか上げない (本番も焼いたモデルだけを上げる) ので、
        // 範囲外のタイルは<b>未定義</b>である [Phase 5c-2a]
        var r = new VkTerrainResources(16, 256, 128, 8192);
        int w = VkTerrainResources.ATLAS_W, h = VkTerrainResources.ATLAS_H;
        var readback = new VkBuffer((long) w * h * 4);
        var t = VkFrameTracker.get();
        try {
            var cmd = t.beginFrame();
            r.recordAtlasUpload(cmd);

            // 転送済みのアトラスを読み戻す
            r.atlas.barrier(cmd, 0, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            copyWhole(cmd, r.atlas.image, readback, w, h);
            VkBarriers.memoryBarrier(cmd,
                org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_HOST_BIT,
                org.lwjgl.vulkan.VK13.VK_ACCESS_2_HOST_READ_BIT);
            t.endFrame();
            t.waitForFrame();

            // (modelId, face) -> テクセル座標 -> 色 が往復すること。
            // シェーダの getBaseUV と同じ規則で引けているかの検査でもある
            int[][] cases = {{0, 0}, {1, 3}, {255, 5}, {256, 1}, {7556, 4}};
            for (int[] c : cases) {
                int modelId = c[0], face = c[1];
                int[] pos = VkTerrainResources.atlasTexelPos(modelId, face);
                int[] got = read(readback, w, pos[0], pos[1]);
                assertArrayEquals(VkTerrainResources.atlasTexel(modelId, face), got,
                    "model " + modelId + " face " + face + " at texel ("
                        + pos[0] + "," + pos[1] + ")");
            }
        } finally {
            readback.free();
            r.free();
        }
    }

    /**
     * <b>隣り合う面セルが別の色であること。</b>
     * ここが同じだと、シェーダが隣の面を引いてしまっても絵に出ない。
     */
    @Test
    void adjacentFaceCellsDiffer() {
        var r = new VkTerrainResources(16, 256, 128);
        int w = VkTerrainResources.ATLAS_W, h = VkTerrainResources.ATLAS_H;
        var readback = new VkBuffer((long) w * h * 4);
        var t = VkFrameTracker.get();
        try {
            var cmd = t.beginFrame();
            r.recordAtlasUpload(cmd);
            r.atlas.barrier(cmd, 0, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            copyWhole(cmd, r.atlas.image, readback, w, h);
            t.endFrame();
            t.waitForFrame();

            var seen = new java.util.HashSet<Integer>();
            for (int face = 0; face < 6; face++) {
                int[] pos = VkTerrainResources.atlasTexelPos(42, face);
                assertTrue(seen.add(MemoryUtil.memGetInt(
                        readback.addr() + ((long) pos[1] * w + pos[0]) * 4L)),
                    "face " + face + " of model 42 shares a colour with another face");
            }
            // 隣のモデルとも違うこと
            int[] a = VkTerrainResources.atlasTexelPos(42, 0);
            int[] b = VkTerrainResources.atlasTexelPos(43, 0);
            assertNotEquals(
                MemoryUtil.memGetInt(readback.addr() + ((long) a[1] * w + a[0]) * 4L),
                MemoryUtil.memGetInt(readback.addr() + ((long) b[1] * w + b[0]) * 4L),
                "adjacent models must be distinguishable");
        } finally {
            readback.free();
            r.free();
        }
    }

    /** ライトマップは別 staging から転送され、一様であること。 */
    @Test
    void lightmapIsUniformAndSeparate() {
        var r = new VkTerrainResources(16, 256, 128);
        var readback = new VkBuffer(16L * 16 * 4);
        var t = VkFrameTracker.get();
        try {
            var cmd = t.beginFrame();
            r.recordAtlasUpload(cmd);
            r.lightmap.barrier(cmd, 0, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            try (MemoryStack stack = stackPush()) {
                var region = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.imageOffset().set(0, 0, 0);
                region.imageExtent().set(16, 16, 1);
                vkCmdCopyImageToBuffer(cmd, r.lightmap.image,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback.handle, region);
            }
            t.endFrame();
            t.waitForFrame();

            // 全ピクセルが白。アトラス柄が漏れていたらここで落ちる
            for (int i = 0; i < 16 * 16; i++) {
                int v = MemoryUtil.memGetInt(readback.addr() + (long) i * 4);
                assertEquals(0xFFFFFFFF, v,
                    "lightmap pixel " + i + " must be uniform white; "
                        + "a non-uniform value means the atlas staging leaked into it");
            }
        } finally {
            readback.free();
            r.free();
        }
    }

    @Test
    void writesModelData() {
        var r = new VkTerrainResources(16, 256, 128);
        try {
            r.writeSimpleModel(3, 5, 7);
            long base = r.model.addr() + 3L * VkTerrainResources.MODEL_SIZE;
            assertNotEquals(0, MemoryUtil.memGetInt(base), "faceData[0] written");
            assertEquals(0xFFFFFFFF, MemoryUtil.memGetInt(base + 28), "colourTint");
            assertEquals((7 << 8) | 5, MemoryUtil.memGetInt(base + 32), "customId holds the tile");
        } finally {
            r.free();
        }
    }

    private static void copyWhole(org.lwjgl.vulkan.VkCommandBuffer cmd, long image,
                                  VkBuffer dst, int w, int h) {
        try (MemoryStack stack = stackPush()) {
            var region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(w, h, 1);
            vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.handle, region);
        }
    }

    private static int[] read(VkBuffer buf, int width, int x, int y) {
        long o = buf.addr() + ((long) y * width + x) * 4L;
        return new int[]{
            MemoryUtil.memGetByte(o) & 0xFF,
            MemoryUtil.memGetByte(o + 1) & 0xFF,
            MemoryUtil.memGetByte(o + 2) & 0xFF,
            MemoryUtil.memGetByte(o + 3) & 0xFF,
        };
    }
}
