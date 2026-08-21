package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.SyntheticTerrain;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkGeometryInvariants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>ジオメトリの不変条件が、実際に壊れたときに落ちること。</b>
 *
 * <h2>なぜ「通ること」より「落ちること」を先に確かめるのか</h2>
 * 5c-3b では<b>規約 1 が「保証」から「要求」に変わる</b>。
 * 実ジオメトリでは位置が実データになるので、
 * 「索引がずれたら絵が変わる」がデータの性質としては成り立たなくなる。
 *
 * <p>代わりに立てるのがこの不変条件だが、<b>通ることは何の証拠にもならない</b> —
 * 規約 4 で 4 回、規約 17 で 1 回、<b>検査が変異を素通しした</b>。
 * だから<b>実データを流す前に、壊して落ちることを確かめる</b>。
 *
 * <h2>期待値の独立性</h2>
 * 検査側は quad のシフトを <b>literal</b> で持ち (quad_format.glsl と突き合わせ)、
 * 生産側は {@link SyntheticTerrain} の定数を使う。
 * <b>どちらかがずれれば食い違う</b>。
 */
public class VkGeometryInvariantsTest {
    @BeforeAll
    static void setup() { VulkanTestSupport.requireVulkan(); }

    private static final int MAX_MODELS = 1 << 16;
    private static final int INDEX_CAPACITY = 1 << 20;

    private record Built(SyntheticTerrain terrain, VkBuffer geo,
                         List<VkGeometryInvariants.SectionGeometry> sections) {}

    /**
     * 合成地形を {@link VkGeometryInvariants.SectionGeometry} に写す。
     *
     * <p>⚠ バケツの並びは <b>translucent, double-sided, 面 0..5</b>
     * [確認済 — {@code RenderDataFactory} の {@code offsets[8]} と
     * {@code SyntheticTerrain.writeGeometry} の書き出し順が一致している]。
     */
    private static Built build(SyntheticTerrain t) {
        var geo = new VkBuffer(Math.max(4096, (long) t.totalQuads() * SyntheticTerrain.QUAD_SIZE));
        int[] starts = t.writeGeometry(geo);
        var out = new ArrayList<VkGeometryInvariants.SectionGeometry>();
        var sections = t.sections();
        for (int si = 0; si < sections.size(); si++) {
            var s = sections.get(si);
            int[] b = new int[VkGeometryInvariants.BUCKETS];
            b[0] = 0;
            b[1] = s.translucentCount;
            b[2] = b[1] + s.faceCounts[SyntheticTerrain.Face.DOUBLE_SIDED.bit];
            for (int f = 0; f < 5; f++) b[3 + f] = b[2 + f] + s.faceCounts[f];
            int total = b[7] + s.faceCounts[5];
            out.add(new VkGeometryInvariants.SectionGeometry(
                si, b, geo.addr() + (long) starts[si] * SyntheticTerrain.QUAD_SIZE, total));
        }
        return new Built(t, geo, out);
    }

    private static List<String> checkOf(Built b) {
        return VkGeometryInvariants.checkSections(b.sections(), MAX_MODELS, INDEX_CAPACITY);
    }

    // ================= セクションごとの不変条件 =================

    /** <b>対照</b>: 正しいデータでは何も報告しないこと (弾きすぎていない)。 */
    @Test
    void healthySyntheticGeometryReportsNothing() {
        var b = build(SyntheticTerrain.boundaryCases());
        try {
            var problems = checkOf(b);
            assertTrue(problems.isEmpty(), "the known-good dataset was flagged: " + problems);
            assertTrue(b.sections().size() > 4, "the dataset must be non-trivial");
            long quads = b.sections().stream().mapToLong(
                VkGeometryInvariants.SectionGeometry::quadCount).sum();
            assertEquals(b.terrain().totalQuads(), quads,
                "the bucket walk must account for every quad the terrain wrote");
        } finally { b.geo().free(); }
    }

    /**
     * <b>範囲外の stateId が捕まること。</b>
     *
     * <p>⚠ これは<b>バリデーションが捕まえない</b>種類の誤りである —
     * シェーダが未定義の領域を引くだけで、絵は「それらしく」出る。
     */
    @Test
    void aStateIdPastTheModelBufferIsCaught() {
        var b = build(SyntheticTerrain.boundaryCases());
        try {
            // 先頭の quad の stateId を 1 つだけ壊す (シフト 26、16 ビット)
            long addr = b.sections().get(0).quadAddress();
            long q = MemoryUtil.memGetLong(addr);
            MemoryUtil.memPutLong(addr, (q & ~(0xFFFFL << 26)) | (0xBEEFL << 26));

            var problems = VkGeometryInvariants.checkSections(b.sections(), 0x100, INDEX_CAPACITY);
            assertTrue(problems.stream().anyMatch(p -> p.contains("model 48879")),
                "an out-of-range stateId was not reported: " + problems);

            // ...そして**十分大きいモデルバッファなら通す** (弾きすぎの対照)
            assertTrue(VkGeometryInvariants.checkSections(b.sections(), MAX_MODELS, INDEX_CAPACITY)
                    .stream().noneMatch(p -> p.contains("model ")),
                "0xBEEF is inside a 65536-entry buffer and must not be flagged");
        } finally { b.geo().free(); }
    }

    /** <b>まったく同じ quad が同じセクションに 2 枚あると捕まること。</b> */
    @Test
    void duplicateQuadsWithinASectionAreCaught() {
        var b = build(SyntheticTerrain.boundaryCases());
        try {
            var s = b.sections().get(0);
            assertTrue(s.quadCount() >= 2, "this section needs at least two quads");
            MemoryUtil.memPutLong(s.quadAddress() + 8,
                MemoryUtil.memGetLong(s.quadAddress()));

            assertTrue(checkOf(b).stream().anyMatch(p -> p.contains("exact duplicates")),
                "a duplicated quad was not reported");
        } finally { b.geo().free(); }
    }

    /** バケツの起点が後戻りしたら捕まること。 */
    @Test
    void bucketStartsThatRunBackwardsAreCaught() {
        var b = build(SyntheticTerrain.boundaryCases());
        try {
            b.sections().get(0).bucketStarts()[3] = -1;
            assertTrue(checkOf(b).stream().anyMatch(p -> p.contains("run backwards")),
                "a backwards bucket start was not reported");
        } finally { b.geo().free(); }
    }

    /** 1 回の描画に収まらない大きさが捕まること (分割が要る)。 */
    @Test
    void bucketsLargerThanTheIndexBufferAreCaught() {
        var b = build(SyntheticTerrain.boundaryCases());
        try {
            var problems = VkGeometryInvariants.checkSections(b.sections(), MAX_MODELS, 2);
            assertTrue(problems.stream().anyMatch(p -> p.contains("must be split across draws")),
                "an oversized bucket was not reported with capacity=2: " + problems);
            // ...十分な容量なら報告しないこと
            assertTrue(checkOf(b).stream().noneMatch(p -> p.contains("must be split")),
                "the same data must be fine with a large index buffer");
        } finally { b.geo().free(); }
    }

    /**
     * <b>quad の各項目が正しいビットから読めること。</b>
     *
     * <h2>なぜ要るのか</h2>
     * この検査系の信用は<b>すべて literal のシフト</b>に乗っている。
     * ⚠ 正常系 ({@link #healthySyntheticGeometryReportsNothing}) は
     * <b>ビット配置を固定しない</b> — シフトを 26 から 25 にずらしても通ってしまう
     * (実際に変異で確かめた)。ここで<b>全項目を釘付けにする</b>。
     *
     * <h2>期待値の出どころ</h2>
     * 生産側 ({@link SyntheticTerrain#writeRun}) が<b>何を書くと決めているか</b>から来る:
     * <ul>
     *   <li>stateId — 写像で <b>0x1234 に固定</b>させる</li>
     *   <li>位置 — セクション内通し番号 {@code k} から {@code (k&31, (k>>5)&31, (k>>10)&31)}</li>
     *   <li>⚠ 大きさは<b>固定できない</b> — 合成地形は全部 1x1 でビットが 0 なので、
     *       シフトをずらしても 0 を読むだけである。だから検査側に置いていない</li>
     *   <li>biomeId — セクション番号</li>
     *   <li>lightId — 常に 0xF0</li>
     * </ul>
     * <b>検査側は literal のシフト、生産側は自分の定数。どちらかがずれれば食い違う</b> [規約 4]。
     */
    @Test
    void everyQuadFieldIsDecodedAtTheRightBits() {
        var t = SyntheticTerrain.boundaryCases();
        var geo = new VkBuffer(Math.max(4096, (long) t.totalQuads() * SyntheticTerrain.QUAD_SIZE));
        try {
            int[] starts = t.writeGeometry(geo, raw -> 0x1234);
            var sections = t.sections();
            int checked = 0;
            for (int si = 0; si < sections.size(); si++) {
                var s = sections.get(si);
                int count = s.translucentCount;
                for (int f = 0; f < 7; f++) count += s.faceCounts[f];
                for (int j = 0; j < count; j++) {
                    long q = MemoryUtil.memGetLong(
                        geo.addr() + (long) (starts[si] + j) * SyntheticTerrain.QUAD_SIZE);
                    String at = "section " + si + " quad " + j;

                    assertEquals(0x1234, VkGeometryInvariants.stateId(q), at + ": stateId bits");
                    assertEquals(j & 0x1F, VkGeometryInvariants.posX(q), at + ": posX bits");
                    assertEquals((j >> 5) & 0x1F, VkGeometryInvariants.posY(q), at + ": posY bits");
                    assertEquals((j >> 10) & 0x1F, VkGeometryInvariants.posZ(q), at + ": posZ bits");
                    assertEquals(si & 0x1FF, VkGeometryInvariants.biomeId(q), at + ": biomeId bits");
                    assertEquals(0xF0, VkGeometryInvariants.lightId(q), at + ": lightId bits");
                    assertTrue(VkGeometryInvariants.face(q) <= 6, at + ": face out of range");
                    checked++;
                }
            }
            assertTrue(checked > 100, "only " + checked + " quads were checked");
        } finally { geo.free(); }
    }

    // ================= prefix の不変条件 =================
    //
    // ⚠ 期待値に**面マスクを使わない**。総数は「実際に書いた quad 数」を渡す。
    // 面マスクから足し直すと生産者と同じ式になり、マスクが壊れても一致してしまう
    // [規約 4 / 失敗例 19]。

    /** <b>対照</b>: 正しい prefix は何も報告しないこと。 */
    @Test
    void aHealthyPrefixReportsNothing() {
        int[] prefix = {0, 3, 3, 7, 7, 7, 10};   // 平坦部あり = 空スロットあり
        assertTrue(VkGeometryInvariants.checkPrefix(prefix, 6, 10).isEmpty(),
            "a valid prefix with plateaus was flagged");
    }

    @Test
    void aPrefixThatRunsBackwardsIsCaught() {
        int[] prefix = {0, 3, 2, 7, 7, 7, 10};
        assertTrue(VkGeometryInvariants.checkPrefix(prefix, 6, 10).stream()
                .anyMatch(p -> p.contains("runs backwards")),
            "a non-monotonic prefix was not reported");
    }

    @Test
    void aPrefixThatDoesNotStartAtZeroIsCaught() {
        int[] prefix = {1, 3, 3, 7, 7, 7, 10};
        assertTrue(VkGeometryInvariants.checkPrefix(prefix, 6, 10).stream()
                .anyMatch(p -> p.contains("must start at 0")),
            "a prefix starting off zero was not reported");
    }

    /**
     * <b>末尾が実際に書いた総数と食い違ったら捕まること。</b>
     *
     * <p>これが、面マスクを使い回していたら<b>捕まえられなかった</b>誤りである。
     */
    @Test
    void aSentinelThatDisagreesWithWhatWasWrittenIsCaught() {
        int[] prefix = {0, 3, 3, 7, 7, 7, 10};
        assertTrue(VkGeometryInvariants.checkPrefix(prefix, 6, 11).stream()
                .anyMatch(p -> p.contains("sentinel")),
            "a sentinel disagreeing with the written quad count was not reported");
    }

    /**
     * <b>二分探索が空スロットに着地する prefix が捕まること。</b>
     *
     * <p>末尾の番兵が総数と合っていても、<b>途中の平坦部の扱いが壊れれば</b>
     * 描く quad が 1 枚ずれる。ここでは <b>entryCount を 1 つ短く</b>宣言して
     * 「最後の quad がどのスロットにも属さない」状態を作る。
     */
    @Test
    void quadsThatResolveToNoEntryAreCaught() {
        int[] prefix = {0, 3, 3, 7, 7, 7, 10};
        // entryCount を 5 と偽ると、prefix[5]=7 までしか覆えず 7..9 が宙に浮く
        var problems = VkGeometryInvariants.checkPrefix(java.util.Arrays.copyOf(prefix, 6), 5, 10);
        assertTrue(problems.stream().anyMatch(p -> p.contains("empty prefix entry")
                || p.contains("sentinel")),
            "quads outside every entry were not reported: " + problems);
    }

    /** 長さが合わない prefix は<b>黙って進まない</b>こと。 */
    @Test
    void aPrefixOfTheWrongLengthIsCaught() {
        assertTrue(VkGeometryInvariants.checkPrefix(new int[]{0, 3, 7}, 6, 7).stream()
                .anyMatch(p -> p.contains("were expected")),
            "a mis-sized prefix was not reported");
    }
}
