package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.client.core.vk.SyntheticTerrain;
import me.cortex.voxy.client.core.vk.VkDepth;
import me.cortex.voxy.client.core.vk.VkFrameTracker;
import me.cortex.voxy.client.core.vk.VkRenderTarget;
import me.cortex.voxy.client.core.vk.VkSceneUniform;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer;
import me.cortex.voxy.client.core.vk.VkTerrainResources;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryUtil;

import java.util.function.Consumer;

/**
 * 合成地形 1 シーンぶんの GPU 資源と、<b>Voxy 自身の的に描いた参照</b> (色、必要なら深度)。
 *
 * <p>{@link McNativeTerrainProbe} の組み立てを切り出したもの。{@code McNativeTerrainLoad}
 * も同じ資源 (地形資源、Voxy の地形パイプライン、固定した視点、参照画像) を必要とするので、
 * フェンス待ちと漏洩の規律 (round-6 review R6-TERRAIN-WAIT) を二度書かないためにここに置く。
 *
 * <h2>参照を作る過程で済むこと</h2>
 * {@link VkTerrainRenderer#recordBeforeRenderPass} 相当 (アトラスのアップロード、深度境界の
 * クリア、ホスト書き込みのバリア) が<b>フェンスで待たれた状態で</b>済む。だから呼び出し側は
 * MC のパスの中で {@link VkTerrainRenderer#recordDrawsInRenderPass} だけを積めばよい。
 * ユニフォーム (視点) は組み立て時に一度書くだけで、以後書き換えない。
 *
 * <h2>⚠ 提出したが待ちを観測できなかったら壊さない</h2>
 * 参照フレームを提出した後にフェンス待ちが成功しなかった場合、資源はまだ使用中かもしれない。
 * その場合は<b>意図的に漏らし</b>、{@code onLeak} で呼び出し側に数えさせる。
 */
final class McNativeTerrainScene {
    final VkTerrainResources res;
    final VkTerrainRenderer renderer;
    final int drawCount;
    final int width, height, colourFormat;
    /** 参照の RGB (画素ごと {@code r | g << 8 | b << 16})、行 0 = Vulkan の行 0。 */
    final int[] colour;
    /** 参照の深度 (D32、逆Z、クリア値 {@link VkDepth#CLEAR})、または読まなかったなら {@code null}。 */
    final float[] depth;
    /** 参照のうち背景色でない画素数。 */
    final long set;
    final float[] mvp;
    private boolean freed;

    private McNativeTerrainScene(VkTerrainResources res, VkTerrainRenderer renderer, int drawCount,
                                 int width, int height, int colourFormat, int[] colour,
                                 float[] depth, long set, float[] mvp) {
        this.res = res;
        this.renderer = renderer;
        this.drawCount = drawCount;
        this.width = width;
        this.height = height;
        this.colourFormat = colourFormat;
        this.colour = colour;
        this.depth = depth;
        this.set = set;
        this.mvp = mvp;
    }

    /** 背景色を RGBA8_UNORM の RGB に詰めたもの ({@link #colour} と同じ詰め方)。 */
    static int packRgb(float[] clear) {
        return (Math.round(clear[2] * 255) << 16)
            | (Math.round(clear[1] * 255) << 8)
            | Math.round(clear[0] * 255);
    }

    /**
     * 地形資源とパイプラインを作り、参照を Voxy 自身の的に描いて読み戻す。
     *
     * @param colourFormat  MC の colour フォーマット。{@link VkRenderTarget#FORMAT_COLOR}
     *                      でなければ呼び出し側が先に断ること (参照と比べられない)
     * @param terrain       合成地形
     * @param mvp           固定する視点 (列優先 16 要素)
     * @param cameraSection 面マスクの基準セクション (カメラのいるセクション)
     * @param readDepth     参照の深度も読み戻すか
     * @param clear         参照の背景色
     * @param note          失敗の説明の受け手
     * @param onLeak        提出済みの資源を意図的に漏らしたときに呼ぶ
     * @return シーン、または作れなかったなら {@code null} (理由は {@code note} へ)
     */
    static McNativeTerrainScene build(int colourFormat, int width, int height,
                                      SyntheticTerrain terrain, float[] mvp, int[] cameraSection,
                                      boolean readDepth, float[] clear, Consumer<String> note,
                                      Runnable onLeak) {
        boolean startedTracker = false;
        VkTerrainResources res = null;
        VkTerrainRenderer renderer = null;
        VkRenderTarget reference = null;
        // ⚠ round-6 review R6-TERRAIN-WAIT: 「提出した」と「待ちを観測した」を分けて持つ。
        boolean submitted = false;
        boolean waitObserved = false;
        try {
            boolean wasRunning = frameTrackerRunning();
            VkFrameTracker.init();
            startedTracker = !wasRunning;

            // stateId は疎に散らばるので、モデルバッファは実際に使われる最大値で確保する。
            res = new VkTerrainResources(terrain.sectionCount(), terrain.totalQuads(), 4096,
                terrain.maxStateId() + 1);
            int[] starts = terrain.writeGeometry(res.geometry);
            terrain.writeMetadata(res.sectionMetadata, starts);
            terrain.writePositions(res.positionScratch);
            res.fillModels(VkTerrainResources.MODEL_FLAG_SHADED);
            var draws = terrain.opaqueDrawCommands(starts, cameraSection);
            SyntheticTerrain.writeDrawCommands(res.drawCall, draws, res.indexQuadCapacity);

            renderer = new VkTerrainRenderer(res, width, height,
                VkTerrainRenderer.Barriers.CONSERVATIVE);
            // 視点は固定する。毎フレーム書き換えないので、ホスト書き込みのバリアが
            // MC のパスの中で要らなくなり、参照と完全に同じ入力になる。
            VkSceneUniform.write(res.uniform, mvp, new int[]{0, 0, 0}, 1, new float[]{0, 0, 0});

            reference = new VkRenderTarget(width, height);
            var tracker = VkFrameTracker.get();
            var cmd = tracker.beginFrame();
            renderer.record(cmd, reference, draws.size(), clear, VkDepth.CLEAR);
            reference.recordReadback(cmd);
            if (readDepth) reference.recordDepthReadback(cmd);
            tracker.endFrame();
            submitted = true;
            tracker.waitForFrame();
            waitObserved = true;

            int[] pixels = new int[width * height];
            long base = reference.readbackBuffer().addr();
            long set = 0;
            int clearRgb = packRgb(clear);
            for (int i = 0; i < pixels.length; i++) {
                int rgb = MemoryUtil.memGetInt(base + (long) i * 4) & 0x00FFFFFF;
                pixels[i] = rgb;
                if (rgb != clearRgb) set++;
            }
            float[] depth = null;
            if (readDepth) {
                depth = new float[width * height];
                long dbase = reference.depthReadbackBuffer().addr();
                for (int i = 0; i < depth.length; i++) {
                    depth[i] = MemoryUtil.memGetFloat(dbase + (long) i * 4);
                }
            }
            Logger.info("[native-vk] terrain reference on the adopted device: " + set
                + " of " + pixels.length + " pixels are not background");
            var built = new McNativeTerrainScene(res, renderer, draws.size(), width, height,
                colourFormat, pixels, depth, set, mvp.clone());
            res = null;
            renderer = null;
            return built;
        } catch (Throwable t) {
            note.accept("could not build the terrain scene: " + t);
            var trace = t.getStackTrace();
            for (int i = 0; i < Math.min(6, trace.length); i++) note.accept("  at " + trace[i]);
            return null;
        } finally {
            // ⚠ round-6 review R6-TERRAIN-WAIT: <b>提出したが待ちを観測できていない</b>なら
            // 何も壊さない。診断 1 個ぶんの資源を漏らす方が、実行中の参照を壊すより遥かに良い。
            if (submitted && !waitObserved) {
                onLeak.run();
                note.accept("the reference frame was submitted but its fence wait was not"
                    + " observed to succeed; leaking the reference target, renderer and"
                    + " resources on purpose rather than destroying something that may still"
                    + " be in use");
            } else {
                if (reference != null) {
                    try { reference.free(); } catch (Throwable t) {
                        note.accept("could not free the reference target: " + t);
                    }
                }
                if (renderer != null) {
                    try { renderer.free(); } catch (Throwable ignored) { }
                }
                if (res != null) {
                    try { res.free(); } catch (Throwable ignored) { }
                }
            }
            if (startedTracker) {
                // 参照画像を描くためだけに起こしたので、ここで片付ける。毎フレームの経路は
                // トラッカーを使わない (MC のコマンドバッファに積むだけ)。
                // ⚠ {@code VkFrameTracker.shutdown} は destroy 経由で実際に vkDeviceWaitIdle
                // を呼ぶ。採用した device は MC のものなので、これは MC の投入まで待たせる。
                try { VkFrameTracker.shutdown(); } catch (Throwable t) {
                    note.accept("could not shut the frame tracker down after building the"
                        + " reference: " + t);
                }
            }
        }
    }

    /** 参照の RGB を行ごと 3 バイトで取り出す (PPM の本体)。 */
    byte[] colourBytes(int[] rect) {
        int w = rect[2] - rect[0], h = rect[3] - rect[1];
        byte[] body = new byte[Math.max(0, w) * Math.max(0, h) * 3];
        int into = 0;
        for (int y = rect[1]; y < rect[3]; y++) {
            for (int x = rect[0]; x < rect[2]; x++) {
                int rgb = this.colour[y * this.width + x];
                body[into++] = (byte) (rgb & 0xFF);
                body[into++] = (byte) ((rgb >> 8) & 0xFF);
                body[into++] = (byte) ((rgb >> 16) & 0xFF);
            }
        }
        return body;
    }

    /** 参照の深度を行ごとリトルエンディアンの float32 で取り出す。 */
    byte[] depthBytes(int[] rect) {
        int w = rect[2] - rect[0], h = rect[3] - rect[1];
        var buf = java.nio.ByteBuffer.allocate(Math.max(0, w) * Math.max(0, h) * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int y = rect[1]; y < rect[3]; y++) {
            for (int x = rect[0]; x < rect[2]; x++) {
                buf.putFloat(this.depth[y * this.width + x]);
            }
        }
        return buf.array();
    }

    /** 資源を解放する。<b>提出の完了を観測した後に</b>呼ぶこと (呼び出し側の責務)。 */
    void free() {
        if (this.freed) return;
        this.freed = true;
        try {
            this.renderer.free();
        } catch (Throwable t) {
            Logger.warn("[native-vk] could not free the terrain renderer: " + t);
        }
        try {
            this.res.free();
        } catch (Throwable t) {
            Logger.warn("[native-vk] could not free the terrain resources: " + t);
        }
    }

    static boolean frameTrackerRunning() {
        try {
            VkFrameTracker.get();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
