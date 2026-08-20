package me.cortex.voxy.vk;

import org.junit.jupiter.api.Test;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkLayerProperties;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * LWJGL 同梱の MoltenVK でバリデーションレイヤが使えるかの確認。
 *
 * <p>これは環境調査であり、常に PASS する。結果は標準出力に出す。
 */
public class ValidationLayerProbeTest {

    @Test
    void enumerateInstanceLayersAndExtensions() {
        List<String> layers = new ArrayList<>();
        List<String> extensions = new ArrayList<>();

        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);

            int r = vkEnumerateInstanceLayerProperties(count, null);
            if (r == VK_SUCCESS && count.get(0) > 0) {
                var props = VkLayerProperties.calloc(count.get(0), stack);
                vkEnumerateInstanceLayerProperties(count, props);
                for (int i = 0; i < props.capacity(); i++) {
                    layers.add(props.get(i).layerNameString()
                        + "  (spec " + verStr(props.get(i).specVersion()) + ")");
                }
            }

            r = vkEnumerateInstanceExtensionProperties((String) null, count, null);
            if (r == VK_SUCCESS && count.get(0) > 0) {
                var props = VkExtensionProperties.calloc(count.get(0), stack);
                vkEnumerateInstanceExtensionProperties((String) null, count, props);
                for (int i = 0; i < props.capacity(); i++) {
                    extensions.add(props.get(i).extensionNameString());
                }
            }
        }

        System.out.println("=== instance layers (" + layers.size() + ") ===");
        layers.forEach(l -> System.out.println("  " + l));
        System.out.println("=== instance extensions (" + extensions.size() + ") ===");
        extensions.forEach(e -> System.out.println("  " + e));

        boolean hasValidation = layers.stream().anyMatch(l -> l.startsWith("VK_LAYER_KHRONOS_validation"));
        boolean hasDebugUtils = extensions.contains("VK_EXT_debug_utils");
        System.out.println("VK_LAYER_KHRONOS_validation : " + (hasValidation ? "AVAILABLE" : "NOT AVAILABLE"));
        System.out.println("VK_EXT_debug_utils          : " + (hasDebugUtils ? "AVAILABLE" : "NOT AVAILABLE"));
        System.out.println("VK_ICD_FILENAMES            : " + System.getenv("VK_ICD_FILENAMES"));
        System.out.println("VK_LAYER_PATH               : " + System.getenv("VK_LAYER_PATH"));
        System.out.println("VULKAN_SDK                  : " + System.getenv("VULKAN_SDK"));
    }

    /** ローダー経由にしたときに VkContext が初期化できるかを確認する。 */
    @Test
    void reportVkContextInitOutcome() {
        System.out.println("=== VkContext.init() outcome ===");
        System.out.println("  org.lwjgl.vulkan.libname = " + System.getProperty("org.lwjgl.vulkan.libname"));
        try {
            me.cortex.voxy.client.core.vk.VkContext.init();
            var ctx = me.cortex.voxy.client.core.vk.VkContext.get();
            System.out.println("  OK  queueFamily=" + ctx.queueFamily);
        } catch (Throwable t) {
            System.out.println("  FAILED: " + t.getClass().getName() + ": " + t.getMessage());
        }

        // 物理デバイスが列挙できるかを素の API で確認 (portability の影響を見る)
        try (MemoryStack stack = stackPush()) {
            var app = org.lwjgl.vulkan.VkApplicationInfo.calloc(stack)
                .sType$Default().apiVersion(VK_MAKE_VERSION(1, 2, 0));
            var ici = org.lwjgl.vulkan.VkInstanceCreateInfo.calloc(stack)
                .sType$Default().pApplicationInfo(app);
            PointerBuffer pp = stack.mallocPointer(1);
            int r = vkCreateInstance(ici, null, pp);
            System.out.println("  plain vkCreateInstance -> VkResult " + r);
            if (r == VK_SUCCESS) {
                var inst = new org.lwjgl.vulkan.VkInstance(pp.get(0), ici);
                IntBuffer c = stack.mallocInt(1);
                vkEnumeratePhysicalDevices(inst, c, null);
                System.out.println("  physical devices = " + c.get(0));
                vkDestroyInstance(inst, null);
            }
        }
    }

    /** どの MoltenVK を使っているか / sync2 が native にあるかの確認。 */
    @Test
    void reportDriverIdentity() {
        VulkanTestSupport.requireVulkan();
        var ctx = me.cortex.voxy.client.core.vk.VkContext.get();
        System.out.println("=== driver identity ===");
        System.out.println("  " + ctx.driverIdentity());
        System.out.println("  loaded from : " + me.cortex.voxy.client.core.vk.VkContext.vulkanLibraryPath());
        System.out.println("  validation  : " + ctx.validationEnabled);
        System.out.println("  syncValidation : " + ctx.syncValidationEnabled);

        try (MemoryStack stack = stackPush()) {
            IntBuffer c = stack.mallocInt(1);
            vkEnumerateDeviceExtensionProperties(ctx.physical, (String) null, c, null);
            var props = VkExtensionProperties.calloc(c.get(0), stack);
            vkEnumerateDeviceExtensionProperties(ctx.physical, (String) null, c, props);
            boolean sync2 = false, portabilitySubset = false;
            for (int i = 0; i < props.capacity(); i++) {
                String n = props.get(i).extensionNameString();
                if (n.equals("VK_KHR_synchronization2")) sync2 = true;
                if (n.equals("VK_KHR_portability_subset")) portabilitySubset = true;
            }
            System.out.println("  VK_KHR_synchronization2   : " + (sync2 ? "NATIVE" : "absent"));
            System.out.println("  VK_KHR_portability_subset : " + (portabilitySubset ? "present" : "absent"));
        }
    }

    /**
     * レイヤが提供する instance 拡張 (レイヤ名を指定しないと出てこない)。
     *
     * <p>⚠ <b>レイヤ名での問い合わせは、そのレイヤが実在するときしか行ってはならない。</b>
     * LWJGL 同梱の MoltenVK はローダーを介さないため explicit layer の概念が無く、
     * 存在しないレイヤ名を渡すと <b>libMoltenVK 内で SIGSEGV になり JVM ごと落ちる</b>
     * [確認済 — 素の {@code ./gradlew test} で再現]。
     * レイヤ一覧に載っていることを先に確かめる。
     */
    @Test
    void enumerateLayerProvidedExtensions() {
        System.out.println("=== layer-provided extensions (VK_LAYER_KHRONOS_validation) ===");
        if (!instanceLayers().contains("VK_LAYER_KHRONOS_validation")) {
            System.out.println("  (layer absent; not querying it -- the bundled MoltenVK "
                + "crashes on a layer name it does not know)");
            return;
        }
        try (MemoryStack stack = stackPush()) {
            IntBuffer c = stack.mallocInt(1);
            int r = vkEnumerateInstanceExtensionProperties("VK_LAYER_KHRONOS_validation", c, null);
            if (r != VK_SUCCESS || c.get(0) == 0) { System.out.println("  (none / layer absent)"); return; }
            var props = VkExtensionProperties.calloc(c.get(0), stack);
            vkEnumerateInstanceExtensionProperties("VK_LAYER_KHRONOS_validation", c, props);
            for (int i = 0; i < props.capacity(); i++) System.out.println("  " + props.get(i).extensionNameString());
        }
    }

    private static java.util.Set<String> instanceLayers() {
        var out = new java.util.HashSet<String>();
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            if (vkEnumerateInstanceLayerProperties(count, null) != VK_SUCCESS || count.get(0) == 0) {
                return out;
            }
            var props = VkLayerProperties.calloc(count.get(0), stack);
            vkEnumerateInstanceLayerProperties(count, props);
            for (int i = 0; i < props.capacity(); i++) out.add(props.get(i).layerNameString());
        }
        return out;
    }

    private static String verStr(int v) {
        return VK_VERSION_MAJOR(v) + "." + VK_VERSION_MINOR(v) + "." + VK_VERSION_PATCH(v);
    }
}
