package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.VkHierarchicalScene.Visibility;
import org.junit.jupiter.api.Test;

import static me.cortex.voxy.client.core.vk.VkHierarchicalScene.visibilityWord;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5c-5a: <b>可視の語の作り方</b>。装置が要らないので素の JVM で回る。
 *
 * <h2>何を守っているのか</h2>
 * temporal に回るかどうかは <b>{@code visibilityData} の bit 31</b> だけで決まる
 * [確認済 — {@code cmdgen.comp}: {@code renderTemporally = (dat & 0x80000000u) == 0u}]。
 * 下位 31 bit が {@code frameId} と一致しなければ<b>そもそも描かれない</b>。
 *
 * <p>⚠ <b>2 つを取り違えると症状が正反対になる。</b>
 * 下位を間違えれば<b>何も出ない</b>、bit 31 を間違えれば
 * <b>不透明に出るはずのものが temporal に回る</b> — 後者は絵が出るので気付きにくい。
 */
public class VkVisibilityModeTest {
    private static final int FRAME = 0x1234;

    /** <b>どのモードでも「描く」印は変わらない。</b> 変わるのは bit 31 だけである。 */
    @Test
    void everyModeStillMarksTheSectionAsDrawnThisFrame() {
        for (var mode : Visibility.values()) {
            if (mode == Visibility.CULL) continue;
            for (int i = 0; i < 4; i++) {
                assertEquals(FRAME, visibilityWord(mode, i, FRAME) & 0x7fffffff,
                    mode + " index " + i + ": the low 31 bits must equal frameId,"
                        + " or cmdgen treats the section as 0 quads");
            }
        }
    }

    /** ⚠ {@code ALL_VISIBLE} は temporal を<b>構造的に空</b>にする。 */
    @Test
    void allVisibleLeavesNothingForTemporal() {
        for (int i = 0; i < 8; i++) {
            assertNotEquals(0, visibilityWord(Visibility.ALL_VISIBLE, i, FRAME) & 0x80000000,
                "ALL_VISIBLE must set bit 31 on every section");
        }
    }

    /** {@code NONE_VISIBLE} は全てを temporal に回す。 */
    @Test
    void noneVisibleSendsEverythingToTemporal() {
        for (int i = 0; i < 8; i++) {
            assertEquals(0, visibilityWord(Visibility.NONE_VISIBLE, i, FRAME) & 0x80000000,
                "NONE_VISIBLE must clear bit 31 on every section");
        }
    }

    /**
     * <b>EVEN と ODD は互いに素で、全体を覆う。</b>
     *
     * <p>⚠ これが 5c-5a の主張の土台である。この性質が崩れると
     * 「temporal の quad 数の和が全体になる」という<b>数え方に依存しない主張</b>が
     * 成立しなくなる [規約 4]。
     */
    @Test
    void evenAndOddPartitionTheSections() {
        for (int i = 0; i < 64; i++) {
            boolean evenNew = (visibilityWord(Visibility.EVEN_NEW, i, FRAME) & 0x80000000) == 0;
            boolean oddNew = (visibilityWord(Visibility.ODD_NEW, i, FRAME) & 0x80000000) == 0;
            assertNotEquals(evenNew, oddNew,
                "index " + i + " must be new in exactly one of EVEN_NEW / ODD_NEW;"
                    + " otherwise the sum of the two temporal tables is not the whole");
        }
    }

    /**
     * ⚠ <b>対照</b>: EVEN も ODD も<b>非空</b>であること。
     *
     * <p>片方が空でも「互いに素で全体を覆う」は成立する (∅ と全体)。
     * それでは分割の恒等式が<b>フィルタについて何も主張しない</b> [規約 18]。
     */
    @Test
    void neitherHalfIsEmpty() {
        int evenCount = 0, oddCount = 0;
        for (int i = 0; i < 64; i++) {
            if ((visibilityWord(Visibility.EVEN_NEW, i, FRAME) & 0x80000000) == 0) evenCount++;
            if ((visibilityWord(Visibility.ODD_NEW, i, FRAME) & 0x80000000) == 0) oddCount++;
        }
        assertEquals(32, evenCount, "EVEN_NEW must select half the sections");
        assertEquals(32, oddCount, "ODD_NEW must select the other half");
    }

    /** {@code CULL} はホストが書かないモードなので、語を求めること自体が誤りである。 */
    @Test
    void askingForACullWordIsAMistake() {
        assertThrows(IllegalArgumentException.class,
            () -> visibilityWord(Visibility.CULL, 0, FRAME),
            "CULL means the cull pass writes the buffer; computing a host word would"
                + " silently overwrite what the GPU wrote");
    }
}
