package me.cortex.voxy.client.core.vk;

import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * <b>synchronization2 / dynamic_rendering の呼び出しを、device が実際に持っている形で出す</b>。
 *
 * <p>どちらも Vulkan 1.3 でコアに入ったが、<b>1.2 の device に KHR 拡張として載っていることもある</b>。
 * その 2 つは機能的に同じでも<b>別の関数ポインタ</b>で、コア側は device が 1.3 でなければ
 * {@code null} のままである。Voxy はコア側を直接呼んでいたので、
 * <b>Minecraft の device (1.2 + {@code VK_KHR_synchronization2} +
 * {@code VK_KHR_dynamic_rendering}) に載せた瞬間に LWJGL の null チェックで落ちた</b>
 * — {@code Checks.check} が {@code NullPointerException} を投げる
 * [docs/ai/vulkan-native-integration-survey.md で実測]。
 *
 * <p>そこで呼ぶ直前に<b>そのコマンドバッファの device 能力</b>を見て振り分ける。
 * 構造体 ({@code VkDependencyInfo} / {@code VkRenderingInfo} / {@code sType}) は
 * コアと KHR で同一なので、渡すものは変わらない。
 *
 * <p>自前 device (Voxy が 1.4 を要求して作る) では常にコア側が選ばれるので、
 * 既存の挙動は変わらない。
 */
public final class VkCmd {
    private VkCmd() {}

    /** {@code vkCmdPipelineBarrier2} / {@code ...2KHR}。 */
    public static void pipelineBarrier2(VkCommandBuffer cmd, VkDependencyInfo dependency) {
        if (cmd.getCapabilities().vkCmdPipelineBarrier2 != NULL) {
            VK13.vkCmdPipelineBarrier2(cmd, dependency);
        } else {
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dependency);
        }
    }

    /** {@code vkCmdBeginRendering} / {@code ...KHR}。 */
    public static void beginRendering(VkCommandBuffer cmd, VkRenderingInfo rendering) {
        if (cmd.getCapabilities().vkCmdBeginRendering != NULL) {
            VK13.vkCmdBeginRendering(cmd, rendering);
        } else {
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, rendering);
        }
    }

    /** {@code vkCmdEndRendering} / {@code ...KHR}。 */
    public static void endRendering(VkCommandBuffer cmd) {
        if (cmd.getCapabilities().vkCmdEndRendering != NULL) {
            VK13.vkCmdEndRendering(cmd);
        } else {
            KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        }
    }

    /**
     * 診断用: この device がコア 1.3 の入口を持っているか。
     * false なら KHR 拡張経由で動いている (Minecraft の device がこちら)。
     */
    public static boolean hasCoreEntryPoints(VkCommandBuffer cmd) {
        var caps = cmd.getCapabilities();
        return caps.vkCmdPipelineBarrier2 != NULL
            && caps.vkCmdBeginRendering != NULL
            && caps.vkCmdEndRendering != NULL;
    }
}
