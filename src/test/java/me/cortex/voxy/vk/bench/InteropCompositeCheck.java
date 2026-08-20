package me.cortex.voxy.vk.bench;

import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer.Barriers;
import me.cortex.voxy.client.core.vk.interop.Cgl;
import me.cortex.voxy.client.core.vk.interop.GlDepthImport;
import me.cortex.voxy.client.core.vk.interop.GlVkSync;
import me.cortex.voxy.client.core.vk.interop.GlInteropCompositor;
import me.cortex.voxy.client.core.vk.interop.VkInteropImage;
import me.cortex.voxy.client.core.vk.interop.VkInteropProbe;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Phase 5b — <b>interop 経由の合成が、Phase 4 のオフスクリーン出力と一致すること。</b>
 *
 * <pre>
 * 参照経路 (Phase 4):  Vulkan 描画 → 自前 RGBA8 画像 → vkCmdCopyImageToBuffer → 画素
 * interop 経路 (5b):   Vulkan 描画 → IOSurface(BGRA) ─┐
 *                      深度解決     → IOSurface(R32F) ─┴→ GL 合成 → GL FBO → glReadPixels → 画素
 * </pre>
 *
 * <b>両者が画素単位でビット一致すること</b>が 5b の合格条件である。
 *
 * <h2>なぜ JUnit ではないか</h2>
 * GL コンテキストが要る。GLFW は macOS で {@code glfwInit} を
 * プロセスの main スレッドから呼ぶことを要求する ({@code -XstartOnFirstThread}) が、
 * Gradle の test worker はテストを別スレッドで走らせるため満たせない
 * [確認済 — docs/phase5a-gl-to-vk-sync.md §7]。
 *
 * <pre>
 * ./gradlew interopCompositeCheck
 * ./gradlew interopCompositeCheck -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true
 * </pre>
 *
 * <h2>この検査を空虚に満たす方法と、その塞ぎ方</h2>
 * <ol>
 *   <li><b>どちらも背景色だけ</b> → 一致は自明。
 *       C1 が被覆画素数と色数の下限を要求する</li>
 *   <li><b>同じものを 2 回読んでいる / 比較が鈍い</b> →
 *       C2 が<b>別の視点で描いた結果とは一致しないこと</b>を要求する</li>
 *   <li><b>深度が壊れていても色が合っていれば通る</b> →
 *       C3 が深度を独立に突き合わせ、
 *       C4 が「{@code gl_FragDepth} を書かない合成」で<b>深度検査が落ちること</b>を確かめ、
 *       さらに<b>そのとき色検査は通ってしまうこと</b>も示す
 *       (= 色検査だけでは深度の破損を捕まえられない、という事実の記録)</li>
 *   <li><b>深度が一様で比較に意味が無い</b> →
 *       C5 が深度の相異なる値の数と非クリア画素数の下限を要求する</li>
 *   <li><b>R と B が入れ替わっていても気付かない</b> (BGRA の IOSurface を扱うので
 *       最も踏みやすい) → C6a が「絵に R != B の画素があること」を、
 *       C6 が「入れ替えたら落ちること」を確かめる</li>
 *   <li><b>上下が反転していても気付かない</b> — 合成の反転と {@code glReadPixels} の反転が
 *       打ち消し合い、<b>配列としては完全に一致してしまう</b>。
 *       <b>実際にこれを踏んだ</b> [docs/phase5b-composite.md §6]。
 *       C7 が両方向を突き合わせて、素通りが起きないことを見張る</li>
 * </ol>
 */
public final class InteropCompositeCheck {
    static final int W = 512, H = 512;
    static final float[] CLEAR = {0.05f, 0.05f, 0.10f, 1.0f};
    static final Path OUT = Path.of("build", "vk-test-output");

    static long window;
    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        initGl();
        VkContext.init();
        VkFrameTracker.init();

        SyntheticTerrain terrain = SyntheticTerrain.boundaryCases();
        VkTerrainResources res = null;
        VkRenderTarget refRt = null, ioRt = null;
        VkTerrainRenderer refRenderer = null, ioRenderer = null;
        VkDepthResolve resolve = null;
        VkInteropImage colourInterop = null, depthInterop = null;
        GlInteropCompositor compositor = null, compositorNoDepth = null,
            compositorSwapped = null, compositorFlipped = null;
        VkBuffer depthReadback = null;

        try {
            var scene = buildScene(terrain);
            res = scene.res();
            int drawCount = scene.drawCount();

            refRt = new VkRenderTarget(W, H);
            refRenderer = new VkTerrainRenderer(res, W, H, Barriers.CONSERVATIVE);

            colourInterop = new VkInteropImage(W, H, VkInteropImage.Kind.COLOR_BGRA8,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            depthInterop = new VkInteropImage(W, H, VkInteropImage.Kind.DEPTH_R32F,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);

            ioRt = new VkRenderTarget(W, H, colourInterop.texture(),
                VkInteropImage.Kind.COLOR_BGRA8.vkFormat);
            ioRenderer = new VkTerrainRenderer(res, W, H, Barriers.CONSERVATIVE,
                VkTerrainRenderer.Mode.PER_SECTION, VkTerrainRenderer.Pass.OPAQUE,
                VkInteropImage.Kind.COLOR_BGRA8.vkFormat);
            resolve = new VkDepthResolve(ioRt.depth, W, H);
            depthReadback = new VkBuffer((long) W * H * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);

            System.out.println("interop colour: vk memReq=" + colourInterop.memoryRequirementSize
                + " IOSurface allocSize=" + colourInterop.allocSize);

            // --- GL 側の出力先 ---
            int glColour = colourInterop.glTexture();
            int glDepth = depthInterop.glTexture();
            GlFbo out = new GlFbo(W, H);
            compositor = new GlInteropCompositor();
            compositorNoDepth = new GlInteropCompositor(false);   // 深度を書かない (欠陥ではなく設定)
            compositorSwapped = new GlInteropCompositor(GlInteropCompositor.Defect.SWAPPED_CHANNELS);
            compositorFlipped = new GlInteropCompositor(GlInteropCompositor.Defect.FLIPPED);

            float[] mvp = closeUpMvp();
            float[] otherMvp = wideMvp();

            // ---------- 参照経路 ----------
            renderReference(refRenderer, refRt, res, mvp, drawCount);
            int[] ref = readTargetPixels(refRt);
            float[] refDepth = readVulkanDepth(refRt, depthReadback);
            refRt.writePng(OUT.resolve("5b-reference.png"), true);

            // ---------- interop 経路 ----------
            renderInterop(ioRenderer, ioRt, resolve, colourInterop, depthInterop, res, mvp, drawCount);
            out.bind();
            compositor.composite(glColour, glDepth);
            glFinish();
            int[] got = out.readColour();
            float[] gotDepth = out.readDepth();
            float[] resolved = readInteropDepth(depthInterop, depthReadback);
            // 差分ゼロ比較は機械的な検証でしかない。「意味のある絵か」は目で見るしかないので
            // 参照と interop 経由の両方を書き出す [VkRenderTarget.writePng と同じ理由]
            writePng(got, OUT.resolve("5b-interop-composite.png"));
            writeDepthPng(gotDepth, OUT.resolve("5b-interop-depth.png"));

            // ---------- 検査 ----------
            System.out.println();
            System.out.println("=== controls ===");
            controlNonTrivialPicture(ref);
            controlDepthIsNotUniform(refDepth);

            System.out.println();
            System.out.println("=== main comparison ===");
            long colourDiff = countDiff(ref, got);
            report("colour: interop composite == phase 4 offscreen", colourDiff == 0,
                colourDiff + " pixels differ");

            long resolveDiff = countDiffF(refDepth, resolved);
            report("depth : resolve pass == vulkan depth attachment", resolveDiff == 0,
                resolveDiff + " texels differ");

            long depthDiff = countDiffDepth(resolved, gotDepth);
            report("depth : gl_FragDepth write-back == resolved depth", depthDiff == 0,
                depthDiff + " texels differ");

            // ---------- 感度の対照 ----------
            System.out.println();
            System.out.println("=== sensitivity controls ===");

            // C2: 別視点なら一致しないこと
            renderInterop(ioRenderer, ioRt, resolve, colourInterop, depthInterop, res, otherMvp, drawCount);
            out.bind();
            compositor.composite(glColour, glDepth);
            glFinish();
            int[] other = out.readColour();
            long otherDiff = countDiff(ref, other);
            report("C2 a different viewpoint does NOT match", otherDiff > 0,
                "rendering another MVP produced an identical image; the comparison is blind");

            // C4: gl_FragDepth を書かない合成では深度検査が落ち、色検査は通ること
            renderInterop(ioRenderer, ioRt, resolve, colourInterop, depthInterop, res, mvp, drawCount);
            out.bind();
            out.clearDepth(0.5f);
            compositorNoDepth.composite(glColour, glDepth);
            glFinish();
            int[] brokenColour = out.readColour();
            float[] brokenDepth = out.readDepth();

            report("C4 dropping gl_FragDepth breaks the depth check",
                countDiffDepth(resolved, brokenDepth) > 0,
                "the depth check passed without writing gl_FragDepth; it proves nothing");
            report("C4 dropping gl_FragDepth does NOT break the colour check",
                countDiff(ref, brokenColour) == 0,
                "colour changed too, so this control does not isolate depth");

            // C6: R/B の入れ替えを検出できること。
            // 絵が灰色ばかりだと BGRA/RGBA の取り違えが見えないので、まず色に偏りがあることを示す
            controlPictureHasAsymmetricChannels(ref);
            renderInterop(ioRenderer, ioRt, resolve, colourInterop, depthInterop, res, mvp, drawCount);
            out.bind();
            compositorSwapped.composite(glColour, glDepth);
            glFinish();
            long swappedDiff = countDiff(ref, out.readColour());
            report("C6 swapping R and B breaks the colour check", swappedDiff > 0,
                "a channel swap was invisible; the BGRA/RGBA path is not actually verified");

            // C7: 上下反転。合成での反転と glReadPixels での反転が打ち消し合うため、
            // 「反転せずに比べる」と壊れた合成が素通りする。両方向を明示的に見る
            renderInterop(ioRenderer, ioRt, resolve, colourInterop, depthInterop, res, mvp, drawCount);
            out.bind();
            compositorFlipped.composite(glColour, glDepth);
            glFinish();
            int[] flipped = out.readColour();
            report("C7 an upside-down composite is caught by the direct comparison",
                countDiff(ref, flipped) > 0,
                "the orientation of the composite is not verified at all");
            report("C7 ...and would have slipped through a flipped comparison",
                countDiffFlipped(ref, flipped) == 0,
                "expected the flipped comparison to be fooled; if it is not, "
                    + "the note in docs/phase5b-composite.md 6 is wrong");

            // C8: 5c-1b の非対称パターンで **行が保存されること**
            checkRowPreservation(colourInterop, depthInterop, out, compositor, compositorFlipped,
                glColour, glDepth);

            // C9: 合成が GL の状態を元に戻すこと。
            // ⚠ **両方の変種で見ること** — 深度を書く版と書かない版は
            // 触る状態が違う (書かない版は深度テストを切る)。
            // MC が使うのは書かない版なので、そちらを外すと**本番の不具合を見逃す**
            checkGlStateIsRestored("depth", out, compositor, glColour, glDepth);
            checkGlStateIsRestored("colour-only", out, compositorNoDepth, glColour, glDepth);

            // C10〜C13: 5c-1c の深度経路 (GL が書き Vulkan が読む向き)
            System.out.println();
            System.out.println("=== 5c-1c depth path ===");
            checkDepthImportPath(out, compositorNoDepth);

            // C14: 5c-1d の合成 — MC の深度と比べて勝った画素にだけ書く
            System.out.println();
            System.out.println("=== 5c-1d depth-tested composite ===");
            checkDepthTestedComposite(ioRenderer, ioRt, resolve, colourInterop, depthInterop,
                res, mvp, drawCount, out, glColour, glDepth, resolved);

            // C16: 5c-3a の深度再投影 — 自前の投影で描いて MC の空間へ写し直す
            System.out.println();
            System.out.println("=== 5c-3a depth reprojection ===");
            checkDepthReprojection(ioRenderer, ioRt, colourInterop, depthInterop,
                res, drawCount, depthReadback);

        } catch (Throwable t) {
            t.printStackTrace();
            failures.add("exception: " + t);
        } finally {
            if (depthReadback != null) depthReadback.free();
            if (resolve != null) resolve.free();
            if (ioRenderer != null) ioRenderer.free();
            if (refRenderer != null) refRenderer.free();
            if (ioRt != null) ioRt.free();
            if (refRt != null) refRt.free();
            if (compositor != null) compositor.free();
            if (compositorNoDepth != null) compositorNoDepth.free();
            if (compositorSwapped != null) compositorSwapped.free();
            if (compositorFlipped != null) compositorFlipped.free();
            if (colourInterop != null) colourInterop.free();
            if (depthInterop != null) depthInterop.free();
            if (res != null) res.free();
            teardown();
        }

        System.out.println();
        if (VkContext.get() != null && VkContext.suppressedValidationCount() > 0) {
            System.out.println("suppressed " + VkContext.suppressedValidationCount()
                + " known interop validation messages (docs/phase5b-composite.md 3)");
        }
        if (failures.isEmpty()) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println("FAILURES:");
            failures.forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
    }

    /**
     * <b>C8 — framebuffer の行 N が GL の行 N に届くこと。</b>
     *
     * <p>5c-1b が MC 上で出すのと<b>同じ非対称パターン</b>を使う
     * ({@code VkInteropProbe.recordTestPattern})。目視と機械的検査が
     * 別の模様を見ていては意味がないため。
     *
     * <h2>⚠ これが言えること / 言えないこと</h2>
     * <table>
     *   <tr><td>言える</td><td><b>行の保存</b> — Vulkan が書いた行 N が GL 側の行 N に届く</td></tr>
     *   <tr><td><b>言えない</b></td><td><b>行 N が画面の上か下か</b>。
     *       パターン生成も検証も同じ「framebuffer の行」を参照しているので、
     *       規約が反転していれば<b>両方が反転して一致する</b>
     *       [規約 4 / 11 例目]</td></tr>
     * </table>
     *
     * <p>物理的な上下は <b>MC のスクリーンショット (F2) を独立した基準にして目視</b>で確かめる
     * [docs/phase5c1b-completion.md]。
     *
     * <p>感度の対照: {@code Defect.FLIPPED} の合成では<b>落ちなければならない</b>。
     */
    static void checkRowPreservation(VkInteropImage colour, VkInteropImage depth, GlFbo out,
                                     GlInteropCompositor good, GlInteropCompositor flipped,
                                     int glColour, int glDepth) {
        recordPatternFrame(colour);
        out.bind();
        good.composite(glColour, glDepth);
        glFinish();
        int[] px = out.readColour();

        int bandStart = VkInteropProbe.bandStartRow(H);
        int cornerStart = VkInteropProbe.cornerStartRow(H);
        int cornerEnd = VkInteropProbe.cornerEndCol(W);
        int band = VkInteropProbe.bandColourRgba();
        int bg = VkInteropProbe.backgroundColourRgba();
        int corner = VkInteropProbe.cornerColourRgba();

        // 帯の中、帯の外、四角の中、四角と同じ行だが右側
        boolean bandOk   = px[(bandStart + H / 16) * W + W / 2] == band;
        boolean belowOk  = px[(bandStart / 2) * W + W / 2] == bg;
        boolean cornerOk = px[(cornerStart + H / 32) * W + cornerEnd / 2] == corner;
        boolean rightOk  = px[(cornerStart + H / 32) * W + W / 2] == band;

        report("C8 the band lands on the rows Vulkan wrote", bandOk && belowOk,
            "row " + (bandStart + H / 16) + " should be the band and row " + (bandStart / 2)
                + " the background");
        report("C8 the corner square keeps its column range", cornerOk && rightOk,
            "the square must be on the low-x side of its rows, with the band to its right");

        // 感度: 反転した合成では落ちること
        recordPatternFrame(colour);
        out.bind();
        flipped.composite(glColour, glDepth);
        glFinish();
        int[] bad = out.readColour();
        boolean detected = bad[(bandStart + H / 16) * W + W / 2] != band;
        report("C8 a flipped composite breaks the row check", detected,
            "the row check did not notice a vertical flip; it is vacuous");
    }

    /**
     * <b>C9 — 合成が触った GL の状態を全て元に戻すこと。</b>
     *
     * <h2>なぜ機械的に見張るのか</h2>
     * MC 上で <b>mob が地形を貫通して見える</b>不具合になった
     * [docs/phase5c1b-completion.md §9]。合成が素の GL で深度テストを切り、
     * 復帰を {@code GlStateManager} で行っていたため、
     * <b>キャッシュが「既に有効」と判断して {@code glEnable} を発行せず</b>、
     * 実際の GL は無効のまま MC のエンティティ描画に進んでいた。
     *
     * <p>ここは Minecraft の無い素の GL なので<b>キャッシュの影響を受けず、
     * 「素の GL の値が元に戻るか」だけを見られる</b>。これが守るべき契約である。
     *
     * <h2>この検査を空虚に満たす方法と、その塞ぎ方</h2>
     * <ul>
     *   <li><b>元の状態が既定値と同じ</b> → 「戻った」が自明になる。
     *       合成の前に<b>わざと既定と異なる状態</b>を作る
     *       (深度テスト ON・深度書き込み OFF・{@code GL_GREATER}・ブレンド ON・シザー ON)</li>
     *   <li><b>検査が変化を見ていない</b> → 対照として
     *       <b>わざと状態を変えた場合に落ちること</b>を確かめる</li>
     *   <li><b>⚠ 変種を 1 つしか見ていない</b> → <b>実際に踏んだ</b>。
     *       最初は深度を書く版だけで見ていたため、
     *       復帰処理を外す変異を入れても<b>通ってしまった</b>
     *       (書く版は深度テストを<b>有効</b>にするので、
     *       事前状態と同じになり差が出ない)。
     *       <b>MC が使うのは書かない版</b>なので、そちらを外すと本番の不具合を見逃す。
     *       両方の変種で回すこと</li>
     * </ul>
     */
    static void checkGlStateIsRestored(String variant, GlFbo out, GlInteropCompositor compositor,
                                       int glColour, int glDepth) {
        out.bind();
        // 既定とは違う、見分けの付く状態を作る
        glEnable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDepthFunc(GL_GREATER);
        glEnable(GL_BLEND);
        glEnable(GL_SCISSOR_TEST);
        glScissor(3, 5, 7, 11);

        int[] before = snapshotGlState();
        compositor.composite(glColour, glDepth);
        int[] after = snapshotGlState();

        report("C9 [" + variant + "] the composite restores every GL state it touches",
            java.util.Arrays.equals(before, after),
            "before=" + java.util.Arrays.toString(before)
                + " after=" + java.util.Arrays.toString(after));

        // 対照: わざと変えたら落ちること
        glDisable(GL_DEPTH_TEST);
        int[] mutated = snapshotGlState();
        report("C9 [" + variant + "] the snapshot notices a deliberate change",
            !java.util.Arrays.equals(before, mutated),
            "the snapshot is blind to the depth-test flag; C9 proves nothing");

        // 後片付け
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_BLEND);
        glDepthMask(true);
        glDepthFunc(GL_LESS);
    }

    /** 合成が触りうる GL の状態を 1 本の配列にまとめる。 */
    static int[] snapshotGlState() {
        return new int[] {
            glGetInteger(GL_CURRENT_PROGRAM),
            glGetInteger(GL_VERTEX_ARRAY_BINDING),
            glGetInteger(GL_ACTIVE_TEXTURE),
            glIsEnabled(GL_DEPTH_TEST) ? 1 : 0,
            glGetBoolean(GL_DEPTH_WRITEMASK) ? 1 : 0,
            glGetInteger(GL_DEPTH_FUNC),
            glIsEnabled(GL_BLEND) ? 1 : 0,
            glIsEnabled(GL_SCISSOR_TEST) ? 1 : 0,
            glGetInteger(org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING),
        };
    }

    /** パターンを 1 フレーム描いて GL に渡すところまで。 */
    static void recordPatternFrame(VkInteropImage colour) {
        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        VkInteropProbe.recordTestPattern(cmd, colour, W, H);
        t.endFrame();
        t.waitForFrame();
    }

    /**
     * <b>C10〜C13 — 5c-1c の深度経路。</b>
     *
     * <pre>
     * GL の深度テクスチャ ──(GlDepthImport)──→ IOSurface(R32F) ──(VkDepthVisualise)──→ IOSurface(BGRA)
     *                                                                                    └→ GL 合成
     * </pre>
     *
     * <p><b>5b までとは向きが逆である</b> — ここで初めて <b>GL が書いて Vulkan が読む</b>。
     *
     * <h2>⚠ これが言えること / 言えないこと</h2>
     * <table>
     *   <tr><td>言える</td><td><b>行と値の保存</b> — GL が行 N に書いた深度が
     *       Vulkan 側の行 N に<b>ビット単位で</b>届く</td></tr>
     *   <tr><td>言える</td><td><b>空の判定が {@link VkDepth#FAR} に結び付いている</b> —
     *       クリア値の画素だけがマゼンタになる</td></tr>
     *   <tr><td><b>言えない</b></td><td><b>行 N が画面の上か下か</b>。
     *       生成も検証も同じ「framebuffer の行」を参照しているので、
     *       規約が反転すれば<b>両方が反転して一致する</b> [規約 4]</td></tr>
     * </table>
     *
     * <p>物理的な上下は <b>MC 上で「空が画面の上に出るか」</b>を見て確かめる。
     * <b>空の位置は Voxy の規約に一切依存しない外部の事実</b>である。
     */
    static void checkDepthImportPath(GlFbo out, GlInteropCompositor compositor) {
        // 行ごとに異なる値を持つパターン。**一様な帯ではずれを見逃す**
        float[] src = depthPattern();

        VkInteropImage depthIn = null, colourOut = null;
        VkDepthVisualise vis = null;
        GlDepthImport imp = null, impFlipped = null, impNoSampler = null;
        VkBuffer readback = null;
        int mcDepth = 0, hostileSampler = 0;
        try {
            depthIn = new VkInteropImage(W, H, VkInteropImage.Kind.DEPTH_R32F,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            depthIn.glTexture();
            // ⚠ GL が書く前に UNDEFINED -> GENERAL を済ませる。
            // 怠ると**最初の 1 フレームだけ**書き込みが捨てられる [Phase 5a §6.1]
            depthIn.primeLayout();

            colourOut = new VkInteropImage(W, H, VkInteropImage.Kind.COLOR_BGRA8,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            colourOut.glTexture();

            vis = new VkDepthVisualise(depthIn.texture(), W, H,
                VkInteropImage.Kind.COLOR_BGRA8.vkFormat);
            imp = new GlDepthImport();
            impFlipped = new GlDepthImport(GlDepthImport.Defect.FLIPPED);
            impNoSampler = new GlDepthImport(GlDepthImport.Defect.NO_SAMPLER_RESET);
            readback = new VkBuffer((long) W * H * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
            mcDepth = makeDepthTexture(src);

            // --- 対照: パターンが行を区別できること ---
            var distinct = new java.util.HashSet<Float>();
            for (float v : src) distinct.add(v);
            report("C10a the depth pattern distinguishes rows",
                distinct.size() >= H * 3 / 4,
                distinct.size() + " distinct values for " + H + " rows; "
                    + "a uniform pattern cannot detect a row shift");

            // --- C10: 値と行がそのまま届くこと ---
            imp.record(mcDepth, depthIn);
            GlVkSync.waitForGl();
            float[] got = readGlWrittenInterop(depthIn, readback);
            long diff = countDiffF(src, got);
            report("C10 MC's depth reaches the interop image bit-for-bit", diff == 0,
                diff + " texels differ (first mismatch at " + firstDiff(src, got) + ")");

            // --- C10 感度: 反転した取り込みでは落ちること ---
            impFlipped.record(mcDepth, depthIn);
            GlVkSync.waitForGl();
            float[] flipped = readGlWrittenInterop(depthIn, readback);
            report("C10 a flipped import breaks the depth check",
                countDiffF(src, flipped) > 0,
                "the depth check did not notice a vertical flip; it is vacuous");
            report("C10 ...and the flipped result really is the mirror image",
                countDiffFlippedF(src, flipped) == 0,
                "the defective import produced something other than a flip; "
                    + "the control does not isolate orientation");

            // --- C11: Vulkan が可視化した色 (本番の合成経路を通す) ---
            imp.record(mcDepth, depthIn);
            GlVkSync.waitForGl();
            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            vis.record(cmd, depthIn.texture(), colourOut.texture());
            t.endFrame();
            t.waitForFrame();
            out.bind();
            compositor.composite(colourOut.glTexture(), depthIn.glTexture());
            glFinish();
            int[] px = out.readColour();
            writePng(px, OUT.resolve("5c1c-depth-visualised.png"));

            int skyStart = skyStartRow();
            int magenta = 0xFF000000
                | (Math.round(VkDepthVisualise.SKY_RGB[2] * 255) << 16)
                | (Math.round(VkDepthVisualise.SKY_RGB[1] * 255) << 8)
                | Math.round(VkDepthVisualise.SKY_RGB[0] * 255);

            boolean skyOk = true, groundOk = true;
            for (int y = skyStart; y < H && skyOk; y++) {
                if (px[y * W + W / 2] != magenta) skyOk = false;
            }
            for (int y = 0; y < skyStart && groundOk; y++) {
                int v = px[y * W + W / 2];
                int r = v & 0xFF, g = (v >>> 8) & 0xFF, b = (v >>> 16) & 0xFF;
                if (v == magenta || r != g || g != b) groundOk = false;
            }
            // ⚠ ここが **VkDepth.FAR とシェーダの FAR を結び付けている**。
            // 規約を非逆Zに変えると FAR が 1.0 になり、この検査が落ちる [規約 6]
            report("C11 only the cleared (FAR) texels become the sky colour", skyOk,
                "rows [" + skyStart + "," + H + ") should all be the sky colour");
            report("C11 the drawn texels become grey, not the sky colour", groundOk,
                "rows [0," + skyStart + ") should be grey (r == g == b) and never the sky colour");

            // 単調性: 深度が大きい (= 手前) ほど明るいこと。
            // 5c-1c の目視判定「手前が明るい」を機械的に固定する
            boolean monotone = true;
            for (int y = 1; y < skyStart; y++) {
                int prev = px[(y - 1) * W + W / 2] & 0xFF;
                int cur = px[y * W + W / 2] & 0xFF;
                if (cur > prev) monotone = false;   // パターンは行が増えるほど遠い
            }
            int nearest = px[0 * W + W / 2] & 0xFF;
            int furthest = px[(skyStart - 1) * W + W / 2] & 0xFF;
            report("C11 nearer depth is brighter, and the range is usable",
                monotone && nearest - furthest > 32,
                "brightness must fall monotonically with distance; "
                    + "nearest=" + nearest + " furthest=" + furthest + " monotone=" + monotone);

            // --- C12 / C13: ホストが残す状態 (規約 7) ---
            // オフスクリーンの検査が 5c-1b の黒画面を再現できなかったのは、
            // **ベンチにサンプラオブジェクトが存在しなかった**からである。
            // ここでホストの状態を明示的に模す
            hostileSampler = org.lwjgl.opengl.GL33C.glGenSamplers();
            org.lwjgl.opengl.GL33C.glSamplerParameteri(hostileSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            org.lwjgl.opengl.GL33C.glSamplerParameteri(hostileSampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glActiveTexture(GL_TEXTURE0);
            org.lwjgl.opengl.GL33C.glBindSampler(0, hostileSampler);

            imp.record(mcDepth, depthIn);
            GlVkSync.waitForGl();
            report("C12 a sampler left bound by the host does not affect the import",
                countDiffF(src, readGlWrittenInterop(depthIn, readback)) == 0,
                "the host's sampler leaked into the import; this is the 5c-1b black screen");

            org.lwjgl.opengl.GL33C.glBindSampler(0, hostileSampler);
            impNoSampler.record(mcDepth, depthIn);
            GlVkSync.waitForGl();
            report("C12 ...and an import that keeps the host's sampler IS broken by it",
                countDiffF(src, readGlWrittenInterop(depthIn, readback)) > 0,
                "the hostile sampler changed nothing, so C12 proves nothing");
            org.lwjgl.opengl.GL33C.glBindSampler(0, 0);

            // テクスチャ側に残った不整合なパラメータでも同じこと
            glBindTexture(GL_TEXTURE_2D, mcDepth);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glBindTexture(GL_TEXTURE_2D, 0);

            imp.record(mcDepth, depthIn);
            GlVkSync.waitForGl();
            report("C13 hostile texture parameters do not affect the import",
                countDiffF(src, readGlWrittenInterop(depthIn, readback)) == 0,
                "the import depends on the host's texture parameters");

            impNoSampler.record(mcDepth, depthIn);
            GlVkSync.waitForGl();
            report("C13 ...and an import without its own sampler IS broken by them",
                countDiffF(src, readGlWrittenInterop(depthIn, readback)) > 0,
                "the hostile texture parameters changed nothing, so C13 proves nothing");

            glBindTexture(GL_TEXTURE_2D, mcDepth);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glBindTexture(GL_TEXTURE_2D, 0);
        } catch (Throwable e) {
            e.printStackTrace();
            failures.add("5c-1c depth path: " + e);
        } finally {
            if (hostileSampler != 0) {
                org.lwjgl.opengl.GL33C.glBindSampler(0, 0);
                org.lwjgl.opengl.GL33C.glDeleteSamplers(hostileSampler);
            }
            if (mcDepth != 0) glDeleteTextures(mcDepth);
            if (readback != null) readback.free();
            if (impNoSampler != null) impNoSampler.free();
            if (impFlipped != null) impFlipped.free();
            if (imp != null) imp.free();
            if (vis != null) vis.free();
            if (colourOut != null) colourOut.free();
            if (depthIn != null) depthIn.free();
        }
    }

    /**
     * <b>C14 — 深度テストする合成 (5c-1d の本番設定)。</b>
     *
     * <p>5c-1b で<b>深度を一様に潰して mob が地形を貫通した</b>のがこの場所である
     * [docs/phase5c1b-completion.md §9]。今回は Voxy の実深度を書くので状況が違うが、
     * <b>どの画素に、どういう条件で書くか</b>を機械的に固定しておく。
     *
     * <h2>作る状況</h2>
     * GL 側のフレームバッファを「MC が描いた後」に見立てて 2 つに塗り分ける:
     *
     * <pre>
     * 左半分: 深度 = mcNear (Voxy のどの断片より手前)   → Voxy は **1 画素も出てはならない**
     * 右半分: 深度 = FAR    (空)                        → Voxy が描いた画素だけ出る
     * </pre>
     *
     * <h2>要求する 3 つのこと</h2>
     * <ol>
     *   <li><b>C14a</b> MC の近景がある側で Voxy が出ない (= Voxy が MC を不当に隠さない)</li>
     *   <li><b>C14b</b> 空の側では Voxy が出る (= 隠しすぎてもいない)</li>
     *   <li><b>C14c</b> Voxy が描いていない画素は<b>どちらの側でも</b> MC の色が残る
     *       (= クリア色が漏れない)</li>
     * </ol>
     *
     * <p>感度の対照として、<b>無条件上書き</b> ({@link GlInteropCompositor.DepthMode#OVERWRITE})
     * では C14a と C14c が<b>落ちなければならない</b>。落ちなければ、この検査は
     * 合成のモードを区別していないことになる。
     */
    static void checkDepthTestedComposite(VkTerrainRenderer renderer, VkRenderTarget rt,
                                          VkDepthResolve resolve, VkInteropImage colour,
                                          VkInteropImage depth, VkTerrainResources res,
                                          float[] mvp, int drawCount, GlFbo out,
                                          int glColour, int glDepth, float[] voxyDepth)
            throws java.io.IOException {
        GlInteropCompositor tested = null, overwrite = null;
        try {
            tested = GlInteropCompositor.forHost();
            overwrite = new GlInteropCompositor(GlInteropCompositor.DepthMode.OVERWRITE);

            // Voxy の最も手前の深度より確実に手前の値を選ぶ。
            // ⚠ これが取れないと C14a は「たまたま隠れた」と区別が付かない
            float maxVoxy = VkDepth.CLEAR;
            long drawn = 0;
            for (float d : voxyDepth) {
                if (d != VkDepth.CLEAR) drawn++;
                if (d > maxVoxy) maxVoxy = d;       // 逆Z: 大きいほど手前
            }
            float mcNear = Math.min(1.0f, maxVoxy + 0.05f);
            report("C14 control: the scene has geometry and headroom above it",
                drawn > 1000 && mcNear > maxVoxy,
                drawn + " drawn texels, maxVoxy=" + maxVoxy + " mcNear=" + mcNear);

            int[] got = compositeOverMcDepth(renderer, rt, resolve, colour, depth, res, mvp,
                drawCount, out, tested, glColour, glDepth, mcNear);
            writePng(got, OUT.resolve("5c1d-depth-tested-composite.png"));

            // 判定は **列** で分ける (左 = MC の近景、右 = 空)
            long voxyOnNearSide = 0, voxyOnSkySide = 0, clearLeaked = 0;
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int v = got[y * W + x];
                    boolean isMc = v == MC_COLOUR_RGBA;
                    boolean voxyDrewHere = voxyDepth[y * W + x] != VkDepth.CLEAR;
                    if (x < W / 2) {
                        if (!isMc) voxyOnNearSide++;
                    } else if (!isMc) {
                        voxyOnSkySide++;
                    }
                    // Voxy が描いていない画素に MC 以外の色が来ていたらクリア色の漏れ
                    if (!voxyDrewHere && !isMc) clearLeaked++;
                }
            }

            report("C14a Voxy does not draw where MC's terrain is nearer", voxyOnNearSide == 0,
                voxyOnNearSide + " pixels overwrote MC even though MC was nearer");
            report("C14b Voxy does draw where MC has only sky", voxyOnSkySide > 1000,
                voxyOnSkySide + " pixels came from Voxy; the composite is hiding everything");
            report("C14c undrawn Voxy pixels keep MC's colour", clearLeaked == 0,
                clearLeaked + " pixels leaked the Voxy clear colour (the discard is not working)");

            // ---- 感度の対照: 無条件上書きなら a と c が落ちること ----
            int[] bad = compositeOverMcDepth(renderer, rt, resolve, colour, depth, res, mvp,
                drawCount, out, overwrite, glColour, glDepth, mcNear);
            long badNearSide = 0, badLeaked = 0;
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int v = bad[y * W + x];
                    boolean isMc = v == MC_COLOUR_RGBA;
                    if (x < W / 2 && !isMc) badNearSide++;
                    if (voxyDepth[y * W + x] == VkDepth.CLEAR && !isMc) badLeaked++;
                }
            }
            report("C14 an unconditional composite DOES overwrite MC's nearer terrain",
                badNearSide > 1000,
                "overwriting mode changed nothing on the near side; C14a proves nothing");
            report("C14 an unconditional composite DOES leak the clear colour",
                badLeaked > 1000,
                "overwriting mode leaked nothing; C14c proves nothing");

            // ---- C15: 深度アタッチメントが無い描画先を検出できること ----
            // ⚠ これは**ホストの FBO の作り方**の問題なので、
            // 検査が自分で FBO を作っている限り再現しない [規約 7]。
            // 5c-1d では実際にこの状態で「MC の深度は無傷なのに Voxy が隠されない」
            // という一見正常な壊れ方になった。ここで明示的に作って捕まえる
            checkMissingDepthAttachmentIsDetected(tested, out, glColour, glDepth);
        } finally {
            if (tested != null) tested.free();
            if (overwrite != null) overwrite.free();
        }
    }

    /**
     * <b>C15 — 深度アタッチメントの無い描画先を検出すること。</b>
     *
     * <p>深度テストする合成は、束縛先に深度が無いと<b>成立しない</b>。
     * しかも壊れ方が<b>一見正常</b>である:
     *
     * <ul>
     *   <li>深度テストが常に通る → <b>Voxy が MC の地形に隠されない</b></li>
     *   <li>{@code gl_FragDepth} の書き先が無い → <b>MC の深度バッファは無傷</b></li>
     * </ul>
     *
     * <p>「深度を壊していないから正しい」と読み違えやすい。<b>気づけるようにする。</b>
     */
    static void checkMissingDepthAttachmentIsDetected(GlInteropCompositor tested, GlFbo out,
                                                      int glColour, int glDepth) {
        int colourOnlyFbo = glGenFramebuffers();
        int colourTex = glGenTextures();
        try {
            glBindTexture(GL_TEXTURE_2D, colourTex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, colourOnlyFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colourTex, 0);
            glViewport(0, 0, W, H);
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            report("C15 control: a colour-only framebuffer is complete",
                status == GL_FRAMEBUFFER_COMPLETE,
                "status 0x" + Integer.toHexString(status)
                    + "; the control cannot create the situation it is meant to detect");

            GlInteropCompositor.resetMissingDepthAttachmentCount();
            tested.composite(glColour, glDepth);
            glFinish();
            report("C15 compositing without a depth attachment is detected",
                GlInteropCompositor.missingDepthAttachmentCount() > 0,
                "the guard did not fire; a host that forgets the depth attachment would look"
                    + " correct (Voxy never occluded, Minecraft's depth intact)");

            // 対照: 深度がある描画先では**鳴らない**こと。鳴りっぱなしなら区別していない
            GlInteropCompositor.resetMissingDepthAttachmentCount();
            out.bind();
            tested.composite(glColour, glDepth);
            glFinish();
            report("C15 ...and a framebuffer WITH depth does not trip the guard",
                GlInteropCompositor.missingDepthAttachmentCount() == 0,
                "the guard fires even when a depth attachment is present; it does not"
                    + " distinguish the two cases");
        } finally {
            glDeleteFramebuffers(colourOnlyFbo);
            glDeleteTextures(colourTex);
        }
    }

    /** 「MC が描いた後」を模した状態を作り、その上に合成して読み戻す。 */
    static int[] compositeOverMcDepth(VkTerrainRenderer renderer, VkRenderTarget rt,
                                      VkDepthResolve resolve, VkInteropImage colour,
                                      VkInteropImage depth, VkTerrainResources res, float[] mvp,
                                      int drawCount, GlFbo out, GlInteropCompositor compositor,
                                      int glColour, int glDepth, float mcNear) {
        renderInterop(renderer, rt, resolve, colour, depth, res, mvp, drawCount);
        out.bind();
        // MC の色で塗り潰す。Voxy が出た画素は必ずこの色から変わる
        glClearColor(MC_COLOUR[0], MC_COLOUR[1], MC_COLOUR[2], 1.0f);
        glDepthMask(true);
        glClearDepth(VkDepth.CLEAR);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        // 左半分だけ「MC の近景地形がある」ことにする
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, 0, W / 2, H);
        glClearDepth(mcNear);
        glClear(GL_DEPTH_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);

        compositor.composite(glColour, glDepth);
        glFinish();
        return out.readColour();
    }

    /** 「MC が描いた色」。合成後にこの色のままなら Voxy はそこに出ていない。 */
    static final float[] MC_COLOUR = {0.20f, 0.40f, 0.80f};
    static final int MC_COLOUR_RGBA = 0xFF000000
        | (Math.round(MC_COLOUR[2] * 255) << 16)
        | (Math.round(MC_COLOUR[1] * 255) << 8)
        | Math.round(MC_COLOUR[0] * 255);

    /** 空 (深度がクリア値のまま) にする行の開始。<b>絵の上 1/4</b> (GL 規約なので行が大きい側)。 */
    static int skyStartRow() { return H * 3 / 4; }

    /**
     * 検査用の深度パターン。
     *
     * <ul>
     *   <li>上 1/4 (行が大きい側) は {@link VkDepth#CLEAR} — MC が空に残す値</li>
     *   <li>残りは<b>行ごとに異なる値</b>で、行 0 (絵の下端) が最も手前</li>
     * </ul>
     *
     * <p>実際の MC の画面と<b>同じ形</b>にしてある (空が上、近い地形が下) ので、
     * 検査が書き出す PNG と実機のスクリーンショットを見比べられる。
     */
    static float[] depthPattern() {
        float[] d = new float[W * H];
        int sky = skyStartRow();
        for (int y = 0; y < H; y++) {
            float v = y >= sky
                ? VkDepth.CLEAR
                // 逆Z: 大きいほど手前。行 0 (絵の下端) を最も手前にする
                : 0.999f - 0.998f * (y / (float) (sky - 1));
            for (int x = 0; x < W; x++) d[y * W + x] = v;
        }
        return d;
    }

    /** MC の深度テクスチャを模す。<b>同じ書式 (D32F)</b> でなければ経路を検証したことにならない。 */
    static int makeDepthTexture(float[] values) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        FloatBuffer buf = MemoryUtil.memAllocFloat(values.length);
        try {
            buf.put(values).flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, W, H, 0,
                GL_DEPTH_COMPONENT, GL_FLOAT, buf);
        } finally {
            MemoryUtil.memFree(buf);
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    /**
     * <b>GL が書いた</b> interop の R32F を読む。
     *
     * <p>{@link #readInteropDepth} との違いは src のアクセスマスクだけである —
     * こちらは直前の書き手が GL なので、Vulkan 側に「待つべき書き込み」は無い
     * (順序は {@link GlVkSync} が CPU 側で担保している)。
     */
    static float[] readGlWrittenInterop(VkInteropImage img, VkBuffer dst) {
        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        img.toGeneral(cmd,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        copyToBuffer(cmd, img.imageHandle(), VK_IMAGE_ASPECT_COLOR_BIT,
            VK_IMAGE_LAYOUT_GENERAL, dst);
        t.endFrame();
        t.waitForFrame();
        return floats(dst);
    }

    /** 上下反転して比べる (float 版)。<b>C10 の感度対照専用。</b> */
    static long countDiffFlippedF(float[] a, float[] b) {
        long n = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (Float.floatToRawIntBits(a[y * W + x])
                    != Float.floatToRawIntBits(b[(H - 1 - y) * W + x])) n++;
            }
        }
        return n;
    }

    /** 最初に食い違った位置。失敗したときに「どうずれたか」が読めるようにする。 */
    static String firstDiff(float[] a, float[] b) {
        for (int i = 0; i < a.length; i++) {
            if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) {
                return "row " + (i / W) + " col " + (i % W) + ": expected " + a[i] + " got " + b[i];
            }
        }
        return "none";
    }

    // ---------------- 検査の報告 ----------------

    /**
     * <b>C16 — 深度の再投影 (5c-3a)。</b>
     *
     * <h2>何を主張するのか</h2>
     * Voxy は自前の投影 (near=16 / far=48000) で描き、書き戻す前に深度を
     * <b>MC の投影空間へ写し直す</b>。その写し直しが正しいことを、
     * <b>MC を起動せずに</b>言い切る。
     *
     * <pre>
     * A: dst の投影で描いて素直に解決     → これが答え
     * B: src の投影で描いて (inv(src),dst) で再投影 → A と一致すべき
     * </pre>
     *
     * <p>⚠ 両者は<b>同じ画素を覆う</b>。投影が違うのは深度の行だけで、
     * x/y の行は共通だからである [docs/phase5c3-plan.md 2.2]。
     * これが成り立たなければ比較そのものが無意味になるので、覆いも数えて比べる。
     *
     * <h2>この検査を空虚に満たす方法と、その塞ぎ方</h2>
     * <ol>
     *   <li><b>再投影が何もしていない</b> (dst を無視して素通し) →
     *       {@code C16b} が「src をそのまま解決したもの」と<b>違う</b>ことを要求する</li>
     *   <li><b>画面座標 → NDC の写像がずれている</b> (Y 反転など) →
     *       ⚠ <b>深度だけを比べる検査では原理的に捕まらない。</b>
     *       (ndc_x, ndc_y, depth) から復元した点は、xy を変えても<b>視空間の z が同じ</b>で、
     *       写し先が同じ視点の標準的な透視投影なら深度は z だけで決まるためである
     *       [実際に Y 反転の変異が素通りした — 失敗例 24]。
     *       {@code C16c} が<b>深度が横位置に依存する投影</b>を使って捕まえる</li>
     * </ol>
     */
    static void checkDepthReprojection(VkTerrainRenderer renderer, VkRenderTarget rt,
                                       VkInteropImage colour, VkInteropImage depth,
                                       VkTerrainResources res, int drawCount, VkBuffer readback) {
        VkDepthResolve plain = null, reproj = null;
        try {
            plain = new VkDepthResolve(rt.depth, W, H);
            reproj = new VkDepthResolve(rt.depth, W, H, true);

            float[] srcMvp = closeUpMvpWithPlanes(0.1f, 2000f);
            float[] dstMvp = closeUpMvpWithPlanes(0.5f, 300f);
            var srcM = new org.joml.Matrix4f().set(srcMvp);
            var dstM = new org.joml.Matrix4f().set(dstMvp);
            var invSrc = new org.joml.Matrix4f(srcM).invert();

            // A — 写し先の投影で素直に描いて解決した「答え」
            renderInterop(renderer, rt, plain, colour, depth, res, dstMvp, drawCount);
            float[] answer = readInteropDepth(depth, readback);

            // src の投影で素直に解決したもの (対照用: 再投影が何もしていない場合の値)
            renderInterop(renderer, rt, plain, colour, depth, res, srcMvp, drawCount);
            float[] srcPlain = readInteropDepth(depth, readback);

            // B — src で描いて (inv(src), dst) で再投影
            reproj.setReprojection(invSrc, dstM);
            renderInterop(renderer, rt, reproj, colour, depth, res, srcMvp, drawCount);
            float[] reprojected = readInteropDepth(depth, readback);

            // --- 覆いが同じであること (比較が成立する前提) ---
            int drawnA = countDrawn(answer), drawnB = countDrawn(reprojected);
            report("C16 control: both projections cover the same pixels",
                drawnA > 5000 && Math.abs(drawnA - drawnB) < drawnA / 100,
                "drawn A=" + drawnA + " B=" + drawnB + "; the comparison would be meaningless");

            // --- C16b: 再投影が「答え」に一致すること ---
            double worst = worstDrawnDiff(answer, reprojected);
            report("C16b reprojected depth matches rendering in the target projection",
                worst < 1e-5, "worst difference " + worst);
            System.out.println("     [C16b] worst |reprojected - answer| = " + worst
                + " over " + drawnA + " drawn pixels");

            // --- 対照: 再投影しなければ**一致しない** ---
            double ifNoop = worstDrawnDiff(answer, srcPlain);
            report("C16 ...and NOT reprojecting does not match",
                ifNoop > 1e-2,
                "src and dst depths differ by only " + ifNoop + "; C16b proves nothing");

            // --- C16c: 復元した**世界の点**そのものが正しいこと ---
            //
            // ⚠ ここまでの検査は**横位置の誤りを検出できない**。深度は視空間の z だけで
            // 決まるので、画面座標 → NDC の写像がずれていても答えが変わらない
            // [失敗例 24 — Y 反転の変異が素通りした]。
            //
            // そこで**深度の行に横位置を混ぜた投影** (斜め投影) を写し先にする。
            // clip.z が view.x / view.y にも依存するので、復元した点がずれれば深度も動く。
            // x/y の行は触らないので**覆う画素は変わらず**、比較は成立したままである。
            float[] shearedProj = VkSceneUniform.perspective(
                (float) Math.toRadians(60), (float) W / H, 0.5f, 300f);
            shearedProj[2] = 0.002f;    // clip.z += 0.002 * view.x  (列優先: m[col*4+row])
            shearedProj[6] = -0.0015f;  // clip.z -= 0.0015 * view.y
            float[] shearedMvp = VkSceneUniform.mul(shearedProj,
                VkSceneUniform.lookAt(new float[]{14.0f, 7.0f, 13.0f},
                    new float[]{8.5f, 0.5f, 0.5f}, new float[]{0, 1, 0}));

            renderInterop(renderer, rt, plain, colour, depth, res, shearedMvp, drawCount);
            float[] shearAnswer = readInteropDepth(depth, readback);

            reproj.setReprojection(invSrc, new org.joml.Matrix4f().set(shearedMvp));
            renderInterop(renderer, rt, reproj, colour, depth, res, srcMvp, drawCount);
            double shearWorst = worstDrawnDiff(shearAnswer, readInteropDepth(depth, readback));

            report("C16c the reconstructed world position is right, not just its depth",
                shearWorst < 1e-5, "worst difference " + shearWorst);
            System.out.println("     [C16c] worst difference under a laterally-dependent"
                + " projection = " + shearWorst);

            // ...そしてその投影では横位置が**実際に効く** (C16c が空虚でない対照)
            double shearVsPlain = worstDrawnDiff(shearAnswer, answer);
            report("C16c control: that projection really does depend on lateral position",
                shearVsPlain > 1e-3,
                "the shear changed the depth by only " + shearVsPlain + "; C16c proves nothing");

            // --- C16 control: src==dst の往復は恒等 ---
            reproj.setReprojection(new org.joml.Matrix4f(srcM).invert(), srcM);
            renderInterop(renderer, rt, reproj, colour, depth, res, srcMvp, drawCount);
            double roundTrip = worstDrawnDiff(srcPlain, readInteropDepth(depth, readback));
            report("C16 control: reprojecting onto itself is the identity",
                roundTrip < 1e-5, "worst round-trip difference " + roundTrip
                    + " — a flipped or mis-scaled NDC mapping would show up here");
            System.out.println("     [C16] worst identity round-trip difference = " + roundTrip);
        } finally {
            if (plain != null) plain.free();
            if (reproj != null) reproj.free();
        }
    }

    /** 何か描かれた画素数 (FAR でないもの)。 */
    static int countDrawn(float[] depth) {
        int n = 0;
        for (float v : depth) if (v != VkDepth.FAR) n++;
        return n;
    }

    /** <b>両方が描いている画素</b>での最大差。空 (FAR) の画素は比べない。 */
    static double worstDrawnDiff(float[] a, float[] b) {
        double worst = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == VkDepth.FAR || b[i] == VkDepth.FAR) continue;
            worst = Math.max(worst, Math.abs(a[i] - b[i]));
        }
        return worst;
    }

    static void report(String what, boolean ok, String detail) {
        System.out.printf("%-58s %s%n", what, ok ? "PASS" : ("FAIL  (" + detail + ")"));
        if (!ok) failures.add(what + " -- " + detail);
    }

    static void controlNonTrivialPicture(int[] ref) {
        int clear = 0xFF000000
            | (Math.round(CLEAR[2] * 255) << 16)
            | (Math.round(CLEAR[1] * 255) << 8)
            | Math.round(CLEAR[0] * 255);
        long covered = 0;
        var colours = new java.util.HashSet<Integer>();
        for (int v : ref) {
            colours.add(v);
            if ((v & 0x00FFFFFF) != (clear & 0x00FFFFFF)) covered++;
        }
        report("C1 the reference picture is non-trivial", covered > 1000 && colours.size() >= 4,
            covered + " px covered, " + colours.size() + " distinct colours");
    }

    /**
     * <b>R != B の画素が十分にあること。</b>
     * 灰色だけの絵なら BGRA と RGBA を取り違えても差が出ず、
     * C6 が「入れ替えを検出できた」と言えなくなる。
     */
    static void controlPictureHasAsymmetricChannels(int[] ref) {
        long asym = 0;
        for (int v : ref) {
            int r = v & 0xFF, b = (v >>> 16) & 0xFF;
            if (r != b) asym++;
        }
        report("C6a the reference has pixels where R != B", asym > 1000,
            asym + " px have R != B; a channel swap would be invisible otherwise");
    }

    static void controlDepthIsNotUniform(float[] depth) {
        var vals = new java.util.HashSet<Float>();
        long nonClear = 0;
        for (float d : depth) {
            vals.add(d);
            if (d != VkDepth.CLEAR) nonClear++;   // 逆Zなのでクリア値は 0.0 [VkDepth]
        }
        report("C5 the depth buffer is not uniform", vals.size() >= 2 && nonClear > 1000,
            nonClear + " non-clear texels, " + vals.size() + " distinct values");
    }

    /** glReadPixels の RGBA バイト列を PNG に書く。アルファは面/LoD の符号化なので潰す。 */
    static void writePng(int[] rgba, Path path) throws java.io.IOException {
        var img = new java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                // 読み戻しの 0 行目は絵の下端 (GL 規約)。PNG は 0 行目が上端なので入れ替える
                int v = rgba[(H - 1 - y) * W + x];
                int r = v & 0xFF, g = (v >>> 8) & 0xFF, b = (v >>> 16) & 0xFF;
                img.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }
        java.nio.file.Files.createDirectories(path.toAbsolutePath().getParent());
        javax.imageio.ImageIO.write(img, "PNG", path.toFile());
    }

    /**
     * 深度を見えるようにして書く。<b>クリア値 (逆Zでは 0.0) を黒、手前ほど明るく。</b>
     * 逆Zでは「手前ほど値が大きい」ので、非逆Z時代の正規化をそのまま使うと
     * <b>明暗が反転する</b>。
     */
    static void writeDepthPng(float[] depth, Path path) throws java.io.IOException {
        var img = new java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        float max = VkDepth.CLEAR;
        for (float d : depth) if (d > max) max = d;   // 逆Z: 最も手前 = 最大
        float span = Math.max(1e-6f, max - VkDepth.CLEAR);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float d = depth[(H - 1 - y) * W + x];
                int v = Math.round(((d - VkDepth.CLEAR) / span) * 255);
                v = Math.clamp(v, 0, 255);
                img.setRGB(x, y, 0xFF000000 | (v << 16) | (v << 8) | v);
            }
        }
        java.nio.file.Files.createDirectories(path.toAbsolutePath().getParent());
        javax.imageio.ImageIO.write(img, "PNG", path.toFile());
    }

    /**
     * <b>Vulkan の配列と {@code glReadPixels} が返した配列を、そのまま添字で比べる。</b>
     *
     * <p>Vulkan 経路は GL 規約の Y を通すので
     * ({@code VkSceneUniform.perspective} に Vulkan の Y 反転を入れていない)、
     * <b>どちらの読み戻しも 0 行目が絵の下端</b>になる。読み替えは要らない。
     *
     * <p>⚠ <b>「反転して比べる」に戻してはならない。</b>
     * 合成側でも反転していると打ち消し合い、<b>上下逆さまの絵が素通りする</b>。
     * 5b で実際に踏んだ [docs/phase5b-composite.md §6]。C7 が両方向を見張る。
     */
    static long countDiff(int[] vk, int[] gl) {
        long n = 0;
        for (int i = 0; i < vk.length; i++) if (vk[i] != gl[i]) n++;
        return n;
    }

    /** 上下反転して比べる。<b>C7 専用</b> — 正しい合成ならここは一致してはならない。 */
    static long countDiffFlipped(int[] a, int[] b) {
        long n = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (a[y * W + x] != b[(H - 1 - y) * W + x]) n++;
            }
        }
        return n;
    }

    /** 深度はビット単位で比べる。{@code ==} だと NaN を取り逃す。両方 Vulkan 由来なら反転しない。 */
    static long countDiffF(float[] a, float[] b) {
        long n = 0;
        for (int i = 0; i < a.length; i++) {
            if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) n++;
        }
        return n;
    }

    /** Vulkan 由来の深度と {@code glReadPixels} 由来の深度。<b>行の向きは同じ</b> (上記)。 */
    static long countDiffDepth(float[] vk, float[] gl) {
        return countDiffF(vk, gl);
    }

    // ---------------- Vulkan 側 ----------------

    record Scene(VkTerrainResources res, int drawCount) {}

    static Scene buildScene(SyntheticTerrain t) {
        var res = new VkTerrainResources(t.sectionCount(), t.totalQuads(), 4096, t.maxStateId() + 1);
        int[] starts = t.writeGeometry(res.geometry);
        t.writeMetadata(res.sectionMetadata, starts);
        t.writePositions(res.positionScratch);
        res.fillModels(VkTerrainResources.MODEL_FLAG_SHADED);
        var draws = t.opaqueDrawCommands(starts, SyntheticTerrain.ORIGIN);
        SyntheticTerrain.writeDrawCommands(res.drawCall, draws, res.indexQuadCapacity);
        return new Scene(res, draws.size());
    }

    static float[] mvp(float[] eye, float[] centre) {
        return VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(60), (float) W / H, 0.1f, 2000f),
            VkSceneUniform.lookAt(eye, centre, new float[]{0, 1, 0}));
    }

    /** {@link #closeUpMvp} と<b>同じ視点</b>で、投影の平面だけを変えた MVP。 */
    static float[] closeUpMvpWithPlanes(float near, float far) {
        return VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(60), (float) W / H, near, far),
            VkSceneUniform.lookAt(new float[]{14.0f, 7.0f, 13.0f},
                new float[]{8.5f, 0.5f, 0.5f}, new float[]{0, 1, 0}));
    }

    static float[] closeUpMvp() {
        return mvp(new float[]{14.0f, 7.0f, 13.0f}, new float[]{8.5f, 0.5f, 0.5f});
    }

    static float[] wideMvp() {
        return mvp(new float[]{99.0f, 48.0f, 100.0f}, new float[]{65.0f, 2.0f, 0.5f});
    }

    static void renderReference(VkTerrainRenderer renderer, VkRenderTarget rt,
                                VkTerrainResources res, float[] mvp, int drawCount) {
        VkSceneUniform.write(res.uniform, mvp, new int[]{0, 0, 0}, 1, new float[]{0, 0, 0});
        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        renderer.record(cmd, rt, drawCount, CLEAR);
        rt.recordReadback(cmd);
        t.endFrame();
        t.waitForFrame();
    }

    static void renderInterop(VkTerrainRenderer renderer, VkRenderTarget rt, VkDepthResolve resolve,
                              VkInteropImage colour, VkInteropImage depth,
                              VkTerrainResources res, float[] mvp, int drawCount) {
        VkSceneUniform.write(res.uniform, mvp, new int[]{0, 0, 0}, 1, new float[]{0, 0, 0});
        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        renderer.record(cmd, rt, drawCount, CLEAR);
        resolve.record(cmd, rt.depth, depth);
        // 色を GL に渡す。深度側は VkDepthResolve が GENERAL にしている
        colour.toGeneral(cmd,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
        t.endFrame();
        // Vulkan -> GL の同期。Phase 0 が 0.315ms と測った待ち
        t.waitForFrame();
    }

    static int[] readTargetPixels(VkRenderTarget rt) {
        long base = rt.readbackBuffer().addr();
        int[] out = new int[W * H];
        for (int i = 0; i < out.length; i++) out[i] = MemoryUtil.memGetInt(base + (long) i * 4);
        return out;
    }

    /** Vulkan の深度アタッチメント (D32_SFLOAT) をそのまま読む。 */
    static float[] readVulkanDepth(VkRenderTarget rt, VkBuffer dst) {
        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        rt.depth.barrier(cmd, 0, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        copyToBuffer(cmd, rt.depth.image, VK_IMAGE_ASPECT_DEPTH_BIT,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst);
        t.endFrame();
        t.waitForFrame();
        return floats(dst);
    }

    /** interop の R32F 画像 (深度解決の出力) を読む。 */
    static float[] readInteropDepth(VkInteropImage img, VkBuffer dst) {
        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        img.toGeneral(cmd,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        copyToBuffer(cmd, img.imageHandle(), VK_IMAGE_ASPECT_COLOR_BIT,
            VK_IMAGE_LAYOUT_GENERAL, dst);
        t.endFrame();
        t.waitForFrame();
        return floats(dst);
    }

    static void copyToBuffer(VkCommandBuffer cmd, long image, int aspect, int layout, VkBuffer dst) {
        try (MemoryStack stack = stackPush()) {
            var region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource().aspectMask(aspect)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(W, H, 1);
            vkCmdCopyImageToBuffer(cmd, image, layout, dst.handle, region);
        }
    }

    static float[] floats(VkBuffer buf) {
        float[] out = new float[W * H];
        long base = buf.addr();
        for (int i = 0; i < out.length; i++) out[i] = MemoryUtil.memGetFloat(base + (long) i * 4);
        return out;
    }

    // ---------------- GL 側 ----------------

    /** 合成結果を受ける GL のフレームバッファ。深度は 32F で持つ (値をそのまま読み戻すため)。 */
    static final class GlFbo {
        final int fbo, colour, depth, w, h;

        GlFbo(int w, int h) {
            this.w = w; this.h = h;
            this.colour = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, this.colour);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            this.depth = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, this.depth);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, w, h, 0,
                GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
            this.fbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, this.fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, this.colour, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, this.depth, 0);
            int s = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (s != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("composite FBO incomplete: 0x" + Integer.toHexString(s));
            }
        }

        void bind() {
            glBindFramebuffer(GL_FRAMEBUFFER, this.fbo);
            glViewport(0, 0, this.w, this.h);
        }

        /** 深度だけ既知の値で埋める。C4 で「書かれなかった」ことを見えるようにするため。 */
        void clearDepth(float v) {
            glDepthMask(true);
            glClearDepth(v);
            glClear(GL_DEPTH_BUFFER_BIT);
        }

        int[] readColour() {
            int[] px = new int[this.w * this.h];
            ByteBuffer buf = MemoryUtil.memAlloc(this.w * this.h * 4);
            try {
                glReadPixels(0, 0, this.w, this.h, GL_RGBA, GL_UNSIGNED_BYTE, buf);
                // glReadPixels は RGBA バイト順。リトルエンディアンで読むと 0xAABBGGRR になる
                for (int i = 0; i < px.length; i++) px[i] = buf.getInt(i * 4);
            } finally {
                MemoryUtil.memFree(buf);
            }
            return px;
        }

        float[] readDepth() {
            float[] d = new float[this.w * this.h];
            FloatBuffer buf = MemoryUtil.memAllocFloat(this.w * this.h);
            try {
                glReadPixels(0, 0, this.w, this.h, GL_DEPTH_COMPONENT, GL_FLOAT, buf);
                buf.get(d);
            } finally {
                MemoryUtil.memFree(buf);
            }
            return d;
        }
    }

    static void initGl() {
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed (need -XstartOnFirstThread)");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(W, H, "phase5b", NULL, NULL);
        if (window == NULL) throw new IllegalStateException("glfwCreateWindow failed");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        if (Cgl.currentContext().address() == 0) throw new IllegalStateException("no CGL context");
        System.out.println("GL: " + glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION));
        System.out.println("size: " + W + "x" + H);
    }

    static void teardown() {
        try {
            if (VkFrameTracker.isRecordingFrame()) VkFrameTracker.get().endFrame();
            VkFrameTracker.get().waitIdle();
            VkSampler.shutdown();
            VkQuadIndexBuffer.shutdown();
            VkFrameTracker.shutdown();
        } catch (Throwable t) {
            System.out.println("teardown: " + t);
        }
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
