package me.cortex.voxy.client.core.vk.interop;

import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL30C.*;

/**
 * 合成先として <b>MC のカラーテクスチャを付け替えるだけの FBO</b>。
 *
 * <h2>なぜ要るのか — 何もしないと既定フレームバッファに描いてしまう</h2>
 * Sodium の地形パスの中で {@code GL_DRAW_FRAMEBUFFER_BINDING} を読むと <b>0</b> だった
 * [確認済 — 実機ログ]。つまりその時点で束縛されているのは<b>既定フレームバッファ</b>で、
 * MC がワールドを描いている先ではない。そこへ描いても
 * <b>MC が後からメインターゲットを画面に転送する際に上書きされて消える</b>。
 *
 * <p>既存の GL 経路も同じ問題を<b>同じ方法</b>で解いている —
 * {@code AbstractRenderPipeline.runPipeline} が自前の FBO に
 * MC の色/深度テクスチャを付け替えてから合成している [確認済]。
 *
 * <h2>⚠ {@code GlFramebuffer} は使えない</h2>
 * Voxy の {@code GlFramebuffer} は {@code glCreateFramebuffers} /
 * {@code glNamedFramebuffer*} = <b>GL 4.5 DSA</b> で書かれている。
 * Apple の GL 4.1 には無く、呼ぶと<b>JVM ごと abort する</b>
 * [docs/phase5c1a-completion.md §7 の監査]。
 * ここは {@code glGenFramebuffers} (3.0) と {@code glFramebufferTexture2D} (3.0) だけで書く。
 */
public final class GlScratchFramebuffer {
    private int fbo;
    private int attachedColour = -1;
    private int attachedTarget = -1;
    private int attachedDepth = -1;
    private boolean freed;

    /**
     * 指定のカラーテクスチャを付けて束縛する。
     *
     * @return 束縛前の {@code GL_DRAW_FRAMEBUFFER_BINDING}。{@link #restore} に渡すこと
     */
    public int bindWithColour(int colourTexture) {
        return this.bindWithColour(GL_TEXTURE_2D, colourTexture);
    }

    /**
     * 付け替えるテクスチャの<b>ターゲットを明示する</b>版。
     *
     * <p>interop 画像は {@code CGLTexImageIOSurface2D} の制約で
     * <b>{@code GL_TEXTURE_RECTANGLE} でしか作れない</b>ので、
     * それを描き先にする 5c-1c の深度取り込みパスではこちらが要る
     * [{@link GlDepthImport}]。{@code glFramebufferTexture2D} は
     * RECTANGLE を textarget として受け付ける (GL 3.1 以降)。
     *
     * @param target {@code GL_TEXTURE_2D} または {@code GL_TEXTURE_RECTANGLE}
     */
    public int bindWithColour(int target, int colourTexture) {
        return this.bind(target, colourTexture, 0);
    }

    /**
     * カラーに加えて<b>深度テクスチャも付ける</b>。
     *
     * <h2>⚠ 深度を付けないと深度テストは<b>成立しない</b></h2>
     * 深度アタッチメントの無い FBO では、深度テストは<b>常に通り</b>、
     * {@code gl_FragDepth} の書き込み先も無い。つまり
     *
     * <ul>
     *   <li>Voxy が MC の近景に<b>隠されなくなる</b> (テストが効かない)</li>
     *   <li>MC の深度バッファは<b>無傷のまま</b> (書き込み先が無い)</li>
     * </ul>
     *
     * <p>この 2 つは<b>同時に</b>起きるので、「深度を壊していないから正しい」と
     * 誤読しやすい。5c-1d で実際にこの状態になった。
     *
     * <p>既存の GL 経路も<b>同じように深度を付け替えている</b>
     * [確認済 — {@code AbstractRenderPipeline.runPipeline} が
     * {@code bind(GL_DEPTH_ATTACHMENT, sourceDepthTexture)}]。
     *
     * @param depthTexture MC の深度テクスチャ ({@code GL_TEXTURE_2D})。0 なら付けない
     */
    public int bindWithColourAndDepth(int target, int colourTexture, int depthTexture) {
        return this.bind(target, colourTexture, depthTexture);
    }

    private int bind(int target, int colourTexture, int depthTexture) {
        if (this.freed) throw new IllegalStateException("scratch framebuffer was freed");
        int previous = org.lwjgl.opengl.GL11C.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);

        if (this.fbo == 0) this.fbo = glGenFramebuffers();
        bindFbo(this.fbo);

        boolean changed = this.attachedColour != colourTexture || this.attachedTarget != target
            || this.attachedDepth != depthTexture;
        if (changed) {
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                target, colourTexture, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                GL_TEXTURE_2D, depthTexture, 0);
            this.attachedColour = colourTexture;
            this.attachedTarget = target;
            this.attachedDepth = depthTexture;
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                bindFbo(previous);
                throw new IllegalStateException("composite FBO incomplete: 0x"
                    + Integer.toHexString(status));
            }
        }
        return previous;
    }

    public void restore(int previous) {
        bindFbo(previous);
    }

    /** Blaze3D のキャッシュにも伝える。素の GL だけで変えるとキャッシュがずれる。 */
    private static void bindFbo(int fbo) {
        com.mojang.blaze3d.opengl.GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        if (this.fbo != 0) { glDeleteFramebuffers(this.fbo); this.fbo = 0; }
    }
}
