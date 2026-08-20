package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.model.ModelAtlasLayout;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.VkTerrainResources.AtlasScale;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Phase 5c-2b — <b>ベイク結果が Vulkan のアトラスの「正しい場所」に入ること。</b>
 *
 * <h2>何を守っているのか</h2>
 * GL と Vulkan は<b>同じ CPU バッファ</b>を受け取り、<b>同じ配置の式</b>
 * ({@link ModelAtlasLayout}) を使う。だから「両者が同じものを作る」ことは
 * <b>比較する以前に構造で保証されている</b>。
 *
 * <p>残るのは<b>その式がシェーダの期待と合っているか</b>で、それをここで見る。
 * シェーダは {@code 1.0/256.0} の格子を直書きしているので、
 * <b>格子がずれたら絵が静かにずれる</b> (Stage 1 で踏んだ型)。
 *
 * <h2>この検査を空虚に満たす方法と、その塞ぎ方</h2>
 * <ol>
 *   <li><b>全部同じ色で埋める</b> → どこに置いても一致する。
 *       <b>(モデル, 面, ミップ) ごとに違う色</b>を入れる</li>
 *   <li><b>ミップ 0 しか見ない</b> → 段の位置がずれても気づかない。<b>全段</b>を見る</li>
 *   <li><b>1 モデルしか見ない</b> → タイルの刻みが違っても気づかない。
 *       <b>下位バイトと上位バイトの両方が効く id</b> を選ぶ</li>
 * </ol>
 */
public class VkModelUploadTargetTest {
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
     * <b>配置の式が、実装 2 つとシェーダの 3 者で一致していること。</b>
     *
     * <p>{@code ModelFactory.MODEL_TEXTURE_SIZE} は<b>コンパイル時定数</b>なので、
     * ここで参照しても {@code ModelFactory} は読み込まれない
     * (読み込むと {@code Minecraft.getInstance()} に触れて落ちる)。
     */
    @Test
    void theLayoutAgreesWithTheBakerAndTheShader() {
        assertEquals(ModelFactory.MODEL_TEXTURE_SIZE, ModelAtlasLayout.FACE_TEXELS,
            "the layout must use the same face size as the baker");
        // シェーダが直書きしている格子 (quads.frag の 1.0/256.0)
        assertEquals(256, ModelAtlasLayout.TILES);
        // 実物の寸法 [確認済 — RenderResourceReuse]
        assertEquals(16 * 3 * 256, ModelAtlasLayout.atlasWidth(16));
        assertEquals(16 * 2 * 256, ModelAtlasLayout.atlasHeight(16));
        assertEquals(4, ModelAtlasLayout.mipLevels(16));

        // タイル原点は GL 実装が使っていた式と同じでなければならない
        for (int id : new int[]{0, 1, 255, 256, 7556}) {
            assertEquals((id & 0xFF) * 16 * 3, ModelAtlasLayout.tileX(id, 16), "tileX for " + id);
            assertEquals(((id >> 8) & 0xFF) * 16 * 2, ModelAtlasLayout.tileY(id, 16), "tileY for " + id);
        }

        // ミップは連続。段の始まりは前段までの合計
        assertEquals(0, ModelAtlasLayout.mipByteOffset(16, 0));
        assertEquals(48L * 32 * 4, ModelAtlasLayout.mipByteOffset(16, 1));
        assertEquals((48L * 32 + 24L * 16) * 4, ModelAtlasLayout.mipByteOffset(16, 2));
    }

    /** (モデル, 面, ミップ) から一意に決まる色。<b>ずれたら必ず別の色になる。</b> */
    private static int colourOf(int modelId, int face, int lvl) {
        return 0xFF000000 | ((modelId & 0xFF) << 16) | ((face * 40 + 15) << 8) | (lvl * 60 + 20);
    }

    /** ベイク結果と同じ形 (ミップ連続) の CPU バッファを作る。 */
    private static MemoryBuffer bakeLike(int modelId, int faceTexels, int mips) {
        var buf = new MemoryBuffer(ModelAtlasLayout.bytesPerModel(faceTexels));
        long a = buf.address;
        for (int lvl = 0; lvl < mips; lvl++) {
            int cell = ModelAtlasLayout.cellTexels(faceTexels, lvl);
            int w = ModelAtlasLayout.tileWidth(faceTexels, lvl);
            int h = ModelAtlasLayout.tileHeight(faceTexels, lvl);
            long base = a + ModelAtlasLayout.mipByteOffset(faceTexels, lvl);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int face = (x / cell) * ModelAtlasLayout.FACE_ROWS + (y / cell);
                    MemoryUtil.memPutInt(base + ((long) y * w + x) * 4L, colourOf(modelId, face, lvl));
                }
            }
        }
        return buf;
    }

    /**
     * <b>置いたものが、置くべき場所から読み出せること。</b>
     *
     * <p>アトラス全体は 402MB あるので、<b>そのモデルのタイルだけ</b>読み戻す。
     */
    @Test
    void bakedTexturesLandOnTheTileTheShaderWillSample() {
        // 上位バイトも効く id を含める (7556 は 0x1D84 → タイル (0x84, 0x1D))
        int[] ids = {0, 1, 7556};
        int maxModels = 8192;
        var res = new VkTerrainResources(4, 64, 16, maxModels,
            VkQuadIndexBuffer.DEFAULT_QUAD_CAPACITY, AtlasScale.REAL);
        var target = new VkModelUploadTarget(res);
        int face = AtlasScale.REAL.facePx, mips = AtlasScale.REAL.mipLevels;
        var readback = new VkBuffer(ModelAtlasLayout.bytesPerModel(face),
            VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        try {
            for (int id : ids) {
                var baked = bakeLike(id, face, mips);
                try {
                    target.uploadModelTexture(id, baked, mips);
                } finally {
                    baked.free();
                }
            }
            assertEquals(ids.length, target.pendingCount(), "all models should be queued");

            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            assertEquals(ids.length, target.recordUploads(cmd));
            t.endFrame();
            t.waitForFrame();
            assertEquals(0, target.pendingCount(), "the queue must be empty after recording");

            for (int id : ids) {
                readTile(res, readback, id, face, mips);
                long a = readback.addr();
                for (int lvl = 0; lvl < mips; lvl++) {
                    int cell = ModelAtlasLayout.cellTexels(face, lvl);
                    int w = ModelAtlasLayout.tileWidth(face, lvl);
                    int h = ModelAtlasLayout.tileHeight(face, lvl);
                    long base = a + ModelAtlasLayout.mipByteOffset(face, lvl);
                    // 面ごとに中央の 1 テクセルを見る (境界の丸めではなく配置を見たい)
                    for (int f = 0; f < 6; f++) {
                        int cx = (f / ModelAtlasLayout.FACE_ROWS) * cell + cell / 2;
                        int cy = (f % ModelAtlasLayout.FACE_ROWS) * cell + cell / 2;
                        int got = MemoryUtil.memGetInt(base + ((long) cy * w + cx) * 4L);
                        assertEquals(colourOf(id, f, lvl), got,
                            "model " + id + " face " + f + " mip " + lvl
                                + " landed on the wrong texel (expected tile ("
                                + tileXLiteral(id) + "," + tileYLiteral(id)
                                + "), size " + w + "x" + h + ")");
                    }
                }
            }
        } finally {
            readback.free();
            target.free();
            res.free();
        }
    }

    /**
     * 読み戻しの位置は<b>共有の式を使わず、literal で書く</b>。
     *
     * <p>⚠ {@link ModelAtlasLayout} を書き込みにも読み戻しにも使うと、
     * <b>式が間違っていても両方が同じだけずれて一致する</b> [規約 4]。
     * 実際、最初はそう書いていて<b>タイルの刻みを取り違える変異を素通しした</b>。
     */
    private static int tileXLiteral(int modelId) {
        return (modelId & 0xFF) * ModelFactory.MODEL_TEXTURE_SIZE * 3;
    }

    private static int tileYLiteral(int modelId) {
        return ((modelId >> 8) & 0xFF) * ModelFactory.MODEL_TEXTURE_SIZE * 2;
    }

    /** そのモデルのタイルだけを全ミップ読み戻す。 */
    private static void readTile(VkTerrainResources res, VkBuffer dst, int modelId,
                                 int face, int mips) {
        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        res.atlas.barrierAll(cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        try (MemoryStack stack = stackPush()) {
            var regions = VkBufferImageCopy.calloc(mips, stack);
            for (int lvl = 0; lvl < mips; lvl++) {
                var r = regions.get(lvl);
                r.bufferOffset(ModelAtlasLayout.mipByteOffset(face, lvl))
                    .bufferRowLength(0).bufferImageHeight(0);
                r.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(lvl).baseArrayLayer(0).layerCount(1);
                // ⚠ 共有の式ではなく literal を使う (上の注意)
                r.imageOffset().set(tileXLiteral(modelId) >> lvl, tileYLiteral(modelId) >> lvl, 0);
                r.imageExtent().set(ModelAtlasLayout.tileWidth(face, lvl),
                    ModelAtlasLayout.tileHeight(face, lvl), 1);
            }
            vkCmdCopyImageToBuffer(cmd, res.atlas.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                dst.handle, regions);
        }
        VkBarriers.memoryBarrier(cmd,
            org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
            org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_HOST_BIT,
            org.lwjgl.vulkan.VK13.VK_ACCESS_2_HOST_READ_BIT);
        t.endFrame();
        t.waitForFrame();
    }

    /**
     * <b>範囲外のモデル id は弾くこと。</b>
     * 通してしまうと<b>シェーダが未定義の領域を引く</b>形でしか出ず、
     * バリデーションも捕まえない。
     */
    @Test
    void modelIdsOutsideTheRangeAreRejected() {
        var res = new VkTerrainResources(4, 64, 16, 32);
        var target = new VkModelUploadTarget(res);
        var buf = new MemoryBuffer(64).zero();
        try {
            assertThrows(IllegalArgumentException.class, () -> target.uploadModel(32, buf));
            assertThrows(IllegalArgumentException.class, () -> target.uploadModel(-1, buf));
            // 範囲内は通ること (弾きすぎていないことの対照)
            assertDoesNotThrow(() -> target.uploadModel(31, buf));
        } finally {
            buf.free();
            target.free();
            res.free();
        }
    }
}
