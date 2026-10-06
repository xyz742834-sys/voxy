package me.cortex.voxy.client.core.vk.mcnative;

import me.cortex.voxy.client.core.vk.SyntheticTerrain;
import me.cortex.voxy.client.core.vk.VkBarriers;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.VkFrameTracker;
import me.cortex.voxy.client.core.vk.VkTerrainRenderer;
import me.cortex.voxy.client.core.vk.VkTerrainResources;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.shader.VkAutoBindingShader;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * <b>Voxy の「本物の」シェーダ機構が、採用した Minecraft の device 上で動くことを確かめる</b>。
 *
 * <p>これまでの証明 ({@link McNativeComputeProbe} / {@link McNativeVkContext}) は、
 * その場で組んだ最小のパイプラインだった。ここで走らせるのは<b>Voxy の本番資産</b>である:
 *
 * <ul>
 *   <li>{@code VkShaderLoader.parse} — {@code #import <voxy:lod/vk/quad_index.glsl>} の解決と
 *       define の注入を含む、Voxy 自身のシェーダ読み込み</li>
 *   <li>{@link VkAutoBindingShader} — SPIR-V から拾ったバインディングで記述子集合を自動的に組む層</li>
 *   <li>{@link VkFrameTracker} — Voxy のフレーム/コマンドバッファ管理 (採用した device と
 *       コマンドプールの上で動く)</li>
 *   <li>{@link VkBarriers} — synchronization2 のバリア翻訳</li>
 *   <li>{@link VkBuffer} / {@link VkTerrainResources} / {@link SyntheticTerrain} — バッファ層と実データ</li>
 * </ul>
 *
 * <p>走らせるのは {@code index_probe.comp} — 頂点シェーダと<b>同じ</b> {@code resolveQuad} を
 * 全通し番号について実行し、結果を書き出す実在のシェーダである。答えは
 * {@code table.resolveLinear} (CPU の線形探索) と<b>全数</b>突き合わせる。
 * 「動いた」ではなく「正しい答えを出した」ところまで見るため。
 *
 * <p>⚠ 採用モード ({@code -Dvoxy.native.adopt=true}) のときだけ、セッションに 1 回だけ走る。
 * 例外は投げない。
 */
public final class McNativeRealShaderProbe {
    private McNativeRealShaderProbe() {}

    private static boolean ran;
    private static Result last = new Result(false, false, 0, 0, null, null, List.of());

    /**
     * @param quadsChecked CPU 参照と突き合わせた通し番号の数
     * @param mismatches   食い違った件数 (0 でなければ失敗)
     */
    /** @param device 実際に走らせた {@code VkDevice} (round-3 review B3: 同一性の出典)。 */
    public record Result(boolean attempted, boolean succeeded, int quadsChecked, int mismatches,
                         String firstMismatch, String device, List<String> notes) {}

    public static Result last() { return last; }

    public static Result runOnce() {
        if (ran) return last;
        ran = true;
        var notes = new ArrayList<String>();
        if (!VkContext.isAdopted()) {
            notes.add("skipped: VkContext has not adopted Minecraft's device");
            last = new Result(false, false, 0, 0, null, deviceHandle(), List.copyOf(notes));
            return last;
        }
        last = runAgainstCurrentContext();
        Logger.info("[native-vk] Voxy's real shader stack on Minecraft's device: "
            + (last.succeeded()
                ? last.quadsChecked() + " quad ordinals resolved, all matching the CPU reference"
                : "FAILED " + last.notes()));
        return last;
    }

    /**
     * <b>いまの {@link VkContext} に対して同じ検査を走らせる</b> (採用の有無を問わない)。
     *
     * <p>採用モードでなくても呼べるようにしてあるのは、Minecraft を起動せずに
     * JUnit で同じ経路を回せるようにするため — この継ぎ目が無いと、失敗の原因を
     * 実機 1 周 (数分) ごとにしか切り分けられない。
     */
    public static Result runAgainstCurrentContext() {
        var notes = new ArrayList<String>();
        boolean startedTracker = false;
        try {
            // init() は冪等。既に誰かが起こしていたら触らない (後で shutdown しないため)。
            boolean wasRunning = frameTrackerRunning();
            VkFrameTracker.init();
            startedTracker = !wasRunning;
            return run(notes);
        } catch (Throwable t) {
            notes.add("the real-shader probe failed: " + t);
            var trace = t.getStackTrace();
            for (int i = 0; i < Math.min(6, trace.length); i++) notes.add("  at " + trace[i]);
            return new Result(true, false, 0, 0, null, deviceHandle(), List.copyOf(notes));
        } finally {
            if (startedTracker) {
                try {
                    // ⚠ waitIdle は使わない — 採用した device は Minecraft のものなので、
                    // vkDeviceWaitIdle は MC の投入まで待たせてしまう。
                    // 自分のフレームだけはフェンスで待ってある。
                    VkFrameTracker.shutdown();
                } catch (Throwable t) {
                    Logger.warn("[native-vk] could not shut the frame tracker down: " + t);
                }
            }
        }
    }

    /** 走らせている {@code VkDevice} のハンドル。採用した context のものになる。 */
    private static String deviceHandle() {
        try {
            return "0x" + Long.toHexString(VkContext.get().device.address());
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@link VkFrameTracker} が既に動いているか。{@code get()} は未初期化だと投げる。 */
    private static boolean frameTrackerRunning() {
        try {
            VkFrameTracker.get();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static Result run(List<String> notes) {
        var terrain = SyntheticTerrain.boundaryCases();

        // 幾何を一度書いて各セクションの開始位置を得る (CPU 参照表を組むのに要る)。
        int[] starts;
        var geometry = new VkBuffer(Math.max(4096L,
            (long) terrain.totalQuads() * SyntheticTerrain.QUAD_SIZE));
        try {
            starts = terrain.writeGeometry(geometry);
        } finally {
            geometry.free();
        }
        var table = terrain.mergedTable(starts, SyntheticTerrain.ORIGIN);
        int total = table.totalQuads();
        if (total <= 0) {
            notes.add("the synthetic table has no quads, so the check would prove nothing");
            return new Result(true, false, 0, 0, null, deviceHandle(), List.copyOf(notes));
        }

        var resources = new VkTerrainResources(terrain.sectionCount(), terrain.totalQuads(), 4096,
            terrain.maxStateId() + 1);
        VkBuffer results = null;
        VkAutoBindingShader shader = null;
        long pipeline = 0;
        boolean drained = false;
        try {
            int[] resourceStarts = terrain.writeGeometry(resources.geometry);
            var gpuTable = terrain.mergedTable(resourceStarts, SyntheticTerrain.ORIGIN);
            SyntheticTerrain.writeMergedEntries(resources.mergedEntry, gpuTable);
            SyntheticTerrain.writeMergedPrefix(resources.mergedPrefix, gpuTable);

            results = new VkBuffer(Math.max(4096L, (long) total * 8)).fill(0xDEADBEEF);

            // ⚠ ここが本題: Voxy 自身のシェーダ読み込みと記述子自動束縛を、MC の device 上で。
            shader = VkShader.makeAuto().name("native-merged-index-probe")
                .addSource(ShaderType.COMPUTE, VkShaderLoader.parse("voxy:lod/vk/index_probe.comp"))
                .compile();
            pipeline = computePipeline(shader);

            shader.ssbo(0, results)
                  .ssbo(VkTerrainRenderer.MERGED_ENTRY_BINDING, resources.mergedEntry)
                  .ssbo(VkTerrainRenderer.MERGED_PREFIX_BINDING, resources.mergedPrefix);
            shader.pushUInt(0, total);

            var tracker = VkFrameTracker.get();
            var cmd = tracker.beginFrame();
            VkBarriers.memoryBarrier(cmd,
                VK13.VK_PIPELINE_STAGE_2_HOST_BIT, VK13.VK_ACCESS_2_HOST_WRITE_BIT,
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            shader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
            shader.flushPushConstants(cmd);
            vkCmdDispatch(cmd, (total + 63) / 64, 1, 1);
            VkBarriers.memoryBarrier(cmd,
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK13.VK_PIPELINE_STAGE_2_HOST_BIT, VK13.VK_ACCESS_2_HOST_READ_BIT);
            tracker.endFrame();
            tracker.waitForFrame();
            drained = true;

            int mismatches = 0;
            String firstMismatch = null;
            for (int q = 0; q < total; q++) {
                long quadIndex = Integer.toUnsignedLong(MemoryUtil.memGetInt(results.addr() + (long) q * 8));
                long drawId = Integer.toUnsignedLong(MemoryUtil.memGetInt(results.addr() + (long) q * 8 + 4));
                int[] expected = gpuTable.resolveLinear(q);
                if (quadIndex != expected[0] || drawId != expected[1]) {
                    mismatches++;
                    if (firstMismatch == null) {
                        firstMismatch = "ordinal " + q + ": GPU (" + quadIndex + ", " + drawId
                            + ") vs CPU (" + expected[0] + ", " + expected[1] + ")";
                    }
                }
            }
            if (mismatches != 0) notes.add(mismatches + " of " + total + " ordinals disagree with the CPU reference");
            return new Result(true, mismatches == 0, total, mismatches, firstMismatch, deviceHandle(), List.copyOf(notes));
        } finally {
            // ⚠ 途中で投げた場合、フレームが記録中のまま残り、提出済みのコマンドが
            // パイプラインを参照している可能性がある。先に閉じて待つ
            // (でなければ vkDestroyPipeline が「実行中のコマンドが参照している」と
            // 正しく指摘される — 実際に 1 度出した)。
            try {
                if (VkFrameTracker.isRecordingFrame()) {
                    var tracker = VkFrameTracker.get();
                    tracker.endFrame();
                    tracker.waitForFrame();
                    drained = true;
                }
            } catch (Throwable t) {
                notes.add("could not drain the frame before cleanup: " + t);
            }
            // ⚠ round-2 review B5: 排出を確認できていないのに破棄していた。
            // 確認できないなら<b>壊さずに漏らす</b> (実行中参照の方が遥かに悪い)。
            if (!drained) {
                notes.add("leaking the probe's pipeline, shader and buffers on purpose: the"
                    + " submitted frame was never observed to complete");
                return new Result(true, false, 0, 0, null, deviceHandle(), List.copyOf(notes));
            }
            if (pipeline != 0) vkDestroyPipeline(VkContext.get().device, pipeline, null);
            if (shader != null) shader.free();
            if (results != null) results.free();
            resources.free();
        }
    }

    private static long computePipeline(VkShader shader) {
        try (MemoryStack stack = stackPush()) {
            var ci = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                .stage(shader.stageInfos(stack).get(0))
                .layout(shader.pipelineLayout());
            long[] p = new long[1];
            VkContext.check(vkCreateComputePipelines(VkContext.get().device, VK_NULL_HANDLE, ci, null, p),
                "vkCreateComputePipelines(native real shader)");
            return p[0];
        }
    }

    /** 証跡用の JSON。 */
    public static String json(Result r) {
        var sb = new StringBuilder("{\n");
        sb.append("  \"attempted\": ").append(r.attempted()).append(",\n");
        sb.append("  \"succeeded\": ").append(r.succeeded()).append(",\n");
        sb.append("  \"quadOrdinalsChecked\": ").append(r.quadsChecked()).append(",\n");
        sb.append("  \"mismatches\": ").append(r.mismatches()).append(",\n");
        sb.append("  \"device\": ").append(McNativeVulkanProbe.quote(r.device())).append(",\n");
        sb.append("  \"firstMismatch\": ").append(McNativeVulkanProbe.quote(r.firstMismatch())).append(",\n");
        sb.append("  \"notes\": [");
        for (int i = 0; i < r.notes().size(); i++) {
            sb.append(i == 0 ? "\n    " : ",\n    ").append(McNativeVulkanProbe.quote(r.notes().get(i)));
        }
        sb.append(r.notes().isEmpty() ? "]\n}" : "\n  ]\n}");
        return sb.toString();
    }
}
