package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GL 側 {@code DownloadStream} の Vulkan 版。
 *
 * <h2>2 つの経路</h2>
 * <ul>
 *   <li>{@link #download} — <b>退避あり (既定・安全側)</b>。記録時に
 *       {@code vkCmdCopyBuffer} でスクラッチへコピーし、フレーム完了後にスクラッチを読む。
 *       GL 版と同じ「コマンドストリームのその時点のスナップショット」意味論を保つ。</li>
 *   <li>{@link #downloadDirect} — 退避なし。フレーム完了後に対象を直接読む。
 *       <b>同一フレーム内でその領域が再度書かれない</b>ことを確認済みの箇所でのみ使う。</li>
 * </ul>
 *
 * <h2>なぜ既定が退避側なのか</h2>
 * 全 download 箇所を調べたところ、直接読みが安全なのは 1 箇所だけだった
 * [確認済 — docs/phase3-uploadstream-proposal.md 3.0]。
 * 特に {@code HierarchicalOcclusionTraverser:352} は download の<b>次の行で</b>
 * リクエスト件数をクリアしており、直接読みでは必ず 0 になる。
 * 「FREX 有効時のみ危険」な箇所も、条件はユーザー環境で変わるため退避側に倒す。
 *
 * <h2>遅延</h2>
 * GL 版と同じく <b>1 フレーム</b>。結果は次の {@link VkFrameTracker#beginFrame()} で配送される
 * (フェンス待ちの直後 = GPU アイドル)。
 */
public class VkDownloadStream {
    private static final long SCRATCH_SIZE = 1 << 25;   // 32 MB (GL 版と同じ)

    public interface DownloadResultConsumer {
        void consume(long address, long size);
    }

    /** 退避あり: スクラッチの [offset, offset+size) を読む。 */
    private record Staged(long scratchOffset, long size, DownloadResultConsumer consumer) {}
    /** 退避なし: 対象バッファを直接読む。 */
    private record Direct(VkBuffer target, long offset, long size, DownloadResultConsumer consumer) {}

    private final VkBuffer scratch;
    private final long alignment;
    private long bump;

    private final List<Staged> staged = new ArrayList<>();
    private final List<Direct> direct = new ArrayList<>();

    public VkDownloadStream(long scratchSize) {
        this.scratch = new VkBuffer(scratchSize, false).name("VkDownloadStream.scratch");
        this.alignment = Math.max(VkContext.get().minStorageBufferOffsetAlignment, 16);
    }

    // ---------------- staged (default) ----------------

    public void download(VkBuffer target, DownloadResultConsumer consumer) {
        this.download(target, 0, target.size(), consumer);
    }

    public void download(VkBuffer target, Consumer<MemoryBuffer> consumer) {
        this.download(target, 0, target.size(), consumer);
    }

    public void download(VkBuffer target, long offset, long size, Consumer<MemoryBuffer> consumer) {
        this.download(target, offset, size,
            (ptr, sz) -> consumer.accept(MemoryBuffer.createUntrackedUnfreeableRawFrom(ptr, sz)));
    }

    /**
     * 記録中のコマンドバッファに {@code vkCmdCopyBuffer} を積み、フレーム完了後にスクラッチを読む。
     * コピー時点のスナップショットが保たれるため、あとで対象が書き換わっても影響を受けない。
     */
    public void download(VkBuffer target, long offset, long size, DownloadResultConsumer consumer) {
        validate(target, offset, size);
        var cmd = VkFrameTracker.get().commandBuffer();   // 記録中でなければここで例外

        long dst = this.alloc(size, target);

        try (MemoryStack stack = stackPush()) {
            // 対象への書き込みが終わってからコピーする
            var pre = VkBufferMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(target.handle).offset(offset).size(size);
            vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, null, pre, null);

            var region = VkBufferCopy.calloc(1, stack)
                .srcOffset(offset).dstOffset(dst).size(size);
            vkCmdCopyBuffer(cmd, target.handle, this.scratch.handle, region);

            // コピー結果をホストが読むので HOST_READ まで可視化する
            var post = VkBufferMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(this.scratch.handle).offset(dst).size(size);
            vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                0, null, post, null);
        }

        this.staged.add(new Staged(dst, size, consumer));
    }

    // ---------------- direct (opt-in) ----------------

    /**
     * 退避せず、フレーム完了後に対象を直接読む。
     *
     * <p><b>同一フレーム内でこの領域が再度書かれないことを確認したうえで使うこと。</b>
     * 書かれる場合はコピーを挟む {@link #download} を使う。
     */
    public void downloadDirect(VkBuffer target, long offset, long size, DownloadResultConsumer consumer) {
        validate(target, offset, size);
        this.direct.add(new Direct(target, offset, size, consumer));
    }

    public void downloadDirect(VkBuffer target, DownloadResultConsumer consumer) {
        this.downloadDirect(target, 0, target.size(), consumer);
    }

    // ---------------- delivery ----------------

    /**
     * フレーム完了後 (GPU アイドル) に結果を配送する。
     * {@link VkFrameTracker} のフレーム開始フックから呼ばれる。
     */
    public void deliver() {
        for (var d : this.staged) {
            try {
                d.consumer().consume(this.scratch.addr() + d.scratchOffset(), d.size());
            } catch (Throwable t) {
                Logger.error("download consumer failed", t);
            }
        }
        for (var d : this.direct) {
            try {
                d.target().assertNotFreed();
                d.consumer().consume(d.target().addr() + d.offset(), d.size());
            } catch (Throwable t) {
                Logger.error("direct download consumer failed", t);
            }
        }
        this.staged.clear();
        this.direct.clear();
        this.bump = 0;
    }

    private long alloc(long size, VkBuffer target) {
        long aligned = alignUp(size, this.alignment);
        if (this.bump + aligned > this.scratch.size()) {
            throw new IllegalStateException(
                "VkDownloadStream scratch exhausted.\n"
              + "    target    : 0x" + Long.toHexString(target.handle) + "\n"
              + "    requested : " + size + " bytes (aligned to " + aligned + ")\n"
              + "    remaining : " + (this.scratch.size() - this.bump) + " bytes\n"
              + "    capacity  : " + this.scratch.size() + " bytes\n"
              + "  The ring is reset once per frame after delivery.");
        }
        long a = this.bump;
        this.bump += aligned;
        return a;
    }

    private static void validate(VkBuffer target, long offset, long size) {
        if (size <= 0) throw new IllegalArgumentException("size must be > 0, got " + size);
        if (offset < 0 || offset + size > target.size()) {
            throw new IllegalArgumentException("download out of range: offset=" + offset
                + " size=" + size + " bufferSize=" + target.size());
        }
    }

    private static long alignUp(long v, long a) { return ((v + a - 1) / a) * a; }

    public int pendingCount() { return this.staged.size() + this.direct.size(); }

    public void free() { this.scratch.free(); }

    // ---------------- instance ----------------

    private static VkDownloadStream INSTANCE;

    public static VkDownloadStream get() {
        if (INSTANCE == null) throw new IllegalStateException("VkDownloadStream not initialised");
        return INSTANCE;
    }

    public static void init() {
        if (INSTANCE != null) return;
        INSTANCE = new VkDownloadStream(SCRATCH_SIZE);
        VkFrameTracker.get().addFrameBeginHook(INSTANCE::deliver);
    }

    public static void shutdown() {
        if (INSTANCE == null) return;
        INSTANCE.free();
        INSTANCE = null;
    }
}
