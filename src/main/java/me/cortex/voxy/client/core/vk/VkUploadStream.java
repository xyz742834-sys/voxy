package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * GL 側 {@code UploadStream} の Vulkan 版。
 *
 * <h2>GL 版から消えたもの</h2>
 * GL 版の複雑さはすべて「CPU が書いた内容が GPU に見えるタイミングの制御」のためだった。
 * ユニファイドメモリ + {@code HOST_COHERENT} + in-flight = 1 でその制御が不要になる。
 *
 * <table>
 *   <tr><th>GL 版</th><th>Vulkan 版</th></tr>
 *   <tr><td>ステージング + {@code glCopyNamedBufferSubData}</td><td><b>不要</b> — 対象へ直接書く</td></tr>
 *   <tr><td>{@code glFlushMappedNamedBufferRange}</td><td><b>不要</b> — {@code HOST_COHERENT}</td></tr>
 *   <tr><td>{@code GlFence} + {@code UploadFrame} キュー</td><td><b>不要</b> — in-flight = 1</td></tr>
 *   <tr><td>{@code AllocationArena}</td><td><b>不要</b> — バンプ + フレーム境界リセット</td></tr>
 * </table>
 *
 * <h2>2 つのモード</h2>
 * <ol>
 *   <li><b>モード 1</b> {@link #upload}: 対象バッファのマップ先を直接返す。転送は起きない。
 *       GL 版と API 形状が同じなので呼び出し側は無変更。</li>
 *   <li><b>モード 2</b> {@link #rawUploadAddress}: スクラッチリングに確保し、
 *       そこを SSBO として dynamic offset でバインドする用途
 *       (docs/phase3-descriptor-survey.md カテゴリ C、3 箇所)。</li>
 * </ol>
 */
public class VkUploadStream {
    /** モード 2 専用のスクラッチ。モード 1 が転送を使わなくなったぶん GL 版より小さくてよい。 */
    private static final long SCRATCH_SIZE = 1 << 24;   // 16 MB

    private final VkBuffer scratch;
    private final long alignment;
    private long bump;
    /** デバッグ用: 直近の確保者。枯渇時のメッセージに出す。 */
    private String lastRequester = "(none)";

    public VkUploadStream(long scratchSize) {
        this.scratch = new VkBuffer(scratchSize, false).name("VkUploadStream.scratch");
        this.alignment = Math.max(VkContext.get().minStorageBufferOffsetAlignment, 16);
    }

    // ---------------- モード 1: 対象へ直接書く ----------------

    /**
     * 対象バッファの書き込み先アドレスを返す。<b>転送は起きない</b> —
     * ユニファイドメモリなので対象のマップ先がそのまま書き込み先になる。
     *
     * @return 書き込み可能なホストアドレス
     */
    public long upload(VkBuffer target, long offset, long size) {
        assertInRecordingWindow(target, offset, size);
        if (offset < 0 || size <= 0 || offset + size > target.size()) {
            throw new IllegalArgumentException("upload out of range: offset=" + offset
                + " size=" + size + " bufferSize=" + target.size());
        }
        return target.addr() + offset;
    }

    public long uploadTo(VkBuffer target) {
        return this.upload(target, 0, target.size());
    }

    public void upload(VkBuffer target, long destOffset, MemoryBuffer data) {
        data.cpyTo(this.upload(target, destOffset, data.size));
    }

    /**
     * 「アップロードは記録窓 (beginFrame 〜 endFrame) の中でのみ」を強制する。
     *
     * <p>in-flight = 1 では記録中に GPU がアイドルであることが保証されるため直接書き込みが安全になる。
     * <b>記録窓の外で書くと、サブミット済みのコマンドが読んでいる最中のバッファを書き換えうる。</b>
     * in-flight = 1 では窓が広いので実害が出にくく、多重化した瞬間に静かに壊れる類の問題なので、
     * 今のうちから実行時に落とす。
     */
    private static void assertInRecordingWindow(VkBuffer target, long offset, long size) {
        if (VkFrameTracker.isRecordingFrame()) return;
        throw new IllegalStateException(
            "upload outside the frame recording window: target=0x" + Long.toHexString(target.handle)
          + " offset=" + offset + " size=" + size + "\n"
          + "  Direct writes are only safe between VkFrameTracker.beginFrame() and endFrame(),\n"
          + "  where in-flight=1 guarantees the GPU is idle. Writing after submit races with execution.\n"
          + "  See docs/phase3-uploadstream-proposal.md 4.");
    }

    // ---------------- モード 2: スクラッチリング ----------------

    /**
     * スクラッチに {@code size} バイト確保し、そのバッファ先頭からのオフセットを返す。
     * 返り値は {@code minStorageBufferOffsetAlignment} の倍数なので
     * そのまま {@code pDynamicOffsets} に渡せる。
     */
    public long rawUploadAddress(int size) {
        return this.rawUploadAddress(size, "(unnamed)");
    }

    public long rawUploadAddress(int size, String requester) {
        if (size <= 0) throw new IllegalArgumentException("size must be > 0, got " + size);
        assertInRecordingWindow(this.scratch, this.bump, size);

        long aligned = alignUp(size, this.alignment);
        if (this.bump + aligned > this.scratch.size()) {
            throw new IllegalStateException(
                "VkUploadStream scratch exhausted.\n"
              + "    requester   : " + requester + "\n"
              + "    requested   : " + size + " bytes (aligned to " + aligned + ")\n"
              + "    remaining   : " + (this.scratch.size() - this.bump) + " bytes\n"
              + "    capacity    : " + this.scratch.size() + " bytes\n"
              + "    last alloc  : " + this.lastRequester + "\n"
              + "  The ring is reset at each frame boundary; this means one frame's mode-2 uploads\n"
              + "  exceeded the scratch. Raise SCRATCH_SIZE, or split the work across frames.\n"
              + "  See docs/phase3-uploadstream-proposal.md 5-2.");
        }
        long addr = this.bump;
        this.bump += aligned;
        this.lastRequester = requester + " (" + size + "B @ " + addr + ")";
        return addr;
    }

    /** スクラッチ先頭のホストアドレス。{@link #rawUploadAddress} の戻り値を足して使う。 */
    public long getBaseAddress() { return this.scratch.addr(); }

    /** dynamic offset でバインドする対象。 */
    public VkBuffer getRawBuffer() { return this.scratch; }

    /** 現フレームのスクラッチ使用量 (デバッグ・実測用)。 */
    public long usedThisFrame() { return this.bump; }

    // ---------------- frame lifecycle ----------------

    /**
     * GL 版との API 互換のために残してあるが <b>何もしない</b>。
     * {@code HOST_COHERENT} なのでフラッシュは不要で、転送も起きない。
     */
    public void commit() { }

    /** フレーム境界でリングを巻き戻す。{@link VkFrameTracker} のフックから呼ばれる。 */
    public void resetForNewFrame() {
        this.bump = 0;
        this.lastRequester = "(none)";
    }

    public void free() {
        this.scratch.free();
    }

    private static long alignUp(long v, long a) { return ((v + a - 1) / a) * a; }

    // ---------------- instance ----------------

    private static VkUploadStream INSTANCE;

    public static VkUploadStream get() {
        if (INSTANCE == null) throw new IllegalStateException("VkUploadStream not initialised");
        return INSTANCE;
    }

    public static void init() {
        if (INSTANCE != null) return;
        INSTANCE = new VkUploadStream(SCRATCH_SIZE);
        VkFrameTracker.get().addFrameBeginHook(INSTANCE::resetForNewFrame);
    }

    public static void shutdown() {
        if (INSTANCE == null) return;
        INSTANCE.free();
        INSTANCE = null;
    }
}
