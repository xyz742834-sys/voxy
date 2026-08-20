package me.cortex.voxy.client.core.vk.interop;

import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_COLOR_WRITEMASK;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_WRITEMASK;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NONE;
import static org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.GL_TRUE;
import static org.lwjgl.opengl.GL11C.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glColorMask;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glGetBoolean;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.glIsEnabled;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL14C.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;
import static org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL33C.glDeleteSamplers;
import static org.lwjgl.opengl.GL33C.glGenSamplers;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;

/**
 * <b>Minecraft の深度を interop の R32F 画像へ写す GL のパス</b> (Phase 5c-1c)。
 *
 * <pre>
 * MC の深度 (D32F, GL_TEXTURE_2D) ─→ この GL パス ─→ IOSurface(R32F) ─→ Vulkan が読む
 * </pre>
 *
 * <h2>なぜコピーで済まないか</h2>
 * <ul>
 *   <li>IOSurface に depth aspect の面は作れない。使えるのは {@code 'BGRA'} と {@code 'r00f'}
 *       だけ [確認済 — Phase 0 §8.2]。つまり深度は<b>色として運ぶ</b>しかない</li>
 *   <li>interop の GL 側は {@code CGLTexImageIOSurface2D} の制約で
 *       <b>{@code GL_TEXTURE_RECTANGLE}</b> である。深度 (2D) からの
 *       {@code glCopyTexSubImage} はターゲットもフォーマット種別も跨げない</li>
 * </ul>
 *
 * <p>これは {@link me.cortex.voxy.client.core.vk.VkDepthResolve} の<b>逆向き</b>であり、
 * GL 側 {@code initDepthStencil} が d32 → d24s8 でやっていた
 * 「フォーマット不一致のためのフルスクリーンコピー」と同じ形である。
 *
 * <h2>⚠ 自前のサンプラを bind すること</h2>
 * GL ではテクスチャユニットにサンプラオブジェクトが bind されていると
 * <b>テクスチャ側のパラメータよりサンプラ側が優先</b>される。
 * MC / Sodium はサンプラを多用しているので、残ったままだと
 *
 * <ul>
 *   <li>mipmap フィルタが効いて <b>incomplete 扱い</b>になる (5c-1b で黒画面を踏んだ経路。
 *       [docs/phase5c1b-completion.md §12])</li>
 *   <li>{@code GL_TEXTURE_COMPARE_MODE} が残っていると <b>深度値ではなく比較結果</b>
 *       (0 か 1) が返る</li>
 * </ul>
 *
 * <p>どちらも「黒い / 二値の深度」として現れ、<b>絵を見ても取り違えやすい</b>。
 * したがって自前のサンプラ (NEAREST・CLAMP・compare NONE) を bind し、終わったら戻す。
 * 既存の GL 経路も同じ対処をしている
 * [確認済 — {@code AbstractRenderPipeline.DEPTH_SAMPLER}]。
 *
 * <h2>状態の復帰は<b>素の GL</b> で行う</h2>
 * {@code GlStateManager} のキャッシュに触ると、復帰が
 * <b>まさに効いてほしい場面で no-op になる</b>
 * [docs/phase5c1b-completion.md §9]。FBO だけは行きも帰りも
 * {@link GlScratchFramebuffer} 経由 (= 同じ API) なので整合が保たれる。
 *
 * <h2>GL 4.1 の範囲で書くこと</h2>
 * DSA ({@code glCreate*} / {@code glNamed*}) は全て GL 4.5 で、Apple の GL には無く
 * 呼ぶと<b>JVM ごと abort する</b> [docs/phase5c1a-completion.md §7]。
 * ここで使うのは {@code glGenSamplers} (3.3) までである。
 */
public final class GlDepthImport {
    private static final String VERT = """
        #version 410 core
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    /**
     * <b>わざと壊した取り込み。対照実験専用で、本番では {@link Defect#NONE} 以外を使ってはならない。</b>
     */
    public enum Defect {
        /** 正しい取り込み。本番はこれ。 */
        NONE,
        /**
         * <b>要らない上下反転を入れる。</b>
         *
         * <p>Voxy は経路上の Y 反転を <b>0 個</b>にすると決めている
         * [docs/phase5c-y-orientation.md]。深度経路も同じで、反転は誤りである。
         *
         * <p>この欠陥は<b>色経路とは独立</b>なので、
         * 色側の反転で打ち消されることがない — それが 5c-1c で
         * 深度経路を別に通す理由そのものである [docs/phase5c-plan.md §9]。
         */
        FLIPPED,
        /**
         * <b>自前のサンプラを bind しない。</b>ホストが残したサンプラをそのまま使う。
         *
         * <p>5c-1b で<b>実際に踏んだ黒画面</b>の再現である
         * [docs/phase5c1b-completion.md §12]。当時これをオフスクリーンで再現できなかったのは
         * <b>ベンチにサンプラオブジェクトが存在しなかった</b>からで、
         * 「対照環境に存在しない要素は対照では検出できない」という形で規約 7 になった。
         *
         * <p>この欠陥は<b>検査側がホストの状態を模したときにだけ</b>落ちる。
         * つまり<b>規約 7 を守れているかどうかを測る対照</b>として働く。
         */
        NO_SAMPLER_RESET
    }

    /**
     * 深度を素直に色 (R) へ写すだけ。<b>値の変換は一切しない。</b>
     *
     * <p>ここで値を触ると、Vulkan 側が見る深度が MC の深度と
     * <b>ビット単位で同じである</b>という主張ができなくなる。
     * 変換 (逆Zの読み替え・線形化・可視化) は全て Vulkan 側で行う。
     */
    private static String frag(Defect defect) {
        String coord = defect == Defect.FLIPPED
            ? "ivec2 c = ivec2(int(gl_FragCoord.x), textureSize(srcDepth, 0).y - 1 - int(gl_FragCoord.y));"
            : "ivec2 c = ivec2(gl_FragCoord.xy);";
        return """
            #version 410 core
            uniform sampler2D srcDepth;
            out vec4 fragColour;
            void main() {
            """
            + "    " + coord + "\n"
            + "    fragColour = vec4(texelFetch(srcDepth, c, 0).r, 0.0, 0.0, 1.0);\n"
            + "}\n";
    }

    private final int program;
    private final int vao;
    private final int sampler;
    private final int locSrc;
    private final Defect defect;
    private final GlScratchFramebuffer fbo = new GlScratchFramebuffer();
    private boolean freed;

    public GlDepthImport() {
        this(Defect.NONE);
    }

    public GlDepthImport(Defect defect) {
        this.defect = defect;
        int vs = compile(GL_VERTEX_SHADER, VERT);
        int fs = compile(GL_FRAGMENT_SHADER, frag(defect));
        this.program = glCreateProgram();
        glAttachShader(this.program, vs);
        glAttachShader(this.program, fs);
        glLinkProgram(this.program);
        if (glGetProgrami(this.program, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException("depth import link: " + glGetProgramInfoLog(this.program));
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
        this.locSrc = glGetUniformLocation(this.program, "srcDepth");
        this.vao = glGenVertexArrays();

        // ⚠ サンプラ側がテクスチャ側のパラメータより優先される。
        // MC の深度テクスチャに何が設定されていても、ここで上書きできる
        this.sampler = glGenSamplers();
        glSamplerParameteri(this.sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(this.sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        // 深度テクスチャを sampler2D で読むので、比較モードは必ず切る。
        // 残っていると深度値ではなく比較結果 (0 か 1) が返る
        glSamplerParameteri(this.sampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);
    }

    private static int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("depth import compile: " + glGetShaderInfoLog(s));
        }
        return s;
    }

    public Defect defect() { return this.defect; }

    /**
     * MC の深度テクスチャを {@code dst} (interop の R32F) へ写す。
     *
     * <p><b>描き先・ビューポート・描画状態を全てこの関数が作り、全て戻す。</b>
     * 呼び出し側の GL 状態は Sodium と Blaze3D の都合で決まっており、
     * そのまま使うと「既定フレームバッファに 16x16 で描く」ことになる
     * [docs/phase5c1b-completion.md §8]。
     *
     * <p>⚠ <b>この関数は GL → Vulkan の同期を行わない。</b>
     * 呼び出し後、Vulkan が {@code dst} を読む前に同期すること
     * [docs/phase5a-gl-to-vk-sync.md]。
     *
     * @param mcDepthTexture MC の深度テクスチャ ({@code GL_TEXTURE_2D}, D32F)
     * @param dst 書き先。{@link VkInteropImage.Kind#DEPTH_R32F} であること
     */
    public void record(int mcDepthTexture, VkInteropImage dst) {
        if (this.freed) throw new IllegalStateException("depth import was freed");
        if (dst.kind != VkInteropImage.Kind.DEPTH_R32F) {
            throw new IllegalArgumentException("depth import writes R32F, not " + dst.kind);
        }

        // --- 触るものを全て控える ---
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevActive = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int prevTex2d = glGetInteger(GL_TEXTURE_BINDING_2D);
        int prevSampler = glGetInteger(GL_SAMPLER_BINDING);
        boolean prevDepthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean prevDepthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean prevBlend = glIsEnabled(GL_BLEND);
        boolean prevScissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean[] prevColourMask = new boolean[4];
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var buf = stack.malloc(4);
            org.lwjgl.opengl.GL11C.glGetBooleanv(GL_COLOR_WRITEMASK, buf);
            for (int i = 0; i < 4; i++) prevColourMask[i] = buf.get(i) != 0;
        }
        int[] prevViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, prevViewport);

        // interop の GL 側は RECTANGLE でしか作れないので、そのターゲットで付け替える
        int prevFbo = this.fbo.bindWithColour(Cgl.GL_TEXTURE_RECTANGLE, dst.glTexture());
        glViewport(0, 0, dst.width, dst.height);

        // 素通しに必要な状態を作る
        if (prevBlend) glDisable(GL_BLEND);
        if (prevScissor) glDisable(GL_SCISSOR_TEST);
        // 深度アタッチメントを持たない FBO だが、テストが有効なままだと
        // 実装によっては何も描かれない。書き込みも切っておく
        if (prevDepthTest) glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glColorMask(true, true, true, true);

        glUseProgram(this.program);
        glBindVertexArray(this.vao);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, mcDepthTexture);
        if (this.defect != Defect.NO_SAMPLER_RESET) glBindSampler(0, this.sampler);
        glUniform1i(this.locSrc, 0);

        glDrawArrays(GL_TRIANGLES, 0, 3);

        // --- 控えたものを素の GL で戻す (キャッシュには触らない) ---
        glBindSampler(0, prevSampler);
        glBindTexture(GL_TEXTURE_2D, prevTex2d);
        glActiveTexture(prevActive);
        glBindVertexArray(prevVao);
        glUseProgram(prevProgram);

        if (prevDepthTest) glEnable(GL_DEPTH_TEST);
        glDepthMask(prevDepthMask);
        glColorMask(prevColourMask[0], prevColourMask[1], prevColourMask[2], prevColourMask[3]);
        if (prevBlend) glEnable(GL_BLEND);
        if (prevScissor) glEnable(GL_SCISSOR_TEST);

        glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        this.fbo.restore(prevFbo);
    }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        this.fbo.free();
        glDeleteSamplers(this.sampler);
        glDeleteVertexArrays(this.vao);
        glDeleteProgram(this.program);
    }
}
