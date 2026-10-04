package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.VkDepth;
import me.cortex.voxy.client.core.vk.VkFrameTracker;
import me.cortex.voxy.client.core.vk.VkRenderTarget;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>D3 の常設回帰</b>: {@code LOAD} されるアタッチメントのバリアが、
 * そのパスが実際に行う<b>読み</b>を宣言していること。
 *
 * <h2>何を見ているのか</h2>
 * {@link VkRenderTarget#beginRendering} は {@code clearColor == null} で
 * カラーを、{@code clearDepth == null} で深度を {@code VK_ATTACHMENT_LOAD_OP_LOAD}
 * にする。LOAD は<b>アタッチメントを読む</b>操作なので、
 * 遷移の dstAccess に {@code ..._ATTACHMENT_READ_BIT} が無いと、
 * 直前の書き込み (前のパスの書き込み、あるいは<b>レイアウト遷移そのもの</b>) と
 * この読みとの間にメモリ依存が張られない。
 * 同期バリデーションはこれを {@code SYNC-HAZARD-READ-AFTER-WRITE} として報告する
 * [確認済 — D3。修正前は {@link VkHiZDepthSourceTest} が 3 件出していた]。
 *
 * <h2>⚠ 2 本立てで見る</h2>
 * <ol>
 *   <li><b>ハザードが出ないこと</b> — これが D3 の本体。
 *       <b>バリデーション無効の実行では何も主張できない</b> (メッセージが 1 件も
 *       溜まらないため) ので、意味を持つのは {@code -PvkValidation=true
 *       -PvkSyncEnv=true} の実行だけである。それでも<b>skip にはしない</b>:
 *       下の 2. は常に主張できるし、skip は「通った」と見分けが付きにくい。</li>
 *   <li><b>LOAD が LOAD のままであること</b> — 直前のパスが書いた中身が
 *       次のパスに<b>残っている</b>ことを読み戻して確かめる。
 *       ハザードを「常に CLEAR する」「LOAD をやめる」で消す修正は、
 *       1. を満たしても<b>ここで落ちる</b>。</li>
 * </ol>
 *
 * <p>⚠ グローバルな {@link VkContext#validationMessages()} は他のテストの分も
 * 溜まっているので、<b>自分が積んだ区間だけ</b>を切り出して見る
 * ({@link #hazardsFrom}) — {@code clearValidationMessages()} で消すと
 * 他のテスト ({@link VkBarriersTest} の陽性対照など) の足を払う。
 */
public class VkAttachmentLoadHazardTest {
    private static final int W = 32, H = 16;

    /** 1 パス目が書く色。2 パス目の LOAD でそのまま残っていなければならない。 */
    private static final float[] FIRST_COLOUR = {0.0f, 1.0f, 0.0f, 1.0f};
    private static final int[] FIRST_COLOUR_RGBA = {0, 255, 0, 255};

    /** 1 パス目が書く深度。クリア既定値 ({@link VkDepth#CLEAR}) と<b>違う</b>値であること。 */
    private static final float FIRST_DEPTH = 0.5f;

    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
        VkFrameTracker.init();
    }

    @AfterAll
    static void teardown() {
        if (VkFrameTracker.isRecordingFrame()) VkFrameTracker.get().endFrame();
        VkFrameTracker.get().waitIdle();
        VkFrameTracker.shutdown();
    }

    /**
     * <b>観測された D3 そのものの形</b>: 新品のターゲットにカラー LOAD で描き始める
     * ({@link VkHiZDepthSourceTest} が {@code beginRendering(cmd, null, clearDepth)} で
     * 通る経路)。
     *
     * <p>「新品なら前の書き込みは無い」は<b>誤り</b>である —
     * {@code UNDEFINED -> COLOR_ATTACHMENT_OPTIMAL} のレイアウト遷移それ自体が
     * 書き込みとして扱われ、LOAD の読みはそれと競合する。
     */
    @Test
    void aFreshTargetThatLoadsItsColourIsHazardFree() {
        var target = new VkRenderTarget(W, H);
        try {
            int mark = mark();
            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            target.beginRendering(cmd, null, VkDepth.CLEAR);   // カラー LOAD / 深度 CLEAR
            target.endRendering(cmd);
            t.endFrame();
            t.waitForFrame();

            assertNoReadAfterWrite(mark, "a LOAD-ed colour attachment on a fresh render target");
        } finally {
            target.free();
        }
    }

    /**
     * <b>深度側の兄弟</b>: {@code clearDepth == null} は深度を LOAD にする。
     * 観測された診断はカラー側だけだったが、ソース上は同じ形である
     * (docs/ai/gpu-contracts.md の D3)。
     */
    @Test
    void aFreshTargetThatLoadsItsDepthIsHazardFree() {
        var target = new VkRenderTarget(W, H);
        try {
            int mark = mark();
            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            target.beginRendering(cmd, FIRST_COLOUR, null);    // カラー CLEAR / 深度 LOAD
            target.endRendering(cmd);
            t.endFrame();
            t.waitForFrame();

            assertNoReadAfterWrite(mark, "a LOAD-ed depth attachment on a fresh render target");
        } finally {
            target.free();
        }
    }

    /**
     * <b>本物の RAW</b>: 同じコマンドバッファで「書くパス -> 両方を LOAD するパス」と積む。
     * 2 パス目の LOAD は 1 パス目の書き込みを読むので、
     * src 側で<b>その書き込みが available</b> になっていなければならない。
     *
     * <p>⚠ 併せて<b>中身が残っていること</b>も見る (上記 2.)。
     */
    @Test
    void loadingBothAttachmentsAfterAPassThatWroteThemIsHazardFree() {
        var target = new VkRenderTarget(W, H);
        try {
            int mark = mark();
            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            target.beginRendering(cmd, FIRST_COLOUR, FIRST_DEPTH);   // 書く
            target.endRendering(cmd);
            target.beginRendering(cmd, null, null);                  // 両方 LOAD
            target.endRendering(cmd);
            target.recordReadback(cmd);
            target.recordDepthReadback(cmd);
            t.endFrame();
            t.waitForFrame();

            assertNoReadAfterWrite(mark,
                "a second pass that LOADs both attachments written by the first pass");

            assertArrayEquals(FIRST_COLOUR_RGBA, target.pixelAt(W / 2, H / 2),
                "the second pass must LOAD what the first pass wrote; if this is the clear"
                    + " colour or black, the LOAD was dropped or turned into a CLEAR"
                    + " (that silences the hazard instead of fixing it)");
            assertEquals(FIRST_DEPTH, target.depthAt(W / 2, H / 2), 1e-6f,
                "the second pass must LOAD the depth the first pass wrote; "
                    + VkDepth.CLEAR + " means the depth LOAD became a CLEAR");
        } finally {
            target.free();
        }
    }

    /**
     * {@link VkRenderTarget#beginRenderingDepthOnly} も同じ規約に従うこと
     * (遮蔽カリングが通る経路。常に深度 LOAD である)。
     */
    @Test
    void theDepthOnlyHelperIsHazardFree() {
        var target = new VkRenderTarget(W, H);
        try {
            int mark = mark();
            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            target.beginRendering(cmd, FIRST_COLOUR, FIRST_DEPTH);   // 深度を書く
            target.endRendering(cmd);
            target.beginRenderingDepthOnly(cmd);                     // 深度 LOAD
            target.endRendering(cmd);
            target.recordDepthReadback(cmd);
            t.endFrame();
            t.waitForFrame();

            assertNoReadAfterWrite(mark, "beginRenderingDepthOnly after a depth-writing pass");
            assertEquals(FIRST_DEPTH, target.depthAt(W / 2, H / 2), 1e-6f,
                "the depth-only pass must LOAD the depth the previous pass wrote");
        } finally {
            target.free();
        }
    }

    // ---------------- helpers ----------------

    /** 今の指摘件数。これ以降に増えた分だけが「このテストが出した」分である。 */
    private static int mark() {
        return VkContext.validationMessages().size();
    }

    private static List<String> hazardsFrom(int mark) {
        var all = VkContext.validationMessages();
        return all.subList(Math.min(mark, all.size()), all.size()).stream()
            .filter(m -> m.contains("SYNC-HAZARD-READ-AFTER-WRITE"))
            .toList();
    }

    private static void assertNoReadAfterWrite(int mark, String what) {
        var hazards = hazardsFrom(mark);
        System.out.println("[vk] " + what + ": SYNC-HAZARD-READ-AFTER-WRITE = " + hazards.size()
            + " (sync validation " + (VkContext.get().syncValidationEnabled ? "on" : "OFF -"
            + " this case asserts nothing about hazards in this run") + ")");
        assertTrue(hazards.isEmpty(),
            () -> "D3 regression: " + what + " must not produce SYNC-HAZARD-READ-AFTER-WRITE."
                + " The attachment is LOAD-ed, so the pass READS it: the layout transition must"
                + " declare ..._ATTACHMENT_READ_BIT in its destination access and make the prior"
                + " write available in its source access. Got:\n  "
                + String.join("\n  ", hazards));
    }
}
