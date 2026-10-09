package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.client.core.vk.VkDepth;
import me.cortex.voxy.client.core.vk.VkFrameTracker;
import me.cortex.voxy.client.core.vk.VkModelUploadTarget;
import me.cortex.voxy.client.core.vk.VkQuadIndexBuffer;
import me.cortex.voxy.client.core.vk.VkRealMesher;
import me.cortex.voxy.client.core.vk.VkRealModelBakery;
import me.cortex.voxy.client.core.vk.VkRealSectionUpload;
import me.cortex.voxy.client.core.vk.VkRenderTarget;
import me.cortex.voxy.client.core.vk.VkSceneUniform;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer;
import me.cortex.voxy.client.core.vk.VkTerrainResources;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

import java.util.function.Consumer;

/**
 * <b>実セクション</b>の GPU 資源と、Voxy 自身の的に描く参照 ({@code McNativeRealLoad} 用)。
 *
 * <p>組み立ては {@code VkInteropProbe.ensureRealScene} と同じ部品を同じ順で使う:
 * {@link VkTerrainResources} (REAL アトラス、外部の中身)、{@link VkModelUploadTarget}、
 * ワールドの Mapper を借りた {@link VkRealModelBakery}、{@link VkRealMesher#meshAround}、
 * {@link VkRealModelBakery#replayBiomes}、{@link VkRealSectionUpload#upload}。新しい地形の
 * アルゴリズムは持ち込まない。
 *
 * <h2>参照は標本ごと</h2>
 * 行列は Minecraft のカメラに従って毎フレーム変わるので、参照 (色と D32 深度) は標本の
 * フレームごとに描く ({@link #reference})。手順: Voxy のフレームトラッカーで
 * <b>device をアイドルにしてから</b> (前に MC の提出がこのユニフォームを読んでいないことを
 * 観測する) ユニフォームを書き、焼いたタイルとアトラスを流し、自前の的に描いて読み戻し、
 * フェンスを待つ。その後、同じユニフォームのまま MC の LOAD パスで描画コマンドだけを積む。
 * ユニフォームは次の標本まで書き換えない。
 */
final class McNativeRealScene {
    final VkTerrainResources res;
    final VkModelUploadTarget modelTarget;
    final VkRealModelBakery bakery;
    final VkRealMesher mesher;
    final VkTerrainRenderer renderer;
    final VkRenderTarget target;
    final int width, height, level, radius;
    /** メッシュ化した中心 (レベル {@link #level} のセクション座標)。 */
    final int[] centre;
    final int sectionCount, totalQuads, drawCount, meshed;
    private boolean freed;
    /** 参照の提出を観測できなかった (= 資源が使用中かもしれない) なら以後使わない。 */
    boolean poisoned;

    private McNativeRealScene(VkTerrainResources res, VkModelUploadTarget modelTarget,
                              VkRealModelBakery bakery, VkRealMesher mesher,
                              VkTerrainRenderer renderer, VkRenderTarget target, int width,
                              int height, int level, int radius, int[] centre, int sectionCount,
                              int totalQuads, int drawCount, int meshed) {
        this.res = res;
        this.modelTarget = modelTarget;
        this.bakery = bakery;
        this.mesher = mesher;
        this.renderer = renderer;
        this.target = target;
        this.width = width;
        this.height = height;
        this.level = level;
        this.radius = radius;
        this.centre = centre;
        this.sectionCount = sectionCount;
        this.totalQuads = totalQuads;
        this.drawCount = drawCount;
        this.meshed = meshed;
    }

    /**
     * レベル {@code level} のセクション {@code centre} を中心に半径 {@code radius} をメッシュ化し、
     * 資源に置く。何もメッシュ化できなければ {@code null} (理由は {@code note})。
     * GPU には何も提出しない (提出は {@link #reference})。
     */
    static McNativeRealScene build(WorldEngine world, int level, int[] centre, int radius,
                                   int maxQuads, int width, int height, Consumer<String> note) {
        int side = 2 * radius + 1;
        int maxSections = side * side * side;
        VkTerrainResources res = null;
        VkModelUploadTarget modelTarget = null;
        VkRealModelBakery bakery = null;
        VkRealMesher mesher = null;
        VkTerrainRenderer renderer = null;
        VkRenderTarget target = null;
        java.util.List<me.cortex.voxy.client.core.rendering.building.BuiltSection> built = null;
        try {
            res = new VkTerrainResources(maxSections, maxQuads, maxSections * 7, 1 << 16,
                VkQuadIndexBuffer.DEFAULT_QUAD_CAPACITY, VkTerrainResources.AtlasScale.REAL);
            res.useExternalAtlasContent();
            modelTarget = new VkModelUploadTarget(res);
            // ⚠ ワールドの Mapper を借りる。新しく作るとブロック id が全部別物になる [5c-3b]
            bakery = new VkRealModelBakery(modelTarget, world.getMapper());
            mesher = new VkRealMesher(world, bakery);
            built = mesher.meshAround(centre[0], centre[1], centre[2], radius, level);
            int meshed = built.size();
            if (built.isEmpty()) {
                note.accept("nothing meshed at level " + level + " around " + java.util.Arrays.toString(centre));
                return null;
            }
            bakery.replayBiomes();
            var uploaded = VkRealSectionUpload.upload(built, res);
            renderer = new VkTerrainRenderer(res, width, height, VkTerrainRenderer.Barriers.CONSERVATIVE);
            target = new VkRenderTarget(width, height);
            Logger.info("[native-vk] real-LOAD scene: " + uploaded.sectionCount() + " sections, "
                + uploaded.totalQuads() + " quads, " + uploaded.drawCount() + " draws at level "
                + level + " around " + java.util.Arrays.toString(centre) + " r=" + radius);
            var scene = new McNativeRealScene(res, modelTarget, bakery, mesher, renderer, target,
                width, height, level, radius, centre.clone(), uploaded.sectionCount(),
                uploaded.totalQuads(), uploaded.drawCount(), meshed);
            res = null; modelTarget = null; bakery = null; mesher = null; renderer = null; target = null;
            return scene;
        } catch (Throwable t) {
            note.accept("could not build the real-section scene: " + t);
            return null;
        } finally {
            if (built != null) {
                for (var b : built) {
                    try { b.free(); } catch (Throwable ignored) { }
                }
            }
            // nothing was submitted on any of these yet, so they can be freed directly
            freeAll(target, renderer, mesher, bakery, modelTarget, res);
        }
    }

    /** 参照の結果: 画素ごとの RGB ({@code r | g << 8 | b << 16}) と深度 (Vulkan の行順)。 */
    record Reference(int[] colour, float[] depth, long set) {}

    /**
     * 行列 {@code mvp} でユニフォームを書き、自前の的に描いて読み戻す。
     *
     * @return 参照、または描けなかったなら {@code null} (理由は {@code note})。提出したが
     *         フェンスを観測できなかった場合は {@link #poisoned} を立て、{@code onLeak} を呼ぶ
     */
    Reference reference(float[] mvp, int[] anchor, float[] cameraSubPos, int frameId, float[] clear,
                        Consumer<String> note, Runnable onLeak) {
        if (this.freed || this.poisoned) return null;
        boolean startedTracker = false;
        boolean submitted = false, observed = false;
        try {
            startedTracker = !McNativeTerrainScene.frameTrackerRunning();
            VkFrameTracker.init();
            var tracker = VkFrameTracker.get();
            // ⚠ The uniform is read by Minecraft's earlier submissions of this scene's draws.
            // Write it only after the device is observed idle.
            if (!tracker.waitIdleChecked()) {
                note.accept("vkDeviceWaitIdle did not succeed before the real-LOAD uniform write;"
                    + " not writing it");
                return null;
            }
            VkSceneUniform.write(this.res.uniform, mvp, anchor, frameId, cameraSubPos);
            var cmd = tracker.beginFrame();
            this.bakery.recordUploads(cmd);
            this.renderer.record(cmd, this.target, this.drawCount, clear, VkDepth.CLEAR);
            this.target.recordReadback(cmd);
            this.target.recordDepthReadback(cmd);
            tracker.endFrame();
            submitted = true;
            tracker.waitForFrame();
            observed = true;
            int n = this.width * this.height;
            int[] colour = new int[n];
            float[] depth = new float[n];
            long base = this.target.readbackBuffer().addr();
            long dbase = this.target.depthReadbackBuffer().addr();
            int clearRgb = McNativeTerrainScene.packRgb(clear);
            long set = 0;
            for (int i = 0; i < n; i++) {
                int rgb = MemoryUtil.memGetInt(base + (long) i * 4) & 0x00FFFFFF;
                colour[i] = rgb;
                if (rgb != clearRgb) set++;
                depth[i] = MemoryUtil.memGetFloat(dbase + (long) i * 4);
            }
            return new Reference(colour, depth, set);
        } catch (Throwable t) {
            note.accept("the real-LOAD reference failed: " + t);
            return null;
        } finally {
            if (submitted && !observed) {
                this.poisoned = true;
                onLeak.run();
                note.accept("the real-LOAD reference was submitted but its fence wait was not"
                    + " observed; the scene is leaked on purpose");
            }
            if (startedTracker) {
                try { VkFrameTracker.shutdown(); } catch (Throwable t) {
                    note.accept("could not shut the frame tracker down: " + t);
                }
            }
        }
    }

    /** 解放。<b>Minecraft の提出の完了を観測した後に</b>呼ぶこと (呼び出し側の責務)。 */
    void free() {
        if (this.freed || this.poisoned) return;
        this.freed = true;
        freeAll(this.target, this.renderer, this.mesher, this.bakery, this.modelTarget, this.res);
    }

    private static void freeAll(VkRenderTarget target, VkTerrainRenderer renderer, VkRealMesher mesher,
                                VkRealModelBakery bakery, VkModelUploadTarget modelTarget,
                                VkTerrainResources res) {
        if (target != null) try { target.free(); } catch (Throwable ignored) { }
        if (renderer != null) try { renderer.free(); } catch (Throwable ignored) { }
        if (mesher != null) try { mesher.free(); } catch (Throwable ignored) { }
        if (bakery != null) try { bakery.free(); } catch (Throwable ignored) { }
        if (modelTarget != null) try { modelTarget.free(); } catch (Throwable ignored) { }
        if (res != null) try { res.free(); } catch (Throwable ignored) { }
    }
}
