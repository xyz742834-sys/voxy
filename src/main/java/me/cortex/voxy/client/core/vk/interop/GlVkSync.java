package me.cortex.voxy.client.core.vk.interop;

import static org.lwjgl.opengl.GL32C.GL_ALREADY_SIGNALED;
import static org.lwjgl.opengl.GL32C.GL_CONDITION_SATISFIED;
import static org.lwjgl.opengl.GL32C.GL_SYNC_FLUSH_COMMANDS_BIT;
import static org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static org.lwjgl.opengl.GL32C.GL_TIMEOUT_EXPIRED;
import static org.lwjgl.opengl.GL32C.GL_WAIT_FAILED;
import static org.lwjgl.opengl.GL32C.glClientWaitSync;
import static org.lwjgl.opengl.GL32C.glDeleteSync;
import static org.lwjgl.opengl.GL32C.glFenceSync;

/**
 * <b>GL が書いたものを Vulkan が読む前に置く同期。</b>
 *
 * <h2>なぜ CPU 側で待つのか</h2>
 * GL の書き込み順序は<b>Vulkan のバリアでは表現できない</b>。
 * 2 つの API は別々のコマンドストリームを持ち、共有しているのは
 * IOSurface の<b>メモリだけ</b>だからである。したがって
 * 「GL の描画が終わったこと」は CPU 側で確かめるしかない
 * [docs/phase5a-gl-to-vk-sync.md §4]。
 *
 * <h2>コスト — 実測 0.3 ms 未満</h2>
 * Phase 5a が 1920x1080 で測った。同期を入れて増えるのは <b>0.3 ms 未満</b>で、
 * 実行間のばらつきと同程度である。理由は
 * <b>同期を呼ばなくても待ちは発生しており、待つ場所が CPU 側に移るだけ</b>だから。
 *
 * <p>⚠ <b>「同期無しでも値が合った」ことは同期が不要である証拠にならない。</b>
 * Phase 5a は約 800 回試して<b>陳腐な読みを 1 度も構成できなかった</b>が、
 * それは「起きない」ことの証明ではない [未検証 — docs/phase5a-gl-to-vk-sync.md §4 の C3]。
 * コスト差が測定ノイズ以下なら、<b>正しさが確認できているほうを選ぶ</b>
 * [判断済 — docs/phase5-status.md §3 の 5]。
 *
 * <h2>なぜ {@code glFinish} ではなくフェンスか</h2>
 * コストはほぼ同じ (5a の表では差が 0.03 ms 程度) だが、
 * フェンスは<b>待つ対象がこのフレームの GL 作業に限定される</b>。
 * {@code glFinish} は GL のパイプライン全体を空にするので、
 * <b>Voxy と無関係な MC の作業まで巻き込む</b>。
 */
public final class GlVkSync {
    private GlVkSync() {}

    /** 1 回の待ちで許す時間 (ns)。これを超えたら再試行する。 */
    private static final long SLICE_NS = 10_000_000L;
    /** 再試行の上限。合計 10 秒でハングとみなす。 */
    private static final int MAX_ATTEMPTS = 1000;

    /**
     * ここまでに発行した GL のコマンドが<b>GPU 上で完了する</b>まで待つ。
     *
     * <p>{@code GL_SYNC_FLUSH_COMMANDS_BIT} が要る — これが無いと、
     * まだフラッシュされていないコマンドを待ち続けて<b>永久に返らない</b>ことがある。
     *
     * <p>GL コンテキストが current なスレッドから呼ぶこと。
     */
    public static void waitForGl() {
        long fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (fence == 0L) {
            throw new IllegalStateException("glFenceSync returned 0 (no GL context?)");
        }
        try {
            int r = GL_TIMEOUT_EXPIRED;
            for (int attempt = 0; attempt < MAX_ATTEMPTS && r == GL_TIMEOUT_EXPIRED; attempt++) {
                r = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, SLICE_NS);
            }
            if (r != GL_ALREADY_SIGNALED && r != GL_CONDITION_SATISFIED) {
                throw new IllegalStateException("glClientWaitSync -> 0x"
                    + Integer.toHexString(r) + (r == GL_WAIT_FAILED ? " (WAIT_FAILED)" : " (timed out)"));
            }
        } finally {
            glDeleteSync(fence);
        }
    }
}
