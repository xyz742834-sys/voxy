package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkGeometryFlush;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>溜まったジオメトリが正しい位置へ流れること</b> (Phase 5c-4c)。
 *
 * <h2>⚠ 罠は<b>要素単位とバイト単位</b></h2>
 * {@code getUploads()} の鍵は<b>要素 (quad) 単位</b>の位置である。
 * バイトに直し忘れると <b>8 分の 1 の位置へ書き、他のセクションを黙って壊す</b>。
 * 落ちないし、地形は「どこかが変」になるだけである。
 *
 * <p>期待値は<b>literal の {@code * 8}</b> で書く — 実装の定数を引くと
 * <b>両方が同じだけずれて一致する</b> [規約 4 / 失敗例 19]。
 */
public class VkGeometryFlushTest {
    @BeforeAll
    static void setup() { VulkanTestSupport.requireVulkan(); }

    /** 中身が識別できるセクションを作る。 */
    private static BuiltSection section(long pos, int quads, long fill) {
        var geo = new MemoryBuffer(quads * 8L);
        for (int i = 0; i < quads; i++) MemoryUtil.memPutLong(geo.address + i * 8L, fill + i);
        return new BuiltSection(pos, (byte) 0, 0, geo, new int[8], null);
    }

    @Test
    void uploadsLandAtTheirElementOffsetTimesEight() {
        var geom = new BasicAsyncGeometryManager(1024, 1 << 20);
        var geometry = new VkBuffer(1 << 20);
        var metadata = new VkBuffer(1024L * 32);
        try {
            int idA = geom.uploadSection(section(WorldEngine.getWorldSectionId(0, 0, 0, 0), 3, 0x1000L));
            int idB = geom.uploadSection(section(WorldEngine.getWorldSectionId(0, 1, 0, 0), 5, 0x2000L));

            // 流す前に「どこへ置かれるはずか」を控える
            var expected = new java.util.HashMap<Integer, long[]>();
            var it = geom.getUploads().int2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                var e = it.next();
                int n = (int) (e.getValue().size / 8);
                var vals = new long[n];
                for (int i = 0; i < n; i++) vals[i] = MemoryUtil.memGetLong(e.getValue().address + i * 8L);
                expected.put(e.getIntKey(), vals);
            }
            assertEquals(2, expected.size(), "both sections must be pending");

            var r = VkGeometryFlush.flush(geom, geometry, metadata);
            assertEquals(2, r.uploaded());
            assertEquals(8, r.quadsUploaded(), "three quads plus five");
            assertEquals(2, r.metadata(), "both sections need their metadata written");

            for (var e : expected.entrySet()) {
                // ⚠ literal の * 8。実装の ELEMENT_SIZE を引かない
                long base = geometry.addr() + Integer.toUnsignedLong(e.getKey()) * 8L;
                for (int i = 0; i < e.getValue().length; i++) {
                    assertEquals(e.getValue()[i], MemoryUtil.memGetLong(base + i * 8L),
                        "element offset " + e.getKey() + " quad " + i
                            + ": the upload did not land at offset*8");
                }
            }

            // ⚠ メタデータは **中身まで**見る。「0 でない」だけでは
            // 刻みを 32 から 16 にずらす変異が素通りした (実測)
            //
            // 先頭 8 バイトは位置で、**上位ワードが先** [SectionMeta.writeMetadataSplitParts]。
            // これは packPosition の下位ワード (px) に当たる
            // [VkRealPositionTest.theWorldKeyIsPackPositionWithItsWordsSwapped]
            long[] positions = {
                WorldEngine.getWorldSectionId(0, 0, 0, 0),
                WorldEngine.getWorldSectionId(0, 1, 0, 0)};
            int[] ids = {idA, idB};
            for (int k = 0; k < ids.length; k++) {
                long base = metadata.addr() + (long) ids[k] * 32;   // literal の 32
                assertEquals((int) (positions[k] >>> 32), MemoryUtil.memGetInt(base),
                    "section " + ids[k] + ": the high word of the position must be first at id*32");
                assertEquals((int) positions[k], MemoryUtil.memGetInt(base + 4),
                    "section " + ids[k] + ": the low word must follow it");
            }

            // 流したら空になること (二重に流さない)
            assertTrue(geom.getUploads().isEmpty(), "uploads must be consumed");
            assertTrue(geom.getUpdateIds().isEmpty(), "metadata updates must be consumed");
            assertEquals(0, VkGeometryFlush.flush(geom, geometry, metadata).uploaded(),
                "a second flush must have nothing to do");
        } finally {
            geometry.free();
            metadata.free();
        }
    }

    /**
     * <b>バッファに入らない位置は黙って書かないこと。</b>
     *
     * <p>範囲外書き込みは<b>バリデーションが捕まえない</b> — 隣を壊すだけである。
     */
    @Test
    void anUploadThatDoesNotFitIsRejected() {
        var geom = new BasicAsyncGeometryManager(1024, 1 << 20);
        var tiny = new VkBuffer(64);            // 8 quad ぶんしかない
        var metadata = new VkBuffer(1024L * 32);
        try {
            geom.uploadSection(section(WorldEngine.getWorldSectionId(0, 0, 0, 0), 64, 1));
            var e = assertThrows(IllegalStateException.class,
                () -> VkGeometryFlush.flush(geom, tiny, metadata));
            assertTrue(e.getMessage().contains("exceeds the buffer"),
                "must say what went wrong: " + e.getMessage());
        } finally {
            tiny.free();
            metadata.free();
        }
    }

    /**
     * <b>対照</b>: 要素単位のままバイトとして扱うと<b>実際に食い違う</b>こと。
     *
     * <p>これが無いと、上の検査は「たまたま 0 番地に置かれた」場合に空虚になる。
     */
    @Test
    void theElementOffsetIsNotAlreadyAByteOffset() {
        var geom = new BasicAsyncGeometryManager(1024, 1 << 20);
        try {
            geom.uploadSection(section(WorldEngine.getWorldSectionId(0, 0, 0, 0), 200, 1));
            geom.uploadSection(section(WorldEngine.getWorldSectionId(0, 1, 0, 0), 200, 2));
            int[] keys = geom.getUploads().keySet().toIntArray();
            Arrays.sort(keys);
            assertTrue(keys.length == 2 && keys[1] > 0,
                "the second section must be placed away from zero, otherwise the"
                    + " element-versus-byte distinction is untested: " + Arrays.toString(keys));
        } finally {
            var it = geom.getUploads().int2ObjectEntrySet().fastIterator();
            while (it.hasNext()) it.next().getValue().free();
        }
    }
}
