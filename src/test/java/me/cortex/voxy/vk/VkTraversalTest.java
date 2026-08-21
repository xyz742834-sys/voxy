package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * <b>階層トラバーサルが「木の切り口」を選ぶこと</b> (Phase 5c-4b)。
 *
 * <h2>なぜ合成の木なのか</h2>
 * トラバーサルは<b>何を描くかを GPU が選ぶ</b>ので、実データでは出力を予測できない。
 * 木の形をこちらが決めれば「この構成なら必ずこれが出る」が言えるので、
 * <b>機構の誤り</b>と<b>選択の誤り</b>を切り分けられる
 * [docs/phase5c4-plan.md 3]。
 *
 * <h2>この検査を空虚に満たす方法と、その塞ぎ方</h2>
 * <ol>
 *   <li><b>全部描く</b> → {@link #aCoarseThresholdDrawsOnlyTheRoot} が落ちる</li>
 *   <li><b>何も描かない</b> → どの検査も「非空」を同時に要求する</li>
 *   <li><b>上限に当たっても黙って捨てる</b> →
 *       {@link #overflowingTheQueueIsBoundedAndCountedExactly} が<b>数まで</b>要求する</li>
 * </ol>
 */
public class VkTraversalTest {
    private static final int HIZ_W = 64, HIZ_H = 64, HIZ_LEVELS = 6;
    /** 木の根の LoD。子は 1 段細かい。 */
    private static final int ROOT_LEVEL = 1;

    private static VkTexture hiz;

    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
        VkFrameTracker.init();
        // ⚠ HiZ は**全部 FAR** にする。遮蔽を効かせないことで、
        // この検査が見ているものを「視錐台と LoD の選択」だけに絞る [1 変数ずつ]
        hiz = new VkTexture(VkHiZ.FORMAT, HIZ_LEVELS, HIZ_W, HIZ_H,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT).name("hizAllFar");
        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        for (int l = 0; l < HIZ_LEVELS; l++) {
            hiz.barrier(cmd, l, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        }
        try (MemoryStack stack = stackPush()) {
            var clear = VkClearColorValue.calloc(stack);
            clear.float32(0, VkDepth.FAR);
            var range = VkImageSubresourceRange.calloc(1, stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(HIZ_LEVELS).baseArrayLayer(0).layerCount(1);
            vkCmdClearColorImage(cmd, hiz.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, range);
        }
        for (int l = 0; l < HIZ_LEVELS; l++) {
            hiz.barrier(cmd, l, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        }
        t.endFrame();
        t.waitForFrame();
    }

    @AfterAll
    static void teardown() {
        if (VkFrameTracker.isRecordingFrame()) VkFrameTracker.get().endFrame();
        VkFrameTracker.get().waitIdle();
        if (hiz != null) hiz.free();
        VkSampler.shutdown();
        VkFrameTracker.shutdown();
    }

    /** カメラは原点で -Z を向く。木は前方に置く。 */
    private static Matrix4f mvp() {
        return new Matrix4f().set(VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(70), 1.0f, 0.05f, 4096f),
            VkSceneUniform.lookAt(new float[]{0, 0, 0}, new float[]{0, 0, -1}, new float[]{0, 1, 0})));
    }

    /** 木を作る。メッシュ id = ノード id なので、描かれたものからノードへ戻せる。 */
    private static VkNodeTree treeInFront() {
        var t = new VkNodeTree();
        // 根は LoD 1、セクション座標 (0,0,-2) -> ワールド [0,64) x [0,64) x [-128,-64)
        int root = t.add(ROOT_LEVEL, 0, 0, -2, 0, 1, 8);
        for (int i = 0; i < 8; i++) {
            t.addLeaf(ROOT_LEVEL - 1, (i & 1), ((i >> 1) & 1), -4 + ((i >> 2) & 1), 1 + i);
        }
        t.markTop(root);
        return t;
    }

    /** 1 フレーム走らせて、描かれたメッシュ id を返す。 */
    private static int[] run(VkTraversal tr, VkNodeTree tree, float minSSS) {
        return run(tr, tree, minSSS, new int[]{0, 0, 0});
    }

    /**
     * カメラのセクションを指定して走らせる。
     *
     * <p>⚠ MVP は<b>カメラ相対</b>なので動かさない。
     * {@code camSecPos} を動かすと<b>世界のほうが動く</b> —
     * これは本番と同じ組み立てである [{@code basePos = ((pos&lt;&lt;lod)-camSecPos)&lt;&lt;5 - camSubSecPos}]。
     */
    private static int[] run(VkTraversal tr, VkNodeTree tree, float minSSS, int[] camSection) {
        tree.write(tr.nodeData);
        tree.writeTopNodes(tr.topNodeIds);
        var m = mvp();
        tr.writeUniform(m, camSection, new float[]{0, 0, 0},
            (HIZ_W << 16) | HIZ_H, minSSS, VkHostViewport.frustumPlanes(m), 1, -1.0f);
        tr.reset(tree.topNodes().length);

        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        tr.record(cmd, tree.topNodes().length);
        t.endFrame();
        t.waitForFrame();
        int[] drawn = tr.renderedMeshes();
        Arrays.sort(drawn);
        return drawn;
    }

    private static VkTraversal traversal(int queueCapacity) {
        return new VkTraversal(hiz, 64, queueCapacity, 256, 256);
    }

    // ================= 【B】両方向 =================

    /**
     * <b>降りない設定では根だけが描かれること。</b>
     *
     * <p>⚠ 「全部描く」実装をここが落とす。
     */
    @Test
    void aCoarseThresholdDrawsOnlyTheRoot() {
        var tr = traversal(64);
        try {
            int[] drawn = run(tr, treeInFront(), 1e9f);   // 降下の閾値が巨大 = 降りない
            assertArrayEquals(new int[]{0}, drawn,
                "only the root should be drawn, got " + Arrays.toString(drawn));
            assertEquals(0, tr.droppedNodePushes(), "nothing should have been dropped");
        } finally { tr.free(); }
    }

    /**
     * <b>降りる設定では子だけが描かれ、根は描かれないこと。</b>
     *
     * <p>⚠ これが【B】の反対側である。片方だけでは
     * 「常に根」「常に子」と区別が付かない [5c-1e と同じ形]。
     */
    @Test
    void aFineThresholdDrawsTheChildrenAndNotTheRoot() {
        var tr = traversal(64);
        try {
            int[] drawn = run(tr, treeInFront(), 0.0f);   // 閾値 0 = 降りられるだけ降りる
            assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8}, drawn,
                "all eight children and nothing else should be drawn, got " + Arrays.toString(drawn));
        } finally { tr.free(); }
    }

    // ================= 【A】反鎖 (切り口であること) =================

    /**
     * <b>描かれた集合は反鎖であること</b> — 祖先と子孫が同時に描かれてはならない。
     *
     * <h2>なぜこれが要なのか</h2>
     * トラバーサルは各ノードで「自分を描く」か「子を積む」かのどちらかをする。
     * したがって出力は<b>木の切り口</b>でなければならない。
     * <b>どの切り口かを予測せずに、切り口であることを主張できる</b> [規約 4]。
     *
     * <table>
     *   <tr><th>破れ方</th><th>絵に出る形</th></tr>
     *   <tr><td>祖先と子孫が両方</td><td>同じ体積が<b>二重に描かれる</b> (深度で争う)</td></tr>
     *   <tr><td>どちらも無い</td><td><b>穴が開く</b></td></tr>
     * </table>
     */
    @Test
    void theDrawnSetIsAlwaysAnAntichain() {
        // 閾値を振って、どの切り口が選ばれても反鎖であることを要求する
        for (float minSSS : new float[]{1e9f, 1.0f, 0.5f, 0.1f, 0.01f, 0.0f}) {
            var tr = traversal(64);
            try {
                var tree = treeInFront();
                int[] drawn = run(tr, tree, minSSS);
                assertTrue(drawn.length > 0,
                    "nothing was drawn at minSSS=" + minSSS + "; the antichain check would be vacuous");
                for (int a : drawn) {
                    for (int b : drawn) {
                        if (a == b) continue;
                        assertFalse(tree.isAncestorOf(a, b),
                            "at minSSS=" + minSSS + ": node " + a + " is an ancestor of node " + b
                                + " and both were drawn — the same volume is covered twice");
                    }
                }
            } finally { tr.free(); }
        }
    }

    // ================= 視錐台 =================

    /**
     * <b>カメラの後ろの木は描かれないこと</b>。そして<b>前の木は描かれること</b>。
     *
     * <p>⚠ 期待値は<b>「カメラの後ろ」という配置の事実</b>であって、
     * シェーダの平面判定を再実行したものではない [規約 4]。
     */
    @Test
    void aTreeBehindTheCameraIsCulledButOneInFrontIsNot() {
        var tr = traversal(64);
        try {
            assertTrue(run(tr, treeInFront(), 1e9f).length > 0,
                "control: the tree in front must be drawn");

            var behind = new VkNodeTree();
            // カメラの後ろ (+Z 側)
            int root = behind.add(ROOT_LEVEL, 0, 0, 4, 0, 1, 8);
            for (int i = 0; i < 8; i++) behind.addLeaf(ROOT_LEVEL - 1, i & 1, 0, 9, 1 + i);
            behind.markTop(root);

            assertEquals(0, run(tr, behind, 1e9f).length,
                "a tree behind the camera must be culled by the frustum");
        } finally { tr.free(); }
    }

    // ================= 上限 =================

    /**
     * <b>キューが溢れても範囲外に触らず、捨てた数が正確に数えられること</b>。
     *
     * <h2>なぜ「0 でない」では足りないのか</h2>
     * 上限が効いて捨てたのに回数が 0 なら、カウンタが機能していない。
     * 逆に、数が合っていなければ<b>「捨てた」と「そもそも来なかった」が区別できない</b>
     * [規約 18]。だから<b>数まで</b>要求する。
     *
     * <h2>予測</h2>
     * 容量 4 のキューに根が子 8 つを積むので:
     * <ul>
     *   <li>書き込み: 添字 0..3 が入り、<b>4..7 は捨てられる</b> → {@code droppedNodePushes == 4}</li>
     *   <li>読み出し: 個数は 8 のままなので、<b>4..7 は範囲外</b> → {@code droppedNodeReads == 4}</li>
     *   <li>描かれるのは<b>入った 4 つだけ</b></li>
     * </ul>
     */
    @Test
    void overflowingTheQueueIsBoundedAndCountedExactly() {
        var tr = traversal(4);              // ⚠ 子 8 つに対して容量 4
        try {
            int[] drawn = run(tr, treeInFront(), 0.0f);

            assertEquals(4, drawn.length,
                "only the four children that fit should be drawn, got " + Arrays.toString(drawn));
            assertEquals(4, tr.droppedNodePushes(),
                "four pushes did not fit and must be counted exactly");
            assertEquals(4, tr.droppedNodeReads(),
                "the metadata still says eight, so four reads are out of range and must be counted");
            for (int d : drawn) {
                assertTrue(d >= 1 && d <= 8, "a drawn mesh id is out of range: " + d);
            }
        } finally { tr.free(); }
    }

    /**
     * <b>対照</b>: 容量が足りていれば 1 つも捨てないこと。
     *
     * <p>これが無いと「常に 4 を返すカウンタ」と区別が付かない [規約 11]。
     */
    @Test
    void aQueueThatFitsDropsNothing() {
        var tr = traversal(64);
        try {
            int[] drawn = run(tr, treeInFront(), 0.0f);
            assertEquals(8, drawn.length, "all eight children fit");
            assertEquals(0, tr.droppedNodePushes(), "nothing should be dropped when it fits");
            assertEquals(0, tr.droppedNodeReads(), "nothing should be read out of range");
        } finally { tr.free(); }
    }

    /**
     * <b>ディスパッチの間にバリアが積まれていること</b>。
     *
     * <h2>⚠⚠ この検査が捕まえる範囲を<b>実測した</b></h2>
     * この環境では<b>同期バリデーションが機能しない</b>ので、
     * バリアの正しさは観測では検証できない [未検証]。
     * どこまで捕まるのかを変異で測った:
     *
     * <table>
     *   <tr><th>変異</th><th>結果</th></tr>
     *   <tr><td>ループ内の {@code barrier(cmd)} 呼び出しを消す</td><td>✅ <b>この検査が落ちる</b></td></tr>
     *   <tr><td>{@code barrier()} の中身 ({@code computeToIndirect}) を空にする</td>
     *       <td>❌ <b>どの検査も通る</b></td></tr>
     * </table>
     *
     * <p>後者が通るのは、<b>数えているのが呼び出しであって効果ではない</b>ためと、
     * 仕事量が小さいと GPU が事実上直列に走るためである。
     *
     * <p><b>したがって、この検査は「呼び出しが消えたこと」しか言わない。</b>
     * バリアの範囲が足りているかは、<b>この環境では誰も見張っていない</b> [規約 11]。
     */
    @Test
    void everyDispatchIsSeparatedByABarrier() {
        var tr = traversal(64);
        try {
            run(tr, treeInFront(), 0.0f);
            assertEquals(VkTraversal.MAX_ITERATIONS, tr.barriersEmitted(),
                "one barrier between each pair of dispatches, plus one after the last;"
                    + " the next iteration reads the previous one's count through"
                    + " vkCmdDispatchIndirect");
        } finally { tr.free(); }
    }

    // ================= 【C】LoD と距離 =================

    /** 描かれたうち<b>最も細かい</b> LoD レベル (小さいほど細かい)。 */
    private static int finestLevel(VkNodeTree tree, int[] drawnMeshes) {
        int finest = Integer.MAX_VALUE;
        for (int mesh : drawnMeshes) finest = Math.min(finest, tree.levelOf(mesh));
        return finest;
    }

    /**
     * <b>同じフレームで、近いところは細かく、遠いところは粗く選ばれること</b>。
     *
     * <p>⚠ <b>両方向を 1 回の実行で要求する</b> — 片方だけだと
     * 「常に細かい」「常に粗い」を落とせない [5c-1e と同じ構造]。
     * 木は 2 本、<b>大きさは同じで距離だけが違う</b>。
     */
    @Test
    void nearThingsAreDrawnFinerThanFarThingsInTheSameFrame() {
        var tr = new VkTraversal(hiz, 256, 512, 512, 256);
        try {
            // 近い木 (LoD0 セクション -8 付近) と 遠い木 (-64 付近)。大きさは同じ
            var near = VkNodeTree.twoLevelOctree(2, 0, 0, -2);
            var far  = VkNodeTree.twoLevelOctree(2, 0, 0, -16);

            int[] drawnNear = run(tr, near, 0.02f);
            int[] drawnFar  = run(tr, far,  0.02f);

            assertTrue(drawnNear.length > 0, "control: the near tree must be drawn at all");
            assertTrue(drawnFar.length > 0, "control: the far tree must be drawn at all");

            int fNear = finestLevel(near, drawnNear);
            int fFar  = finestLevel(far,  drawnFar);
            assertTrue(fNear < fFar,
                "the near tree must be drawn finer than the far one, got near=" + fNear
                    + " far=" + fFar + " (smaller is finer)"
                    + "; near drew " + drawnNear.length + " nodes, far drew " + drawnFar.length);

            // ...そして遠いほうは根で止まっていること (「両方とも細かい」を落とす)
            assertEquals(2, fFar, "the far tree should not have descended at all");
        } finally { tr.free(); }
    }

    /**
     * <b>カメラを遠ざけると切り口は粗くなる一方で、細かくなることはないこと。</b>
     *
     * <p>個々の選択を予測せず、<b>変化の向きだけ</b>を言う。
     * 5c-1c で {@code movingTheCameraCloserIncreasesTheClosestDepth} を作ったのと同じ発想。
     *
     * <h2>⚠ 両方向</h2>
     * <ul>
     *   <li>遠ざかる → 最も細かいレベルは<b>細かくならない</b> (単調非減少)</li>
     *   <li>近づく → <b>粗くならない</b> (同じ列を逆に読むので同時に主張される)</li>
     *   <li>⚠ そして<b>実際に変わること</b> — 全部同じなら単調性は何も言っていない</li>
     * </ul>
     *
     * <p>⚠ 「遠ざかると描かれる数が<b>減る</b>」とは言えない。
     * 粗くなるので数は減りうるが、<b>体積は同じ</b>である。増えないことだけを言う。
     */
    @Test
    void movingAwayOnlyEverCoarsensTheCut() {
        var tr = new VkTraversal(hiz, 256, 512, 512, 256);
        try {
            var tree = VkNodeTree.twoLevelOctree(2, 0, 0, -2);
            int[] distances = {0, 2, 4, 8, 16, 32};
            int[] finest = new int[distances.length];
            int[] counts = new int[distances.length];

            for (int i = 0; i < distances.length; i++) {
                // camSecPos.z を増やす = カメラが +Z へ下がる = 木が遠ざかる
                int[] drawn = run(tr, tree, 0.02f, new int[]{0, 0, distances[i]});
                assertTrue(drawn.length > 0,
                    "nothing drawn at camera section z=" + distances[i]
                        + "; the monotonicity claim would be vacuous");
                finest[i] = finestLevel(tree, drawn);
                counts[i] = drawn.length;
            }
            System.out.println("[vk] cut vs distance: finest=" + Arrays.toString(finest)
                + " drawn=" + Arrays.toString(counts));

            for (int i = 1; i < distances.length; i++) {
                assertTrue(finest[i] >= finest[i - 1],
                    "moving away made the cut FINER at z=" + distances[i]
                        + ": finest went " + finest[i - 1] + " -> " + finest[i]
                        + " (" + Arrays.toString(finest) + ")");
                assertTrue(counts[i] <= counts[i - 1],
                    "moving away increased the number of drawn nodes at z=" + distances[i]
                        + ": " + counts[i - 1] + " -> " + counts[i]
                        + " (" + Arrays.toString(counts) + ")");
            }

            // ⚠ 実際に変わっていること。全部同じなら上の単調性は空虚である
            assertTrue(finest[finest.length - 1] > finest[0],
                "the cut never coarsened across the whole sweep " + Arrays.toString(finest)
                    + " — the monotonicity check is not distinguishing anything");
        } finally { tr.free(); }
    }
}
