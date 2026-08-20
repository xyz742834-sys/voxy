import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.IntBuffer;
import java.util.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** MC 26.2 の Vulkan バックエンドが要求する拡張が、この MoltenVK にあるかの列挙のみ。 */
public class ExtProbe {
    static final String[] WANTED = {
        "VK_KHR_swapchain", "VK_KHR_dynamic_rendering", "VK_KHR_push_descriptor",
        "VK_KHR_synchronization2", "VK_KHR_portability_subset",
        "VK_EXT_multi_draw", "VK_EXT_vertex_attribute_divisor",
        "VK_NV_device_diagnostic_checkpoints", "VK_AMD_buffer_marker",
    };
    public static void main(String[] a) {
        System.out.println("lib = " + (System.getProperty("org.lwjgl.vulkan.libname") == null
            ? "(LWJGL bundled MoltenVK)" : System.getProperty("org.lwjgl.vulkan.libname")));
        try (MemoryStack s = stackPush()) {
            IntBuffer v = s.mallocInt(1);
            int instVer = VK11.vkEnumerateInstanceVersion(v) == VK_SUCCESS ? v.get(0) : VK_MAKE_VERSION(1,0,0);
            System.out.println("instance api = " + VK_VERSION_MAJOR(instVer) + "." + VK_VERSION_MINOR(instVer)
                + "." + VK_VERSION_PATCH(instVer));

            Set<String> instExt = new HashSet<>();
            vkEnumerateInstanceExtensionProperties((String) null, v, null);
            var ip = VkExtensionProperties.calloc(v.get(0), s);
            vkEnumerateInstanceExtensionProperties((String) null, v, ip);
            for (int i = 0; i < ip.capacity(); i++) instExt.add(ip.get(i).extensionNameString());

            var app = VkApplicationInfo.calloc(s).sType$Default()
                .pApplicationName(s.UTF8("extprobe")).apiVersion(Math.min(instVer, VK_MAKE_VERSION(1,2,0)));
            var ici = VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(app);
            if (instExt.contains("VK_KHR_portability_enumeration")) {
                ici.flags(0x1);
                ici.ppEnabledExtensionNames(s.pointers(s.UTF8("VK_KHR_portability_enumeration")));
            }
            PointerBuffer pp = s.mallocPointer(1);
            int r = vkCreateInstance(ici, null, pp);
            if (r != VK_SUCCESS) { System.out.println("vkCreateInstance -> " + r); return; }
            var inst = new VkInstance(pp.get(0), ici);

            vkEnumeratePhysicalDevices(inst, v, null);
            var devs = s.mallocPointer(v.get(0));
            vkEnumeratePhysicalDevices(inst, v, devs);
            var phys = new VkPhysicalDevice(devs.get(0), inst);

            var props = VkPhysicalDeviceProperties.calloc(s);
            vkGetPhysicalDeviceProperties(phys, props);
            System.out.println("device = " + props.deviceNameString()
                + "  api=" + VK_VERSION_MAJOR(props.apiVersion()) + "." + VK_VERSION_MINOR(props.apiVersion())
                + "." + VK_VERSION_PATCH(props.apiVersion()));

            Set<String> devExt = new TreeSet<>();
            vkEnumerateDeviceExtensionProperties(phys, (String) null, v, null);
            var dp = VkExtensionProperties.calloc(v.get(0), s);
            vkEnumerateDeviceExtensionProperties(phys, (String) null, v, dp);
            for (int i = 0; i < dp.capacity(); i++) devExt.add(dp.get(i).extensionNameString());
            System.out.println("device extensions = " + devExt.size());
            for (String w : WANTED) System.out.printf("  %-40s %s%n", w, devExt.contains(w) ? "YES" : "no");
            vkDestroyInstance(inst, null);
        }
    }
}
