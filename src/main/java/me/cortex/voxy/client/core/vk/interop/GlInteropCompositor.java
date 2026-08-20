package me.cortex.voxy.client.core.vk.interop;

import me.cortex.voxy.client.core.vk.VkDepth;

import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.GL_TRUE;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glColorMask;
import static org.lwjgl.opengl.GL11C.glDepthFunc;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;

/**
 * Vulkan が描いた結果を <b>GL 側のフレームバッファに合成する</b>パス。
 *
 * <pre>
 * interop 色 (BGRA IOSurface)   ──┐
 *                                 ├─→ この GL パス ─→ MC のフレームバッファ
 * interop 深度 (R32F IOSurface) ──┘      色 + gl_FragDepth
 * </pre>
 *
 * <p>Phase 0 の {@code GlComposite} を Voxy に取り込んだもの。
 * {@code CGLTexImageIOSurface2D} が {@code GL_TEXTURE_RECTANGLE} 専用なので、
 * サンプラは {@code sampler2DRect} で座標は<b>非正規化</b>である。
 *
 * <h2>色をそのまま通すこと</h2>
 * 地形シェーダの出力アルファは<b>不透明度ではない</b> —
 * {@code face | lodLevel<<3 | hasAO<<6} が詰まっている
 * [確認済 — `quad_util.glsl`、`VkRenderTarget.writePng` の注記]。
 * したがってこのパスは<b>ブレンドを掛けず、アルファも含めてビット単位で素通し</b>する。
 * 掛けた瞬間に Vulkan 側の出力と比較できなくなる。
 *
 * <p>GL 4.1 を前提にしている (Apple の GL は 4.1 が上限 [確認済 — Phase 0 §8.5])。
 * サンプラの {@code layout(binding=)} は GL 4.2 以降なので使わず、
 * {@code glUniform1i} でユニットを渡す。
 */
public final class GlInteropCompositor {
    private static final String VERT = """
        #version 410 core
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    /**
     * 合成が <b>MC の深度バッファに何をするか</b>。
     *
     * <h2>⚠ ここは一度壊した場所である</h2>
     * 5c-1b で {@code GL_ALWAYS} + 深度書き込みのまま合成し、
     * <b>MC の深度バッファを一様に潰して mob が地形を貫通した</b>
     * [docs/phase5c1b-completion.md §9]。深度を書くこと自体が危険なのではなく、
     * <b>どの画素に、どういう条件で書くか</b>が要点である。
     */
    public enum DepthMode {
        /**
         * 深度を書かない。深度テストも切る (5c-1b / 5c-1c)。
         * <b>まだ Voxy 自身の深度が無い段</b>で使う。
         */
        NONE,
        /**
         * <b>無条件に上書き</b> ({@code GL_ALWAYS})。
         *
         * <p>5b のオフスクリーン比較専用 — 「解決した深度がそのまま書き戻る」ことを
         * 画素単位で確かめるのに要る。<b>ホストの中で使ってはならない</b>
         * (MC の深度を全画素潰す)。
         */
        OVERWRITE,
        /**
         * <b>MC の深度と比較して勝った画素にだけ書く</b> (5c-1d 以降の本番)。
         *
         * <p>2 つの条件が同時に要る:
         * <ul>
         *   <li><b>Voxy が何も描いていない画素は {@code discard}</b> —
         *       そこの深度はクリア値 (逆Zでは {@link VkDepth#FAR} = 0.0) で、
         *       MC の空の深度と同じなので、比較だけでは落とせない。
         *       捨てないと<b>空がクリア色で塗り潰される</b></li>
         *   <li>残りは {@link VkDepth#GL_CLOSER_EQUAL_COMPARE} で深度テスト —
         *       MC の近景地形より奥の LoD は<b>そこで落ちる</b></li>
         * </ul>
         *
         * <p>⚠ <b>Voxy の深度が MC と同じ投影空間にあることが前提である。</b>
         * 別の near/far で描いた深度をそのまま書くと、値の意味が食い違って
         * 前後関係が壊れる。既存の GL 経路が
         * {@code transformBlitDepth} で<b>深度を再投影してから</b>書き戻しているのは
         * このためである [確認済 — {@code AbstractRenderPipeline}]。
         */
        TEST
    }

    /**
     * <b>わざと壊した合成。対照実験専用で、本番では {@link Defect#NONE} 以外を使ってはならない。</b>
     *
     * <p>「検査が通った」に意味を持たせるには、<b>壊れた実装なら落ちること</b>を
     * 同じ検査で示す必要がある [docs/phase5b-composite.md §5]。
     * そのための欠陥をここに列挙しておく。
     */
    public enum Defect {
        /** 正しい合成。本番はこれ。 */
        NONE,
        /**
         * R と B を入れ替える。<b>BGRA の IOSurface を RGBA として読んだ場合</b>に
         * 起きる壊れ方で、interop で最も踏みやすい間違いである。
         * 絵が灰色ばかりだとこの間違いは<b>見えない</b>ので、検査の感度確認に使う。
         */
        SWAPPED_CHANNELS,
        /**
         * <b>要らない上下反転を入れる。</b>
         *
         * <p>5b では合成側で反転していた (Vulkan 側が Vulkan 規約の Y で描いていたため)。
         * 5c で<b>経路上の Y 反転を 0 個にする</b>と決めたので、いまや反転は誤りである
         * [docs/phase5c-y-orientation.md]。
         *
         * <p>この誤りは読み戻しの比較では<b>捕まりにくい</b> —
         * 比較側でも反転すると打ち消し合うため。C7 が両方向を突き合わせて見張る。
         */
        FLIPPED,
        /**
         * <b>テクスチャを読まず、定数色 (マゼンタ) を出す。</b> 診断専用。
         *
         * <p>「描画が画面に届いているか」と「テクスチャの読みが黒いか」を分ける。
         * マゼンタで塗られれば描画は届いており、原因は<b>テクスチャの読み</b>側にある。
         */
        CONSTANT_COLOUR
    }

    /**
     * <h2>上下の向き — <b>反転しない</b></h2>
     * Voxy の Vulkan 経路は<b>GL 規約の Y をそのまま通す</b>
     * ({@code VkSceneUniform.perspective} に Vulkan の Y 反転を入れていない)。
     * したがって IOSurface の 0 行目は<b>絵の下端</b>で、
     * GL の {@code gl_FragCoord.y} が 0 の位置と一致する。
     * <b>読み替えは要らない。</b>
     *
     * <p>5b の実装は合成側で {@code height-1-y} に読み替えていた。
     * これは Vulkan 側が Vulkan 規約で描いていたからで、
     * <b>反転を要する設計を選んだこと自体が誤りだった</b>
     * [docs/phase5c-y-orientation.md]。
     *
     * <p>⚠ <b>反転の有無は読み戻しの画素比較では捕まりにくい。</b>
     * 合成で反転し、比較でも反転すると<b>打ち消し合って一致してしまう</b>。
     * 5b で実際に踏み、PNG を目で見るまで気付かなかった
     * [docs/phase5b-composite.md §6]。{@link Defect#FLIPPED} と C7 がこれを見張る。
     */
    private static String frag(Defect defect, DepthMode depth) {
        String sample = switch (defect) {
            case SWAPPED_CHANNELS -> "texelFetch(colourTex, c).bgra";
            case CONSTANT_COLOUR -> "vec4(1.0, 0.0, 1.0, 1.0)";   // マゼンタ。診断専用
            default -> "texelFetch(colourTex, c)";
        };
        String coord = defect == Defect.FLIPPED
            ? "ivec2 c = ivec2(int(gl_FragCoord.x), textureSize(colourTex).y - 1 - int(gl_FragCoord.y));"
            : "ivec2 c = ivec2(gl_FragCoord.xy);";
        // ⚠ クリア値を直書きしない。逆Zをやめたら合成も一緒に変わらなければならない [規約 6]
        String discardUndrawn = depth == DepthMode.TEST
            ? "    if (texelFetch(depthTex, c).r == " + glslFloat(VkDepth.CLEAR) + ") discard;\n"
            : "";
        return """
            #version 410 core
            uniform sampler2DRect colourTex;
            uniform sampler2DRect depthTex;
            out vec4 fragColour;
            void main() {
            """
            + "    " + coord + "\n"
            + discardUndrawn
            + "    fragColour = " + sample + ";\n"
            + (depth != DepthMode.NONE ? "    gl_FragDepth = texelFetch(depthTex, c).r;\n" : "")
            + "}\n";
    }

    /** GLSL の float リテラル。{@code 0.0} が {@code 0} になると int になるので必ず小数点を付ける。 */
    private static String glslFloat(float v) {
        String s = Float.toString(v);
        return s.contains(".") || s.contains("e") || s.contains("E") ? s : s + ".0";
    }

    private final int program;
    private final int vao;
    private final int locColour, locDepth;
    private final Defect defect;
    private final DepthMode depthMode;
    private boolean freed;

    /** 5b のオフスクリーン比較用: 色 + 深度を<b>無条件に</b>書く。 */
    public GlInteropCompositor() {
        this(Defect.NONE, DepthMode.OVERWRITE);
    }

    /** <b>ホストの中で使う合成</b> (5c-1d 以降)。深度は比較して勝った画素だけ書く。 */
    public static GlInteropCompositor forHost() {
        return new GlInteropCompositor(Defect.NONE, DepthMode.TEST);
    }

    /**
     * @param writeDepth {@code gl_FragDepth} を書くか。
     *
     * <p><b>false は欠陥ではなく正当な用途がある</b> — 深度をまだ運んでいない段階
     * (Phase 5c-1b の色だけの確認) では、書くと<b>MC の深度バッファを
     * 一様に潰してしまう</b> (合成は {@code GL_ALWAYS} なので必ず上書きになる)。
     *
     * <p>対照実験で「深度が壊れていても色検査は通る」ことを示すのにも使う。
     */
    public GlInteropCompositor(boolean writeDepth) {
        this(Defect.NONE, writeDepth ? DepthMode.OVERWRITE : DepthMode.NONE);
    }

    public GlInteropCompositor(Defect defect) {
        this(defect, DepthMode.OVERWRITE);
    }

    public GlInteropCompositor(DepthMode depthMode) {
        this(Defect.NONE, depthMode);
    }

    public GlInteropCompositor(Defect defect, boolean writeDepth) {
        this(defect, writeDepth ? DepthMode.OVERWRITE : DepthMode.NONE);
    }

    public GlInteropCompositor(Defect defect, DepthMode depthMode) {
        this.defect = defect;
        this.depthMode = depthMode;
        int vs = compile(GL_VERTEX_SHADER, VERT);
        int fs = compile(GL_FRAGMENT_SHADER, frag(defect, depthMode));
        this.program = glCreateProgram();
        glAttachShader(this.program, vs);
        glAttachShader(this.program, fs);
        glLinkProgram(this.program);
        if (glGetProgrami(this.program, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException("compositor link: " + glGetProgramInfoLog(this.program));
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
        this.locColour = glGetUniformLocation(this.program, "colourTex");
        this.locDepth = glGetUniformLocation(this.program, "depthTex");
        this.vao = glGenVertexArrays();
    }

    private static int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("compositor compile: " + glGetShaderInfoLog(s));
        }
        return s;
    }

    public Defect defect() { return this.defect; }

    public DepthMode depthMode() { return this.depthMode; }

    /**
     * 現在束縛されているフレームバッファへ合成する。
     * ビューポートは呼び出し側が設定しておくこと。
     *
     * <p><b>呼ぶ前に GL → Vulkan ではなく Vulkan → GL の同期を済ませておくこと。</b>
     * この関数は同期を行わない。
     *
     * <h2>⚠ 触った GL の状態は<b>素の GL で元に戻す</b></h2>
     * ホスト (Minecraft) は Blaze3D の {@code GlStateManager} で状態を<b>キャッシュ</b>している。
     * キャッシュは
     *
     * <pre>if (v == this.enabled) return;   // 一致していたら GL を呼ばない</pre>
     *
     * という作りなので [確認済 — {@code GlStateManager$BooleanState.setEnabled} のバイトコード]、
     * <b>素の GL で状態を変えたあとに {@code GlStateManager} で戻そうとしても、
     * キャッシュが「既にその値」だと判断して GL を呼ばない</b>。
     * 復帰処理が<b>まさに効いてほしい場面で no-op になる</b>。
     *
     * <p>実際にこれで <b>MC の深度テストが無効のまま残り、
     * mob が地形を貫通して見える</b>状態になった [docs/phase5c1b-completion.md §9]。
     *
     * <p>正しいのは<b>キャッシュに一切触らず、素の GL で元の値に戻す</b>ことである。
     * そうすればホストのキャッシュは<b>最初から正しいまま</b>で、食い違いが生じない。
     */
    public void composite(int colourRectTex, int depthRectTex) {
        if (this.freed) throw new IllegalStateException("compositor was freed");

        // --- 触るものを全て控える ---
        int prevProgram = org.lwjgl.opengl.GL11C.glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = org.lwjgl.opengl.GL11C.glGetInteger(
            org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING);
        int prevActive = org.lwjgl.opengl.GL11C.glGetInteger(
            org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int prevRect0 = org.lwjgl.opengl.GL11C.glGetInteger(TEXTURE_BINDING_RECTANGLE);
        glActiveTexture(GL_TEXTURE1);
        int prevRect1 = org.lwjgl.opengl.GL11C.glGetInteger(TEXTURE_BINDING_RECTANGLE);
        // ⚠ **サンプラオブジェクト**。GL では unit にサンプラが bind されていると
        // テクスチャ側のパラメータより**サンプラ側が優先**される。
        // MC / Sodium はサンプラを多用しており、残ったままだと
        // RECTANGLE テクスチャに非互換な設定 (mipmap フィルタ等) が適用されて
        // **incomplete 扱いになり黒が返る**。
        // 既存の GL 経路も同じ対処をしている [確認済 — VoxyRenderSystem が glBindSampler(i,0)]
        glActiveTexture(GL_TEXTURE0);
        int prevSampler0 = org.lwjgl.opengl.GL11C.glGetInteger(
            org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING);
        glActiveTexture(GL_TEXTURE1);
        int prevSampler1 = org.lwjgl.opengl.GL11C.glGetInteger(
            org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING);
        boolean prevDepthTest = org.lwjgl.opengl.GL11C.glIsEnabled(GL_DEPTH_TEST);
        boolean prevDepthMask = org.lwjgl.opengl.GL11C.glGetBoolean(
            org.lwjgl.opengl.GL11C.GL_DEPTH_WRITEMASK);
        int prevDepthFunc = org.lwjgl.opengl.GL11C.glGetInteger(
            org.lwjgl.opengl.GL11C.GL_DEPTH_FUNC);
        boolean prevBlend = org.lwjgl.opengl.GL11C.glIsEnabled(org.lwjgl.opengl.GL11C.GL_BLEND);
        boolean prevScissor = org.lwjgl.opengl.GL11C.glIsEnabled(
            org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST);
        boolean[] prevColourMask = new boolean[4];
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var buf = stack.malloc(4);
            org.lwjgl.opengl.GL11C.glGetBooleanv(org.lwjgl.opengl.GL11C.GL_COLOR_WRITEMASK, buf);
            for (int i = 0; i < 4; i++) prevColourMask[i] = buf.get(i) != 0;
        }

        // ⚠ 深度テストする合成は、**束縛先に深度アタッチメントが無いと成立しない**。
        // 無い場合、深度テストは常に通り gl_FragDepth の書き先も無いので、
        // 「MC の深度は無傷なのに Voxy が何にも隠されない」という
        // **一見正常に見える壊れ方**になる [5c-1d で実際に踏んだ]
        if (this.depthMode == DepthMode.TEST) checkDepthAttachment();

        // 素通しに必要な状態を作る
        org.lwjgl.opengl.GL33C.glBindSampler(0, 0);
        org.lwjgl.opengl.GL33C.glBindSampler(1, 0);
        if (prevBlend) glDisable(org.lwjgl.opengl.GL11C.GL_BLEND);
        if (prevScissor) glDisable(org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST);

        glUseProgram(this.program);
        glBindVertexArray(this.vao);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(Cgl.GL_TEXTURE_RECTANGLE, colourRectTex);
        glUniform1i(this.locColour, 0);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(Cgl.GL_TEXTURE_RECTANGLE, depthRectTex);
        glUniform1i(this.locDepth, 1);

        // 素通し: ブレンド無し、全チャネル書き込み
        glColorMask(true, true, true, true);
        if (this.depthMode == DepthMode.TEST) {
            // ⚠ **本番の設定**。MC の深度と比べて勝った画素にだけ書く。
            // 描いていない画素はフラグメントシェーダが discard する
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(VkDepth.GL_CLOSER_EQUAL_COMPARE);
            glDepthMask(true);
        } else if (this.depthMode == DepthMode.OVERWRITE) {
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_ALWAYS);
            glDepthMask(true);
        } else {
            // ⚠ マスクも切ること。GL_ALWAYS のまま深度書き込みを許すと
            // gl_FragDepth を書かなくても**ラスタライザ由来の深度**が入り、
            // MC の深度バッファを潰す
            glDisable(GL_DEPTH_TEST);
            glDepthMask(false);
        }

        glDrawArrays(GL_TRIANGLES, 0, 3);

        // --- 控えたものを素の GL で戻す (キャッシュには触らない) ---
        org.lwjgl.opengl.GL33C.glBindSampler(0, prevSampler0);
        org.lwjgl.opengl.GL33C.glBindSampler(1, prevSampler1);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(Cgl.GL_TEXTURE_RECTANGLE, prevRect1);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(Cgl.GL_TEXTURE_RECTANGLE, prevRect0);
        glActiveTexture(prevActive);

        glBindVertexArray(prevVao);
        glUseProgram(prevProgram);

        if (prevDepthTest) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        glDepthMask(prevDepthMask);
        glDepthFunc(prevDepthFunc);
        glColorMask(prevColourMask[0], prevColourMask[1], prevColourMask[2], prevColourMask[3]);
        if (prevBlend) glEnable(org.lwjgl.opengl.GL11C.GL_BLEND);
        if (prevScissor) glEnable(org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST);
    }

    /** {@code GL_TEXTURE_BINDING_RECTANGLE}。LWJGL の GL31C にあるが定数だけ使う。 */
    private static final int TEXTURE_BINDING_RECTANGLE = 0x84F6;

    /** 深度アタッチメント無しで {@link DepthMode#TEST} を使った回数。<b>検査が読む。</b> */
    private static int missingDepthAttachments;

    public static int missingDepthAttachmentCount() { return missingDepthAttachments; }

    public static void resetMissingDepthAttachmentCount() { missingDepthAttachments = 0; }

    /**
     * 束縛中の描画先に深度アタッチメントがあるか確かめる。
     *
     * <p><b>無ければ数え、最初の 1 回だけ大きく報告する。</b>
     * 例外にしないのは、ホストの描画ループを落とすより
     * <b>誤った絵と一緒に理由がログに残るほうが調査しやすい</b>ため。
     */
    private static void checkDepthAttachment() {
        int fbo = org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (fbo == 0) return;   // 既定フレームバッファは別の問い合わせが要る
        int type = org.lwjgl.opengl.GL30C.glGetFramebufferAttachmentParameteri(
            org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER,
            org.lwjgl.opengl.GL30C.GL_DEPTH_ATTACHMENT,
            org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (type != org.lwjgl.opengl.GL11C.GL_NONE) return;
        if (missingDepthAttachments++ == 0) {
            me.cortex.voxy.common.Logger.error(
                "the depth-tested composite is drawing into a framebuffer with NO depth"
                + " attachment. The depth test cannot work: Voxy will not be occluded by"
                + " Minecraft's terrain, and gl_FragDepth goes nowhere. Attach the host's"
                + " depth texture (GlScratchFramebuffer.bindWithColourAndDepth)");
        }
    }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        glDeleteVertexArrays(this.vao);
        glDeleteProgram(this.program);
    }
}
