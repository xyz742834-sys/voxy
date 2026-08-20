import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.IntBuffer;
import java.util.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** MC 26.2 の REQUIRED_DEVICE_FEATURES 9 個が、この MoltenVK で true かの確認のみ。 */
public class FeatProbe {
    public static void main(String[] a) {
        System.out.println("lib = " + (System.getProperty("org.lwjgl.vulkan.libname") == null
            ? "(LWJGL bundled MoltenVK)" : System.getProperty("org.lwjgl.vulkan.libname")));
        try (MemoryStack s = stackPush()) {
            IntBuffer v = s.mallocInt(1);
            int instVer = VK11.vkEnumerateInstanceVersion(v) == VK_SUCCESS ? v.get(0) : VK_MAKE_VERSION(1,0,0);
            Set<String> instExt = new HashSet<>();
            vkEnumerateInstanceExtensionProperties((String) null, v, null);
            var ip = VkExtensionProperties.calloc(v.get(0), s);
            vkEnumerateInstanceExtensionProperties((String) null, v, ip);
            for (int i = 0; i < ip.capacity(); i++) instExt.add(ip.get(i).extensionNameString());

            var app = VkApplicationInfo.calloc(s).sType$Default()
                .pApplicationName(s.UTF8("featprobe")).apiVersion(Math.min(instVer, VK_MAKE_VERSION(1,2,0)));
            var ici = VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(app);
            if (instExt.contains("VK_KHR_portability_enumeration")) {
                ici.flags(0x1);
                ici.ppEnabledExtensionNames(s.pointers(s.UTF8("VK_KHR_portability_enumeration")));
            }
            PointerBuffer pp = s.mallocPointer(1);
            if (vkCreateInstance(ici, null, pp) != VK_SUCCESS) { System.out.println("no instance"); return; }
            var inst = new VkInstance(pp.get(0), ici);
            vkEnumeratePhysicalDevices(inst, v, null);
            var devs = s.mallocPointer(v.get(0));
            vkEnumeratePhysicalDevices(inst, v, devs);
            var phys = new VkPhysicalDevice(devs.get(0), inst);

            var f11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default();
            var f12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
            var sync2 = VkPhysicalDeviceSynchronization2Features.calloc(s).sType$Default();
            var dyn = VkPhysicalDeviceDynamicRenderingFeatures.calloc(s).sType$Default();
            var vad = VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT.calloc(s)
                .sType(org.lwjgl.vulkan.EXTVertexAttributeDivisor
                    .VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_FEATURES_EXT);
            f11.pNext(f12.address()); f12.pNext(sync2.address());
            sync2.pNext(dyn.address()); dyn.pNext(vad.address());
            var f2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(f11.address());
            VK11.vkGetPhysicalDeviceFeatures2(phys, f2);
            var base = f2.features();

            System.out.printf("  %-38s %s%n", "multiDrawIndirect", base.multiDrawIndirect());
            System.out.printf("  %-38s %s%n", "fillModeNonSolid", base.fillModeNonSolid());
            System.out.printf("  %-38s %s%n", "samplerAnisotropy", base.samplerAnisotropy());
            System.out.printf("  %-38s %s%n", "shaderDrawParameters", f11.shaderDrawParameters());
            System.out.printf("  %-38s %s%n", "timelineSemaphore", f12.timelineSemaphore());
            System.out.printf("  %-38s %s%n", "hostQueryReset", f12.hostQueryReset());
            System.out.printf("  %-38s %s%n", "synchronization2", sync2.synchronization2());
            System.out.printf("  %-38s %s%n", "dynamicRendering", dyn.dynamicRendering());
            System.out.printf("  %-38s %s%n", "vertexAttributeInstanceRateDivisor",
                vad.vertexAttributeInstanceRateDivisor());
            vkDestroyInstance(inst, null);
        }
    }
}
