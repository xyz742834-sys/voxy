package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * ジオメトリの<b>不変条件</b> — 絵の正しさとは独立に成立するもの (Phase 5c-3b)。
 *
 * <h2>なぜ要るのか — 規約 1 が「保証」から「要求」に変わる</h2>
 * 合成データは<b>各 quad が別々の可視位置を占める</b>ことを構造で保証していた。
 * 実ジオメトリでは位置が実データになるので<b>その保証は失われる</b>。
 * 代わりに「壊れていない」ことの下限を、<b>絵を見ずに</b>言えるようにする。
 *
 * <h2>⚠ 期待値は生産者と式を共有しない [規約 4]</h2>
 * ここは<b>できあがったバッファの中身だけ</b>を見る。
 * 総数は<b>バッファの大きさ</b>から取り、面マスクや prefix の構築式を再実行しない。
 *
 * <p>5c-2a で {@code ModelAtlasLayout} を一本化した直後、
 * <b>書き込みと読み戻しが同じ式を引いたせいで変異が素通りした</b> (失敗例 19)。
 * <b>共有した瞬間に打ち消しの経路ができる。</b>
 *
 * <h2>quad のビット配置 [確認済 — {@code lod/quad_format.glsl}]</h2>
 * <table>
 *   <tr><th>項目</th><th>ビット数</th><th>シフト</th></tr>
 *   <tr><td>face</td><td>3</td><td>0</td></tr>
 *   <tr><td>sizeX / sizeY</td><td>4 / 4</td><td>3 / 7</td></tr>
 *   <tr><td>posZ / posY / posX</td><td>5 / 5 / 5</td><td>11 / 16 / 21</td></tr>
 *   <tr><td>stateId</td><td>16</td><td>26</td></tr>
 *   <tr><td>biomeId</td><td>9</td><td>46</td></tr>
 *   <tr><td>lightId</td><td>8</td><td>55</td></tr>
 * </table>
 * <b>シフトはここに literal で書く。</b> 生産者の定数を参照すると、
 * どちらかがずれても<b>両方が同じだけずれて一致する</b>。
 */
public final class VkGeometryInvariants {
    private VkGeometryInvariants() {}

    /** 1 セクションぶんのジオメトリ。<b>誰が作ったかは問わない</b>。 */
    public record SectionGeometry(long position, int[] bucketStarts, long quadAddress, int quadCount) {}

    /** バケツの数。translucent, double-sided, 面 0..5 [確認済 — {@code RenderDataFactory}]。 */
    public static final int BUCKETS = 8;

    // ---- quad の取り出し (literal。quad_format.glsl と突き合わせること) ----
    public static int face(long q)    { return (int) (q         & 0x7L); }
    // ⚠ **sizeX / sizeY は置いていない。**
    //
    // 合成地形は全 quad が 1x1 なので、大きさのビットは**全部 0** である。
    // シフトを 3 から 4 にずらす変異を入れても 0 を読むだけで**検査が素通しした**。
    // 固定できない値を公開すると、後から誰かが**確かめられていない前提**の上に書く。
    //
    // 実ジオメトリは貪欲メッシュで大きさが変わるので、そこで初めて固定できる。
    // (逆に、実データで「全部 1x1」なら**メッシュの併合が効いていない**という信号になる)
    public static int posZ(long q)    { return (int) ((q >>> 11) & 0x1FL); }
    public static int posY(long q)    { return (int) ((q >>> 16) & 0x1FL); }
    public static int posX(long q)    { return (int) ((q >>> 21) & 0x1FL); }
    public static int stateId(long q) { return (int) ((q >>> 26) & 0xFFFFL); }
    public static int biomeId(long q) { return (int) ((q >>> 46) & 0x1FFL); }
    public static int lightId(long q) { return (int) ((q >>> 55) & 0xFFL); }

    /**
     * セクションごとの主張をまとめて確かめる。
     *
     * @param maxModels          モデルバッファの要素数。{@code stateId} がこれ以上なら<b>範囲外読み</b>
     * @param indexQuadCapacity  索引バッファが 1 回の描画で扱える quad 数。超えると分割が要る
     * @return 問題の説明。<b>空なら全部成立</b>
     */
    public static List<String> checkSections(List<SectionGeometry> sections,
                                             int maxModels, int indexQuadCapacity) {
        var problems = new ArrayList<String>();
        for (int si = 0; si < sections.size(); si++) {
            var s = sections.get(si);
            String at = "section " + si + " (pos=" + s.position() + ")";

            if (s.bucketStarts().length != BUCKETS) {
                problems.add(at + ": has " + s.bucketStarts().length + " bucket starts, expected " + BUCKETS);
                continue;
            }

            // --- バケツの起点は 0 始まり・単調非減少・総数を超えない ---
            //
            // ⚠ 総数は**バッファの大きさから来た quadCount** であって、
            // バケツを足し直した値ではない。足し直すと生産者と同じ式になる
            if (s.bucketStarts()[0] != 0) {
                problems.add(at + ": bucket 0 must start at 0, got " + s.bucketStarts()[0]);
            }
            for (int b = 1; b < BUCKETS; b++) {
                if (s.bucketStarts()[b] < s.bucketStarts()[b - 1]) {
                    problems.add(at + ": bucket starts run backwards at " + b + ": "
                        + s.bucketStarts()[b - 1] + " -> " + s.bucketStarts()[b]);
                }
            }
            if (s.bucketStarts()[BUCKETS - 1] > s.quadCount()) {
                problems.add(at + ": the last bucket starts at " + s.bucketStarts()[BUCKETS - 1]
                    + " but the section only has " + s.quadCount() + " quads");
            }

            // --- 1 回の描画に収まること ---
            for (int b = 0; b < BUCKETS; b++) {
                int count = (b + 1 < BUCKETS ? s.bucketStarts()[b + 1] : s.quadCount())
                    - s.bucketStarts()[b];
                if (count > indexQuadCapacity) {
                    problems.add(at + ": bucket " + b + " has " + count
                        + " quads, more than the index buffer's " + indexQuadCapacity
                        + " — it must be split across draws");
                }
            }

            // --- stateId が範囲内であること ---
            //
            // ⚠ これは**バリデーションが捕まえない**。シェーダが未定義の領域を引くだけで、
            // 絵は「それらしく」出る
            int worstState = -1;
            var seen = new HashSet<Long>(Math.max(16, s.quadCount() * 2));
            int duplicates = 0;
            for (int i = 0; i < s.quadCount(); i++) {
                long q = MemoryUtil.memGetLong(s.quadAddress() + (long) i * 8L);
                int st = stateId(q);
                if (st >= maxModels) worstState = Math.max(worstState, st);
                if (!seen.add(q)) duplicates++;
            }
            if (worstState >= 0) {
                problems.add(at + ": a quad references model " + worstState
                    + " but the model buffer only holds " + maxModels
                    + " — the shader would read out of range (validation does not catch this)");
            }
            if (duplicates > 0) {
                problems.add(at + ": " + duplicates + " of " + s.quadCount()
                    + " quads are exact duplicates of another quad in the same section");
            }
        }
        return problems;
    }

    /**
     * merged テーブルの prefix についての主張。
     *
     * <p>⚠ <b>末尾の照合に面マスクを使わない。</b> {@code totalQuads} は
     * <b>実際にバッファへ書かれた quad の数</b>を渡すこと。
     * 面マスクから足し直すと、生産者と同じ式を再実行することになり、
     * <b>マスクが壊れていても両方が同じだけ壊れて一致する</b> [規約 4]。
     *
     * @param prefix     長さ {@code entryCount + 1}。末尾は番兵
     * @param totalQuads 生産者とは<b>別の経路</b>で数えた総 quad 数
     */
    public static List<String> checkPrefix(int[] prefix, int entryCount, int totalQuads) {
        var problems = new ArrayList<String>();
        if (prefix.length != entryCount + 1) {
            problems.add("prefix has " + prefix.length + " entries; " + (entryCount + 1)
                + " were expected (one per entry plus the end sentinel)");
            return problems;
        }
        if (prefix[0] != 0) {
            problems.add("prefix must start at 0, got " + prefix[0]);
        }
        for (int i = 1; i < prefix.length; i++) {
            if (prefix[i] < prefix[i - 1]) {
                problems.add("prefix runs backwards at " + i + ": "
                    + prefix[i - 1] + " -> " + prefix[i]);
            }
        }
        if (prefix[prefix.length - 1] != totalQuads) {
            problems.add("the prefix sentinel is " + prefix[prefix.length - 1]
                + " but " + totalQuads + " quads were written");
        }

        // --- 二分探索が長さ 0 のエントリに着地しないこと ---
        //
        // 密なレイアウトでは quad 数 0 の面もスロットを占め、prefix に平坦部を作る。
        // 平坦部の手前を指してしまうと**描く quad が 1 枚ずれる**
        int landedOnEmpty = 0;
        for (int q = 0; q < prefix[prefix.length - 1]; q++) {
            int e = search(prefix, entryCount, q);
            if (e < 0 || e >= entryCount || prefix[e + 1] <= prefix[e]) {
                landedOnEmpty++;
            }
        }
        if (landedOnEmpty > 0) {
            problems.add(landedOnEmpty + " quad ordinals resolve to an empty prefix entry"
                + " — the drawn quad would be off by one");
        }
        return problems;
    }

    /**
     * {@code prefix[lo] <= q < prefix[lo+1]} を満たす {@code lo}。
     *
     * <p>⚠ <b>シェーダの二分探索とは別に書いてある</b> — 同じ実装を呼ぶと、
     * 探索が壊れていても<b>検査が一緒に壊れて通る</b> [規約 4]。
     * ここは<b>線形探索</b>にしてある。遅いが、検査の側が単純であることのほうが重要である。
     */
    private static int search(int[] prefix, int entryCount, int q) {
        for (int i = 0; i < entryCount; i++) {
            if (prefix[i] <= q && q < prefix[i + 1]) return i;
        }
        return -1;
    }
}
