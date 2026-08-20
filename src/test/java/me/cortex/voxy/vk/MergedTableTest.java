package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.SyntheticTerrain;
import me.cortex.voxy.client.core.vk.SyntheticTerrain.Face;
import me.cortex.voxy.client.core.vk.SyntheticTerrain.Section;
import me.cortex.voxy.client.core.vk.VkBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 2a: 統合描画テーブルの不変条件を <b>CPU 側で全数検査</b>する。
 *
 * <p>テーブルさえ出せれば、二分探索の正しさはシェーダを動かさずに全数で確かめられる
 * (docs/phase4-proposal.md 4.2)。GPU 側の GLSL 実装が同じ答えを出すかは
 * {@code VkMergedIndexTest} が別途、同じ全数検査で押さえる。
 *
 * <p>この段階では<b>描画経路は Stage 1 のまま</b>である。テーブルは追加で作るだけで、
 * 絵は変わらない ({@code VkTerrainRenderTest.tableGenerationDoesNotChangeTheImage})。
 */
public class MergedTableTest {

    @BeforeAll
    static void setup() { VulkanTestSupport.requireVulkan(); }

    private record Built(SyntheticTerrain terrain, int[] starts, SyntheticTerrain.MergedTable table) {}

    private static Built build(SyntheticTerrain t) {
        var geo = new VkBuffer(Math.max(4096, (long) t.totalQuads() * SyntheticTerrain.QUAD_SIZE));
        try {
            int[] starts = t.writeGeometry(geo);
            return new Built(t, starts, t.mergedTable(starts, SyntheticTerrain.ORIGIN));
        } finally {
            geo.free();
        }
    }

    // ---------------- 不変条件 ----------------

    /**
     * prefix は 0 始まり・単調非減少・末尾は総 quad 数。
     *
     * <p><b>密なレイアウトなので「狭義」ではない。</b> quad 数 0 の面もスロットを占め、
     * prefix に平坦部を作る。平坦部があっても解が一意なのは、二分探索が
     * {@code prefix[lo] <= q < prefix[lo+1]} を満たす lo に収束するためで、
     * この条件は長さ 0 のエントリでは成立しない
     * ({@link #binarySearchNeverLandsOnAnEmptyEntry} が実際に確かめる)。
     */
    @Test
    void prefixIsMonotonicAndEndsAtTheTotal() {
        var b = build(SyntheticTerrain.boundaryCases());
        int[] p = b.table().prefix();

        assertEquals(0, p[0], "prefix must start at 0");
        assertEquals(b.table().entryCount() + 1, p.length, "prefix needs the end sentinel");
        assertEquals(7 * b.terrain().sectionCount(), b.table().entryCount(),
            "the dense layout allocates one slot per (face, section)");

        for (int i = 1; i < p.length; i++) {
            assertTrue(p[i] >= p[i - 1],
                "prefix must not run backwards at " + i + ": " + p[i - 1] + " -> " + p[i]);
        }

        // 面方向マスクで落ちたぶんは総数に入らない
        int expected = 0;
        for (var s : b.terrain().sections()) {
            int msk = SyntheticTerrain.faceMask(s, SyntheticTerrain.ORIGIN);
            for (int f = 0; f < 7; f++) if ((msk & (1 << f)) != 0) expected += s.faceCounts[f];
        }
        assertEquals(expected, p[p.length - 1], "the sentinel must equal the visible opaque quad count");
        assertEquals(expected, b.table().totalQuads());
        assertTrue(b.table().nonEmptyEntryCount() < b.table().entryCount(),
            "this dataset must actually contain empty slots, otherwise the plateau case is untested");
    }

    /**
     * <b>二分探索が長さ 0 のエントリに着地しないこと。</b>
     * 密なレイアウトを採る前提そのもの。ここが崩れると
     * 描く quad が 1 枚ずれる (平坦部の手前のエントリを指してしまう)。
     */
    @Test
    void binarySearchNeverLandsOnAnEmptyEntry() {
        var b = build(SyntheticTerrain.boundaryCases());
        var table = b.table();
        int[] p = table.prefix();
        int empties = 0;
        for (int q = 0; q < table.totalQuads(); q++) {
            int e = binarySearch(p, table.entryCount(), q);
            assertTrue(p[e + 1] > p[e],
                "quad ordinal " + q + " resolved to entry " + e + ", which is empty ("
                    + p[e] + " -> " + p[e + 1] + ")");
        }
        for (int e = 0; e < table.entryCount(); e++) if (p[e + 1] == p[e]) empties++;
        assertTrue(empties > 0, "the dataset must contain empty entries for this test to mean anything");
        System.out.println("[cpu] dense table: " + table.entryCount() + " slots, "
            + empties + " empty, " + table.totalQuads() + " quads");
    }

    /** 面ごとの区間が連続していて、重ならず、順に並ぶこと。 */
    @Test
    void faceRegionsAreContiguousAndDisjoint() {
        var b = build(SyntheticTerrain.boundaryCases());
        var table = b.table();
        int[] fs = table.faceEntryStart();

        assertEquals(8, fs.length);
        assertEquals(0, fs[0]);
        assertEquals(table.entryCount(), fs[7], "the regions must cover every entry");
        for (int f = 0; f < 7; f++) {
            assertTrue(fs[f] <= fs[f + 1], "face region " + f + " must not run backwards");
            for (int e = fs[f]; e < fs[f + 1]; e++) {
                assertEquals(f, table.entries().get(e).faceBit(),
                    "entry " + e + " sits in face region " + f + " but claims another face");
            }
        }

        // 面の draw が区間とちょうど噛み合うこと
        int ordinal = 0;
        for (var d : table.faceDraws()) {
            assertEquals(ordinal, d.quadOrdinalStart(), "face " + d.faceBit() + " must start where the previous ended");
            int sum = 0;
            for (int e = fs[d.faceBit()]; e < fs[d.faceBit() + 1]; e++) {
                sum += table.entries().get(e).quadCount();
            }
            assertEquals(sum, d.quadCount(), "face " + d.faceBit() + " draw size must match its entries");
            ordinal += d.quadCount();
        }
        assertEquals(table.totalQuads(), ordinal, "the 7 draws must cover every quad exactly once");
    }

    /**
     * <b>統合テーブルと Stage 1 のコマンド列が同じ集合を描くこと。</b>
     *
     * <p>これが Stage 2b の核心である。並び順は違ってよいが、
     * <b>(quad 番号, drawId) の組の集合が完全に一致</b>しなければならない。
     * 一致すれば「同じ quad を同じセクション位置で描く」ことが
     * <b>絵を出す前に</b>確定する。
     */
    @Test
    void mergedTableDrawsExactlyWhatStage1Draws() {
        var b = build(SyntheticTerrain.boundaryCases());

        // Stage 1: セクション順のコマンドを展開する
        var stage1 = new ArrayList<String>();
        for (var d : b.terrain().opaqueDrawCommands(b.starts(), SyntheticTerrain.ORIGIN)) {
            for (int i = 0; i < d.quadCount(); i++) {
                stage1.add((d.quadOffset() + i) + "@" + d.drawId());
            }
        }

        // Stage 2b: 通し番号を 0..total-1 まで解決する
        var stage2 = new ArrayList<String>();
        for (int q = 0; q < b.table().totalQuads(); q++) {
            int[] r = b.table().resolveLinear(q);
            stage2.add(r[0] + "@" + r[1]);
        }

        assertEquals(stage1.size(), stage2.size(), "both paths must draw the same number of quads");
        assertEquals(new HashSet<>(stage1), new HashSet<>(stage2),
            "the set of (quad, section) pairs must be identical");
        // 重複が無いこと (同じ quad を 2 回描いていない)
        assertEquals(stage1.size(), new HashSet<>(stage1).size(), "stage 1 must not repeat a quad");
        assertEquals(stage2.size(), new HashSet<>(stage2).size(), "stage 2 must not repeat a quad");
    }

    /**
     * <b>二分探索が線形探索と全通り一致すること</b> (CPU 版)。
     * GLSL 版と同じアルゴリズムをここで書き、境界を全数で潰す。
     */
    @Test
    void binarySearchAgreesWithLinearSearchEverywhere() {
        for (var t : List.of(SyntheticTerrain.boundaryCases(), SyntheticTerrain.minimal())) {
            var b = build(t);
            var table = b.table();
            for (int q = 0; q < table.totalQuads(); q++) {
                int e = binarySearch(table.prefix(), table.entryCount(), q);
                int[] lin = table.resolveLinear(q);
                var entry = table.entries().get(e);
                assertEquals(lin[0], entry.quadStart() + (q - table.prefix()[e]),
                    "quad ordinal " + q + ": binary search resolved a different quad");
                assertEquals(lin[1], entry.drawId(),
                    "quad ordinal " + q + ": binary search resolved a different section");
            }
        }
    }

    /**
     * {@code lod/vk/quad_index.glsl} の {@code findMergedEntry} と同一のアルゴリズム。
     * <b>GLSL 側を変えたらここも変えること</b> — 突き合わせの意味が消える。
     */
    private static int binarySearch(int[] prefix, int entryCount, int quadOrdinal) {
        int lo = 0, hi = entryCount;
        for (int step = 0; step < 32 && lo + 1 < hi; step++) {
            int mid = lo + ((hi - lo) >>> 1);
            if (prefix[mid] <= quadOrdinal) lo = mid; else hi = mid;
        }
        return lo;
    }

    /** 探索の上限 32 回で足りること (GPU 側の無限ループ防止の根拠)。 */
    @Test
    void thirtyTwoStepsIsEnoughForAnyTableWeCanBuild() {
        // 2^32 エントリまで 32 回で収束する。実際に使う規模で余裕を確かめる
        var b = build(SyntheticTerrain.boundaryCases());
        int n = b.table().entryCount();
        assertTrue(Math.ceil(Math.log(Math.max(2, n)) / Math.log(2)) <= 32,
            "table has " + n + " entries; the bounded loop would not converge");
    }

    // ---------------- バッファへの書き出し ----------------

    /** SSBO に書いた内容が読み戻せること (先頭のエントリ数を含む)。 */
    @Test
    void tableRoundTripsThroughBuffers() {
        var b = build(SyntheticTerrain.boundaryCases());
        var table = b.table();
        var entryBuf = new VkBuffer(Math.max(4096,
            (long) table.entryCount() * SyntheticTerrain.MERGED_ENTRY_SIZE));
        var prefixBuf = new VkBuffer(Math.max(4096, 4L + (long) table.prefix().length * 4));
        try {
            SyntheticTerrain.writeMergedEntries(entryBuf, table);
            SyntheticTerrain.writeMergedPrefix(prefixBuf, table);

            assertEquals(table.entryCount(),
                org.lwjgl.system.MemoryUtil.memGetInt(prefixBuf.addr()),
                "the prefix buffer must start with the entry count");

            for (int i = 0; i < table.entryCount(); i++) {
                long o = entryBuf.addr() + (long) i * SyntheticTerrain.MERGED_ENTRY_SIZE;
                assertEquals(table.entries().get(i).quadStart(),
                    org.lwjgl.system.MemoryUtil.memGetInt(o), "entry " + i + " quadStart");
                assertEquals(table.entries().get(i).drawId(),
                    org.lwjgl.system.MemoryUtil.memGetInt(o + 4), "entry " + i + " drawId");
            }
            for (int i = 0; i < table.prefix().length; i++) {
                assertEquals(table.prefix()[i],
                    org.lwjgl.system.MemoryUtil.memGetInt(prefixBuf.addr() + 4L + (long) i * 4),
                    "prefix " + i);
            }
        } finally {
            entryBuf.free();
            prefixBuf.free();
        }
    }

    /**
     * <b>共有インデックスバッファ (T) を超える面が正しく分割されること。</b>
     *
     * <p>分割後の draw が元の区間をちょうど覆い、重なりも隙間も無いことを確かめる。
     * ここがずれると quad が二度描かれたり抜けたりする。
     */
    @Test
    void facesLargerThanTheIndexBufferAreSplit() {
        var b = build(SyntheticTerrain.boundaryCases());
        var table = b.table();
        int t = 8;      // わざと小さくして分割経路を踏む

        int expectedDraws = 0;
        for (var d : table.faceDraws()) {
            expectedDraws += Math.max(1, (d.quadCount() + t - 1) / t);
        }
        assertTrue(expectedDraws > table.faceDraws().size(),
            "T=" + t + " must actually cause splitting, otherwise this test checks nothing");
        assertEquals(expectedDraws, SyntheticTerrain.faceDrawCount(table, t));

        var buf = new VkBuffer((long) expectedDraws * SyntheticTerrain.DRAW_COMMAND_SIZE + 64);
        try {
            int written = SyntheticTerrain.writeFaceDraws(buf, table, t);
            assertEquals(expectedDraws, written);

            // 面ごとに、分割された draw が元の区間をちょうど覆うこと
            int slot = 0;
            for (var d : table.faceDraws()) {
                int covered = 0;
                int chunks = Math.max(1, (d.quadCount() + t - 1) / t);
                for (int c = 0; c < chunks; c++, slot++) {
                    long o = buf.addr() + (long) slot * SyntheticTerrain.DRAW_COMMAND_SIZE;
                    int indexCount = org.lwjgl.system.MemoryUtil.memGetInt(o);
                    int instances = org.lwjgl.system.MemoryUtil.memGetInt(o + 4);
                    int baseVertex = org.lwjgl.system.MemoryUtil.memGetInt(o + 12);
                    int quads = indexCount / 6;

                    assertEquals(0, indexCount % 6, "indexCount must be a multiple of 6");
                    assertTrue(quads <= t, "chunk " + c + " of face " + d.faceBit()
                        + " has " + quads + " quads, over T=" + t);
                    assertEquals(quads == 0 ? 0 : 1, instances, "instanceCount");
                    // baseVertex は quad 通し番号 * 4。前のチャンクの直後から始まること
                    assertEquals((d.quadOrdinalStart() + covered) << 2, baseVertex,
                        "face " + d.faceBit() + " chunk " + c + " must continue where the last ended");
                    covered += quads;
                }
                assertEquals(d.quadCount(), covered,
                    "face " + d.faceBit() + " chunks must cover exactly its quads");
            }
            assertEquals(written, slot, "every written draw must belong to a face");
        } finally {
            buf.free();
        }
    }

    /** 上限の式が実際の本数を下回らないこと (GPU 経路が渡す値の根拠)。 */
    @Test
    void maxFaceDrawCountIsAnUpperBound() {
        var b = build(SyntheticTerrain.boundaryCases());
        for (int t : new int[]{1, 2, 8, 64, 1000, 1 << 20}) {
            int actual = SyntheticTerrain.faceDrawCount(b.table(), t);
            int bound = SyntheticTerrain.maxFaceDrawCount(b.table().totalQuads(), t);
            assertTrue(bound >= actual,
                "T=" + t + ": bound " + bound + " is below the actual " + actual);
        }
    }

    // ---------------- 境界条件が実際に効いていること ----------------

    /**
     * Stage 1 で作った境界条件が統合テーブルに現れていること。
     * <b>境界条件がテーブルの形に届いていなければ、Stage 2b で狙い撃ちにならない。</b>
     */
    @Test
    void boundaryCasesReachTheTable() {
        var b = build(SyntheticTerrain.boundaryCases());
        var table = b.table();

        // ③ 空セクションはスロットは占めるが quad を 1 枚も出さない
        var drawIds = new HashSet<Integer>();
        for (int e = 0; e < table.entryCount(); e++) {
            if (table.prefix()[e + 1] > table.prefix()[e]) drawIds.add(table.entries().get(e).drawId());
        }
        var sections = b.terrain().sections();
        for (int si = 0; si < sections.size(); si++) {
            if (sections.get(si).totalQuads() == 0) {
                assertFalse(drawIds.contains(si), "empty section " + si + " must contribute no quads");
            }
        }

        // ⑨ 面方向マスクで丸ごと落ちるセクションがあること
        // (Stage 3 で GPU 側の msk が効いていることの対照になる)
        int fullyMasked = -1;
        for (int si = 0; si < sections.size(); si++) {
            if (sections.get(si).totalQuads() > 0
                && SyntheticTerrain.faceMask(sections.get(si), SyntheticTerrain.ORIGIN) == 0) {
                fullyMasked = si;
            }
        }
        assertTrue(fullyMasked >= 0, "the dataset must contain a section the face mask removes entirely");
        assertFalse(drawIds.contains(fullyMasked),
            "section " + fullyMasked + " is fully masked and must contribute no quads");

        // ⑥ quad 数ちょうど 64 のランがあること (二分探索の境界)
        assertTrue(table.entries().stream().anyMatch(e -> e.quadCount() == 64),
            "the power-of-two run must survive into the table");

        // ⑦ 7 面区分すべてに少なくとも 1 本 **非空の** エントリがあること
        for (int f = 0; f < 7; f++) {
            int drawn = table.faceDraws().get(f).quadCount();
            assertTrue(drawn > 0,
                "face " + f + " draws nothing; the 7-draw split would not be exercised");
        }

        // ④ 大きく偏るラン (100 quads)
        assertTrue(table.entries().stream().anyMatch(e -> e.quadCount() == 100),
            "the lopsided run must survive into the table");

        // ⑧ 負座標のセクションが載っていること
        assertTrue(drawIds.contains(7), "the negative-coordinate section must be drawn");
    }

    /** 空のデータセットでもテーブルが壊れないこと。 */
    @Test
    void allEmptySectionsProduceAnEmptyTable() {
        var t = new SyntheticTerrain()
            .add(new Section(0, 0, 0, 0))
            .add(new Section(1, 0, 0, 0));
        var b = build(t);
        // 密なので slot は 2 セクション x 7 面 = 14 本できるが、すべて長さ 0
        assertEquals(14, b.table().entryCount());
        assertEquals(0, b.table().nonEmptyEntryCount());
        assertEquals(0, b.table().totalQuads());
        for (int v : b.table().prefix()) assertEquals(0, v, "every prefix entry stays at 0");
        for (var d : b.table().faceDraws()) assertEquals(0, d.quadCount());
    }

    /** 半透明ぶんの読み飛ばしが Stage 1 とテーブルで一致すること。 */
    @Test
    void translucentRunsAreSkippedConsistently() {
        var t = new SyntheticTerrain()
            .add(new Section(0, 0, 0, 0).translucent(7).face(Face.UP, 3));
        var b = build(t);
        assertEquals(7, b.table().entryCount(), "1 section x 7 faces (dense)");
        assertEquals(1, b.table().nonEmptyEntryCount(), "only UP has quads");
        // 半透明 7 枚のあと、両面 0 枚を挟んで UP が始まる。UP は面 1 -> slot 1
        assertEquals(7, b.table().entries().get(1).quadStart(),
            "the opaque run must start after the translucent quads");
        assertEquals(b.terrain().opaqueDrawCommands(b.starts(), SyntheticTerrain.ORIGIN).get(0).quadOffset(),
            b.table().entries().get(1).quadStart(),
            "stage 1 and the merged table must agree on where the run starts");
    }
}
