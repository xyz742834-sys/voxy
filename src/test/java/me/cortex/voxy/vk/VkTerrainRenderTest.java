package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer.Barriers;
import me.cortex.voxy.client.core.vk.shader.SpirvCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import me.cortex.voxy.client.core.vk.shader.VkShaderType;
import org.junit.jupiter.api.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 1 項目 4/5: 地形シェーダを無改造で Vulkan 上に載せ、絵が出ることと
 * その絵が決定的であることを確かめる。
 *
 * <h2>ここで検証していること / していないこと</h2>
 * <table>
 *   <tr><th>している</th><th>していない</th></tr>
 *   <tr><td>同じ入力から決定的に同じ絵が出る</td><td><b>GL 版と同じ絵か</b></td></tr>
 *   <tr><td>索引をずらすと絵が変わる (比較の感度)</td><td>実ワールドで正しく見えるか</td></tr>
 *   <tr><td>保守的バリアと絞ったバリアが一致する</td><td>バリアが十分である証明</td></tr>
 * </table>
 *
 * <p>入力は合成データである。実データ (ワールド読み込み + メッシュ生成 + アトラス) は
 * この環境で得られない [確認済 — docs/phase2-binding-audit.md 8.4]。
 * Stage 2b が問うのは「統合前と統合後で同じ入力から同じ絵が出るか」なので、
 * この限定で足りる (docs/phase4-proposal.md 7.1)。
 */
public class VkTerrainRenderTest {
    private static final int W = 512, H = 512;
    /** 背景。地形が出す色と紛れないように選ぶ。 */
    private static final float[] CLEAR = {0.05f, 0.05f, 0.10f, 1.0f};
    private static final Path OUT = Path.of("build", "vk-test-output");

    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
        VkFrameTracker.init();
    }

    @AfterAll
    static void teardown() {
        if (VkFrameTracker.isRecordingFrame()) VkFrameTracker.get().endFrame();
        VkFrameTracker.get().waitIdle();
        VkSampler.shutdown();
        VkQuadIndexBuffer.shutdown();
        VkFrameTracker.shutdown();
    }

    // ---------------- scene fixture ----------------

    /** 合成地形 + それを描くのに必要な GPU リソース一式。 */
    private record Scene(SyntheticTerrain terrain, VkTerrainResources res,
                         List<SyntheticTerrain.OpaqueDraw> draws,
                         SyntheticTerrain.MergedTable table) implements AutoCloseable {
        int drawCount() { return this.draws.size(); }
        @Override public void close() { this.res.free(); }
    }

    private static Scene build(SyntheticTerrain t) {
        // stateId は疎に散らばるので、モデルバッファは実際に使われる最大値で確保する。
        // 足りないと modelData[stateId] が範囲外読みになり、バリデーションも捕まえない
        var res = new VkTerrainResources(t.sectionCount(), t.totalQuads(), 4096, t.maxStateId() + 1);
        int[] starts = t.writeGeometry(res.geometry);
        t.writeMetadata(res.sectionMetadata, starts);
        t.writePositions(res.positionScratch);
        res.fillModels(VkTerrainResources.MODEL_FLAG_SHADED);

        // Stage 1 の経路 (セクション x 面ごとに 1 draw)
        var draws = t.opaqueDrawCommands(starts, SyntheticTerrain.ORIGIN);
        SyntheticTerrain.writeDrawCommands(res.drawCall, draws, res.indexQuadCapacity);

        // Stage 2 の経路。**別のバッファに置く**ので、片方を描いてももう片方は無傷。
        // 同じバッファを書き換えて A/B すると「書き換え忘れ」で偽の一致が出うる
        var table = t.mergedTable(starts, SyntheticTerrain.ORIGIN);
        SyntheticTerrain.writeMergedEntries(res.mergedEntry, table);
        SyntheticTerrain.writeMergedPrefix(res.mergedPrefix, table);
        SyntheticTerrain.writeFaceDraws(res.mergedDraw, table, res.indexQuadCapacity);
        return new Scene(t, res, draws, table);
    }

    /** 統合経路の draw 本数。面が T を超えると分割されるぶん 7 より増える。 */
    private static int mergedDraws(Scene scene) {
        return SyntheticTerrain.faceDrawCount(scene.table(), scene.res().indexQuadCapacity);
    }

    private static float[] mvp(float[] eye, float[] centre) {
        return VkSceneUniform.mul(
            VkSceneUniform.perspective((float) Math.toRadians(60), (float) W / H, 0.1f, 2000f),
            VkSceneUniform.lookAt(eye, centre, new float[]{0, 1, 0}));
    }

    /**
     * 7 面区分すべてを使うセクション (17 quad) を至近距離から見る視点。
     *
     * <p>そのセクションは<b>原点セクションに置く必要がある</b> — 面方向マスクを
     * 7 面すべて通せるのは {@code relative == 0} のときだけだから
     * ({@code SyntheticTerrainTest.everyBoundaryCaseSurvivesTheFaceMask} が守っている)。
     * quad はセクション内で 1 格子ずつずれるので、ワールドでは x=0..17 の帯になる。
     */
    private static float[] closeUpMvp() {
        return mvp(new float[]{14.0f, 7.0f, 13.0f}, new float[]{8.5f, 0.5f, 0.5f});
    }

    /**
     * セクション 0 / 1 / 3 をまとめて見る視点。
     * <b>セクションごとに位置が違うこと</b> — つまり {@code positionBuffer[gl_BaseInstance]}
     * の経路が効いていること — が絵に出る
     * (gl_BaseInstance は GLSL 互換の要注意箇所。docs/phase2-glsl-compat.md 3.2)。
     */
    private static float[] wideMvp() {
        return mvp(new float[]{99.0f, 48.0f, 100.0f}, new float[]{65.0f, 2.0f, 0.5f});
    }

    /** 1 フレーム描いて読み戻す。呼び出し後 {@code rt} の読み戻しバッファが有効になる。 */
    private static void renderOnce(VkTerrainRenderer renderer, VkRenderTarget rt,
                                   Scene scene, float[] mvp, int drawCount) {
        VkSceneUniform.write(scene.res().uniform, mvp, new int[]{0, 0, 0}, 1, new float[]{0, 0, 0});
        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        renderer.record(cmd, rt, drawCount, CLEAR);
        rt.recordReadback(cmd);
        t.endFrame();
        t.waitForFrame();
    }

    /** 背景色と異なるピクセル数。「何か描かれたか」の指標。 */
    private static long coverage(VkRenderTarget rt) {
        int clear = 0xFF000000
            | (Math.round(CLEAR[2] * 255) << 16)
            | (Math.round(CLEAR[1] * 255) << 8)
            | Math.round(CLEAR[0] * 255);
        long base = rt.readbackBuffer().addr();
        long n = 0;
        for (long i = 0; i < (long) rt.width * rt.height; i++) {
            int v = MemoryUtil.memGetInt(base + i * 4);
            // アルファは面/LoD の符号化なので比較から外す [VkRenderTarget.writePng 参照]
            if ((v & 0x00FFFFFF) != (clear & 0x00FFFFFF)) n++;
        }
        return n;
    }

    // ---------------- 前提の確認 ----------------

    /**
     * <b>頂点シェーダが 64bit quad 経路でコンパイルされていること。</b>
     *
     * <p>{@code quads3.vert} は {@code GL_ARB_gpu_shader_int64} が有効なら
     * {@code Quad = uint64_t}、そうでなければ {@code Quad = ivec2} を選ぶ。
     * <b>両者は stateId の取り出し方が違う</b> (64bit 側は bit 26..41 の 16bit、
     * ivec2 側は 26..45 の 20bit を組み立てる) [確認済 — `quad_format.glsl`]。
     * {@code SyntheticTerrain} は 64bit 側の配置で書いているので、
     * ここが変わると<b>基準画像が静かに間違う</b>。
     */
    @Test
    void vertexShaderUsesThe64BitQuadPath() {
        String src = VkShaderLoader.parse("voxy:lod/gl46/quads3.vert");
        String expanded = SpirvCompiler.preprocess(VkShaderType.from(ShaderType.VERTEX), src, "quad-path-probe");
        assertNotNull(expanded, "preprocessing must succeed");
        assertTrue(expanded.contains("uint64_t"),
            "quads3.vert must take the 64-bit Quad path; SyntheticTerrain encodes for that layout");
    }

    // ---------------- 項目 4: 絵が出る ----------------

    /** 至近距離から見て地形が描かれること + PNG 出力 (項目 5 の目視確認用)。 */
    @Test
    void drawsTheCloseUpView() throws Exception {
        try (var scene = build(SyntheticTerrain.boundaryCases())) {
            var renderer = new VkTerrainRenderer(scene.res(), W, H, Barriers.CONSERVATIVE);
            var rt = new VkRenderTarget(W, H);
            try {
                renderOnce(renderer, rt, scene, closeUpMvp(), scene.drawCount());
                long covered = coverage(rt);
                var colours = rt.distinctColours();
                System.out.println("[vk] close-up: " + scene.drawCount() + " draws, "
                    + covered + " px covered, " + colours.size() + " distinct colours");

                rt.writePng(OUT.resolve("terrain-closeup.png"), true);
                assertTrue(covered > 1000,
                    "expected section 6's quads to cover a good part of the frame, got " + covered + " px");
                assertTrue(colours.size() >= 4,
                    "faces must be distinguishable by colour, got " + colours.size());
            } finally {
                rt.free();
                renderer.free();
            }
        }
    }

    /** 複数セクションが別々の位置に出ること ({@code gl_BaseInstance} 経路の確認)。 */
    @Test
    void drawsMultipleSectionsAtDistinctPositions() throws Exception {
        try (var scene = build(SyntheticTerrain.boundaryCases())) {
            var renderer = new VkTerrainRenderer(scene.res(), W, H, Barriers.CONSERVATIVE);
            var rt = new VkRenderTarget(W, H);
            try {
                renderOnce(renderer, rt, scene, wideMvp(), scene.drawCount());
                long covered = coverage(rt);
                System.out.println("[vk] wide: " + covered + " px covered, "
                    + rt.distinctColours().size() + " distinct colours");
                rt.writePng(OUT.resolve("terrain-wide.png"), true);
                assertTrue(covered > 500, "expected several sections to be visible, got " + covered + " px");
            } finally {
                rt.free();
                renderer.free();
            }
        }
    }

    /** 描画コマンドが 0 件なら背景だけになること (下限の対照)。 */
    @Test
    void zeroDrawsLeavesTheClearColour() {
        try (var scene = build(SyntheticTerrain.boundaryCases())) {
            var renderer = new VkTerrainRenderer(scene.res(), W, H, Barriers.CONSERVATIVE);
            var rt = new VkRenderTarget(W, H);
            try {
                renderOnce(renderer, rt, scene, closeUpMvp(), 0);
                assertEquals(0, coverage(rt), "nothing was drawn, so every pixel must be the clear colour");
            } finally {
                rt.free();
                renderer.free();
            }
        }
    }

    // ---------------- 項目 5: 決定性 ----------------

    /**
     * <b>同じ入力から何度描いても同じ絵になること。</b>
     * Stage 2b の比較 (統合前 vs 統合後で差分ゼロ) はこれが前提になる。
     * 非決定性が混じっていると、統合のバグと区別が付かなくなる。
     */
    @Test
    void repeatedRendersArePixelIdentical() {
        try (var scene = build(SyntheticTerrain.boundaryCases())) {
            var renderer = new VkTerrainRenderer(scene.res(), W, H, Barriers.CONSERVATIVE);
            var targets = new VkRenderTarget[3];
            try {
                float[] mvp = closeUpMvp();
                for (int i = 0; i < targets.length; i++) {
                    targets[i] = new VkRenderTarget(W, H);
                    renderOnce(renderer, targets[i], scene, mvp, scene.drawCount());
                }
                for (int i = 1; i < targets.length; i++) {
                    assertEquals(0, VkRenderTarget.compareColor(targets[0], targets[i]),
                        "render " + i + " differs from render 0");
                }
            } finally {
                for (var rt : targets) if (rt != null) rt.free();
                renderer.free();
            }
        }
    }

    /**
     * <b>同じレンダラを使い回して連続描画しても同じ絵になること。</b>
     * 別ターゲットを 1 枚ずつ描くのと違い、こちらは
     * 「同じ画像に描画 -> 読み戻し -> また描画」で
     * <b>読み戻しと次フレームの描画の WAR</b> を踏む
     * (docs/phase4-buffer-hazards.md の構造と同じ形)。
     */
    @Test
    void reusingOneTargetAcrossFramesIsStable() {
        try (var scene = build(SyntheticTerrain.boundaryCases())) {
            var renderer = new VkTerrainRenderer(scene.res(), W, H, Barriers.CONSERVATIVE);
            var rt = new VkRenderTarget(W, H);
            var reference = new VkRenderTarget(W, H);
            try {
                float[] mvp = closeUpMvp();
                renderOnce(renderer, reference, scene, mvp, scene.drawCount());
                for (int i = 0; i < 4; i++) {
                    renderOnce(renderer, rt, scene, mvp, scene.drawCount());
                    assertEquals(0, VkRenderTarget.compareColor(reference, rt), "frame " + i);
                }
            } finally {
                rt.free();
                reference.free();
                renderer.free();
            }
        }
    }

    // ---------------- 外部のパスに記録する経路 ----------------

    /**
     * <b>パスを自分で開く経路と、他者のパスに入れる経路が同じ絵を出すこと。</b>
     *
     * <p>Minecraft 自身の Vulkan フレームに地形を入れるには、パスの開閉を
     * 握っている {@link VkTerrainRenderer#record} を通せない。そこで
     * {@link VkTerrainRenderer#recordBeforeRenderPass} (パスの外) と
     * {@link VkTerrainRenderer#recordDrawsInRenderPass} (パスの中) に分けたが、
     * <b>分けたことで何かが抜けていないか</b>は絵でしか分からない。
     *
     * <p>⚠ これが示すのは「分割が等価である」ことだけである。
     * Minecraft のパスに入れて正しく見えるかは<b>何も言っていない</b>。
     */
    @Test
    void theSplitRecordingPathDrawsTheSameImage() {
        try (var scene = build(SyntheticTerrain.boundaryCases())) {
            var renderer = new VkTerrainRenderer(scene.res(), W, H, Barriers.CONSERVATIVE);
            var whole = new VkRenderTarget(W, H);
            var split = new VkRenderTarget(W, H);
            try {
                float[] mvp = closeUpMvp();
                renderOnce(renderer, whole, scene, mvp, scene.drawCount());

                VkSceneUniform.write(scene.res().uniform, mvp, new int[]{0, 0, 0}, 1,
                    new float[]{0, 0, 0});
                var tracker = VkFrameTracker.get();
                var cmd = tracker.beginFrame();
                renderer.assertColourFormatMatches(split.colorFormat);
                renderer.recordBeforeRenderPass(cmd);
                split.beginRendering(cmd, CLEAR, VkDepth.CLEAR);
                renderer.recordDrawsInRenderPass(cmd, scene.drawCount());
                split.endRendering(cmd);
                split.recordReadback(cmd);
                tracker.endFrame();
                tracker.waitForFrame();

                assertTrue(coverage(whole) > 0, "the reference frame drew nothing");
                assertEquals(0, VkRenderTarget.compareColor(whole, split),
                    "splitting the recording changed the image");
            } finally {
                split.free();
                whole.free();
                renderer.free();
            }
        }
    }

    /**
     * <b>外部のパスに入れる経路でも、フォーマットの食い違いは落ちること。</b>
     *
     * <p>的を受け取らないので自動では照合できない。呼び出し側が明示的に確かめる
     * ための入口が実際に落ちることを確かめておく — ここが黙ると、
     * 色が入れ替わった絵が「正しく描けた」として通る。
     */
    @Test
    void theExplicitFormatCheckStillRejectsAMismatch() {
        try (var scene = build(SyntheticTerrain.boundaryCases())) {
            var renderer = new VkTerrainRenderer(scene.res(), W, H, Barriers.CONSERVATIVE);
            try {
                assertEquals(VkRenderTarget.FORMAT_COLOR, renderer.colourFormat());
                assertThrows(IllegalArgumentException.class,
                    () -> renderer.assertColourFormatMatches(VkRenderTarget.FORMAT_COLOR + 1));
            } finally {
                renderer.free();
            }
        }
    }

    // ---------------- 比較の感度 (対照) ----------------

    /**
     * <b>索引を 1 quad ずらしたら絵が変わること。</b>
     *
     * <p>これが効かないと「決定的に同じ絵が出た」も「比較で差分ゼロだった」も無意味になる。
     * Stage 2b が索引ずれを検出できるという前提そのものの検査である。
     */
    @Test
    void shiftingTheQuadIndexChangesTheImage() throws Exception {
        var terrain = SyntheticTerrain.boundaryCases();
        try (var scene = build(terrain)) {
            var renderer = new VkTerrainRenderer(scene.res(), W, H, Barriers.CONSERVATIVE);
            var reference = new VkRenderTarget(W, H);
            var shifted = new VkRenderTarget(W, H);
            try {
                float[] mvp = closeUpMvp();
                renderOnce(renderer, reference, scene, mvp, scene.drawCount());

                // baseVertex を 1 quad ぶん進める = 各コマンドが隣の quad から読み始める
                var off = scene.draws().stream()
                    .map(d -> new SyntheticTerrain.OpaqueDraw(
                        d.drawId(), d.quadOffset() + 1, d.quadCount(), d.faceBit()))
                    .toList();
                SyntheticTerrain.writeDrawCommands(scene.res().drawCall, off,
                    scene.res().indexQuadCapacity);
                renderOnce(renderer, shifted, scene, mvp, scene.drawCount());

                long diff = VkRenderTarget.compareColor(reference, shifted);
                System.out.println("[vk] one-quad index shift -> " + diff + " px differ");
                VkRenderTarget.writeDiffPng(reference, shifted, OUT.resolve("terrain-index-shift-diff.png"));
                assertTrue(diff > 0,
                    "a one-quad index shift must be visible; if it is not, the comparison "
                        + "cannot detect draw-merging bugs in Stage 2b either");
            } finally {
                reference.free();
                shifted.free();
                renderer.free();
            }
        }
    }

    // ---------------- バリアを絞る ----------------

    /**
     * <b>保守的バリアと絞ったバリアが同じ絵を出すこと。</b>
     *
     * <p>⚠ これは<b>バリアが十分であることの証明ではない</b>。
     * 同期バリデーションがこの環境で機能せず (docs/phase3-completion.md 3.1)、
     * さらに in-flight = 1 とユニファイドメモリのおかげで
     * <b>足りないバリアが表に出にくい</b>。
     * 観測できるのは「絞っても壊れなかった」までである。
     * 根拠の弱さも含めて docs/phase4-stage1-completion.md に記録してある。
     */
    @Test
    void narrowBarriersProduceTheSameImage() {
        try (var scene = build(SyntheticTerrain.boundaryCases())) {
            var conservative = new VkTerrainRenderer(scene.res(), W, H, Barriers.CONSERVATIVE);
            var narrow = new VkTerrainRenderer(scene.res(), W, H, Barriers.NARROW);
            var a = new VkRenderTarget(W, H);
            var b = new VkRenderTarget(W, H);
            try {
                float[] mvp = closeUpMvp();
                renderOnce(conservative, a, scene, mvp, scene.drawCount());
                renderOnce(narrow, b, scene, mvp, scene.drawCount());
                assertEquals(0, VkRenderTarget.compareColor(a, b),
                    "narrowing the barriers changed the image");
                assertTrue(coverage(a) > 1000, "the comparison is only meaningful if something was drawn");
            } finally {
                a.free();
                b.free();
                conservative.free();
                narrow.free();
            }
        }
    }

    // ---------------- Stage 2b: draw 統合 ----------------

    /**
     * <b>Stage 2b の合格条件: 統合前と統合後がピクセル単位で一致すること。</b>
     *
     * <p>Stage 1 は (セクション, 面) ごとに 13 draws、Stage 2b は面方向別に 7 draws。
     * 索引の解決方法だけが違い、位置・UV・属性の計算は共通コードを通る。
     * <b>1 ピクセルでも違えば統合ロジックのバグ</b> (docs/phase4-proposal.md 4.1)。
     *
     * <p>比較が成立する前提は
     * {@code SyntheticTerrainTest.everyQuadOccupiesADistinctVisiblePosition} が守っている。
     * 共平面の quad があると<b>発行順の違いだけで差分が出て</b>、
     * 統合のバグと区別が付かなくなる。
     */
    @Test
    void mergedPathMatchesPerSectionPixelForPixel() throws Exception {
        try (var scene = build(SyntheticTerrain.boundaryCases())) {
            var perSection = new VkTerrainRenderer(scene.res(), W, H,
                Barriers.CONSERVATIVE, VkTerrainRenderer.Mode.PER_SECTION);
            var merged = new VkTerrainRenderer(scene.res(), W, H,
                Barriers.CONSERVATIVE, VkTerrainRenderer.Mode.MERGED);
            var a = new VkRenderTarget(W, H);
            var b = new VkRenderTarget(W, H);
            try {
                System.out.println("[vk] stage1 draws = " + scene.drawCount()
                    + ", stage2b draws = " + mergedDraws(scene)
                    + ", table entries = " + scene.table().entryCount()
                    + ", quads = " + scene.table().totalQuads());

                float[] mvp = closeUpMvp();
                renderOnce(perSection, a, scene, mvp, scene.drawCount());
                renderOnce(merged, b, scene, mvp, mergedDraws(scene));

                long covered = coverage(a);
                assertTrue(covered > 1000, "the comparison is only meaningful if something was drawn");
                long diff = VkRenderTarget.compareColor(a, b);
                if (diff != 0) {
                    VkRenderTarget.writeDiffPng(a, b, OUT.resolve("terrain-merge-diff.png"));
                    b.writePng(OUT.resolve("terrain-merged.png"), true);
                }
                assertEquals(0, diff, "merging the draws changed " + diff + " pixels; "
                    + "see build/vk-test-output/terrain-merge-diff.png");
                b.writePng(OUT.resolve("terrain-merged.png"), true);
            } finally {
                a.free();
                b.free();
                perSection.free();
                merged.free();
            }
        }
    }

    /**
     * 別の視点でも一致すること。
     * 1 視点だけだと<b>たまたま隠れている誤りを見逃す</b>。
     */
    @Test
    void mergedPathMatchesFromASecondViewpoint() {
        try (var scene = build(SyntheticTerrain.boundaryCases())) {
            var perSection = new VkTerrainRenderer(scene.res(), W, H,
                Barriers.CONSERVATIVE, VkTerrainRenderer.Mode.PER_SECTION);
            var merged = new VkTerrainRenderer(scene.res(), W, H,
                Barriers.CONSERVATIVE, VkTerrainRenderer.Mode.MERGED);
            var a = new VkRenderTarget(W, H);
            var b = new VkRenderTarget(W, H);
            try {
                float[] mvp = wideMvp();
                renderOnce(perSection, a, scene, mvp, scene.drawCount());
                renderOnce(merged, b, scene, mvp, mergedDraws(scene));
                assertTrue(coverage(a) > 500, "nothing was drawn in the wide view");
                assertEquals(0, VkRenderTarget.compareColor(a, b), "wide view differs");
            } finally {
                a.free(); b.free(); perSection.free(); merged.free();
            }
        }
    }

    /**
     * <b>境界条件のセクションを 1 つずつ単独で描いても一致すること。</b>
     *
     * <p>まとめて描くと、あるセクションの誤りが別のセクションに隠される。
     * 空セクション・quad 数ちょうど 64・7 面区分・負座標を個別に通す。
     */
    @Test
    void eachBoundaryCaseMergesIdentically() {
        record Case(String name, SyntheticTerrain terrain, float[] eye, float[] centre) {}
        var cases = List.of(
            new Case("① 最小 (quad 1 枚)",
                new SyntheticTerrain().add(new SyntheticTerrain.Section(0, 0, 0, 0)
                    .face(SyntheticTerrain.Face.UP, 1)),
                new float[]{3, 3, 3}, new float[]{0.5f, 1, 0.5f}),
            new Case("② 1 方向だけ",
                new SyntheticTerrain().add(new SyntheticTerrain.Section(0, 0, 0, 0)
                    .face(SyntheticTerrain.Face.NORTH, 4)),
                new float[]{6, 4, 8}, new float[]{2, 0.5f, 0.5f}),
            new Case("③ 空セクションを挟む",
                new SyntheticTerrain()
                    .add(new SyntheticTerrain.Section(0, 0, 0, 0).face(SyntheticTerrain.Face.UP, 20))
                    .add(new SyntheticTerrain.Section(1, 0, 0, 0))
                    .add(new SyntheticTerrain.Section(2, 0, 0, 0).face(SyntheticTerrain.Face.UP, 20)),
                new float[]{42, 65, 55}, new float[]{42, 1, 0.5f}),
            new Case("⑥ quad 数ちょうど 64",
                new SyntheticTerrain().add(new SyntheticTerrain.Section(0, 0, 0, 0)
                    .face(SyntheticTerrain.Face.EAST, 64)),
                new float[]{40, 20, 40}, new float[]{16, 1, 0.5f}),
            new Case("⑦ 7 面区分すべて",
                new SyntheticTerrain().add(sevenFaceSection()),
                new float[]{16, 8, 20}, new float[]{7, 0.5f, 0.5f}),
            // WEST は relative.x > -1 が要るので x は 0。y/z は負のまま符号拡張を踏む
            new Case("⑧ 負座標",
                new SyntheticTerrain().add(new SyntheticTerrain.Section(0, -2, -3, 1)
                    .face(SyntheticTerrain.Face.WEST, 5)),
                new float[]{18, -118, -178}, new float[]{5, -127, -191}));

        for (var c : cases) {
            try (var scene = build(c.terrain())) {
                var perSection = new VkTerrainRenderer(scene.res(), W, H,
                    Barriers.CONSERVATIVE, VkTerrainRenderer.Mode.PER_SECTION);
                var merged = new VkTerrainRenderer(scene.res(), W, H,
                    Barriers.CONSERVATIVE, VkTerrainRenderer.Mode.MERGED);
                var a = new VkRenderTarget(W, H);
                var b = new VkRenderTarget(W, H);
                try {
                    float[] m = mvp(c.eye(), c.centre());
                    renderOnce(perSection, a, scene, m, scene.drawCount());
                    renderOnce(merged, b, scene, m, mergedDraws(scene));
                    long covered = coverage(a);
                    System.out.println("[vk] " + c.name() + ": " + covered + " px, "
                        + scene.drawCount() + " -> 7 draws");
                    assertTrue(covered > 200,
                        c.name() + ": nothing visible, so the comparison proves nothing");
                    assertEquals(0, VkRenderTarget.compareColor(a, b), c.name() + " differs");
                } finally {
                    a.free(); b.free(); perSection.free(); merged.free();
                }
            }
        }
    }

    private static SyntheticTerrain.Section sevenFaceSection() {
        var s = new SyntheticTerrain.Section(0, 0, 0, 0).translucent(3);
        for (var f : SyntheticTerrain.Face.values()) s.face(f, 2);
        return s;
    }

    /**
     * <b>対照</b>: 統合テーブルを壊すと絵が変わること。
     * 差分ゼロが「比較が効いている」ことの裏付けになる。
     */
    @Test
    void aCorruptedMergedTableChangesTheImage() {
        try (var scene = build(SyntheticTerrain.boundaryCases())) {
            var merged = new VkTerrainRenderer(scene.res(), W, H,
                Barriers.CONSERVATIVE, VkTerrainRenderer.Mode.MERGED);
            var good = new VkRenderTarget(W, H);
            var bad = new VkRenderTarget(W, H);
            try {
                float[] mvp = closeUpMvp();
                renderOnce(merged, good, scene, mvp, mergedDraws(scene));

                // 全エントリの quadStart を 1 ずらす。
                // ⚠ 1 本だけ壊すのでは足りない: 壊した先がこの視点で見えていないと
                // 差分 0 になり、対照として無力になる (実際そうなった)。
                // Stage 1 の shiftingTheQuadIndexChangesTheImage と同じ形にする
                for (int i = 0; i < scene.table().entryCount(); i++) {
                    long slot = scene.res().mergedEntry.addr()
                        + (long) i * SyntheticTerrain.MERGED_ENTRY_SIZE;
                    MemoryUtil.memPutInt(slot, MemoryUtil.memGetInt(slot) + 1);
                }
                renderOnce(merged, bad, scene, mvp, mergedDraws(scene));

                long diff = VkRenderTarget.compareColor(good, bad);
                System.out.println("[vk] corrupted merged entry -> " + diff + " px differ");
                assertTrue(diff > 0, "corrupting the table must change the image; "
                    + "otherwise the zero-diff result above proves nothing");
            } finally {
                good.free(); bad.free(); merged.free();
            }
        }
    }

    /** Stage 2a: テーブルを作っただけでは Stage 1 の絵は変わらないこと。 */
    @Test
    void tableGenerationDoesNotChangeTheImage() {
        var terrain = SyntheticTerrain.boundaryCases();
        try (var withTable = build(terrain)) {
            var r1 = new VkTerrainRenderer(withTable.res(), W, H, Barriers.CONSERVATIVE);
            var a = new VkRenderTarget(W, H);
            var b = new VkRenderTarget(W, H);
            try {
                float[] mvp = closeUpMvp();
                renderOnce(r1, a, withTable, mvp, withTable.drawCount());
                // テーブルを書き直しても Stage 1 経路は一切読まない
                SyntheticTerrain.writeMergedEntries(withTable.res().mergedEntry, withTable.table());
                renderOnce(r1, b, withTable, mvp, withTable.drawCount());
                assertEquals(0, VkRenderTarget.compareColor(a, b),
                    "the per-section path must not depend on the merged table");
            } finally {
                a.free(); b.free(); r1.free();
            }
        }
    }

    // ---------------- 最小データ ----------------

    /**
     * <b>quad 1 枚を、予測した位置に予測した色で描くこと</b> (境界条件 ①)。
     *
     * <p>期待値は CPU 側で全経路をたどって出す:
     * <ol>
     *   <li>位置: {@code minimal()} の唯一の quad は UP 面。{@code quad_util.glsl} の
     *       面オフセット (indentation 0、{@code face&1 == 1} なので +1) により
     *       <b>y = 1 の水平面 (0..1, 1, 0..1)</b> になる。その中心を MVP で射影して画素を出す</li>
     *   <li>色: stateId = {@code (section 0, run 3, i 0)} = 192。
     *       アトラスの (modelId 192, face 1) のテクセル = (192, 0, 55)</li>
     *   <li>ティント: {@code UP_FACE_TINT} = 1.0、ライトマップは一様な白なので RGB は素通り</li>
     *   <li>アルファ: 不透明度ではなく {@code face | lodLevel<<3 | hasAO<<6} = 1 | 0 | 64 = <b>65</b></li>
     * </ol>
     *
     * <p><b>1 つでも外れれば落ちる。</b> アトラスの UV 計算、モデルデータ、
     * ライティング、面ティント、位置エンコードを一度に押さえる。
     *
     * <h2>⚠ 画面上の向き (上下・左右) は<b>押さえていない</b></h2>
     * 射影に使う行列が<b>描画に使う行列と同じ</b>なので、Y や X の符号を反転すると
     * <b>予測画素も一緒に移動して再び一致する</b>
     * [docs/phase4-stage1-completion.md §5.2 の規約 4 / 11 例目]。
     * 加えて対象は 1 枚の平坦な面なので<b>面上のどの画素でも同じ色</b>になり、
     * 位置がずれても色の検査は通る。
     *
     * <p>向きは {@code VkOrientationTest} が受け持つ
     * (行列に依存しない物理的な事実を主張する形)。
     * <b>担当範囲を分けてあるので、ここで重ねて検査しない。</b>
     */
    @Test
    void singleQuadLandsWhereAndHowPredicted() {
        try (var scene = build(SyntheticTerrain.minimal())) {
            assertEquals(1, scene.drawCount(), "one UP face -> one draw command");
            var renderer = new VkTerrainRenderer(scene.res(), W, H, Barriers.CONSERVATIVE);
            var rt = new VkRenderTarget(W, H);
            try {
                float[] m = mvp(new float[]{2.5f, 2.5f, 2.5f}, new float[]{0.5f, 1.0f, 0.5f});
                renderOnce(renderer, rt, scene, m, 1);

                long covered = coverage(rt);
                System.out.println("[vk] single quad: " + covered + " px covered");
                assertTrue(covered > 1000,
                    "a 1x1 quad seen from ~3 units should be large, got " + covered + " px");

                // 面の中心 (0.5, 1, 0.5) を同じ行列で射影する
                float[] ndc = VkSceneUniform.project(m, 0.5f, 1.0f, 0.5f);
                int px = Math.round((ndc[0] * 0.5f + 0.5f) * W);
                int py = Math.round((ndc[1] * 0.5f + 0.5f) * H);
                System.out.println("[vk] quad centre projects to (" + px + "," + py + ") -> "
                    + java.util.Arrays.toString(rt.pixelAt(px, py)));
                assertTrue(px >= 0 && px < W && py >= 0 && py < H, "centre must be on screen");

                // stateId = (section 0, run 3 = UP, i 0) -> 192
                int[] texel = VkTerrainResources.atlasTexel(192, 1);
                assertArrayEquals(new int[]{texel[0], texel[1], texel[2], 65}, rt.pixelAt(px, py),
                    "the projected centre must carry the atlas texel of (model 192, face UP), "
                        + "with alpha = face|lod<<3|hasAO<<6");
            } finally {
                rt.free();
                renderer.free();
            }
        }
    }
}
