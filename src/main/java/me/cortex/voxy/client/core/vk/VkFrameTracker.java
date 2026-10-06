package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.common.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static me.cortex.voxy.client.core.vk.VkContext.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * フレーム境界とサブミッション世代の管理。<b>in-flight = 1</b>。
 *
 * <h2>なぜ in-flight = 1 なのか</h2>
 * Voxy は自前でプレゼンテーションを行わない (スワップチェーンが無い)。
 * 描画結果は IOSurface 経由で GL に渡し、画面に出すのは Minecraft 側である。
 * 合成は GL 側で行うため、<b>Vulkan の描画完了を待たないと GL が正しい内容を読めない</b> —
 * つまり待つことが構造的に必要で、多重化しても待ち自体は消えない。
 *
 * <p>`vkQueueWaitIdle` 相当の同期コストは実測 <b>0.315ms</b> (1920x1080、予算 16.6ms の 1.9%)
 * [実測済 — Phase 0]。多重化の動機となる数字は現時点で存在しないため、
 * 最も単純な構成から始める。フレーム時間が予算を超えることが実測で判明した場合にのみ
 * 多重化を検討する。
 *
 * <h2>これにより単純化されること</h2>
 * <ul>
 *   <li><b>リソースの遅延解放が不要</b>。フレーム境界では GPU が必ずアイドルなので、
 *       そこで解放してよいという単純な規則で足りる。世代付きキューは要らない
 *       (docs/phase2-binding-audit.md §7.2)。</li>
 *   <li>descriptor set の多重化が不要 (1 set で足りる)。</li>
 * </ul>
 *
 * <h2>残してある検出機構</h2>
 * {@link #inFlight(long)} による in-flight 更新検出は
 * <b>将来多重化したときの防波堤として残す</b>。in-flight = 1 では
 * 「フレーム記録中に descriptor を書き換えた」場合にのみ発火する。
 */
public final class VkFrameTracker {
    private static VkFrameTracker INSTANCE;

    /** 記録中の世代。{@link #beginFrame()} で進む。 */
    private long current = 1;
    /** 完了が確認できている最後の世代。 */
    private long completed = 0;

    private final VkCommandBuffer commandBuffer;
    private final long fence;

    /** 現フレーム中に解放要求されたリソース。フレーム終了 (= GPU アイドル) 後に実行する。 */
    private final Deque<Runnable> pendingFree = new ArrayDeque<>();

    /**
     * フェンス待ちの直後 (= GPU アイドル、記録開始前) に走るフック。
     * ダウンロード結果の配送とアップロードリングのリセットに使う。
     */
    private final List<Runnable> frameBeginHooks = new ArrayList<>();

    /** 登録順に実行される。{@link #beginFrame()} のフェンス待ち直後、記録開始前。 */
    public void addFrameBeginHook(Runnable hook) {
        this.frameBeginHooks.add(hook);
    }

    private boolean recording;

    private VkFrameTracker() {
        var ctx = VkContext.get();
        try (MemoryStack stack = stackPush()) {
            var ai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(ctx.commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1);
            PointerBuffer pp = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(ctx.device, ai, pp), "vkAllocateCommandBuffers");
            this.commandBuffer = new VkCommandBuffer(pp.get(0), ctx.device);

            // 最初の beginFrame で待てるよう signaled 状態で作る
            var fci = VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT);
            long[] p = new long[1];
            check(vkCreateFence(ctx.device, fci, null, p), "vkCreateFence");
            this.fence = p[0];
        }
    }

    public static void init() {
        if (INSTANCE == null) INSTANCE = new VkFrameTracker();
    }

    public static VkFrameTracker get() {
        if (INSTANCE == null) throw new IllegalStateException("VkFrameTracker not initialised");
        return INSTANCE;
    }

    public static void shutdown() {
        if (INSTANCE == null) return;
        INSTANCE.destroy();
        INSTANCE = null;
    }

    // ---------------- frame lifecycle ----------------

    /**
     * 前フレームの完了を待ち、コマンドバッファの記録を開始する。
     * in-flight = 1 なので、この時点で GPU は必ずアイドルである。
     */
    public VkCommandBuffer beginFrame() {
        if (this.recording) throw new IllegalStateException("beginFrame() called twice without endFrame()");
        var ctx = VkContext.get();

        // 前フレームの完了待ち
        check(vkWaitForFences(ctx.device, this.fence, true, Long.MAX_VALUE), "vkWaitForFences");
        this.completed = this.current;

        // ここで GPU はアイドル。前フレームの結果配送と解放を行う
        for (Runnable hook : this.frameBeginHooks) {
            try {
                hook.run();
            } catch (Throwable t) {
                Logger.error("frame-begin hook failed", t);
            }
        }
        this.drainPendingFree();

        check(vkResetFences(ctx.device, this.fence), "vkResetFences");
        this.current++;

        check(vkResetCommandBuffer(this.commandBuffer, 0), "vkResetCommandBuffer");
        try (MemoryStack stack = stackPush()) {
            var bi = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(this.commandBuffer, bi), "vkBeginCommandBuffer");
        }
        this.recording = true;
        return this.commandBuffer;
    }

    /** 記録を終了しサブミットする。完了待ちは次の {@link #beginFrame()} で行う。 */
    public void endFrame() {
        if (!this.recording) throw new IllegalStateException("endFrame() without beginFrame()");
        var ctx = VkContext.get();
        check(vkEndCommandBuffer(this.commandBuffer), "vkEndCommandBuffer");
        try (MemoryStack stack = stackPush()) {
            var si = VkSubmitInfo.calloc(stack).sType$Default()
                .pCommandBuffers(stack.pointers(this.commandBuffer));
            check(vkQueueSubmit(ctx.queue, si, this.fence), "vkQueueSubmit");
        }
        this.recording = false;
    }

    /**
     * GL 側が結果を読む直前に呼ぶ。in-flight = 1 の要。
     * interop の合成は GL が行うため、ここで完了を保証しないと GL が古い内容を読む。
     */
    public void waitForFrame() {
        var ctx = VkContext.get();
        check(vkWaitForFences(ctx.device, this.fence, true, Long.MAX_VALUE), "vkWaitForFences");
        this.completed = this.current;
    }

    public VkCommandBuffer commandBuffer() {
        if (!this.recording) throw new IllegalStateException("not recording; call beginFrame() first");
        return this.commandBuffer;
    }

    public boolean isRecording() { return this.recording; }

    // ---------------- resource lifetime ----------------

    /**
     * フレーム境界まで解放を遅らせる。
     *
     * <p>in-flight = 1 なので「次の {@link #beginFrame()}」まで待てば GPU は必ずアイドル。
     * 世代管理は不要で、単純な FIFO で足りる。
     *
     * <p>用途: `rendering/bounding/` のように<b>フレーム途中でバッファを再確保する</b>経路。
     * GL ではドライバが遅延解放していたが Vulkan では使用中の破棄が未定義動作になる。
     */
    public void freeAtFrameEnd(Runnable action) {
        this.pendingFree.add(action);
    }

    private void drainPendingFree() {
        while (!this.pendingFree.isEmpty()) {
            Runnable r = this.pendingFree.poll();
            try {
                r.run();
            } catch (Throwable t) {
                Logger.error("deferred free failed", t);
            }
        }
    }

    // ---------------- submission generations ----------------

    public long current() { return this.current; }
    public long completed() { return this.completed; }

    /** 指定世代がまだ GPU 上で実行中の可能性があるか。 */
    public boolean inFlight(long generation) { return generation > this.completed; }

    /** GPU をアイドルにして全世代を完了扱いにする (シャットダウンやテスト用)。 */
    /**
     * ⚠ 戻り値を捨ててはいけない。採用モードでは device は Minecraft のもので、
     * 待てていないのに破棄へ進むと「実行中のコマンドが参照している」状態を作る
     * (round-2 review B5)。
     */
    public boolean waitIdleChecked() {
        return vkDeviceWaitIdle(VkContext.get().device) == org.lwjgl.vulkan.VK10.VK_SUCCESS;
    }

    public void waitIdle() {
        // 旧来の呼び出し元のために残す。結果を見る必要がある側は waitIdleChecked を使う。
        vkDeviceWaitIdle(VkContext.get().device);
        this.completed = this.current;
        this.drainPendingFree();
    }

    private void destroy() {
        // ⚠ round-2 review B5: 待機の結果を捨てて破棄へ進んでいた。採用モードでは device は
        // Minecraft のものなので、待てていないまま解放すると実行中参照になる。
        // 待てなければ<b>壊さずに漏らす</b> — フェンス 1 個とコマンドバッファ 1 本の漏れは、
        // 使用中のオブジェクトを解放するより遥かに軽い。
        if (!this.waitIdleChecked()) {
            me.cortex.voxy.common.Logger.warn("[vk] vkDeviceWaitIdle did not succeed;"
                + " leaking the frame tracker's fence and command buffer on purpose rather"
                + " than freeing objects that may still be in use");
            return;
        }
        var ctx = VkContext.get();
        vkDestroyFence(ctx.device, this.fence, null);
        vkFreeCommandBuffers(ctx.device, ctx.commandPool, this.commandBuffer);
    }

    // ---------------- static convenience (旧 API 互換) ----------------

    /**
     * トラッカ未初期化でも安全に問い合わせられる版。
     * {@code VkAutoBindingShader} の in-flight 検出から使う。
     */
    public static boolean isInFlight(long generation) {
        return INSTANCE != null && INSTANCE.inFlight(generation);
    }

    /** 記録窓 (beginFrame 〜 endFrame) の中か。未初期化なら false。 */
    public static boolean isRecordingFrame() {
        return INSTANCE != null && INSTANCE.recording;
    }

    public static long currentGeneration() { return INSTANCE == null ? 0 : INSTANCE.current; }
    public static long completedGeneration() { return INSTANCE == null ? 0 : INSTANCE.completed; }

    /** 現在保留中の解放数 (テスト・デバッグ用)。 */
    public int pendingFreeCount() { return this.pendingFree.size(); }

    /** 全 in-flight を完了扱いにする (GPU を触らない。初期化時のバインド設定用)。 */
    public static void markAllCompleted() {
        if (INSTANCE != null) INSTANCE.completed = INSTANCE.current;
    }

    /** テスト用に一覧を取り出す。 */
    static List<Runnable> snapshotPendingFree() {
        return INSTANCE == null ? List.of() : new ArrayList<>(INSTANCE.pendingFree);
    }
}
