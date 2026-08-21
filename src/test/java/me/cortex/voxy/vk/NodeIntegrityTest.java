package me.cortex.voxy.vk;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.core.rendering.ISectionWatcher;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code NodeManager.verifyIntegrity()} が本当に検査しているのか</b> (Phase 5c-4c)。
 *
 * <h2>なぜ確かめるのか</h2>
 * これは<b>上流が用意した不変条件検査</b>なので、こちらの反鎖検査とは
 * 独立した根拠になる。ただし<b>名前だけで信用してはならない</b> —
 * 中身が空だったり、弱い主張だったりする可能性がある。
 *
 * <p><b>通ることではなく、壊したときに落ちることを確かめる</b> [規約 11]。
 *
 * <h2>⚠ 分かったこと</h2>
 * 中身は実質的だった (上から辿って、位置集合・ノード数・要求数・最上位ノード集合を
 * <b>両方向で</b>突き合わせる) が、<b>例外に一切メッセージが無い</b> —
 * 素の {@code new IllegalStateException()} である。
 * 発火しても<b>どの不変条件が壊れたか分からない</b>。
 */
public class NodeIntegrityTest {
    /** 監視状態だけを持つ最小の実装。 */
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

    private record Harness(NodeManager nodes, InMemoryWatcher watcher,
                           BasicAsyncGeometryManager geometry) {}

    private static Harness harness() {
        var watcher = new InMemoryWatcher();
        // ⚠ BasicAsyncGeometryManager は **GL 非依存** である
        // [確認済 — クラス自身が "the underlying store is irrelevant" と書いている]
        var geometry = new BasicAsyncGeometryManager(1024, 1 << 20);
        return new Harness(new NodeManager(4096, geometry, watcher), watcher, geometry);
    }

    /** 最上位の位置。{@code MAX_LOD_LAYER} で、座標はレベルぶんの整列が要る。 */
    private static long topPos(int x, int y, int z) {
        return WorldEngine.getWorldSectionId(WorldEngine.MAX_LOD_LAYER, x, y, z);
    }

    /** 中身のあるセクションを 1 つ作る (quad 1 枚)。 */
    private static BuiltSection section(long pos) {
        var geo = new MemoryBuffer(8);
        org.lwjgl.system.MemoryUtil.memPutLong(geo.address, 1L);
        int[] offsets = new int[8];   // 全部 0 = 先頭のバケツに 1 枚
        return new BuiltSection(pos, (byte) 0, 0, geo, offsets, null);
    }

    /** <b>対照</b>: 素直に組み立てたら通ること (弾きすぎていない)。 */
    @Test
    void aHealthyTreePassesIntegrity() {
        var h = harness();
        long a = topPos(0, 0, 0), b = topPos(1, 0, 0);
        h.nodes().insertTopLevelNode(a);
        h.nodes().insertTopLevelNode(b);
        assertDoesNotThrow(() -> h.nodes().verifyIntegrity(), "two pending top-level requests are valid");

        h.nodes().processGeometryResult(section(a));
        h.nodes().processGeometryResult(section(b));
        assertDoesNotThrow(() -> h.nodes().verifyIntegrity(), "two resolved top-level nodes are valid");
    }

    /**
     * <b>監視が外れたら落ちること。</b>
     *
     * <p>{@code verifyNode} は「監視器は常にそのノードを見ていなければならない」を
     * 主張している。<b>これが実際に効くのかを壊して確かめる</b>。
     */
    @Test
    void dropTheWatchAndIntegrityFails() {
        var h = harness();
        long a = topPos(0, 0, 0);
        h.nodes().insertTopLevelNode(a);
        h.nodes().processGeometryResult(section(a));
        assertDoesNotThrow(() -> h.nodes().verifyIntegrity(), "control: it passes before we break it");

        h.watcher().watched.remove(a);          // 監視器だけを壊す
        assertThrows(IllegalStateException.class, () -> h.nodes().verifyIntegrity(),
            "verifyIntegrity did not notice that the watcher stopped watching a live node"
                + " — it is weaker than its name suggests");
    }

    /**
     * <b>知らない位置を監視していても、それ自体は落とさないこと。</b>
     *
     * <p>⚠ {@code verifyIntegrity()} は引数なしだと<b>監視集合を突き合わせない</b>
     * [確認済 — {@code watchingPosSet != null} のときだけ見る]。
     * <b>弾きすぎていないこと</b>と<b>引数つきなら捕まえること</b>を両方言う。
     */
    @Test
    void theWatchSetIsOnlyCheckedWhenItIsHandedIn() {
        var h = harness();
        long a = topPos(0, 0, 0);
        h.nodes().insertTopLevelNode(a);
        h.nodes().processGeometryResult(section(a));

        h.watcher().watch(topPos(9, 9, 9), 1);   // 余計な監視
        assertDoesNotThrow(() -> h.nodes().verifyIntegrity(),
            "the no-argument form does not compare the watch set");

        var extra = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(h.watcher().watched.keySet());
        assertThrows(IllegalStateException.class, () -> h.nodes().verifyIntegrity(extra, null),
            "handing in the watch set must catch a position nothing is using");
    }

    /**
     * <b>知っているノード集合と食い違ったら落ちること。</b>
     * こちらの反鎖検査が扱うのと同じ「ノード id の集合」を、上流の側から突き合わせる。
     */
    @Test
    void aNodeSetThatDisagreesIsCaught() {
        var h = harness();
        long a = topPos(0, 0, 0);
        h.nodes().insertTopLevelNode(a);
        h.nodes().processGeometryResult(section(a));

        var wrong = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        wrong.add(4095);                        // 存在しないノード
        assertThrows(IllegalStateException.class, () -> h.nodes().verifyIntegrity(null, wrong),
            "a node set that does not match must be rejected");
    }
}
