package me.cortex.voxy.client.core.vk.mcnative;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.cortex.voxy.client.core.vk.shader.SpirvCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderType;
import me.cortex.voxy.common.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * <b>Voxy のシェーダが要る機能が、MC の device で本当に有効になったかを「使って」確かめる</b>。
 *
 * <p>Vulkan には「この device で有効な機能」を問う API が無い。
 * {@link McNativeDeviceFeatures} が要求を足したかどうかは分かっても、
 * <b>効いたか</b>は使ってみるしか確認できない。そこで最小の compute を 1 回だけ走らせる:
 * {@code uint64_t} を SSBO へ書くだけのシェーダで、
 * {@code shaderInt64} と「compute から storage へ書けること」を同時に踏む。
 * 読み戻した値が一致すれば、Voxy のシェーダ資産を MC の device に載せられる根拠になる。
 *
 * <p>MC のフレームには触らない: 自前のコマンドプールと MC の<b>compute キュー</b>
 * (このホストでは graphics とは別ファミリ) に 1 回提出し、fence で待って、全部壊す。
 * ⚠ 使うのは MC の {@code VkDevice} であって、Voxy 自身の {@code VkContext} ではない。
 *
 * <p>⚠ {@code -Dvoxy.native.features=true} のときだけ走る。機能を足していない device で
 * 走らせれば当然失敗し、それは「MC の device が壊れている」ことを意味しないからである。
 */
public final class McNativeComputeProbe {
    private McNativeComputeProbe() {}

    private static final long EXPECTED = 0x0123456789abcdefL;

    private static final String SOURCE = """
        #version 460
        #extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
        layout(local_size_x = 1) in;
        layout(binding = 0, std430) writeonly buffer Out { uint64_t value; };
        void main() {
            // shaderInt64 と「compute から storage buffer へ書く」を同時に踏む。
            value = 0x0123456789abcdefUL;
        }
        """;

    private static boolean ran;
    private static Result last;

    /**
     * @param succeeded   読み戻した値が期待値と一致したか
     * @param readBack    実際に読み戻した値 (16 進)
     * @param queueFamily 提出したキューファミリ
     */
    public record Result(boolean attempted, boolean succeeded, String expected, String readBack,
                         int queueFamily, List<String> notes) {}

    public static Result last() { return last; }

    /** セッションに一度だけ。例外は投げない。 */
    public static Result runOnce(VulkanDevice device) {
        if (ran) return last;
        ran = true;
        var notes = new ArrayList<String>();
        if (!Boolean.getBoolean(McNativeDeviceFeatures.FLAG)) {
            notes.add("skipped: " + McNativeDeviceFeatures.FLAG + " is not set, so the features"
                + " Voxy needs were never requested on Minecraft's device");
            last = new Result(false, false, hex(EXPECTED), null, -1, List.copyOf(notes));
            return last;
        }
        last = run(device, notes);
        Logger.info("[native-vk] int64 compute on Minecraft's device: "
            + (last.succeeded() ? "wrote and read back " + last.readBack()
                                : "FAILED (" + last.notes() + ")"));
        return last;
    }

    private static Result run(VulkanDevice device, List<String> notes) {
        var vk = device.vkDevice();
        long module = 0, setLayout = 0, pipelineLayout = 0, pipeline = 0, pool = 0;
        long descriptorPool = 0, buffer = 0, memory = 0, commandPool = 0, fence = 0;
        int queueFamily = -1;
        String readBack = null;
        boolean ok = false;
        boolean submitted = false, retired = false;
        try (MemoryStack stack = stackPush()) {
            var queue = device.computeQueue();
            queueFamily = queue.queueFamilyIndex();

            // ---- バッファ (ホストから読めるメモリ) ----
            var bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                .size(8)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            long[] handle = new long[1];
            check(vkCreateBuffer(vk, bci, null, handle), "vkCreateBuffer", notes);
            buffer = handle[0];

            var requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(vk, buffer, requirements);
            int memoryType = hostVisibleMemoryType(device, requirements.memoryTypeBits(), stack, notes);
            if (memoryType < 0) throw new IllegalStateException("no host-visible memory type");
            var mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(requirements.size()).memoryTypeIndex(memoryType);
            check(vkAllocateMemory(vk, mai, null, handle), "vkAllocateMemory", notes);
            memory = handle[0];
            check(vkBindBufferMemory(vk, buffer, memory, 0), "vkBindBufferMemory", notes);

            // ---- ディスクリプタ ----
            var binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                .binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            var dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binding);
            check(vkCreateDescriptorSetLayout(vk, dslci, null, handle), "vkCreateDescriptorSetLayout", notes);
            setLayout = handle[0];

            var poolSize = VkDescriptorPoolSize.calloc(1, stack)
                .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            var dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(1).pPoolSizes(poolSize);
            check(vkCreateDescriptorPool(vk, dpci, null, handle), "vkCreateDescriptorPool", notes);
            descriptorPool = handle[0];

            var dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(descriptorPool).pSetLayouts(stack.longs(setLayout));
            long[] sets = new long[1];
            check(vkAllocateDescriptorSets(vk, dsai, sets), "vkAllocateDescriptorSets", notes);
            long set = sets[0];

            var bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(buffer).offset(0).range(8);
            var write = VkWriteDescriptorSet.calloc(1, stack).sType$Default()
                .dstSet(set).dstBinding(0).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(bufferInfo);
            vkUpdateDescriptorSets(vk, write, null);

            // ---- パイプライン ----
            var spirv = SpirvCompiler.compile(VkShaderType.COMPUTE, SOURCE, "voxy-native-int64.comp");
            var smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            check(vkCreateShaderModule(vk, smci, null, handle), "vkCreateShaderModule", notes);
            module = handle[0];

            var plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout));
            check(vkCreatePipelineLayout(vk, plci, null, handle), "vkCreatePipelineLayout", notes);
            pipelineLayout = handle[0];

            var stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            var cpci = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                .stage(stage).layout(pipelineLayout);
            check(vkCreateComputePipelines(vk, VK_NULL_HANDLE, cpci, null, handle),
                "vkCreateComputePipelines", notes);
            pipeline = handle[0];

            // ---- 自前のコマンドプールで 1 回だけ提出 ----
            var cpi = VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(queueFamily);
            check(vkCreateCommandPool(vk, cpi, null, handle), "vkCreateCommandPool", notes);
            commandPool = handle[0];

            var cbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer buffers = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(vk, cbai, buffers), "vkAllocateCommandBuffers", notes);
            var cmd = new VkCommandBuffer(buffers.get(0), vk);

            var begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer", notes);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                stack.longs(set), null);
            vkCmdDispatch(cmd, 1, 1, 1);
            check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer", notes);

            var fci = VkFenceCreateInfo.calloc(stack).sType$Default();
            check(vkCreateFence(vk, fci, null, handle), "vkCreateFence", notes);
            fence = handle[0];
            var submit = VkSubmitInfo.calloc(1, stack).sType$Default()
                .pCommandBuffers(stack.pointers(cmd));
            check(vkQueueSubmit(queue.vkQueue(), submit, fence), "vkQueueSubmit", notes);
            submitted = true;
            // 一度きりの診断なので、ここだけは明示的に待つ。MC の提出寿命には触らない。
            // ⚠ round-1 review B5: タイムアウト (VK_TIMEOUT) でも例外で finally に落ち、
            // <b>実行中のコマンドが参照しているまま</b>破棄していた。待てたかどうかを
            // 記録し、待てていなければ何も壊さない。
            int waited = vkWaitForFences(vk, stack.longs(fence), true, 5_000_000_000L);
            if (waited != VK_SUCCESS) {
                notes.add("vkWaitForFences -> VkResult " + waited
                    + "; the submission may still be running, so nothing is destroyed"
                    + " (resources are leaked on purpose)");
                throw new IllegalStateException("fence wait did not complete: " + waited);
            }
            retired = true;

            PointerBuffer mapped = stack.mallocPointer(1);
            check(vkMapMemory(vk, memory, 0, 8, 0, mapped), "vkMapMemory", notes);
            long value = MemoryUtil.memGetLong(mapped.get(0));
            vkUnmapMemory(vk, memory);
            readBack = hex(value);
            ok = value == EXPECTED;
            if (!ok) notes.add("the shader wrote " + readBack + ", expected " + hex(EXPECTED));
        } catch (Throwable t) {
            notes.add("int64 compute probe failed: " + t);
        } finally {
            // ⚠ 提出したのに退役を確認できていないときは<b>何も壊さない</b>。
            // 壊せば「実行中のコマンドが参照している」状態になる (round-1 review B5)。
            if (submitted && !retired) {
                notes.add("leaking the probe's Vulkan objects on purpose: the submission was"
                    + " never observed to complete");
                return new Result(true, false, hex(EXPECTED), readBack, queueFamily, List.copyOf(notes));
            }
            try {
                if (fence != 0) vkDestroyFence(vk, fence, null);
                if (commandPool != 0) vkDestroyCommandPool(vk, commandPool, null);
                if (pipeline != 0) vkDestroyPipeline(vk, pipeline, null);
                if (pipelineLayout != 0) vkDestroyPipelineLayout(vk, pipelineLayout, null);
                if (module != 0) vkDestroyShaderModule(vk, module, null);
                if (descriptorPool != 0) vkDestroyDescriptorPool(vk, descriptorPool, null);
                if (setLayout != 0) vkDestroyDescriptorSetLayout(vk, setLayout, null);
                if (buffer != 0) vkDestroyBuffer(vk, buffer, null);
                if (memory != 0) vkFreeMemory(vk, memory, null);
            } catch (Throwable t) {
                notes.add("cleanup after the int64 compute probe: " + t);
            }
        }
        return new Result(true, ok, hex(EXPECTED), readBack, queueFamily, List.copyOf(notes));
    }

    private static int hostVisibleMemoryType(VulkanDevice device, int typeBits,
                                             MemoryStack stack, List<String> notes) {
        try {
            VkPhysicalDevice physical = physicalOf(device, stack, notes);
            if (physical == null) return -1;
            var props = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physical, props);
            int wanted = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            for (int i = 0; i < props.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) == 0) continue;
                if ((props.memoryTypes(i).propertyFlags() & wanted) == wanted) return i;
            }
        } catch (Throwable t) {
            notes.add("memory type lookup: " + t);
        }
        return -1;
    }

    private static VkPhysicalDevice physicalOf(VulkanDevice device, MemoryStack stack, List<String> notes) {
        try {
            var instance = device.instance().vkInstance();
            IntBuffer count = stack.mallocInt(1);
            if (vkEnumeratePhysicalDevices(instance, count, null) != VK_SUCCESS || count.get(0) == 0) {
                notes.add("could not enumerate physical devices");
                return null;
            }
            PointerBuffer handles = stack.mallocPointer(count.get(0));
            if (vkEnumeratePhysicalDevices(instance, count, handles) != VK_SUCCESS) return null;
            // ⚠ round-2 review N2: 「最初の 1 つ」では複数 GPU で取り違える。
            // MC が報告する名前と一致するものだけを使い、無ければ測らない。
            String wanted = null;
            try { wanted = device.getDeviceInfo().name(); } catch (Throwable t) { notes.add("name(): " + t); }
            for (int i = 0; i < count.get(0); i++) {
                var candidate = new VkPhysicalDevice(handles.get(i), instance);
                var props = org.lwjgl.vulkan.VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(candidate, props);
                if (wanted != null && wanted.equals(props.deviceNameString())) return candidate;
            }
            notes.add("none of the " + count.get(0) + " physical devices is named "
                + wanted + "; not measuring against a guess");
            return null;
        } catch (Throwable t) {
            notes.add("physical device lookup: " + t);
            return null;
        }
    }

    private static void check(int result, String what, List<String> notes) {
        if (result != VK_SUCCESS) {
            notes.add(what + " -> VkResult " + result);
            throw new IllegalStateException(what + " -> VkResult " + result);
        }
    }

    private static String hex(long v) { return "0x" + Long.toHexString(v); }

    /** 証跡用の JSON。 */
    public static String json(Result r) {
        var sb = new StringBuilder("{\n");
        sb.append("  \"attempted\": ").append(r.attempted()).append(",\n");
        sb.append("  \"succeeded\": ").append(r.succeeded()).append(",\n");
        sb.append("  \"expected\": ").append(McNativeVulkanProbe.quote(r.expected())).append(",\n");
        sb.append("  \"readBack\": ").append(McNativeVulkanProbe.quote(r.readBack())).append(",\n");
        sb.append("  \"queueFamily\": ").append(r.queueFamily()).append(",\n");
        sb.append("  \"notes\": [");
        for (int i = 0; i < r.notes().size(); i++) {
            sb.append(i == 0 ? "\n    " : ",\n    ").append(McNativeVulkanProbe.quote(r.notes().get(i)));
        }
        sb.append(r.notes().isEmpty() ? "]\n}" : "\n  ]\n}");
        return sb.toString();
    }
}
