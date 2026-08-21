package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Phase 5c-4c — <b>GPU 側の区間ごとの時間</b>を測る。
 *
 * <h2>なぜ内訳が要るのか</h2>
 * 合計だけ見ても<b>何を疑えばよいか決まらない</b>。
 * この段階の Voxy はまだ最適化していない — 保守的なバリアが残り、
 * 密テーブルが 1.1MB あり、旧経路も残っている。<b>遅くて正常</b>である。
 *
 * <p>合計を見て「遅い」と判断して最適化に飛ぶと、
 * <b>測ってから絞る</b>という順序が崩れる。内訳が出て初めて何を疑うかが決まる。
 *
 * <h2>⚠ 使えないことがある</h2>
 * キューの {@code timestampValidBits} が 0 なら<b>タイムスタンプを取れない</b>。
 * その場合 {@link #supported()} が偽になり、{@link #readMillis()} は
 * <b>数字を返さない</b> ({@code null})。
 *
 * <p><b>「対応していない」と「0 ms だった」は別物である</b> [規約 18]。
 * 混ぜると「速いから 0」と読めてしまう。
 */
public final class VkGpuTimer {
    private final long pool;
    private final String[] labels;
    private final boolean supported;
    private final float periodNanos;
    private boolean freed;

    /**
     * @param labels 区間の名前。タイムスタンプは {@code labels.length + 1} 個取る
     */
    public VkGpuTimer(String... labels) {
        if (labels.length == 0) throw new IllegalArgumentException("need at least one span");
        this.labels = labels.clone();
        var ctx = VkContext.get();
        this.supported = ctx.timestampValidBits > 0;
        this.periodNanos = ctx.timestampPeriod;
        if (!this.supported) {
            this.pool = VK_NULL_HANDLE;
            return;
        }
        try (MemoryStack stack = stackPush()) {
            var ci = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                .queryType(VK_QUERY_TYPE_TIMESTAMP)
                .queryCount(labels.length + 1);
            long[] p = new long[1];
            VkContext.check(vkCreateQueryPool(ctx.device, ci, null, p), "vkCreateQueryPool");
            this.pool = p[0];
        }
    }

    /** このキューでタイムスタンプが取れるか。 */
    public boolean supported() { return this.supported; }

    /**
     * ⚠ <b>記録の最初に呼ぶこと。</b> 前のフレームの結果を捨てる。
     * リセットせずに書くと<b>未定義の値</b>が返る。
     */
    public void reset(VkCommandBuffer cmd) {
        if (!this.supported) return;
        vkCmdResetQueryPool(cmd, this.pool, 0, this.labels.length + 1);
    }

    /**
     * {@code index} 番目のタイムスタンプを積む。0 が全体の開始。
     *
     * <p>⚠ {@code BOTTOM_OF_PIPE} で取る = <b>そこまでの仕事が終わった時刻</b>。
     * {@code TOP_OF_PIPE} だと「そこに到達した時刻」になり、
     * <b>まだ終わっていない仕事の時間が次の区間に混ざる</b>。
     */
    public void mark(VkCommandBuffer cmd, int index) {
        if (!this.supported) return;
        if (index < 0 || index > this.labels.length) {
            throw new IndexOutOfBoundsException("timestamp " + index
                + " of " + (this.labels.length + 1));
        }
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, this.pool, index);
    }

    /**
     * 区間ごとのミリ秒。<b>フレームの完了を待ってから呼ぶこと。</b>
     *
     * @return 長さ {@code labels.length}。<b>取れないときは {@code null}</b>
     */
    public double[] readMillis() {
        if (!this.supported) return null;
        int n = this.labels.length + 1;
        try (MemoryStack stack = stackPush()) {
            var buf = stack.mallocLong(n);
            int r = vkGetQueryPoolResults(VkContext.get().device, this.pool, 0, n, buf, 8,
                VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
            if (r != VK_SUCCESS) return null;   // ⚠ 取れなかったら数字を作らない
            var out = new double[this.labels.length];
            for (int i = 0; i < out.length; i++) {
                long ticks = buf.get(i + 1) - buf.get(i);
                out[i] = ticks * this.periodNanos / 1_000_000.0;
            }
            return out;
        }
    }

    /** 内訳を 1 行にまとめる。取れないときはそう言う。 */
    public String describe() {
        double[] ms = this.readMillis();
        if (ms == null) {
            return "GPU timings unavailable on this queue (timestampValidBits="
                + VkContext.get().timestampValidBits + ") — no numbers rather than wrong ones";
        }
        var sb = new StringBuilder();
        double total = 0;
        for (int i = 0; i < ms.length; i++) {
            sb.append(this.labels[i]).append('=').append(String.format("%.3f", ms[i])).append("ms  ");
            total += ms[i];
        }
        return sb.append("total=").append(String.format("%.3f", total)).append("ms").toString();
    }

    public String[] labels() { return this.labels.clone(); }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        if (this.pool != VK_NULL_HANDLE) {
            vkDestroyQueryPool(VkContext.get().device, this.pool, null);
        }
    }
}
