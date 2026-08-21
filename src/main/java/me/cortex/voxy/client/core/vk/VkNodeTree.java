package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 5c-4b — <b>形をこちらが決める八分木</b>。
 *
 * <h2>なぜ合成の木を挟むのか</h2>
 * トラバーサルは<b>何を描くかを GPU が選ぶ</b>ので、実データでは出力を予測できない。
 * 予測できないものを対照にすると、
 * <b>機構の誤り</b>と<b>選択の誤り</b>が切り分けられなくなる。
 *
 * <p>木の形を決めてしまえば「この構成なら必ずこれが出る」が言える。
 * 5c-3 で「合成地形 → 実ジオメトリ」と分けたのと<b>同じ構造を木に対して行う</b>
 * [docs/phase5c4-plan.md 3]。
 *
 * <h2>ノードの並び [確認済 — {@code node.glsl} の {@code unpackNode}]</h2>
 * 1 ノード = {@code uvec4} = 16 バイト。
 * <table>
 *   <tr><th>語</th><th>中身</th></tr>
 *   <tr><td>x, y</td><td>位置 ({@code pos_util.glsl} の詰め方 = {@link SyntheticTerrain#packPosition})</td></tr>
 *   <tr><td>z</td><td>{@code meshPtr} (24bit) | {@code flags} の下位 8bit &lt;&lt; 24</td></tr>
 *   <tr><td>w</td><td>{@code childPtr} (24bit) | {@code flags} の上位 8bit &lt;&lt; 24</td></tr>
 * </table>
 *
 * <h2>⚠ 同じ詰め方に<b>ワード順が 2 通り</b>ある</h2>
 * {@code pos_util.glsl} は {@code packedPos.x} = レベル + y + z上位、
 * {@code packedPos.y} = x + z下位 と読む。メモリ上では {@code .x} が先である。
 *
 * <table>
 *   <tr><th></th><th>long の中身</th><th>書き出し</th></tr>
 *   <tr><td>{@link SyntheticTerrain#packPosition}</td><td>下位 = {@code .x}</td>
 *       <td>下位を先に書く (ここ)</td></tr>
 *   <tr><td>{@code NodeStore.nodePosition}</td><td><b>上位 = {@code .x}</b></td>
 *       <td>上位を先に書く</td></tr>
 * </table>
 *
 * <p><b>どちらもメモリ上は同じ並びになる</b>が、long の中では逆である。
 * 片方の書き出し規約でもう片方の long を書くと<b>位置が滅茶苦茶になる</b> —
 * 落ちないし、地形はどこかに描かれる。
 *
 * <p>{@code flags} の意味 [確認済]:
 * <ul>
 *   <li>bit 0 — 要求済み ({@code hasRequested})</li>
 *   <li>bit 2..4 — <b>子の数 - 1</b>。{@code getChildCount} は {@code ((flags>>2)&7)+1}
 *       なので<b>3 ビット幅で 1..8 に収まる</b> — データが壊れても上限がある</li>
 * </ul>
 */
public final class VkNodeTree {
    /** 1 ノードのバイト数。 */
    public static final int NODE_SIZE = 16;

    /** 「無い」を表す番兵 [確認済 — {@code node.glsl}]。 */
    public static final int NULL_NODE = (1 << 24) - 1;
    public static final int NULL_MESH = (1 << 24) - 1;
    /** 子リストが空 / メッシュが空 (どちらも「無い」とは別の状態)。 */
    public static final int EMPTY_QUEUE_ID = (1 << 24) - 2;
    public static final int EMPTY_MESH = (1 << 24) - 2;

    /**
     * @param level    LoD レベル。0 が最も細かい
     * @param meshPtr  描くメッシュ。{@link #NULL_MESH} なら持っていない
     * @param childPtr 子の先頭ノード id。{@link #NULL_NODE} なら葉
     * @param childCount 子の数 (1..8)。{@code childPtr} が有効なときだけ意味を持つ
     */
    public record Node(int level, int x, int y, int z, int meshPtr, int childPtr, int childCount) {}

    private final List<Node> nodes = new ArrayList<>();
    private final List<Integer> topNodes = new ArrayList<>();

    /** ノードを足して id を返す。 */
    public int add(int level, int x, int y, int z, int meshPtr, int childPtr, int childCount) {
        if (childCount < 1 || childCount > 8) {
            throw new IllegalArgumentException("childCount must be 1..8 (3-bit field), got " + childCount);
        }
        this.nodes.add(new Node(level, x, y, z, meshPtr, childPtr, childCount));
        return this.nodes.size() - 1;
    }

    /** 葉 (子を持たず、メッシュを持つ)。 */
    public int addLeaf(int level, int x, int y, int z, int meshPtr) {
        return this.add(level, x, y, z, meshPtr, NULL_NODE, 1);
    }

    /** トラバーサルの入口にする。 */
    public void markTop(int nodeId) { this.topNodes.add(nodeId); }

    public int size() { return this.nodes.size(); }
    public List<Node> nodes() { return List.copyOf(this.nodes); }
    public int[] topNodes() { return this.topNodes.stream().mapToInt(Integer::intValue).toArray(); }
    public Node node(int id) { return this.nodes.get(id); }

    /**
     * ノードの<b>祖先</b>を辿る。{@code -1} で終端。
     *
     * <p>反鎖 (どのノードも他のノードの祖先でない) の検査に使う。
     */
    public int parentOf(int nodeId) {
        for (int i = 0; i < this.nodes.size(); i++) {
            var n = this.nodes.get(i);
            if (n.childPtr() == NULL_NODE) continue;
            if (nodeId >= n.childPtr() && nodeId < n.childPtr() + n.childCount()) return i;
        }
        return -1;
    }

    /** {@code ancestor} が {@code nodeId} の (真の) 祖先か。 */
    public boolean isAncestorOf(int ancestor, int nodeId) {
        for (int p = this.parentOf(nodeId); p != -1; p = this.parentOf(p)) {
            if (p == ancestor) return true;
        }
        return false;
    }

    /** ノードバッファへ書き出す。 */
    public void write(VkBuffer target) {
        long need = (long) this.nodes.size() * NODE_SIZE;
        if (target.size() < need) {
            throw new IllegalArgumentException("node buffer too small: need " + need
                + " got " + target.size());
        }
        long addr = target.addr();
        for (int i = 0; i < this.nodes.size(); i++) {
            var n = this.nodes.get(i);
            long pos = SyntheticTerrain.packPosition(n.x(), n.y(), n.z(), n.level());
            int flags = ((n.childCount() - 1) & 7) << 2;
            long base = addr + (long) i * NODE_SIZE;
            MemoryUtil.memPutInt(base,      (int) pos);
            MemoryUtil.memPutInt(base + 4,  (int) (pos >>> 32));
            MemoryUtil.memPutInt(base + 8,  (n.meshPtr() & 0xFFFFFF) | ((flags & 0xFF) << 24));
            MemoryUtil.memPutInt(base + 12, (n.childPtr() & 0xFFFFFF) | (((flags >> 8) & 0xFF) << 24));
        }
    }

    /** 入口ノードの id を書き出す。 */
    public void writeTopNodes(VkBuffer target) {
        long need = (long) this.topNodes.size() * 4;
        if (target.size() < need) {
            throw new IllegalArgumentException("top node buffer too small: need " + need
                + " got " + target.size());
        }
        for (int i = 0; i < this.topNodes.size(); i++) {
            MemoryUtil.memPutInt(target.addr() + (long) i * 4, this.topNodes.get(i));
        }
    }

    // ---------------- 既知の形 ----------------

    /**
     * <b>1 段だけ降りられる木</b>。根 1 つ + 子 8 つ、全員がメッシュを持つ。
     *
     * <p>根は LoD 1、子は LoD 0 で、根の占める体積を 8 分割する。
     *
     * <h2>これで何が言えるか</h2>
     * <ul>
     *   <li>降りなければ<b>根だけ</b>が描かれる</li>
     *   <li>降りれば<b>子 8 つだけ</b>が描かれる (根は描かれない)</li>
     *   <li>どちらでも<b>反鎖である</b> — 根と子が同時に出たら切り口ではない</li>
     * </ul>
     */
    /**
     * <b>2 段降りられる木</b>。根 1 + 子 8 + 孫 64。全員がメッシュを持つ。
     *
     * <p>LoD が距離に対して単調かを見るのに使う —
     * <b>切り口が 3 段のどこにでも落ちうる</b>ので、
     * 「常に根」「常に葉」では通らない構成になる。
     */
    public static VkNodeTree twoLevelOctree(int level, int x, int y, int z) {
        var t = new VkNodeTree();
        int root = t.add(level, x, y, z, 0, 1, 8);            // 子は id 1..8
        for (int i = 0; i < 8; i++) {
            int cx = (x << 1) | (i & 1), cy = (y << 1) | ((i >> 1) & 1), cz = (z << 1) | ((i >> 2) & 1);
            // 孫は id 9 + i*8 .. 9 + i*8 + 7
            t.add(level - 1, cx, cy, cz, 1 + i, 9 + i * 8, 8);
        }
        for (int i = 0; i < 8; i++) {
            int cx = (x << 1) | (i & 1), cy = (y << 1) | ((i >> 1) & 1), cz = (z << 1) | ((i >> 2) & 1);
            for (int j = 0; j < 8; j++) {
                t.addLeaf(level - 2, (cx << 1) | (j & 1), (cy << 1) | ((j >> 1) & 1),
                    (cz << 1) | ((j >> 2) & 1), 9 + i * 8 + j);
            }
        }
        t.markTop(root);
        return t;
    }

    /** ノード id からその LoD レベル。 */
    public int levelOf(int nodeId) { return this.nodes.get(nodeId).level(); }

    public static VkNodeTree oneLevelOctree(int level, int x, int y, int z) {
        var t = new VkNodeTree();
        int root = t.add(level, x, y, z, 0, 1, 8);   // 子は id 1..8
        for (int i = 0; i < 8; i++) {
            t.addLeaf(level - 1, (x << 1) | (i & 1), (y << 1) | ((i >> 1) & 1), (z << 1) | ((i >> 2) & 1),
                1 + i);
        }
        t.markTop(root);
        return t;
    }
}
