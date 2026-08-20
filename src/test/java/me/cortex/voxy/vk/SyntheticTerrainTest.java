package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.SyntheticTerrain.Face;
import me.cortex.voxy.client.core.vk.SyntheticTerrain.Section;
import org.junit.jupiter.api.*;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 合成データのエンコードが正しいことの検証。
 *
 * <p><b>ここが間違っていると Stage 1 の「基準の絵」が無意味になる</b>ので、
 * シェーダ側の復号ロジックと突き合わせて往復を確認する。
 */
public class SyntheticTerrainTest {
    @BeforeAll
    static void setup() { VulkanTestSupport.requireVulkan(); }

    // ---------------- position encoding ----------------

    /** {@code pos_util.glsl} の復号と往復すること。 */
    @Test
    void positionRoundTrips() {
        int[][] cases = {
            {0, 0, 0, 0},
            {1, 2, 3, 0},
            {100, 50, -200, 2},
            {-1, -2, -3, 1},
            {8388607, 127, 8388607, 15},     // 各フィールドの最大
            {-8388608, -128, -8388608, 0},   // 各フィールドの最小
        };
        for (int[] c : cases) {
            long packed = SyntheticTerrain.packPosition(c[0], c[1], c[2], c[3]);
            int[] back = SyntheticTerrain.unpackPosition(packed);
            assertArrayEquals(c, back,
                "round trip failed for x=" + c[0] + " y=" + c[1] + " z=" + c[2] + " lvl=" + c[3]
                    + " -> " + java.util.Arrays.toString(back));
        }
    }

    // ---------------- quad encoding ----------------

    /** {@code quad_format.glsl} の各 extract と一致すること。 */
    @Test
    void quadFieldsDecodeCorrectly() {
        long q = SyntheticTerrain.packQuad(5, 3, 7, 17, 9, 23, 0xBEEF, 0x1AB, 0xC3);

        assertEquals(5, (int) (q & 0x7), "face");
        assertEquals(3, ((int) (q >>> 3) & 0xF) + 1, "sizeX (stored as size-1)");
        assertEquals(7, ((int) (q >>> 7) & 0xF) + 1, "sizeY");
        assertEquals(23, (int) (q >>> 11) & 0x1F, "posZ");
        assertEquals(9, (int) (q >>> 16) & 0x1F, "posY");
        assertEquals(17, (int) (q >>> 21) & 0x1F, "posX");
        assertEquals(0xBEEF, (int) (q >>> 26) & 0xFFFF, "stateId");
        assertEquals(0x1AB, (int) (q >>> 46) & 0x1FF, "biomeId");
        assertEquals(0xC3, (int) (q >>> 55) & 0xFF, "lightId");
    }

    @Test
    void zeroSizeIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> SyntheticTerrain.packQuad(0, 0, 1, 0, 0, 0, 0, 0, 0));
    }

    // ---------------- buffer layout ----------------

    /** ジオメトリと metadata の quadStart が整合すること。 */
    @Test
    void geometryAndMetadataAgree() {
        var t = SyntheticTerrain.boundaryCases();
        var geo = new VkBuffer(Math.max(4096, (long) t.totalQuads() * SyntheticTerrain.QUAD_SIZE));
        var meta = new VkBuffer((long) t.sectionCount() * SyntheticTerrain.SECTION_METADATA_SIZE);
        try {
            int[] starts = t.writeGeometry(geo);
            t.writeMetadata(meta, starts);

            assertEquals(t.sectionCount(), starts.length);

            // quadStart は非減少で、各セクションの quad 数ぶん進む
            int expected = 0;
            for (int i = 0; i < t.sectionCount(); i++) {
                assertEquals(expected, starts[i], "quadStart of section " + i);
                expected += t.sections().get(i).totalQuads();
            }
            assertEquals(t.totalQuads(), expected, "total must match the sum of sections");

            // metadata に書いた quadStart が読み戻せること (a.w = offset 12)
            for (int i = 0; i < t.sectionCount(); i++) {
                long base = meta.addr() + (long) i * SyntheticTerrain.SECTION_METADATA_SIZE;
                assertEquals(starts[i], MemoryUtil.memGetInt(base + 12), "a.w of section " + i);
            }
        } finally {
            geo.free();
            meta.free();
        }
    }

    /** 面カウントが b.x〜b.w の正しい位置に入ること (cmdgen.comp の読み方と一致)。 */
    @Test
    void faceCountsLandInTheRightHalfWords() {
        var t = new SyntheticTerrain().add(
            new Section(0, 0, 0, 0)
                .translucent(11)
                .face(Face.DOUBLE_SIDED, 22)
                .face(Face.DOWN, 33).face(Face.UP, 44)
                .face(Face.NORTH, 55).face(Face.SOUTH, 66)
                .face(Face.WEST, 77).face(Face.EAST, 88));
        var geo = new VkBuffer(Math.max(4096, (long) t.totalQuads() * SyntheticTerrain.QUAD_SIZE));
        var meta = new VkBuffer(SyntheticTerrain.SECTION_METADATA_SIZE);
        try {
            t.writeMetadata(meta, t.writeGeometry(geo));
            long b = meta.addr() + 16;
            assertEquals(11, MemoryUtil.memGetInt(b)      & 0xFFFF, "b.x low = translucent");
            assertEquals(22, MemoryUtil.memGetInt(b) >>> 16,        "b.x high = double sided");
            assertEquals(33, MemoryUtil.memGetInt(b + 4)  & 0xFFFF, "b.y low = down");
            assertEquals(44, MemoryUtil.memGetInt(b + 4) >>> 16,    "b.y high = up");
            assertEquals(55, MemoryUtil.memGetInt(b + 8)  & 0xFFFF, "b.z low = north");
            assertEquals(66, MemoryUtil.memGetInt(b + 8) >>> 16,    "b.z high = south");
            assertEquals(77, MemoryUtil.memGetInt(b + 12) & 0xFFFF, "b.w low = west");
            assertEquals(88, MemoryUtil.memGetInt(b + 12) >>> 16,   "b.w high = east");
        } finally {
            geo.free();
            meta.free();
        }
    }

    // ---------------- boundary cases ----------------

    /** 狙った境界条件がデータセットに実際に含まれていること。 */
    @Test
    void boundarySetCoversTheIntendedCases() {
        var t = SyntheticTerrain.boundaryCases();
        var ss = t.sections();

        assertTrue(ss.stream().anyMatch(s -> s.totalQuads() == 1), "① 最小: quad 1 枚");

        long singleFace = ss.stream().filter(s -> {
            int used = 0;
            for (int c : s.faceCounts) if (c > 0) used++;
            return used == 1 && s.translucentCount == 0;
        }).count();
        assertTrue(singleFace >= 2, "② 1 方向だけのセクションが含まれること");

        assertTrue(ss.stream().anyMatch(s -> s.totalQuads() == 0),
            "③ 空セクション (prefix が進まない区間) が含まれること");

        assertTrue(ss.stream().anyMatch(s -> s.totalQuads() >= 100),
            "④ 大きく偏るセクションが含まれること");

        assertTrue(ss.stream().anyMatch(s -> s.totalQuads() == 64),
            "⑥ 2 の冪ちょうど (二分探索の境界) が含まれること");

        assertTrue(ss.stream().anyMatch(s -> {
            for (int c : s.faceCounts) if (c == 0) return false;
            return s.translucentCount > 0;
        }), "⑦ 7 draw 種すべてを使うセクションが含まれること");

        assertTrue(ss.stream().anyMatch(s -> s.x < 0 || s.y < 0 || s.z < 0),
            "⑧ 負座標 (符号拡張) が含まれること");

        assertTrue(ss.stream().anyMatch(s -> s.totalQuads() > 0
                && SyntheticTerrain.faceMask(s, SyntheticTerrain.ORIGIN) == 0),
            "⑨ 面方向マスクで丸ごと落ちるセクションが含まれること");
    }

    /**
     * <b>境界条件が面方向マスクを生き延びること。</b>
     *
     * <p>Stage 3 で {@code cmdgen} の {@code msk} を入れた結果、
     * <b>意図した面が丸ごと落ちる境界条件が出た</b> (2 の冪ちょうどの EAST 64 枚と、
     * 負座標の WEST 5 枚)。落ちれば絵にもテーブルにも現れず、
     * <b>その境界条件は何も検査していないことになる</b>。
     *
     * <p>Stage 1 の「索引ずれが絵に出ない」と同じ型の問題なので、
     * 手順ではなく検査にする。セクションの座標を動かすたびにここが守る。
     */
    @Test
    void everyBoundaryCaseSurvivesTheFaceMask() {
        var t = SyntheticTerrain.boundaryCases();
        var ss = t.sections();

        record Expect(String name, java.util.function.Predicate<SyntheticTerrain.Section> match) {}
        var expected = java.util.List.of(
            new Expect("① 最小 (quad 1 枚)", s -> s.totalQuads() == 1),
            new Expect("② 1 方向だけ", s -> s.faceCounts[Face.NORTH.bit] == 4),
            new Expect("④ 大きな偏り (100 枚)", s -> s.faceCounts[Face.UP.bit] == 100),
            new Expect("⑥ 2 の冪ちょうど (64 枚)", s -> s.faceCounts[Face.EAST.bit] == 64),
            new Expect("⑦ 7 面区分すべて", s -> s.translucentCount > 0
                && java.util.Arrays.stream(s.faceCounts).allMatch(c -> c > 0)),
            new Expect("⑧ 負座標", s -> s.x < 0 || s.y < 0 || s.z < 0));

        for (var e : expected) {
            var hit = ss.stream().filter(e.match()).findFirst();
            assertTrue(hit.isPresent(), e.name() + " が見当たらない");
            var s = hit.get();
            int msk = SyntheticTerrain.faceMask(s, SyntheticTerrain.ORIGIN);
            int drawn = 0;
            for (int f = 0; f < 7; f++) if ((msk & (1 << f)) != 0) drawn += s.faceCounts[f];
            assertTrue(drawn > 0, e.name() + ": 面方向マスクで全部落ちている。"
                + "この境界条件はテーブルにも絵にも現れないので何も検査していない "
                + "(セクション座標を relative が条件を満たす位置に移すこと)");
        }

        // ⑦ は 7 面すべてが残らないと「7 draw 種すべて」を検査したことにならない
        var all = ss.stream().filter(s -> s.translucentCount > 0
            && java.util.Arrays.stream(s.faceCounts).allMatch(c -> c > 0)).findFirst().orElseThrow();
        assertEquals(0x7F, SyntheticTerrain.faceMask(all, SyntheticTerrain.ORIGIN),
            "⑦ のセクションは 7 面すべてがマスクを通らなければならない "
                + "(relative == 0 の位置、つまり原点セクションに置くこと)");
    }

    /** 空セクションを挟んでも quadStart が壊れないこと (③ の効き目)。 */
    @Test
    void emptySectionsDoNotBreakOffsets() {
        var t = new SyntheticTerrain()
            .add(new Section(0, 0, 0, 0).face(Face.UP, 5))
            .add(new Section(1, 0, 0, 0))                    // 空
            .add(new Section(2, 0, 0, 0))                    // 空
            .add(new Section(3, 0, 0, 0).face(Face.UP, 7));
        var geo = new VkBuffer(4096);
        try {
            int[] starts = t.writeGeometry(geo);
            assertArrayEquals(new int[]{0, 5, 5, 5}, starts,
                "empty sections must repeat the previous offset, not skip");
        } finally {
            geo.free();
        }
    }

    // ---------------- 規約: 可視位置の一意性 ----------------

    /**
     * <b>【規約】基準データの各 quad は別々の可視位置を占めること。</b>
     *
     * <p>これはデータセットを増やすたびに壊れうる性質で、壊れても
     * <b>全テストが PASS のまま対照実験だけが無力化される</b>。
     * 実際 Stage 1 でそれが起きた (docs/phase4-stage1-completion.md 2.2)。
     * だから手順ではなく<b>データセット自体の不変条件として常設検査する</b>。
     *
     * <p>破れると失うもの:
     * <ul>
     *   <li>索引ずれが絵に出なくなる (隠れた quad と入れ替わるだけになる)</li>
     *   <li>Stage 1 (セクション順) と Stage 2b (面順) の<b>発行順の差</b>が
     *       共平面の勝敗を変え、統合と無関係な差分が出る</li>
     * </ul>
     *
     * <p>{@code quadsAreDistinguishable} は quad の<b>中身</b>の重複しか見ておらず、
     * この性質を見ていないため今回の問題を素通しした。両方が要る。
     */
    @Test
    void everyQuadOccupiesADistinctVisiblePosition() {
        for (var named : namedDatasets()) {
            var prints = named.getValue().footprints();
            var seen = new java.util.HashMap<SyntheticTerrain.Footprint, Integer>();
            for (int i = 0; i < prints.size(); i++) {
                Integer prev = seen.put(prints.get(i), i);
                assertNull(prev, () -> "dataset '" + named.getKey() + "': two quads share a world-space "
                    + "footprint. Index shifts become invisible and draw order starts to matter. "
                    + "See docs/phase4-stage1-completion.md 2.2");
            }
            assertEquals(named.getValue().totalQuads(), prints.size(),
                "footprints must cover every quad of '" + named.getKey() + "'");
        }
    }

    /**
     * 上の検査が実際に重複を捕まえること (対照)。
     * <b>「基準そのものの正しさは対照実験でしか確かめられない」</b>ため、
     * 不変条件検査にも対照を付ける。
     */
    @Test
    void duplicateFootprintsAreActuallyDetected() {
        // 同じセクション座標を 2 回置けば、まったく同じ位置に同じ面が生える
        var t = new SyntheticTerrain()
            .add(new Section(0, 0, 0, 0).face(Face.UP, 1))
            .add(new Section(0, 0, 0, 0).face(Face.UP, 1));
        var prints = t.footprints();
        assertEquals(2, prints.size());
        assertEquals(prints.get(0), prints.get(1),
            "the duplicate must be visible as an equal footprint; if this fails the "
                + "invariant check above cannot detect anything");
    }

    /** 面の向きごとに法線軸が変わること (フットプリント計算が面を区別している証拠)。 */
    @Test
    void footprintsSeparateTheSixFaceDirections() {
        var t = new SyntheticTerrain().add(new Section(0, 0, 0, 0)
            .face(Face.DOWN, 1).face(Face.UP, 1).face(Face.NORTH, 1)
            .face(Face.SOUTH, 1).face(Face.WEST, 1).face(Face.EAST, 1));
        var prints = t.footprints();
        assertEquals(6, prints.size());
        // DOWN/UP -> 法線 Y, NORTH/SOUTH -> 法線 Z, WEST/EAST -> 法線 X
        assertEquals(1, prints.get(0).normalAxis(), "DOWN is Y-normal");
        assertEquals(1, prints.get(1).normalAxis(), "UP is Y-normal");
        assertEquals(2, prints.get(2).normalAxis(), "NORTH is Z-normal");
        assertEquals(2, prints.get(3).normalAxis(), "SOUTH is Z-normal");
        assertEquals(0, prints.get(4).normalAxis(), "WEST is X-normal");
        assertEquals(0, prints.get(5).normalAxis(), "EAST is X-normal");
        assertEquals(6, new java.util.HashSet<>(prints).size(), "all six must be distinct");
    }

    /** 検査対象のデータセット一覧。新しいものを足したらここに載せること。 */
    private static java.util.List<java.util.Map.Entry<String, SyntheticTerrain>> namedDatasets() {
        return java.util.List.of(
            java.util.Map.entry("boundaryCases", SyntheticTerrain.boundaryCases()),
            java.util.Map.entry("minimal", SyntheticTerrain.minimal()));
    }

    /** 各 quad が区別できる stateId を持つこと (索引ずれが絵に出るための前提)。 */
    @Test
    void quadsAreDistinguishable() {
        var t = SyntheticTerrain.boundaryCases();
        var geo = new VkBuffer(Math.max(4096, (long) t.totalQuads() * SyntheticTerrain.QUAD_SIZE));
        try {
            t.writeGeometry(geo);
            var seen = new java.util.HashSet<Long>();
            int dupes = 0;
            for (int i = 0; i < t.totalQuads(); i++) {
                long q = MemoryUtil.memGetLong(geo.addr() + (long) i * SyntheticTerrain.QUAD_SIZE);
                if (!seen.add(q)) dupes++;
            }
            // 完全に一意である必要はないが、大半が区別できないと索引ずれを見逃す
            assertTrue(dupes < t.totalQuads() / 4,
                "too many identical quads (" + dupes + "/" + t.totalQuads()
                    + "); index errors would be invisible");
        } finally {
            geo.free();
        }
    }
}
