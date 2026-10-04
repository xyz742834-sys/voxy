package me.cortex.voxy.vk;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import me.cortex.voxy.client.core.vk.mcnative.McNativeDeviceFeatures;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * {@link McNativeDeviceFeatures} が Minecraft の device 要求に足す機能を、
 * <b>オフセットの数値ではなく振る舞いで</b> 固定する。
 *
 * <p>最初の実装はオフセットを逆算で決め、16 バイトずれて別のフィールド
 * ({@code depthBounds} など) を要求し、実機で {@code VK_ERROR_FEATURE_NOT_PRESENT} を
 * 出して Minecraft の起動を止めた。したがってここで確かめるのは
 * 「足した feature に {@code set} させたとき、<b>狙ったフィールドだけ</b>が立つか」である。
 */
public class McNativeDeviceFeaturesTest {

    private static Set<VulkanFeature> augmentWithFlag() {
        String previous = System.getProperty(McNativeDeviceFeatures.FLAG);
        System.setProperty(McNativeDeviceFeatures.FLAG, "true");
        try {
            return McNativeDeviceFeatures.augment(VulkanBackend.REQUIRED_DEVICE_FEATURES);
        } finally {
            if (previous == null) System.clearProperty(McNativeDeviceFeatures.FLAG);
            else System.setProperty(McNativeDeviceFeatures.FLAG, previous);
        }
    }

    @Test
    void itAddsExactlyTheFeaturesMinecraftDoesNotRequest() {
        var augmented = augmentWithFlag();
        assertTrue(augmented.containsAll(VulkanBackend.REQUIRED_DEVICE_FEATURES),
            "Minecraft's own feature requests must be preserved");
        assertEquals(VulkanBackend.REQUIRED_DEVICE_FEATURES.size() + 4, augmented.size(),
            "expected exactly the four features Voxy needs and Minecraft does not request");
    }

    @Test
    void eachAddedFeatureSetsOnlyTheFieldItNames() {
        var augmented = augmentWithFlag();
        assertFeatureLands(augmented, "drawIndirectFirstInstance",
            VkPhysicalDeviceFeatures.DRAWINDIRECTFIRSTINSTANCE, f -> f.drawIndirectFirstInstance());
        assertFeatureLands(augmented, "shaderInt64",
            VkPhysicalDeviceFeatures.SHADERINT64, f -> f.shaderInt64());
        assertFeatureLands(augmented, "fragmentStoresAndAtomics",
            VkPhysicalDeviceFeatures.FRAGMENTSTORESANDATOMICS, f -> f.fragmentStoresAndAtomics());
        assertFeatureLands(augmented, "vertexPipelineStoresAndAtomics",
            VkPhysicalDeviceFeatures.VERTEXPIPELINESTORESANDATOMICS, f -> f.vertexPipelineStoresAndAtomics());
    }

    private static void assertFeatureLands(Set<VulkanFeature> augmented, String name, int lwjglOffset,
                                           Predicate<VkPhysicalDeviceFeatures> reader) {
        VulkanFeature target = null;
        for (VulkanFeature feature : augmented) {
            if (!VulkanBackend.REQUIRED_DEVICE_FEATURES.contains(feature) && name.equals(feature.name())) {
                target = feature;
                break;
            }
        }
        assertNotNull(target, name + " was not added to Minecraft's feature set");
        try (MemoryStack stack = stackPush()) {
            var probe = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            assertTrue(target.set(probe, true), name + ": set() reported no struct to write into");
            assertTrue(reader.test(probe.features()),
                () -> name + ": LWJGL does not see the field set (offset " + lwjglOffset + ")");
            int set = 0;
            for (int offset = 0; offset < VkPhysicalDeviceFeatures.SIZEOF; offset += 4) {
                if (org.lwjgl.system.MemoryUtil.memGetInt(
                        probe.address() + VkPhysicalDeviceFeatures2.FEATURES + offset) != 0) {
                    set++;
                }
            }
            assertEquals(1, set, name + ": writing it must set exactly one feature field");
        }
    }

    @Test
    void itAddsNothingWhenTheFlagIsUnset() {
        assertFalse(Boolean.getBoolean(McNativeDeviceFeatures.FLAG));
        var same = McNativeDeviceFeatures.augment(VulkanBackend.REQUIRED_DEVICE_FEATURES);
        assertSame(VulkanBackend.REQUIRED_DEVICE_FEATURES, same,
            "with the flag unset, Minecraft's device creation must be untouched");
    }
}
