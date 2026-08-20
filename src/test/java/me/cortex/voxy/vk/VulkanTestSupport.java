package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.VkContext;
import org.junit.jupiter.api.Assumptions;

/**
 * Vulkan テストの共通土台。
 *
 * <p>{@link VkContext} はサーフェスもウィンドウも持たないため Minecraft の起動を必要としないが、
 * MoltenVK の natives がある環境でしか初期化できない。動かない環境では
 * <b>失敗ではなく skip</b> にする (CI が Linux/Windows でも赤くならないように)。
 */
public final class VulkanTestSupport {
    private static Boolean available;
    private static String failure;

    private VulkanTestSupport() {}

    /** VkContext が初期化できなければテストを skip する。 */
    public static void requireVulkan() {
        if (available == null) {
            try {
                VkContext.init();
                available = true;
            } catch (Throwable t) {
                available = false;
                failure = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
        Assumptions.assumeTrue(available,
            () -> "Vulkan/MoltenVK unavailable on " + System.getProperty("os.name")
                + "/" + System.getProperty("os.arch") + " -> " + failure);
    }
}
