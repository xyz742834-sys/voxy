package me.cortex.voxy.client.core.vk.mcnative;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.shader.SpirvCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderType;
import me.cortex.voxy.common.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * <b>Voxy の {@link VkContext} に Minecraft の device を採用させる</b>
 * (docs/ai/project-goal.md の次の優先項目 — 地形移植の最後の構造的ブロッカー)。
 *
 * <p>採用すると、Voxy の GPU 資産 ({@code VkBuffer} / {@code VkTexture} /
 * シェーダ・パイプライン一式) が<b>Minecraft の instance・physical device・device・
 * キュー</b>の上で動く。instance や device を自分で作らないので、GL コンテキストも
 * IOSurface も要らない。
 *
 * <p>⚠ 既定で無効。{@code -Dvoxy.native.adopt=true} のときだけ採用する。
 * また、採用しても Voxy のレンダラは<b>まだ有効にならない</b> —
 * 既存のレンダラは GL 合成に依存しており、そちらの移植はこの次の段階である。
 * ここで確かめるのは「Voxy の GPU 層が MC の device 上で成立するか」だけ。
 *
 * <p>キューは MC の<b>グラフィクスキュー</b>を使う。グラフィクス対応ファミリは
 * 仕様上 compute も対応するので、Voxy の traversal / cull / table 生成も同じキューに流せ、
 * ファミリ跨ぎの所有権移送が要らない (このホストでは graphics=0 / compute=3 と別ファミリ
 * なので、これは実際に意味がある)。
 */
public final class McNativeVkContext {
    private McNativeVkContext() {}

    /** これを true にしたときだけ MC の device を採用する。 */
    public static final String FLAG = "voxy.native.adopt";

    private static final long EXPECTED = 0x0123456789abcdefL;

    private static final String SOURCE = """
        #version 460
        #extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
        layout(local_size_x = 1) in;
        layout(binding = 0, std430) writeonly buffer Out { uint64_t value; };
        void main() {
            value = 0x0123456789abcdefUL;
        }
        """;

    private static boolean attempted;
    private static Status status = new Status(false, false, false, -1, null, false, null, List.of());

    /**
     * @param adopted      {@link VkContext} が MC の device を採用した状態になったか
     * @param provenByVoxy Voxy 自身の {@code VkBuffer} + コマンドプール + キューを使って
     *                     compute が走り、値が読み戻せたか
     */
    public record Status(boolean enabled, boolean attempted, boolean adopted, int queueFamily,
                         String device, boolean provenByVoxy, String readBack, List<String> notes) {}

    public static Status status() { return status; }

    /**
     * 採用する。{@link VkContext} が既に初期化されていれば何もしない
     * (Voxy が自前の device を作ってしまっている場合は、そちらが先に勝つ)。
     * <b>例外は投げない</b> — 失敗したら notes に理由を残し、Voxy は従来どおり動く。
     */
    public static void adoptIfRequested() {
        if (attempted) return;
        attempted = true;
        var notes = new ArrayList<String>();
        boolean enabled = Boolean.getBoolean(FLAG);
        if (!enabled) {
            status = new Status(false, true, false, -1, null, false, null, List.of());
            return;
        }
        try {
            VulkanDevice mc = McNativeVulkan.device(notes);
            if (mc == null) {
                notes.add("Minecraft is not on its Vulkan backend; nothing to adopt");
                status = new Status(true, true, false, -1, null, false, null, List.copyOf(notes));
                return;
            }
            try (MemoryStack stack = stackPush()) {
                VkPhysicalDevice physical = physicalOf(mc, stack, notes);
                if (physical == null) {
                    status = new Status(true, true, false, -1, null, false, null, List.copyOf(notes));
                    return;
                }
                var queue = mc.graphicsQueue();
                // ⚠ round-1 review N4: 「物理デバイスが対応しているか」ではなく
                // 「MC がその device で有効にしたか」を渡す。IOSurface 経路の可否は
                // 有効化の有無で決まるため (MC は VK_EXT_metal_objects を有効にしない)。
                VkContext.initAdopted(mc.instance().vkInstance(), physical, mc.vkDevice(),
                    queue.vkQueue(), queue.queueFamilyIndex(),
                    safeDebugEnabled(mc, notes), false,
                    enabledDeviceExtensions(mc, notes));
                boolean adopted = VkContext.isAdopted();
                if (!adopted) {
                    notes.add("VkContext was already initialised, so Minecraft's device was not adopted");
                }
                status = new Status(true, true, adopted, queue.queueFamilyIndex(),
                    "0x" + Long.toHexString(mc.vkDevice().address()), false, null, List.copyOf(notes));
                Logger.info("[native-vk] adopted Minecraft's Vulkan device into VkContext: " + adopted);
            }
        } catch (Throwable t) {
            notes.add("adoption failed: " + t);
            Logger.warn("[native-vk] could not adopt Minecraft's device: " + t);
            status = new Status(true, true, false, -1, null, false, null, List.copyOf(notes));
        }
    }

    /**
     * <b>採用した context で Voxy の資産が本当に動くことを確かめる</b>。
     *
     * <p>Voxy の {@link VkBuffer} (永続マップ・{@code VkContext.findMemoryType} 経由) に、
     * Voxy の {@link SpirvCompiler} が吐いた {@code uint64_t} を書く compute を、
     * Voxy の {@code commandPool} と {@code queue} で提出して読み戻す。
     * 1 回でも通れば、バッファ層・シェーダ層・キュー/プール層が MC の device 上で
     * 成立していることになる。
     */
    public static Status proveOnce() {
        if (!status.adopted() || status.provenByVoxy()) return status;
        var notes = new ArrayList<String>(status.notes());
        var ctx = VkContext.get();
        long module = 0, setLayout = 0, pipelineLayout = 0, pipeline = 0, descriptorPool = 0, fence = 0;
        VkBuffer out = null;
        String readBack = null;
        boolean ok = false;
        boolean submitted = false, retired = false;
        try (MemoryStack stack = stackPush()) {
            out = new VkBuffer(8, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true).name("native-adopt-probe");

            var binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                .binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            var dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binding);
            long[] handle = new long[1];
            VkContext.check(vkCreateDescriptorSetLayout(ctx.device, dslci, null, handle),
                "vkCreateDescriptorSetLayout(adopted)");
            setLayout = handle[0];

            var poolSize = VkDescriptorPoolSize.calloc(1, stack)
                .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            var dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(1).pPoolSizes(poolSize);
            VkContext.check(vkCreateDescriptorPool(ctx.device, dpci, null, handle),
                "vkCreateDescriptorPool(adopted)");
            descriptorPool = handle[0];

            var dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(descriptorPool).pSetLayouts(stack.longs(setLayout));
            long[] sets = new long[1];
            VkContext.check(vkAllocateDescriptorSets(ctx.device, dsai, sets),
                "vkAllocateDescriptorSets(adopted)");

            var bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(out.handle).offset(0).range(8);
            var write = VkWriteDescriptorSet.calloc(1, stack).sType$Default()
                .dstSet(sets[0]).dstBinding(0).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(bufferInfo);
            vkUpdateDescriptorSets(ctx.device, write, null);

            var spirv = SpirvCompiler.compile(VkShaderType.COMPUTE, SOURCE, "voxy-adopt-probe.comp");
            var smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            VkContext.check(vkCreateShaderModule(ctx.device, smci, null, handle),
                "vkCreateShaderModule(adopted)");
            module = handle[0];

            var plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout));
            VkContext.check(vkCreatePipelineLayout(ctx.device, plci, null, handle),
                "vkCreatePipelineLayout(adopted)");
            pipelineLayout = handle[0];

            var stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            var cpci = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                .stage(stage).layout(pipelineLayout);
            VkContext.check(vkCreateComputePipelines(ctx.device, VK_NULL_HANDLE, cpci, null, handle),
                "vkCreateComputePipelines(adopted)");
            pipeline = handle[0];

            // ⚠ Voxy 自身のコマンドプールとキュー (= 採用した MC の device/queue) を使う。
            var cbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(ctx.commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer buffers = stack.mallocPointer(1);
            VkContext.check(vkAllocateCommandBuffers(ctx.device, cbai, buffers),
                "vkAllocateCommandBuffers(adopted)");
            var cmd = new VkCommandBuffer(buffers.get(0), ctx.device);

            var begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            VkContext.check(vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer(adopted)");
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                stack.longs(sets[0]), null);
            vkCmdDispatch(cmd, 1, 1, 1);
            VkContext.check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer(adopted)");

            var fci = VkFenceCreateInfo.calloc(stack).sType$Default();
            VkContext.check(vkCreateFence(ctx.device, fci, null, handle), "vkCreateFence(adopted)");
            fence = handle[0];
            var submit = VkSubmitInfo.calloc(1, stack).sType$Default().pCommandBuffers(stack.pointers(cmd));
            VkContext.check(vkQueueSubmit(ctx.queue, submit, fence), "vkQueueSubmit(adopted)");
            submitted = true;
            // ⚠ round-1 review B5: タイムアウトでも破棄に進んでいた。待てなければ壊さない。
            int waited = vkWaitForFences(ctx.device, stack.longs(fence), true, 5_000_000_000L);
            if (waited != VK_SUCCESS) {
                notes.add("vkWaitForFences(adopted) -> VkResult " + waited
                    + "; nothing is destroyed (resources are leaked on purpose)");
                throw new IllegalStateException("adopted fence wait did not complete: " + waited);
            }
            retired = true;

            long value = MemoryUtil.memGetLong(out.addr());
            readBack = "0x" + Long.toHexString(value);
            ok = value == EXPECTED;
            if (!ok) notes.add("the adopted-context compute wrote " + readBack
                + ", expected 0x" + Long.toHexString(EXPECTED));
        } catch (Throwable t) {
            notes.add("the adopted-context proof failed: " + t);
        } finally {
            if (submitted && !retired) {
                notes.add("leaking the adopted proof's Vulkan objects on purpose: the submission"
                    + " was never observed to complete");
                status = new Status(status.enabled(), true, status.adopted(), status.queueFamily(),
                    status.device(), false, readBack, List.copyOf(notes));
                return status;
            }
            try {
                if (fence != 0) vkDestroyFence(ctx.device, fence, null);
                if (pipeline != 0) vkDestroyPipeline(ctx.device, pipeline, null);
                if (pipelineLayout != 0) vkDestroyPipelineLayout(ctx.device, pipelineLayout, null);
                if (module != 0) vkDestroyShaderModule(ctx.device, module, null);
                if (descriptorPool != 0) vkDestroyDescriptorPool(ctx.device, descriptorPool, null);
                if (setLayout != 0) vkDestroyDescriptorSetLayout(ctx.device, setLayout, null);
                if (out != null) out.free();
            } catch (Throwable t) {
                notes.add("cleanup after the adopted-context proof: " + t);
            }
        }
        status = new Status(status.enabled(), true, status.adopted(), status.queueFamily(),
            status.device(), ok, readBack, List.copyOf(notes));
        Logger.info("[native-vk] Voxy's own buffer + shader + command pool on Minecraft's device: "
            + (ok ? "wrote and read back " + readBack : "FAILED " + notes));
        return status;
    }

    /**
     * <b>採用した device から手を引く</b>。Minecraft がその device を壊す前に呼ぶこと。
     *
     * <p>採用モードの {@link VkContext#shutdown()} は<b>自分が作ったものだけ</b>
     * (コマンドプール) を壊し、device/instance には触らない。これを呼ばずに
     * Minecraft が device を壊すと、検証レイヤが
     * 「device 破棄前に子オブジェクトを全て破棄せよ」と正しく指摘する。
     */
    public static void releaseAdopted() {
        // ⚠ round-1 review B2: 以前はここで「採用したか」を見る前に Minecraft の device を
        // 取得して vkDeviceWaitIdle していた。採用していないプレイヤー — つまり通常の
        // Vulkan バックエンド利用者 — まで、有効化していない診断のために終了時に
        // 待たされる。<b>採用していなければ何も触らない</b>。
        if (!VkContext.isAdopted()) return;

        // Minecraft の最終フレームがまだ実行中のことがある。そのフレームが参照している
        // パイプラインを壊すと検証レイヤが正しく指摘する ("All submitted commands that
        // refer to pipeline must have completed execution")。終了時なので待って構わない。
        try {
            int waited = vkDeviceWaitIdle(VkContext.get().device);
            if (waited != VK_SUCCESS) {
                Logger.warn("[native-vk] vkDeviceWaitIdle returned " + waited
                    + " at release; not destroying anything (objects are leaked on purpose"
                    + " rather than destroyed while still in use)");
                return;
            }
        } catch (Throwable t) {
            Logger.warn("[native-vk] could not wait for Minecraft's device to go idle: " + t
                + "; not destroying anything");
            return;
        }
        try {
            // ⚠ round-3 review B5: Minecraft の device が差し替わっていると、採用した
            // context は旧 device A を持ち、マーカーは新 device B に属する。A のアイドルを
            // 待って B のオブジェクトを壊すのは「完了を確かめていない破棄」である。
            // 待った device と同じであることを渡して確認させ、違えば壊さない。
            McNativeMarkerDraw.shutdownImmediate(VkContext.get().device);
        } catch (Throwable t) {
            Logger.warn("[native-vk] could not release the marker draw: " + t);
        }
        // ⚠ Voxy の静的キャッシュは device オブジェクトを握っている。採用モードでは
        // device は Minecraft のものなので、MC がそれを壊す前にこちらを空にしないと
        // 「device 破棄前に子オブジェクトを全て破棄せよ」と正しく指摘される
        // (Vk テストの teardown が落としているものと同じ一覧)。
        for (Runnable cache : new Runnable[] {
                me.cortex.voxy.client.core.vk.VkCullPass::shutdown,
                me.cortex.voxy.client.core.vk.VkUploadStream::shutdown,
                me.cortex.voxy.client.core.vk.VkDownloadStream::shutdown,
                me.cortex.voxy.client.core.vk.VkSampler::shutdown,
                me.cortex.voxy.client.core.vk.VkQuadIndexBuffer::shutdown,
                me.cortex.voxy.client.core.vk.VkFrameTracker::shutdown}) {
            try {
                cache.run();
            } catch (Throwable t) {
                Logger.warn("[native-vk] could not release a Voxy GPU cache: " + t);
            }
        }
        try {
            VkContext.shutdown();
            Logger.info("[native-vk] released Minecraft's device from VkContext"
                + " (nothing of Minecraft's was destroyed)");
        } catch (Throwable t) {
            Logger.warn("[native-vk] could not release the adopted VkContext: " + t);
        }
    }

    /**
     * MC がその device で<b>有効にした</b>拡張名。{@code DeviceInfo.underlyingExtensions()} は
     * instance/device を {@code " (I)"} / {@code " (D)"} の接尾辞で区別して並べるので、
     * device 側だけを取り出して接尾辞を落とす。
     */
    private static java.util.Set<String> enabledDeviceExtensions(VulkanDevice device, List<String> notes) {
        var out = new java.util.HashSet<String>();
        try {
            for (String entry : device.getDeviceInfo().underlyingExtensions()) {
                if (entry == null) continue;
                String name = entry.trim();
                if (name.endsWith("(D)")) out.add(name.substring(0, name.length() - 3).trim());
            }
        } catch (Throwable t) {
            notes.add("underlyingExtensions(): " + t + "; treating the device as having none");
        }
        return out;
    }

    private static boolean safeDebugEnabled(VulkanDevice device, List<String> notes) {
        try {
            return device.isDebuggingEnabled();
        } catch (Throwable t) {
            notes.add("isDebuggingEnabled(): " + t);
            return false;
        }
    }

    private static VkPhysicalDevice physicalOf(VulkanDevice device, MemoryStack stack, List<String> notes) {
        try {
            var instance = device.instance().vkInstance();
            IntBuffer count = stack.mallocInt(1);
            if (vkEnumeratePhysicalDevices(instance, count, null) != VK_SUCCESS || count.get(0) == 0) {
                notes.add("could not enumerate Minecraft's physical devices");
                return null;
            }
            PointerBuffer handles = stack.mallocPointer(count.get(0));
            if (vkEnumeratePhysicalDevices(instance, count, handles) != VK_SUCCESS) return null;
            // ⚠ round-1 review N2: 「最初の 1 つ」では複数 GPU で取り違える。
            // MC 自身が報告しているデバイス名と突き合わせる。合わなければ採用しない。
            String wanted = null;
            try {
                wanted = device.getDeviceInfo().name();
            } catch (Throwable t) {
                notes.add("getDeviceInfo().name(): " + t);
            }
            VkPhysicalDevice first = null;
            for (int i = 0; i < count.get(0); i++) {
                var candidate = new VkPhysicalDevice(handles.get(i), instance);
                if (first == null) first = candidate;
                var props = org.lwjgl.vulkan.VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(candidate, props);
                if (wanted != null && wanted.equals(props.deviceNameString())) return candidate;
            }
            if (count.get(0) == 1 && wanted == null) return first;
            notes.add("could not match Minecraft's device " + wanted + " among " + count.get(0)
                + " physical devices; not adopting");
            return null;
        } catch (Throwable t) {
            notes.add("physical device lookup: " + t);
            return null;
        }
    }

    /** 証跡用の JSON。 */
    public static String json(Status s) {
        var sb = new StringBuilder("{\n");
        sb.append("  \"enabled\": ").append(s.enabled()).append(",\n");
        sb.append("  \"attempted\": ").append(s.attempted()).append(",\n");
        sb.append("  \"adopted\": ").append(s.adopted()).append(",\n");
        sb.append("  \"queueFamily\": ").append(s.queueFamily()).append(",\n");
        sb.append("  \"device\": ").append(McNativeVulkanProbe.quote(s.device())).append(",\n");
        sb.append("  \"provenByVoxyBufferAndShader\": ").append(s.provenByVoxy()).append(",\n");
        sb.append("  \"readBack\": ").append(McNativeVulkanProbe.quote(s.readBack())).append(",\n");
        sb.append("  \"notes\": [");
        for (int i = 0; i < s.notes().size(); i++) {
            sb.append(i == 0 ? "\n    " : ",\n    ").append(McNativeVulkanProbe.quote(s.notes().get(i)));
        }
        sb.append(s.notes().isEmpty() ? "]\n}" : "\n  ]\n}");
        return sb.toString();
    }
}
