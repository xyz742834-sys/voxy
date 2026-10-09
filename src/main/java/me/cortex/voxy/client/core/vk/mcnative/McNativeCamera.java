package me.cortex.voxy.client.core.vk.mcnative;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * <b>Minecraft 自身のカメラ行列の写し</b> (native instance mode 用)。
 *
 * <p>Sodium の {@code DefaultChunkRenderer.render} が受け取る {@code ChunkRenderMatrices}
 * (投影・モデルビュー) と {@code CameraTransform} (カメラのワールド座標) を、CUTOUT パスのたびに
 * ここへ写す ({@code MixinDefaultChunkRenderer.doRender})。GL には触らない。レベル描画の末尾の
 * probe は、同じフレームの行列をここから読む。
 *
 * <p>⚠ 写しは<b>そのフレームの</b>ものである。読む側は {@link #frame()} でフレーム番号を
 * 確かめ、古い写しを使わない。
 */
public final class McNativeCamera {
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f RAW_PROJECTION = new Matrix4f();
    private static final Matrix4f MODEL_VIEW = new Matrix4f();
    private static double x, y, z;
    private static int width, height;
    private static long frame;
    private static boolean captured;

    private McNativeCamera() {}

    /** 捕捉: 描画スレッドで、Sodium の CUTOUT パスから。 */
    public static synchronized void capture(Matrix4fc projection, Matrix4fc modelView,
                                            double cameraX, double cameraY, double cameraZ,
                                            int targetWidth, int targetHeight) {
        capture(projection, projection, modelView, cameraX, cameraY, cameraZ, targetWidth, targetHeight);
    }

    /**
     * @param rawProjection Minecraft's camera projection without the extra transforms (view
     *                      bobbing) the passed projection may carry — what
     *                      {@code VoxyRenderSystem.computeProjectionMat} factors out
     */
    public static synchronized void capture(Matrix4fc projection, Matrix4fc rawProjection,
                                            Matrix4fc modelView, double cameraX, double cameraY,
                                            double cameraZ, int targetWidth, int targetHeight) {
        RAW_PROJECTION.set(rawProjection);
        PROJECTION.set(projection);
        MODEL_VIEW.set(modelView);
        x = cameraX;
        y = cameraY;
        z = cameraZ;
        width = targetWidth;
        height = targetHeight;
        frame++;
        captured = true;
    }

    /** 直近の写し (無ければ {@code null})。{@code frame} は捕捉の通し番号。 */
    public record View(Matrix4f projection, Matrix4f modelView, double x, double y, double z,
                       int width, int height, long frame, Matrix4f rawProjection) {}

    public static synchronized View latest() {
        if (!captured) return null;
        return new View(new Matrix4f(PROJECTION), new Matrix4f(MODEL_VIEW), x, y, z, width,
            height, frame, new Matrix4f(RAW_PROJECTION));
    }

    public static synchronized boolean captured() { return captured; }
    public static synchronized long frame() { return frame; }
}
