package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.SyntheticTerrain;
import me.cortex.voxy.common.world.WorldEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>ワールドのキー形式と GPU の位置形式は別物である</b> (Phase 5c-3b)。
 *
 * <pre>
 * WorldEngine.getWorldSectionId : lvl&lt;&lt;60 | y&lt;&lt;52 | z&lt;&lt;28 | x&lt;&lt;4
 * SyntheticTerrain.packPosition : pos_util.glsl の配置 (lvl/y/z を px に、x/z を py に)
 * </pre>
 *
 * <p>{@code BuiltSection.position} は<b>前者</b>である。
 * そのまま GPU へ書くと位置が滅茶苦茶になるが、<b>落ちないし絵も出る</b> —
 * 「地形がどこかに描かれている」ようには見えるので<b>気付きにくい</b>。
 *
 * <h2>期待値の独立性 [規約 4]</h2>
 * 期待値は<b>もとの座標そのもの</b>である。どちらの詰め方も参照していない。
 * 往復のどこかがずれれば食い違う。
 */
public class VkRealPositionTest {
    /** ⚠ 負の座標を必ず含めること — <b>符号拡張がいちばん壊れる</b>。 */
    private static final int[][] CASES = {
        {0, 0, 0, 0}, {1, 2, 3, 0}, {-1, -2, -3, 0}, {100, 60, -40, 0},
        {-2048, -128, 2047, 0}, {8388607, 127, -8388608, 0},
        {5, 6, 7, 3}, {-9, 10, -11, 5},
    };

    @Test
    void theWorldKeyRoundTripsIntoTheGpuPositionFormat() {
        for (int[] c : CASES) {
            int x = c[0], y = c[1], z = c[2], lvl = c[3];
            long key = WorldEngine.getWorldSectionId(lvl, x, y, z);

            // VkRealSectionUpload がやっているのと同じ手順
            long packed = SyntheticTerrain.packPosition(
                WorldEngine.getX(key), WorldEngine.getY(key),
                WorldEngine.getZ(key), WorldEngine.getLevel(key));
            int[] got = SyntheticTerrain.unpackPosition(packed);

            assertArrayEquals(new int[]{x, y, z, lvl}, got,
                "round trip failed for (" + x + "," + y + "," + z + ") lvl " + lvl
                    + " — got (" + got[0] + "," + got[1] + "," + got[2] + ") lvl " + got[3]);
        }
    }

    /**
     * <b>2 つの形式は「別物」ではなく<b>ワードを入れ替えたもの</b>である。</b>
     *
     * <pre>
     * WorldEngine のキー : lvl&lt;&lt;60 | y&lt;&lt;52 | z&lt;&lt;28 | x&lt;&lt;4
     * packPosition       : px = lvl&lt;&lt;28 | y&lt;&lt;20 | z上位     (下位ワード)
     *                      py = x&lt;&lt;4 | z下位&lt;&lt;28            (上位ワード)
     *
     *   (int)(key &gt;&gt;&gt; 32) == px        (int) key == py
     * </pre>
     *
     * <p><b>これが分かると、上流が復号せずに上位ワードから書いている理由が読める</b>
     * [{@code SectionMeta.writeMetadataSplitParts} / {@code NodeStore.writeNode}]。
     * メモリ上は {@code .x = px, .y = py} になり、{@code pos_util.glsl} の読み方と合う。
     *
     * <p>⚠ <b>この関係が崩れたら、上流の書き出しと {@code VkRealSectionUpload} の
     * 両方が同時に壊れる</b>。だからここで固定する。
     */
    @Test
    void theWorldKeyIsPackPositionWithItsWordsSwapped() {
        for (int[] c : CASES) {
            long key = WorldEngine.getWorldSectionId(c[3], c[0], c[1], c[2]);
            long gpu = SyntheticTerrain.packPosition(c[0], c[1], c[2], c[3]);
            String at = "(" + c[0] + "," + c[1] + "," + c[2] + ") lvl " + c[3];
            assertEquals((int) gpu, (int) (key >>> 32), at + ": key's HIGH word must be px");
            assertEquals((int) (gpu >>> 32), (int) key, at + ": key's LOW word must be py");
        }
    }

    /**
     * <b>対照: 2 つの形式は本当に違う。</b>
     *
     * <p>これが無いと「詰め直しは実は不要だった」と区別が付かず、
     * 上の検査は<b>恒等写像でも通ってしまう</b> [規約 11]。
     */
    @Test
    void theTwoFormatsAreActuallyDifferent() {
        int different = 0;
        for (int[] c : CASES) {
            long key = WorldEngine.getWorldSectionId(c[3], c[0], c[1], c[2]);
            long gpu = SyntheticTerrain.packPosition(c[0], c[1], c[2], c[3]);
            if (key != gpu) different++;
        }
        assertTrue(different >= CASES.length - 1,
            "the world key and the GPU position agreed for " + (CASES.length - different)
                + " of " + CASES.length + " cases — if they were the same format,"
                + " the repacking step would be untested");
    }

    /**
     * <b>キーをそのまま GPU 形式として読むと壊れること。</b>
     * 「詰め直しを忘れる」という実際にありうる誤りを、検査が捕まえられることを示す。
     */
    @Test
    void feedingTheRawKeyToTheGpuFormatIsWrong() {
        int wrong = 0;
        for (int[] c : CASES) {
            long key = WorldEngine.getWorldSectionId(c[3], c[0], c[1], c[2]);
            int[] got = SyntheticTerrain.unpackPosition(key);   // ← 詰め直しを忘れた場合
            if (got[0] != c[0] || got[1] != c[1] || got[2] != c[2] || got[3] != c[3]) wrong++;
        }
        assertTrue(wrong >= CASES.length - 1,
            "skipping the repack produced the right coordinates for "
                + (CASES.length - wrong) + " of " + CASES.length + " cases;"
                + " this test cannot detect a forgotten repack");
    }
}
