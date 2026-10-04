package me.cortex.voxy.client.core.vk.mcnative;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

/**
 * <b>Voxy が要求する device 機能を、MC の device が持っているかを突き合わせる</b>。
 *
 * <p>Voxy のレンダラを MC のデバイス上へ移すには、{@code VkContext} が
 * {@code vkCreateDevice} で要求している機能が MC の device でも有効でなければならない。
 * MC は自分が必要な分しか有効化しないので、ここは<b>推測ではなく実測</b>で決める。
 * 1 機能ごとに 2 つの別の問いを出す:
 *
 * <ul>
 *   <li><b>supported</b> — 物理デバイス (MoltenVK) がその機能を提供しているか。
 *       {@code vkGetPhysicalDeviceFeatures2} で直接問う</li>
 *   <li><b>enabledByMinecraft</b> — MC が device 作成時に有効化したか。
 *       {@code VulkanBackend.REQUIRED_DEVICE_FEATURES} の名前集合から読む
 *       (有効済み機能を問う Vulkan API は無いので、MC の宣言が唯一の出典)</li>
 * </ul>
 *
 * <p>supported かつ enabled でない機能は、<b>MC の device 作成に手を入れれば届く</b>もので、
 * supported でない機能は<b>シェーダ側を変えるしかない</b>もの。この区別が移植の計画を決める。
 *
 * <p>⚠ 何も作らず、何も変えない。例外も投げない。
 */
public final class McNativeFeatureAudit {
    private McNativeFeatureAudit() {}

    /**
     * {@code VkContext} が {@code vkCreateDevice} に渡している機能
     * (同クラスの {@code VkPhysicalDeviceFeatures} / {@code Vulkan11Features} /
     * {@code Vulkan13Features} の設定をそのまま写したもの)。
     * ここを増やしたら {@code VkContext} 側と必ず揃えること。
     */
    public static final List<String> VOXY_REQUIRES = List.of(
        "multiDrawIndirect", "drawIndirectFirstInstance", "shaderInt64",
        "fragmentStoresAndAtomics", "vertexPipelineStoresAndAtomics",
        "shaderDrawParameters", "synchronization2", "dynamicRendering");

    /** 1 機能ぶんの答え。 */
    public record Feature(String name, boolean supported, boolean enabledByMinecraft) {}

    /**
     * @param features       Voxy が要求する各機能の状態
     * @param missingEnabled supported だが MC が有効化していないもの (device 作成に手を入れれば届く)
     * @param unsupported    物理デバイスが提供していないもの (シェーダ側を変えるしかない)
     */
    public record Report(String deviceName, boolean zZeroToOne, int graphicsQueueFamily,
                         int computeQueueFamily, int transferQueueFamily,
                         List<Feature> features, List<String> missingEnabled, List<String> unsupported,
                         Set<String> minecraftEnabledFeatureNames, Set<String> underlyingExtensions,
                         List<String> notes) {}

    public static Report audit(VulkanDevice device) {
        var notes = new ArrayList<String>();
        Set<String> enabledNames = minecraftEnabledFeatureNames(notes);
        Map<String, Boolean> supported = new LinkedHashMap<>();
        String deviceName = null;
        boolean zZeroToOne = false;
        int gq = -1, cq = -1, tq = -1;
        Set<String> extensions = new TreeSet<>();

        try {
            var info = device.getDeviceInfo();
            deviceName = info.name();
            zZeroToOne = info.isZZeroToOne();
            extensions.addAll(info.underlyingExtensions());
            // MC 自身が公開している機能表。物理デバイスへの問い合わせと独立した第二の出典。
            var f = info.features();
            noteFlag(notes, "DeviceInfo.features.shaderDrawParameters", f.shaderDrawParameters());
            noteFlag(notes, "DeviceInfo.features.multiDrawIndirect", f.multiDrawIndirect());
            noteFlag(notes, "DeviceInfo.features.nonZeroFirstInstance", f.nonZeroFirstInstance());
            noteFlag(notes, "DeviceInfo.features.drawIndirect", f.drawIndirect());
        } catch (Throwable t) {
            notes.add("getDeviceInfo(): " + t);
        }
        try {
            gq = device.graphicsQueue().queueFamilyIndex();
            cq = device.computeQueue().queueFamilyIndex();
            tq = device.transferQueue().queueFamilyIndex();
        } catch (Throwable t) {
            notes.add("queue families: " + t);
        }

        try (MemoryStack stack = stackPush()) {
            VkPhysicalDevice physical = physicalDeviceOf(device, deviceName, stack, notes);
            if (physical != null) {
                var features11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();
                var features12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
                var features13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
                features11.pNext(features12.address());
                features12.pNext(features13.address());
                var all = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default()
                    .pNext(features11.address());
                vkGetPhysicalDeviceFeatures2(physical, all);
                var base = all.features();
                supported.put("multiDrawIndirect", base.multiDrawIndirect());
                supported.put("drawIndirectFirstInstance", base.drawIndirectFirstInstance());
                supported.put("shaderInt64", base.shaderInt64());
                supported.put("fragmentStoresAndAtomics", base.fragmentStoresAndAtomics());
                supported.put("vertexPipelineStoresAndAtomics", base.vertexPipelineStoresAndAtomics());
                supported.put("shaderDrawParameters", features11.shaderDrawParameters());
                supported.put("synchronization2", features13.synchronization2());
                supported.put("dynamicRendering", features13.dynamicRendering());
            }
        } catch (Throwable t) {
            notes.add("vkGetPhysicalDeviceFeatures2: " + t);
        }

        var features = new ArrayList<Feature>();
        var missingEnabled = new ArrayList<String>();
        var unsupported = new ArrayList<String>();
        for (String name : VOXY_REQUIRES) {
            Boolean isSupported = supported.get(name);
            boolean enabled = enabledNames.contains(name);
            features.add(new Feature(name, Boolean.TRUE.equals(isSupported), enabled));
            if (isSupported == null) {
                notes.add(name + ": support could not be queried");
                continue;
            }
            if (!isSupported) unsupported.add(name);
            else if (!enabled) missingEnabled.add(name);
        }
        return new Report(deviceName, zZeroToOne, gq, cq, tq, List.copyOf(features),
            List.copyOf(missingEnabled), List.copyOf(unsupported),
            Set.copyOf(enabledNames), Set.copyOf(extensions), List.copyOf(notes));
    }

    /**
     * MC が device 作成時に有効化した機能名。
     * {@code VulkanBackend.REQUIRED_DEVICE_FEATURES} は {@code VulkanFeature} の集合で、
     * {@code VulkanFeature} は {@code name} を持つ record なので、そこから読む
     * (有効済み機能を問う Vulkan API は存在しない)。
     */
    private static Set<String> minecraftEnabledFeatureNames(List<String> notes) {
        var names = new TreeSet<String>();
        try {
            for (Object feature : com.mojang.blaze3d.vulkan.VulkanBackend.REQUIRED_DEVICE_FEATURES) {
                String name = featureName(feature);
                if (name != null) names.add(name);
            }
        } catch (Throwable t) {
            notes.add("VulkanBackend.REQUIRED_DEVICE_FEATURES: " + t);
        }
        return names;
    }

    private static String featureName(Object feature) {
        if (feature == null) return null;
        // VulkanFeature は record なので name() が生えている。
        // 名前が変わっても壊れないよう、String 型のアクセサを総当たりで試す。
        for (var method : feature.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || method.getReturnType() != String.class) continue;
            if (method.getName().equals("toString")) continue;
            try {
                method.setAccessible(true);
                Object value = method.invoke(feature);
                if (value instanceof String s && !s.isBlank()) return s;
            } catch (Throwable ignored) {
                // 次の候補を試す
            }
        }
        return null;
    }

    /**
     * MC の device の物理デバイス。{@link VulkanDevice} は保持していないので、
     * インスタンスから列挙して名前で突き合わせる (単一 GPU なら 1 つしか無い)。
     */
    private static VkPhysicalDevice physicalDeviceOf(VulkanDevice device, String deviceName,
                                                     MemoryStack stack, List<String> notes) {
        try {
            var instance = device.instance().vkInstance();
            IntBuffer count = stack.mallocInt(1);
            if (vkEnumeratePhysicalDevices(instance, count, null) != VK_SUCCESS || count.get(0) == 0) {
                notes.add("could not enumerate Minecraft's physical devices");
                return null;
            }
            PointerBuffer handles = stack.mallocPointer(count.get(0));
            if (vkEnumeratePhysicalDevices(instance, count, handles) != VK_SUCCESS) {
                notes.add("could not enumerate Minecraft's physical devices (second call)");
                return null;
            }
            VkPhysicalDevice first = null;
            for (int i = 0; i < count.get(0); i++) {
                var candidate = new VkPhysicalDevice(handles.get(i), instance);
                if (first == null) first = candidate;
                var props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(candidate, props);
                if (deviceName != null && deviceName.equals(props.deviceNameString())) return candidate;
            }
            if (count.get(0) > 1) {
                notes.add("could not match " + deviceName + " among " + count.get(0)
                    + " physical devices; using the first");
            }
            return first;
        } catch (Throwable t) {
            notes.add("physical device lookup: " + t);
            return null;
        }
    }

    private static void noteFlag(List<String> notes, String label, boolean value) {
        notes.add(label + "=" + value);
    }

    /** 証跡用の JSON。 */
    public static String json(Report r) {
        var sb = new StringBuilder("{\n");
        sb.append("  \"deviceName\": ").append(McNativeVulkanProbe.quote(r.deviceName())).append(",\n");
        sb.append("  \"zZeroToOne\": ").append(r.zZeroToOne()).append(",\n");
        sb.append("  \"queueFamilies\": {\"graphics\": ").append(r.graphicsQueueFamily())
            .append(", \"compute\": ").append(r.computeQueueFamily())
            .append(", \"transfer\": ").append(r.transferQueueFamily()).append("},\n");
        sb.append("  \"features\": [");
        for (int i = 0; i < r.features().size(); i++) {
            var f = r.features().get(i);
            sb.append(i == 0 ? "\n    " : ",\n    ")
                .append("{\"name\": ").append(McNativeVulkanProbe.quote(f.name()))
                .append(", \"supported\": ").append(f.supported())
                .append(", \"enabledByMinecraft\": ").append(f.enabledByMinecraft()).append("}");
        }
        sb.append(r.features().isEmpty() ? "]," : "\n  ],").append("\n");
        sb.append("  \"supportedButNotEnabledByMinecraft\": ").append(strings(r.missingEnabled())).append(",\n");
        sb.append("  \"unsupportedByTheDevice\": ").append(strings(r.unsupported())).append(",\n");
        sb.append("  \"minecraftEnabledFeatureNames\": ").append(strings(new ArrayList<>(r.minecraftEnabledFeatureNames()))).append(",\n");
        sb.append("  \"underlyingExtensions\": ").append(strings(new ArrayList<>(r.underlyingExtensions()))).append(",\n");
        sb.append("  \"notes\": ").append(strings(r.notes())).append("\n}");
        return sb.toString();
    }

    private static String strings(List<String> values) {
        if (values.isEmpty()) return "[]";
        var sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(McNativeVulkanProbe.quote(values.get(i)));
        }
        return sb.append("]").toString();
    }
}
