package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.model.ModelAtlasLayout;
import me.cortex.voxy.client.core.model.ModelUploadTarget;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * ベイクしたモデルを <b>Vulkan の資源へ</b>置く (Phase 5c-2b)。
 *
 * <p>{@code ModelStore} (GL) と<b>同じ入力</b>を受け取る —
 * 境界は CPU 側のバッファなので、両者が受け取るものは同一である
 * [{@link ModelUploadTarget} の javadoc]。
 * アトラス内の配置も {@link ModelAtlasLayout} を共有するので、<b>式は 1 つしかない</b>。
 *
 * <h2>GL 実装との違い</h2>
 * <table>
 *   <tr><th></th><th>GL</th><th>Vulkan (ここ)</th></tr>
 *   <tr><td>モデル属性・バイオーム色</td><td>{@code UploadStream} 経由</td>
 *       <td><b>直接書く</b> (ユニファイドメモリで host visible)</td></tr>
 *   <tr><td>アトラス</td><td>{@code glTextureSubImage2D} で即時</td>
 *       <td>staging に溜め、{@link #recordUploads} で<b>まとめて転送</b></td></tr>
 * </table>
 *
 * <h2>⚠ なぜアトラスだけ溜めるのか</h2>
 * テクスチャは {@code TILING_OPTIMAL} なので<b>直接書けない</b> —
 * バッファと違ってコマンドバッファ経由の転送が要る。
 * ベイクは描画フレームの外で起きるので、<b>フレームの中で流す</b>ためにいったん溜める。
 */
public final class VkModelUploadTarget implements ModelUploadTarget {
    /** 1 モデルの属性のバイト数。{@code ModelStore.MODEL_SIZE} と同じ。 */
    public static final int MODEL_SIZE = 64;

    private final VkTerrainResources res;
    /** 溜めたテクスチャ。{@link #recordUploads} で流す。 */
    private final List<Pending> pending = new ArrayList<>();
    private VkBuffer staging;
    private long stagingUsed;
    private boolean freed;

    private record Pending(int modelId, int mipLevels, long bufferOffset) {}

    public VkModelUploadTarget(VkTerrainResources res) {
        this.res = res;
    }

    /**
     * 何もしない。GL のアンパック指定は<b>GL の転送にしか要らない</b>。
     * <b>ここで GL を呼ぶと JVM ごと落ちる</b> — 生の GL 呼び出しは Vulkan 経路に置かない。
     */
    @Override
    public void beginUploads() {}

    /**
     * 何もしない。属性とバイオーム色は<b>ホスト可視のメモリに直に書いている</b>ので、
     * 流す手順が要らない。
     *
     * <p>⚠ <b>アトラスだけは別</b>である — {@code TILING_OPTIMAL} なので直に書けず、
     * {@link #recordUploads} が<b>フレームの中で</b>転送を積む。
     * ここでやらないのは、ベイクが<b>フレームの外で</b>起きるからである。
     */
    @Override
    public void commitUploads() {}

    @Override
    public void uploadModel(int modelId, MemoryBuffer model) {
        this.requireInRange(modelId);
        model.cpyTo(this.res.model.addr() + (long) modelId * MODEL_SIZE);
    }

    @Override
    public void uploadBiomeColours(int index, MemoryBuffer colours) {
        long end = index * 4L + colours.size;
        if (end > this.res.modelColour.size()) {
            throw new IllegalArgumentException("biome colour upload at " + index
                + " (+" + colours.size + "B) exceeds the colour buffer ("
                + this.res.modelColour.size() + "B)");
        }
        colours.cpyTo(this.res.modelColour.addr() + index * 4L);
    }

    @Override
    public void uploadBiomeColourTable(MemoryBuffer colours) {
        if (colours.size > this.res.modelColour.size()) {
            throw new IllegalArgumentException("biome colour table is " + colours.size
                + "B but the buffer is " + this.res.modelColour.size() + "B");
        }
        colours.cpyTo(this.res.modelColour.addr());
    }

    /**
     * ⚠ <b>属性全体ではなく 1 フィールドだけ</b>を書き換える。
     * 全体を書き直すと、既に入っている他のフィールドが消える。
     */
    @Override
    public void patchModelBiomeIndex(int modelId, int biomeIndex) {
        this.requireInRange(modelId);
        MemoryUtil.memPutInt(this.res.model.addr() + (long) MODEL_SIZE * modelId + 4 * 6 + 4,
            biomeIndex);
    }

    /**
     * <b>staging へ写して、転送は後で行う。</b>
     *
     * <p>⚠ {@code texture} は<b>呼び出しから戻ると再利用される</b>
     * [確認済 — {@code ModelBakeResultUpload} は 1 本を使い回す] ので、
     * <b>ここで写しておかなければならない</b>。参照を持つだけでは、
     * 転送の時点で<b>別のモデルの中身</b>になっている。
     */
    @Override
    public void uploadModelTexture(int modelId, MemoryBuffer texture, int mipLevels) {
        this.requireInRange(modelId);
        int face = this.res.atlasScale.facePx;
        long need = ModelAtlasLayout.mipByteOffset(face, Math.min(mipLevels, this.res.atlasScale.mipLevels));
        this.ensureStaging(this.stagingUsed + need);

        MemoryUtil.memCopy(texture.address, this.staging.addr() + this.stagingUsed, need);
        this.pending.add(new Pending(modelId,
            Math.min(mipLevels, this.res.atlasScale.mipLevels), this.stagingUsed));
        this.stagingUsed += need;
    }

    /**
     * 溜めたテクスチャをアトラスへ転送する記録を積む。
     * <b>フレームの中で呼ぶこと。</b> 呼んだあと溜めた分は空になる。
     *
     * @return 転送したモデル数
     */
    public int recordUploads(VkCommandBuffer cmd) {
        if (this.pending.isEmpty()) return 0;

        this.res.atlas.barrierAll(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);

        int face = this.res.atlasScale.facePx;
        int total = 0;
        for (Pending p : this.pending) total += p.mipLevels();
        // ⚠ 個数が入力に比例するのでヒープに取る [規約 13]
        var regions = VkBufferImageCopy.calloc(total);
        try {
            int i = 0;
            for (Pending p : this.pending) {
                int baseX = ModelAtlasLayout.tileX(p.modelId(), face);
                int baseY = ModelAtlasLayout.tileY(p.modelId(), face);
                for (int lvl = 0; lvl < p.mipLevels(); lvl++) {
                    var r = regions.get(i++);
                    r.bufferOffset(p.bufferOffset() + ModelAtlasLayout.mipByteOffset(face, lvl))
                        .bufferRowLength(0).bufferImageHeight(0);
                    r.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(lvl).baseArrayLayer(0).layerCount(1);
                    r.imageOffset().set(baseX >> lvl, baseY >> lvl, 0);
                    r.imageExtent().set(ModelAtlasLayout.tileWidth(face, lvl),
                        ModelAtlasLayout.tileHeight(face, lvl), 1);
                }
            }
            vkCmdCopyBufferToImage(cmd, this.staging.handle, this.res.atlas.image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions);
        } finally {
            regions.free();
        }

        this.res.atlas.barrierAll(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT);

        int n = this.pending.size();
        this.pending.clear();
        this.stagingUsed = 0;
        return n;
    }

    /** 溜まっているモデル数 (診断用)。 */
    public int pendingCount() { return this.pending.size(); }

    // ---- 属性の読み戻し (診断用) ----
    //
    // ⚠ 位置は block_model.glsl の struct BlockModel に合わせてある:
    //   uint faceData[6] (24B) / flagsA (24) / colourTint (28) / customId (32)

    /** {@code flagsA}。bit1 = バイオーム LUT を使う [block_model.glsl の modelHasBiomeLUT]。 */
    public int modelFlags(int modelId) {
        this.requireInRange(modelId);
        return MemoryUtil.memGetInt(this.res.model.addr() + (long) modelId * MODEL_SIZE + 24);
    }

    /**
     * {@code colourTint}。バイオーム LUT を使うモデルでは<b>色表への基点</b>、
     * そうでなければ<b>色そのもの</b>である。
     */
    public int modelColourTint(int modelId) {
        this.requireInRange(modelId);
        return MemoryUtil.memGetInt(this.res.model.addr() + (long) modelId * MODEL_SIZE + 28);
    }

    /** バイオーム色表の {@code index} 番目。 */
    public int biomeColour(int index) {
        if (index < 0 || (index + 1L) * 4L > this.res.modelColour.size()) return 0;
        return MemoryUtil.memGetInt(this.res.modelColour.addr() + index * 4L);
    }

    /**
     * まだ流していないモデルの<b>ミップ 0 のタイル</b>の先頭アドレス。
     *
     * <p>{@code VkRealModelBakery.assertTilesAreDistinguishable} が
     * <b>実際にアトラスへ入る中身</b>を突き合わせるために使う (規約 1 の実データ版)。
     *
     * <p>⚠ {@link #recordUploads} を呼ぶと溜めた分は空になるので、
     * <b>その前に</b>読むこと。
     *
     * @return 見つからなければ 0
     */
    public long stagedTileAddress(int modelId) {
        for (Pending p : this.pending) {
            if (p.modelId() == modelId) {
                return this.staging.addr() + p.bufferOffset()
                    + ModelAtlasLayout.mipByteOffset(this.res.atlasScale.facePx, 0);
            }
        }
        return 0L;
    }

    private void requireInRange(int modelId) {
        if (modelId < 0 || modelId >= this.res.maxModels) {
            // ⚠ 範囲外は**バリデーションが捕まえない** — シェーダが未定義の領域を引く形でしか出ない
            throw new IllegalArgumentException("modelId " + modelId
                + " is outside the resources' model range (" + this.res.maxModels + ")");
        }
    }

    private void ensureStaging(long need) {
        if (this.staging != null && this.staging.size() >= need) return;
        long size = Math.max(need, Math.max(1 << 20, this.staging == null ? 0 : this.staging.size() * 2));
        var bigger = new VkBuffer(size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true).name("modelStaging");
        if (this.staging != null) {
            // 溜めてある分を引き継ぐ。捨てると「転送したつもり」で消える
            MemoryUtil.memCopy(this.staging.addr(), bigger.addr(), this.stagingUsed);
            this.staging.free();
        }
        this.staging = bigger;
    }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        if (this.staging != null) { this.staging.free(); this.staging = null; }
        this.pending.clear();
    }
}
