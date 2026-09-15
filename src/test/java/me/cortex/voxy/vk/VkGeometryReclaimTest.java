package me.cortex.voxy.vk;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.core.rendering.ISectionWatcher;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.vk.VkHierarchicalScene.GeometryReclaimer;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6: <b>{@code GeometryReclaimer}</b> — ジオメトリ回収。装置不要 (GPU を要らない)。
 *
 * <h2>なぜこれが要るのか</h2>
 * 5c-5a はジオメトリ領域が満杯になると<b>止まったまま二度と再開しない</b>
 * ({@code MESHING STOPPED}) 仕様だった。プレイヤーが動き回る実運用では
 * これはいずれ必ず起きる。GL 版 {@code NodeCleaner} を読んで分かったこと
 * (詳細は {@code GeometryReclaimer} の javadoc):
 * <ul>
 *   <li>回収の基準は<b>「最近描かれたか」ではなく「確保されてから何フレーム経ったか」</b></li>
 *   <li>トップレベルノードは<b>候補選びの時点で除外する</b> — 最初は
 *       「{@code removeNodeGeometry} 自身が拒否するので二重に持たなくてよい」と
 *       考えたが誤りで、その防御は {@code NODE_TYPE_LEAF} のときにしか効かない。
 *       ここに書いた装置なしテストがまさにその誤りを暴いた</li>
 * </ul>
 *
 * <p>ここでは 2 つとも<b>壊して確かめる</b> [規約 11]。
 */
public class VkGeometryReclaimTest {
    /** 監視状態だけを持つ最小の実装 ({@code NodeIntegrityTest} と同じ形)。 */
    private static final class InMemoryWatcher implements ISectionWatcher {
        final Long2IntOpenHashMap watched = new Long2IntOpenHashMap();
        @Override public boolean watch(long position, int types) {
            this.watched.put(position, this.watched.get(position) | types);
            return true;
        }
        @Override public boolean unwatch(long position, int types) {
            int v = this.watched.get(position) & ~types;
            if (v == 0) this.watched.remove(position); else this.watched.put(position, v);
            return true;
        }
        @Override public int get(long position) { return this.watched.get(position); }
    }

    private record Harness(NodeManager nodes, GeometryReclaimer reclaimer,
                           BasicAsyncGeometryManager geometry, IntOpenHashSet topLevelIds) {}

    private static Harness harness() {
        var geometry = new BasicAsyncGeometryManager(64, 1 << 20);
        var nodes = new NodeManager(64, geometry, new InMemoryWatcher());
        var topLevelIds = new IntOpenHashSet();
        // ⚠ VkHierarchicalScene と同じ形: トップレベル判定は setTLNCallbacks が
        // 追跡する集合をそのまま使う。GeometryReclaimer 自身は持たない
        nodes.setTLNCallbacks(topLevelIds::add, topLevelIds::remove);
        var reclaimer = new GeometryReclaimer(nodes, topLevelIds::contains);
        nodes.setClear(reclaimer);
        return new Harness(nodes, reclaimer, geometry, topLevelIds);
    }

    private static long topPos(int x, int y, int z) {
        return WorldEngine.getWorldSectionId(WorldEngine.MAX_LOD_LAYER, x, y, z);
    }

    /**
     * 最上位ノードの<b>子の位置</b>。
     *
     * <p>{@code NodeManager.makeChildPos} と同じ規約 [確認済 — 該当メソッドの本体]:
     * {@code addin} のビット 0 が x、ビット 1 が z、ビット 2 が y に対応する。
     * {@code private} なのでここで同じ式を再現する。
     */
    private static long childPos(long parent, int addin) {
        int lvl = WorldEngine.getLevel(parent);
        return WorldEngine.getWorldSectionId(lvl - 1,
            (WorldEngine.getX(parent) << 1) | (addin & 1),
            (WorldEngine.getY(parent) << 1) | ((addin >> 2) & 1),
            (WorldEngine.getZ(parent) << 1) | ((addin >> 1) & 1));
    }

    /** 中身のあるセクションを 1 つ作る。{@code quads} 個の quad を持つ、子は無し。 */
    private static BuiltSection section(long pos, int quads) {
        return section(pos, quads, (byte) 0);
    }

    /**
     * @param childExistence 子の有無ビット (ビット {@code i} = 子 {@code i} が存在)
     *                       [確認済 — {@code NodeManager} 各所の {@code (childExistence & (1<<i))}]。
     *                       ⚠ <b>0 のままだと子リクエストが 1 つも作られない</b> —
     *                       最初そう書いて「子に渡したジオメトリが領域を消費しない」
     *                       という形で踏んだ (子の解決先が無く黙って捨てられていた)
     */
    private static BuiltSection section(long pos, int quads, byte childExistence) {
        var geo = new MemoryBuffer(8L * quads);
        for (int i = 0; i < quads; i++) {
            org.lwjgl.system.MemoryUtil.memPutLong(geo.address + i * 8L, 1L);
        }
        int[] offsets = new int[8];   // 全部 0 = 先頭のバケツに全 quad
        offsets[7] = quads;
        return new BuiltSection(pos, childExistence, 0, geo, offsets, null);
    }

    /** <b>対照</b>: 何も確保していなければ回収は何もしない。 */
    @Test
    void reclaimingWithNothingAllocatedDoesNothing() {
        var h = harness();
        int attempts = h.reclaimer().reclaimWhile(() -> true, 10);
        assertEquals(0, attempts, "there is nothing to reclaim yet");
        assertEquals(0, h.reclaimer().totalReclaimed());
    }

    /**
     * <b>alloc/free の配線が正しいこと。</b>
     *
     * <p>これが壊れていれば以降の全ての主張が意味を持たない —
     * 最初に確かめる。
     */
    @Test
    void allocAndFreeAreTrackedThroughNodeManager() {
        var h = harness();
        long a = topPos(0, 0, 0), b = topPos(1, 0, 0);
        h.nodes().insertTopLevelNode(a);
        h.nodes().insertTopLevelNode(b);
        assertEquals(0, h.reclaimer().trackedCount(),
            "a pending REQUEST is not yet an allocated node; alloc() must not have fired");

        h.nodes().processGeometryResult(section(a, 1));
        h.nodes().processGeometryResult(section(b, 1));
        assertEquals(2, h.reclaimer().trackedCount(), "two nodes resolved, alloc() must have fired twice");

        h.nodes().removeTopLevelNode(a);
        assertEquals(1, h.reclaimer().trackedCount(), "removing a top-level node must fire free()");
    }

    /**
     * <b>⚠⚠ 本命の対照</b>: トップレベルノードは<b>候補にすら挙がらない</b>こと。
     *
     * <h2>これが壊れていたのは実機ではなく、この装置なしテストだった</h2>
     * 最初は「トップレベルが選ばれても {@code removeNodeGeometry} が守るので安全」
     * という設計で、<b>葉のままのトップレベル 1 個だけ</b>で試して通った。
     * それだけで安全と判断したのが甘かった — 子ができて {@code NODE_TYPE_INNER} に
     * なった場合の確認をしていなかった。ここでは<b>両方向</b>を同時に置く:
     * より古い<b>トップレベル T</b> (子あり) と、より新しい<b>非トップレベル C</b>。
     *
     * <ul>
     *   <li>T が古いのに<b>選ばれない</b> → 候補選びで除外している証拠</li>
     *   <li>C が新しいのに<b>選ばれる</b> → 「最古」だけで選んでいるのではなく、
     *       除外が「最古かどうか」の判定より前に効いている証拠</li>
     * </ul>
     */
    @Test
    void topLevelNodesAreNeverAttemptedEvenWhenTheyAreOldest() {
        var h = harness();
        long t = topPos(0, 0, 0);
        h.reclaimer().setCurrentFrame(1);
        h.nodes().insertTopLevelNode(t);
        // 子 0 の存在を宣言する。これが無いと T はずっと葉のままで、
        // この対照が確かめたい「INNER になったトップレベル」を再現できない
        h.nodes().processGeometryResult(section(t, 3, (byte) 1));
        h.nodes().processRequest(t);   // T を子リクエストへ変換 → NODE_TYPE_INNER になる
        long tBytes = h.geometry().getGeometryUsedBytes();

        long c = childPos(t, 0);
        h.reclaimer().setCurrentFrame(2);   // C は T よりずっと新しい
        h.nodes().processGeometryResult(section(c, 1));
        assertEquals(2, h.reclaimer().trackedCount(), "T and C must both be tracked");

        int attempts = h.reclaimer().reclaimWhile(() -> true, 1);

        assertEquals(1, attempts, "the only non-top-level candidate must have been attempted");
        assertNotEquals(0, h.reclaimer().lastAttemptedId(),
            "id 0 is T [confirmed: NodeStore.allocate() hands out 0 first from an empty set]."
                + " If id 0 was attempted, the top-level exclusion did not fire — a newer,"
                + " non-top-level node was skipped in favour of an older, protected one");
        assertEquals(tBytes, h.geometry().getGeometryUsedBytes(),
            "C must actually have been freed, dropping usage back to just T's bytes");
        assertEquals(1, h.reclaimer().trackedCount(), "only T should still be tracked as allocated");
    }

    /**
     * ⚠ <b>対照</b>: 回収の上限に当たったら、それ以上は試みないこと。
     *
     * <p>5 個の非トップレベル候補 (別々のトップレベル親を持つ 5 つの子) を用意し、
     * <b>{@code full} が常に真</b>のまま {@code maxEvictions=3} で呼ぶ。
     * 候補が足りているのに 3 で止まることを確かめる —
     * <b>無限ループにならないこと</b>と<b>候補が尽きる前に止まれること</b>の両方。
     */
    @Test
    void reclaimStopsAtTheCapEvenWithCandidatesRemaining() {
        var h = harness();
        int frame = 1;
        for (int i = 0; i < 5; i++) {
            long parent = topPos(i, 0, 0);
            h.reclaimer().setCurrentFrame(frame++);
            h.nodes().insertTopLevelNode(parent);
            h.nodes().processGeometryResult(section(parent, 1, (byte) 1));
            h.nodes().processRequest(parent);
            h.reclaimer().setCurrentFrame(frame++);
            h.nodes().processGeometryResult(section(childPos(parent, 0), 1));
        }
        assertEquals(10, h.reclaimer().trackedCount(), "5 parents + 5 children");

        int attempts = h.reclaimer().reclaimWhile(() -> true, 3);

        assertEquals(3, attempts, "must stop exactly at the cap, not spin forever, and not"
            + " stop early just because candidates existed");
        assertEquals(3, h.reclaimer().totalReclaimed());
        assertEquals(7, h.reclaimer().trackedCount(),
            "exactly 3 children must have actually been freed (10 - 3 = 7 remain tracked)");
    }
}
