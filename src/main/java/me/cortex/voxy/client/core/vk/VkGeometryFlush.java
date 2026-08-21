package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;

/**
 * Phase 5c-4c — <b>溜まったジオメトリを Vulkan のバッファへ流す</b>。
 *
 * <h2>境界がこの形で出るのは 4 度目、そして<b>今回は上流が既に抽象にしている</b></h2>
 * {@link BasicAsyncGeometryManager} は<b>GL に一切触れない</b> —
 * クラス自身が「the underlying store is irrelevant」と書いている [確認済]。
 * 割り当てを {@code AllocationArena} で決め、
 * <b>CPU 側のバッファを溜めて</b>置き場所に渡すだけである。
 *
 * <p>したがって Vulkan 側は<b>流すだけ</b>で、新しい管理器は要らない。
 *
 * <h2>本番との違い — <b>同期</b></h2>
 * 本番は {@code AsyncNodeManager} が二重バッファで非同期に流す。
 * ここは同期である。<b>この段で見たいのは選択の結果</b>であって、
 * 非同期の調停ではない (5c-3b でメッシュ化を同期にしたのと同じ判断)。
 *
 * <h2>⚠ 所有権</h2>
 * 溜まっている {@code MemoryBuffer} は<b>流した側が free する</b>
 * [確認済 — {@code AsyncNodeManager} が同じことをしている]。
 * 忘れると<b>メッシュを作るたびに漏れる</b>。
 */
public final class VkGeometryFlush {
    private VkGeometryFlush() {}

    /** ジオメトリの 1 要素のバイト数 (quad 1 枚) [確認済 — {@code GEOMETRY_ELEMENT_SIZE}]。 */
    public static final long ELEMENT_SIZE = 8;

    /**
     * @param uploaded      流したジオメトリの塊の数
     * @param quadsUploaded 流した quad の数
     * @param metadata      書き直したセクションメタデータの数
     */
    public record Result(int uploaded, long quadsUploaded, int metadata) {}

    /**
     * 溜まっているものを全部流す。
     *
     * <p>⚠ <b>フレームの記録の外で呼んでよい</b> — 書き先はホスト可視である。
     * ただし<b>GPU が読む前に済ませること</b>。
     */
    public static Result flush(BasicAsyncGeometryManager geometry,
                               VkBuffer geometryBuffer, VkBuffer sectionMetadata) {
        int uploaded = 0;
        long quads = 0;

        var uploads = geometry.getUploads();
        if (!uploads.isEmpty()) {
            var iter = uploads.int2ObjectEntrySet().fastIterator();
            while (iter.hasNext()) {
                var e = iter.next();
                // ⚠ 鍵は**要素単位**の位置である。バイトに直すのを忘れると
                // 8 分の 1 の位置へ書き、**他のセクションを黙って壊す**
                long offset = Integer.toUnsignedLong(e.getIntKey()) * ELEMENT_SIZE;
                var src = e.getValue();
                if (offset + src.size > geometryBuffer.size()) {
                    throw new IllegalStateException("geometry upload at element "
                        + e.getIntKey() + " (+" + src.size + "B) exceeds the buffer ("
                        + geometryBuffer.size() + "B)");
                }
                org.lwjgl.system.MemoryUtil.memCopy(src.address, geometryBuffer.addr() + offset, src.size);
                quads += src.size / ELEMENT_SIZE;
                uploaded++;
                src.free();     // ⚠ 流した側が解放する
            }
            uploads.clear();
        }

        // ⚠ 取り除きは Vulkan 側では何もしない。領域は割り当て器が再利用するだけで、
        // GPU 上の中身は次に書かれるまで残る (読まれないので害はない)
        geometry.getHeapRemovals().clear();

        int metadata = 0;
        var updates = geometry.getUpdateIds();
        if (!updates.isEmpty()) {
            var iter = updates.intIterator();
            while (iter.hasNext()) {
                int id = iter.nextInt();
                long offset = (long) id * BasicAsyncGeometryManager.SECTION_METADATA_SIZE;
                if (offset + BasicAsyncGeometryManager.SECTION_METADATA_SIZE > sectionMetadata.size()) {
                    throw new IllegalStateException("section " + id
                        + " is outside the metadata buffer (" + sectionMetadata.size() + "B)");
                }
                geometry.writeMetadata(id, sectionMetadata.addr() + offset);
                metadata++;
            }
            updates.clear();
        }
        return new Result(uploaded, quads, metadata);
    }
}
