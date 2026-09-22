package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 実機クラッシュ (2026-09-22): <b>"Geometry OOM"</b>。装置不要。
 *
 * <h2>何が起きたか</h2>
 * {@code VkHierarchicalScene.acceptGeometry} は回収の要否・成否を
 * {@code getGeometryUsedBytes() + need <= capacity} という<b>合計バイトの比較</b>
 * だけで判定していた。しかし {@code AllocationArena}
 * ({@code BasicAsyncGeometryManager} が内部で使う確保器) は<b>size 以上の
 * 単一の連続空きブロックが1つ要る</b>方式であって、「空き容量の合計」では
 * 動かない。回収は最古のノードから<b>順に</b>消すだけで、それらがヒープ上で
 * 隣接している保証は無いので、<b>断片化した小さい穴</b>ばかりができうる —
 * 合計は足りていても、要求サイズの連続ブロックが1つも無ければ
 * {@code BasicAsyncGeometryManager.createMeta} が
 * {@code IllegalStateException("Geometry OOM...")} を投げて実機が落ちた。
 *
 * <p>ここでは<b>まさにこの状況を人工的に作り</b>、
 * (1) 素朴な合計チェックなら「入る」と誤判定すること、
 * (2) 実際の確保 ({@code uploadSection}) は本当に失敗すること、
 * (3) 新設した {@link BasicAsyncGeometryManager#canFit} は正しく「入らない」と
 * 判定すること、
 * (4) 隣接した空きが併合されて入るようになれば {@code canFit} も {@code true} に
 * 変わること、
 * を確かめる。
 */
public class VkGeometryFragmentationTest {
    private static final long ELEMENT = 8; // GEOMETRY_ELEMENT_SIZE

    /** {@code quads} 個の quad を持つ、子は無いセクション。 */
    private static BuiltSection section(long pos, int quads) {
        var geo = new MemoryBuffer(ELEMENT * quads);
        for (int i = 0; i < quads; i++) {
            MemoryUtil.memPutLong(geo.address + i * ELEMENT, 1L);
        }
        int[] offsets = new int[8];
        offsets[7] = quads;
        return new BuiltSection(pos, (byte) 0, 0, geo, offsets, null);
    }

    /**
     * ⚠⚠ 本命。断片化した空きの合計は足りているのに、
     * <b>連続ブロックが無いので実際の確保は失敗する</b>ことを確かめる。
     */
    @Test
    void fragmentedFreeSpaceCanFailEvenWhenTheAggregateIsEnough() {
        // 容量 = 1024 要素 (8 セクション分、各 128 要素)
        long capacityBytes = 1024 * ELEMENT;
        var geometry = new BasicAsyncGeometryManager(64, capacityBytes);

        // 8 セクションで満杯にする。1 quad ずつだが 127 要素単位に切り上がるので
        // どれも 128 要素を取る [確認済 — createMeta の upsized]
        int[] ids = new int[8];
        for (int i = 0; i < 8; i++) {
            ids[i] = geometry.uploadSection(section(i, 1));
        }
        assertEquals(1024 * ELEMENT, geometry.getGeometryUsedBytes(), "8 x 128 要素で満杯のはず");

        // 偶数番目だけ解放する。奇数番目が間に残るので、できる穴は互いに
        // 隣接せず、4 個の孤立した 128 要素の穴になる (合計 512 要素分)
        geometry.removeSection(ids[0]);
        geometry.removeSection(ids[2]);
        geometry.removeSection(ids[4]);
        geometry.removeSection(ids[6]);
        assertEquals(512 * ELEMENT, geometry.getGeometryUsedBytes(), "4 セクション分が空いたはず");

        // 256 要素 (2048 バイト) を要求する — 129 quad は (129+127)&~127=256 要素に
        // 切り上がる。単一の穴 (128 要素) には入らない
        long need = ((129 + 127) & ~127L) * ELEMENT;
        assertEquals(256 * ELEMENT, need);

        // ⚠ 素朴な合計チェックなら「入る」と誤判定する: 512(空き)+256(要求) = 768 <= 1024
        assertTrue(geometry.getGeometryUsedBytes() + need <= capacityBytes,
            "対照: 素朴な合計チェックはここで誤って '入る' と判定する");

        // 新設した canFit は正しく「入らない」と判定するはず
        assertFalse(geometry.canFit(need),
            "128 要素の孤立した穴が 4 つあるだけで、256 要素の連続ブロックは無い");

        // 実際に確保を試みると、本当に実機と同じ例外で落ちることを確認する
        // (canFit の予測が絵空事でないことの検証)
        var big = section(100, 129);
        try {
            assertThrows(IllegalStateException.class, () -> geometry.uploadSection(big),
                "canFit(false) の予測どおり、実際の確保も失敗するはず");
        } finally {
            // uploadSection が失敗時に geometryBuffer を消費しているかは実装依存なので
            // ここでは明示的に握り潰さない — MemoryBuffer のリークは別懸念
        }
    }

    /**
     * <b>対照</b>: 隣接した空きは併合されるので、併合後なら {@code canFit} は
     * {@code true} に変わり、実際の確保も成功する。
     */
    @Test
    void adjacentFreesMergeAndBecomeFittable() {
        long capacityBytes = 1024 * ELEMENT;
        var geometry = new BasicAsyncGeometryManager(64, capacityBytes);

        int[] ids = new int[8];
        for (int i = 0; i < 8; i++) {
            ids[i] = geometry.uploadSection(section(i, 1));
        }

        // 末尾 2 つ (隣接、かつヒープの一番上) を解放する -> AllocationArena が
        // 併合し、256 要素の連続ブロックになる [確認済 — AllocationArena.free の
        // 「次が無ければヒープを縮める」分岐]
        geometry.removeSection(ids[7]);
        geometry.removeSection(ids[6]);

        long need = ((129 + 127) & ~127L) * ELEMENT;
        assertTrue(geometry.canFit(need), "隣接した 2 x 128 要素は併合されて 256 要素の連続ブロックになるはず");

        int newId = geometry.uploadSection(section(200, 129));
        assertTrue(newId >= 0, "canFit が true と言った以上、実際の確保も成功しなければならない");
    }

    /** {@link AllocationArena} 単体でも同じ性質を確かめる (より低レベルの対照)。 */
    @Test
    void allocationArenaCanAllocMatchesActualAllocOutcome() {
        var arena = new AllocationArena();
        arena.setLimit(1024);

        long[] addr = new long[8];
        for (int i = 0; i < 8; i++) {
            addr[i] = arena.alloc(128);
            assertNotEquals(AllocationArena.SIZE_LIMIT, addr[i]);
        }

        arena.free(addr[0]);
        arena.free(addr[2]);
        arena.free(addr[4]);
        arena.free(addr[6]);

        assertFalse(arena.canAlloc(256), "孤立した 128 要素の穴が 4 つあるだけ");
        assertEquals(AllocationArena.SIZE_LIMIT, arena.alloc(256),
            "canAlloc(false) は実際の alloc() 失敗と一致しなければならない");

        // 逆に、実際に空いている 128 要素ぴったりなら両方成功するはず
        assertTrue(arena.canAlloc(128));
        assertNotEquals(AllocationArena.SIZE_LIMIT, arena.alloc(128));
    }
}
