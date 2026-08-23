package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.VkHierarchicalScene.TopLevelNodeQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5c-5a: <b>最上位ノード id のキュー</b>。トラバーサルの入口である。
 *
 * <h2>⚠ この検査が守っているもの</h2>
 * トラバーサルの 0 回目は {@code topNodeIds} をソースキューとして読む。
 * <b>誰も書かなければゼロのまま</b>で、{@code reset(n)} が n を渡しても
 * <b>ノード id 0 を n 回訪問する</b>だけになる。
 *
 * <p>⚠ <b>これは落ちない。</b> id 0 がたまたま実在の最上位ノードなら、
 * <b>そこから降りた分だけ絵が出る</b> — 27 個の根のうち 1 個で動いていても
 * 動いているように見える。実際にそうなっていた。
 *
 * <p>したがって主張は「何か書かれた」ではなく
 * <b>「[0, count) が追加した集合とちょうど一致する」</b>である [規約 「何か入っている」は主張ではない]。
 */
public class VkTopLevelQueueTest {
    /** 書き出しを記録するだけの受け皿。装置が要らない。 */
    private static final class Recording implements TopLevelNodeQueue.Sink {
        final int[] slots;
        final List<String> writes = new ArrayList<>();
        Recording(int capacity) { this.slots = new int[capacity]; Arrays.fill(this.slots, -1); }
        @Override public void write(int index, int nodeId) {
            this.slots[index] = nodeId;
            this.writes.add(index + "<-" + nodeId);
        }
    }

    /** バッファの [0, count) を集合として読む。 */
    private static HashSet<Integer> live(Recording r, TopLevelNodeQueue q) {
        var out = new HashSet<Integer>();
        for (int i = 0; i < q.count(); i++) out.add(r.slots[i]);
        return out;
    }

    /** <b>追加したものが順に書き出される。</b> */
    @Test
    void addingWritesEachIdIntoItsSlot() {
        var r = new Recording(8);
        var q = new TopLevelNodeQueue(8, r);
        q.add(41); q.add(7); q.add(19);
        assertEquals(3, q.count());
        assertEquals(List.of("0<-41", "1<-7", "2<-19"), r.writes);
        assertEquals(new HashSet<>(List.of(41, 7, 19)), live(r, q));
    }

    /**
     * <b>途中を消すと末尾が空いた位置へ移る</b> (GL 版 {@code remTLN} と同じ)。
     *
     * <p>⚠ 主張は<b>集合が正しいこと</b>である。並び順は決めていない。
     */
    @Test
    void removingFromTheMiddleMovesTheTailIntoTheGap() {
        var r = new Recording(8);
        var q = new TopLevelNodeQueue(8, r);
        q.add(41); q.add(7); q.add(19);
        q.remove(7);
        assertEquals(2, q.count());
        assertEquals(new HashSet<>(List.of(41, 19)), live(r, q),
            "the live range must hold exactly the remaining ids");
    }

    /** 末尾を消すときは何も書かない (詰めるものが無い)。 */
    @Test
    void removingTheTailWritesNothing() {
        var r = new Recording(8);
        var q = new TopLevelNodeQueue(8, r);
        q.add(41); q.add(7);
        int before = r.writes.size();
        q.remove(7);
        assertEquals(1, q.count());
        assertEquals(before, r.writes.size(), "removing the last slot needs no write");
        assertEquals(new HashSet<>(List.of(41)), live(r, q));
    }

    /**
     * ⚠⚠ <b>これが本命である。</b> 追加と削除を混ぜても
     * <b>[0, count) が生きている集合とちょうど一致し、重複が無い</b>こと。
     *
     * <p>「ゼロのまま」も「同じ id が並ぶ」も、この主張で落ちる。
     * <b>数が合っているだけでは通らない</b>。
     */
    @Test
    void theLiveRangeAlwaysEqualsTheLiveSet() {
        var r = new Recording(16);
        var q = new TopLevelNodeQueue(16, r);
        var expected = new HashSet<Integer>();
        // 決め打ちの手順 (乱数は使わない — 落ちたときに再現できないため)
        int[] adds = {5, 9, 2, 14, 3, 11};
        for (int id : adds) { q.add(id); expected.add(id); }
        int[] removes = {2, 14, 5};
        for (int id : removes) { q.remove(id); expected.remove(id); }
        q.add(30); expected.add(30);
        q.remove(11); expected.remove(11);

        assertEquals(expected.size(), q.count(), "count must track the live set");
        var seen = new HashSet<Integer>();
        for (int i = 0; i < q.count(); i++) {
            assertTrue(seen.add(q.idAt(i)), "slot " + i + " repeats id " + q.idAt(i)
                + "; a repeated id means the traversal visits the same root twice"
                + " and misses another one entirely");
        }
        assertEquals(expected, seen, "the live range must equal the live set");
        assertEquals(expected, live(r, q), "and the same must be true of what was written out");
    }

    /** ⚠ 同じ id を 2 度足すのは誤り。黙って通すと根が重複する。 */
    @Test
    void addingTheSameIdTwiceIsAMistake() {
        var q = new TopLevelNodeQueue(8, new Recording(8));
        q.add(3);
        assertThrows(IllegalStateException.class, () -> q.add(3));
    }

    /** ⚠ 知らない id を消すのも誤り。 */
    @Test
    void removingAnIdThatWasNeverAddedIsAMistake() {
        var q = new TopLevelNodeQueue(8, new Recording(8));
        q.add(3);
        assertThrows(IllegalStateException.class, () -> q.remove(4));
    }

    /** ⚠ 容量を超えたら<b>黙って捨てない</b>。捨てると根が消えて絵に穴が開く。 */
    @Test
    void overflowingTheCapacityIsReported() {
        var q = new TopLevelNodeQueue(2, new Recording(2));
        q.add(1); q.add(2);
        assertThrows(IllegalStateException.class, () -> q.add(3));
    }
}
