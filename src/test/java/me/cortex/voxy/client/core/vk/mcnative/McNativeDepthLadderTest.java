package me.cortex.voxy.client.core.vk.mcnative;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * {@link McNativeDepthLadder} のうち、Minecraft のデバイスを要らない部分を固定する。
 *
 * <p>梯子が MC の深度に対して実際に何を測ったかは {@code scripts/verify.py --only native} の
 * 二つ目の起動が測る。ここで確かめるのは、(1) フラグが無い限り何もしないこと、(2) 証跡が
 * gate の<b>固定した</b>基準 (段の深度、帯、パレット) と同じ定数を出すこと、(3) 既定状態の
 * 証跡が測っていないものを測ったと言わないこと、(4) <b>パイプラインの深度状態そのもの</b>が
 * 書き込み無効であること (round-8 review: JSON の定数を守るテストは bytecode の
 * {@code depthWriteEnable(true)} を見逃した)、(5) 画素の分類が三値量子化のとおりであること。
 */
public class McNativeDepthLadderTest {

    @Test
    void theLadderIsInertWithoutItsFlag() {
        assertFalse(Boolean.getBoolean(McNativeDepthLadder.FLAG),
            "the depth ladder must not be enabled by default in a test JVM");
        assertFalse(McNativeDepthLadder.attempted());
        assertEquals(0, McNativeDepthLadder.drawsRecorded());
        assertTrue(McNativeDepthLadder.samples().isEmpty());

        // Minecraft is not running here; every entry point must still be a silent no-op.
        assertDoesNotThrow(McNativeDepthLadder::renderIfEnabled);
        assertDoesNotThrow(McNativeDepthLadder::shutdown);
        assertDoesNotThrow(() -> McNativeDepthLadder.shutdownImmediate(null));

        assertFalse(McNativeDepthLadder.attempted(), "a disabled ladder must not record an attempt");
        assertEquals(0, McNativeDepthLadder.drawsRecorded());
        String json = McNativeDepthLadder.json();
        assertTrue(json.contains("\"enabled\": false"), json);
        assertTrue(json.contains("\"drawsRecorded\": 0"), json);
        assertTrue(json.contains("\"samples\": []"), json);
        // (another test in this class deliberately leaves a creation-refusal note; a disabled
        // ladder must add none of its own, which is what `after` above establishes)
    }

    /**
     * <b>深度テスト有効・書き込み無効が、パイプラインに渡す状態そのものに入っていること。</b>
     *
     * <p>{@code pipeline()} は {@link McNativeDepthLadder#depthStencilState} を使う。
     * これが {@code depthWriteEnable(true)} に変われば、梯子は MC の深度ではなく
     * 自分の前の段を測ることになる。三つの比較演算すべてで確かめる。
     */
    @Test
    void everyPipelineTestsDepthWithoutWritingIt() {
        // ⚠ round-9 review R9-TEST-COVERAGE: the test used to iterate its own list, so
        // create() passing ALWAYS where LESS belonged escaped. create() and record() now read
        // PIPELINE_COMPARE_OPS by the OP_* indices; this pins the table and the indices.
        int[] ops = McNativeDepthLadder.compareOps();
        assertArrayEquals(McNativeDepthLadder.PIPELINE_COMPARE_OPS, ops);
        assertEquals(VK_COMPARE_OP_LESS, ops[McNativeDepthLadder.OP_LESS], "the rungs must be LESS");
        assertEquals(VK_COMPARE_OP_ALWAYS, ops[McNativeDepthLadder.OP_ALWAYS], "the base must be ALWAYS");
        assertEquals(VK_COMPARE_OP_GREATER, ops[McNativeDepthLadder.OP_GREATER],
            "the complementary control must be GREATER");
        assertEquals(3, ops.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int op : ops) {
                var state = McNativeDepthLadder.depthStencilState(stack, op);
                assertTrue(state.depthTestEnable(), "depth test must be on for compare " + op);
                assertFalse(state.depthWriteEnable(),
                    "the ladder must never write Minecraft's depth (compare " + op + ")");
                assertEquals(op, state.depthCompareOp());
                assertFalse(state.depthBoundsTestEnable());
                assertFalse(state.stencilTestEnable());
            }
        }
    }

    /**
     * <b>作成経路そのものが、表の添字どおりの比較演算と書き込み無効を create-info に入れること。</b>
     *
     * <p>round-10 review R10-CREATE-TEST: 表と helper を検査するテストは、create() の呼び出し
     * 箇所で LESS を ALWAYS に差し替える変異を見逃した。ここでは {@code buildPipelines} —
     * {@code create()} が本番で使う同じ経路 — を偽の creator で呼び、渡された create-info の
     * 深度状態を添字ごとに検査する。デバイスは要らない (作成関数を呼ばないので)。
     */
    @Test
    void creationHandsTheCreatorTheTablesCompareOpsWithWritesOff() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var seen = new java.util.ArrayList<int[]>();
            long[] handles = McNativeDepthLadder.buildPipelines(stack, 0L, 0L, 0L, 37, 126,
                info -> {
                    var ds = info.get(0).pDepthStencilState();
                    assertNotNull(ds, "every pipeline must carry a depth-stencil state");
                    seen.add(new int[] {ds.depthCompareOp(), ds.depthTestEnable() ? 1 : 0,
                        ds.depthWriteEnable() ? 1 : 0});
                    assertEquals(126, info.get(0).pNext() == 0 ? -1
                        : org.lwjgl.vulkan.VkPipelineRenderingCreateInfo.create(info.get(0).pNext())
                            .depthAttachmentFormat(), "the depth attachment format must be passed");
                    return 1000 + seen.size();
                });
            assertEquals(3, seen.size(), "exactly three pipelines are built");
            assertArrayEquals(new long[] {1001, 1002, 1003}, handles);
            assertEquals(VK_COMPARE_OP_LESS, seen.get(McNativeDepthLadder.OP_LESS)[0]);
            assertEquals(VK_COMPARE_OP_ALWAYS, seen.get(McNativeDepthLadder.OP_ALWAYS)[0]);
            assertEquals(VK_COMPARE_OP_GREATER, seen.get(McNativeDepthLadder.OP_GREATER)[0]);
            for (int[] state : seen) {
                assertEquals(1, state[1], "depth test on");
                assertEquals(0, state[2], "depth writes OFF in the create-info the creator receives");
            }
        }
    }

    /**
     * <b>creator の中で状態を書き換えられたら、buildPipelines はそれを観測して失敗すること。</b>
     *
     * <p>round-11 review R10-CREATE-TEST: 本番の creator (Vulkan を呼ぶ 3 行) はテストが差し替える
     * 継ぎ目で、その中で LESS→ALWAYS や書き込み有効に変える変異は 6 テストを通った。いまは
     * buildPipelines が creator の戻った後に構造体を読み戻し、違えば 0 を返して note を残し、
     * 証跡が {@code depthWritesEnabled: true} / 違う pipelineStates を公開する — gate が落とす。
     */
    @Test
    void aCreatorThatRewritesTheStateIsObservedAndRefused() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] handles = McNativeDepthLadder.buildPipelines(stack, 0L, 0L, 0L, 37, 126,
                info -> {
                    var ds = info.get(0).pDepthStencilState();
                    if (ds.depthCompareOp() == VK_COMPARE_OP_LESS) ds.depthCompareOp(VK_COMPARE_OP_ALWAYS);
                    return 7;
                });
            assertArrayEquals(new long[] {0, 0, 0}, handles, "a rewritten state must yield no pipelines");
            int[][] states = McNativeDepthLadder.pipelineStates();
            assertEquals(VK_COMPARE_OP_ALWAYS, states[McNativeDepthLadder.OP_LESS][0],
                "the observed state is what the creator handed on");
            assertTrue(McNativeDepthLadder.json().contains("\"notes\": [\"the depth-stencil state handed"),
                McNativeDepthLadder.json());
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] handles = McNativeDepthLadder.buildPipelines(stack, 0L, 0L, 0L, 37, 126,
                info -> {
                    info.get(0).pDepthStencilState().depthWriteEnable(true);
                    return 7;
                });
            assertArrayEquals(new long[] {0, 0, 0}, handles);
            assertTrue(McNativeDepthLadder.json().contains("\"depthWritesEnabled\": true"),
                "the evidence must say writes were enabled: " + McNativeDepthLadder.json());
        }
        // and the honest creator restores the observed states to the intended ones
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] handles = McNativeDepthLadder.buildPipelines(stack, 0L, 0L, 0L, 37, 126, info -> 9);
            assertArrayEquals(new long[] {9, 9, 9}, handles);
            int[][] states = McNativeDepthLadder.pipelineStates();
            assertEquals(3, states.length);
            for (int i = 0; i < 3; i++) {
                assertEquals(McNativeDepthLadder.PIPELINE_COMPARE_OPS[i], states[i][0]);
                assertEquals(1, states[i][1]);
                assertEquals(0, states[i][2]);
            }
            assertTrue(McNativeDepthLadder.json().contains("\"depthWritesEnabled\": false"));
            assertTrue(McNativeDepthLadder.json().contains("\"pipelineStates\": [[1, 1, 0], [7, 1, 0], [4, 1, 0]]"),
                McNativeDepthLadder.json());
        }
    }

    /**
     * <b>gate が固定している基準と同じ定数を出すこと。</b>
     *
     * <p>{@code scripts/verify.py} は段の深度、帯、パレットを<b>自分の定数</b>と比べる
     * (producer が公開した幾何は基準にならない — round 6 B1)。ここが変われば gate も
     * 変えなければならず、そのことをこのテストが思い出させる。
     */
    @Test
    void thePublishedGeometryIsTheOneTheGatePins() {
        float[] z = McNativeDepthLadder.depths();
        assertEquals(8, McNativeDepthLadder.RUNGS);
        assertEquals(8, z.length);
        var expected = new StringBuilder("\"rungDepths\": [");
        for (int i = 0; i < z.length; i++) {
            // 2^-16 .. 2^-2, ascending: reverse-Z depth of order near/d, ~3000 down to 0.2 blocks
            assertEquals(Math.scalb(1.0f, -(16 - 2 * i)), z[i], 0f, "rung " + i);
            if (i > 0) assertTrue(z[i] > z[i - 1], "rungs must ascend so colour means max{i: z_i < d}");
            expected.append(i > 0 ? ", " : "").append(z[i]);
        }
        String json = McNativeDepthLadder.json();
        assertTrue(json.contains("\"rungs\": 8"), json);
        assertTrue(json.contains(expected.append(']').toString()), json);
        assertTrue(json.contains("\"band\": [-0.6, 0.36, 0.6, 0.2]"), json);
        assertEquals(10, McNativeDepthLadder.PALETTE.length, "base + low + eight rungs");
        assertTrue(json.contains("\"palette\": [[1.0, 1.0, 1.0], [0.5, 0.5, 0.5], [1.0, 0.0, 1.0],"
            + " [0.0, 1.0, 1.0], [1.0, 1.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0],"
            + " [0.0, 0.0, 1.0], [1.0, 0.5, 0.0], [0.5, 0.0, 1.0]]"), json);
        // The band rect formula is the one the gate re-derives.
        // pixel-centre coverage: x=341 (centre 341.5) lies left of the edge at 341.6
        assertArrayEquals(new int[] {342, 307, 1366, 384},
            McNativeDepthLadder.bandRect(1708, 960, false));
        assertArrayEquals(new int[] {342, 576, 1366, 653},
            McNativeDepthLadder.bandRect(1708, 960, true));
        assertEquals(342, McNativeDepthLadder.covered(341.6f));
        assertEquals(1366, McNativeDepthLadder.covered(1366.4f));
        assertEquals(576, McNativeDepthLadder.covered(576.0f));
    }

    /** <b>画素の分類</b>: 三値量子化で 10 色を見分け、境界の外は other。 */
    @Test
    void pixelsAreClassifiedByThreeLevelQuantisation() {
        int other = McNativeDepthLadder.PALETTE.length;
        assertEquals(McNativeDepthLadder.BASE, McNativeDepthLadder.classify(255, 255, 255));
        assertEquals(McNativeDepthLadder.BASE, McNativeDepthLadder.classify(200, 240, 192));
        assertEquals(McNativeDepthLadder.LOW, McNativeDepthLadder.classify(128, 127, 128));
        assertEquals(McNativeDepthLadder.LOW, McNativeDepthLadder.classify(96, 160, 120));
        assertEquals(McNativeDepthLadder.RUNG0, McNativeDepthLadder.classify(255, 0, 255));
        assertEquals(McNativeDepthLadder.RUNG0 + 1, McNativeDepthLadder.classify(0, 255, 255));
        assertEquals(McNativeDepthLadder.RUNG0 + 6, McNativeDepthLadder.classify(255, 128, 0));
        assertEquals(McNativeDepthLadder.RUNG0 + 7, McNativeDepthLadder.classify(128, 0, 255));
        // Scene colours: anything with a channel in a gap is "other", as is black-ish grass.
        assertEquals(other, McNativeDepthLadder.classify(80, 80, 80));
        assertEquals(other, McNativeDepthLadder.classify(170, 0, 255));
        assertEquals(other, McNativeDepthLadder.classify(60, 120, 30));
        // (0,0,0) is a valid three-level code but not a palette entry: other, never a rung.
        assertEquals(other, McNativeDepthLadder.classify(0, 0, 0));
    }

    /** <b>既定状態の証跡が、測っていないことを測ったと言わないこと。</b> */
    @Test
    void theDefaultEvidenceClaimsNothingUnmeasured() {
        String json = McNativeDepthLadder.json();
        assertTrue(json.contains("\"attempted\": false"), json);
        assertTrue(json.contains("\"completed\": false"), json);
        assertTrue(json.contains("\"depthWritesEnabled\": false"), json);
        assertTrue(json.contains("\"zConventionMeasuredHere\": false"), json);
        assertTrue(json.contains("\"terrainProbeEnabled\": false"), json);
        assertTrue(json.contains("\"terrainDrawsRecorded\": 0"), json);
        assertTrue(json.contains("\"markerDrawEnabled\": false"), json);
        assertTrue(json.contains("\"markerDrawsRecorded\": 0"), json);
        assertTrue(json.contains("\"deviceDiverged\": false"), json);
        assertFalse(json.contains("reversedZ"), "no convention may be published: " + json);
        assertFalse(json.contains("lowerBound") || json.contains("upperBound"),
            "a band-wide bound is not a thing this ladder measures: " + json);
        assertTrue(json.contains("\"frameScale\": 4"), json);
        assertEquals(4, McNativeDepthLadder.FRAME_SCALE);
    }
}
