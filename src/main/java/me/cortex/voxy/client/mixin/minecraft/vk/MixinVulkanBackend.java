package me.cortex.voxy.client.mixin.minecraft.vk;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import me.cortex.voxy.client.core.vk.mcnative.McNativeDeviceFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Set;

/**
 * <b>Minecraft が自分の Vulkan device に要求する機能へ、Voxy が要る分を足す</b>。
 *
 * <p>Voxy のシェーダは {@code shaderInt64} や
 * {@code fragmentStoresAndAtomics} などを使うが、Minecraft は自分が使う分しか
 * 有効化しない。したがって Voxy を<b>MC の device 上で</b>動かすには、
 * device 作成の瞬間に足すしかない [docs/ai/vulkan-native-integration-survey.md]。
 *
 * <p>⚠ ここは <b>Minecraft 自身のレンダラ初期化</b>である。したがって:
 *
 * <ul>
 *   <li>{@code -Dvoxy.native.features=true} が無ければ
 *       {@link McNativeDeviceFeatures#augment} は<b>渡された集合をそのまま返す</b> —
 *       通常プレイでは MC の device 作成は 1 バイトも変わらない</li>
 *   <li>{@code require = 0} にしてある。MC 側の内部メソッドが変わって注入点が消えても
 *       <b>ゲームを落とさない</b> (その場合 Voxy のネイティブ経路が使えないだけで、
 *       実際に有効化されたかは native-feature-audit.json に出る)</li>
 * </ul>
 */
@Mixin(VulkanBackend.class)
public class MixinVulkanBackend {

    @ModifyArg(
        method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"),
        index = 2,
        require = 0)
    private static Set<VulkanFeature> voxy$addRequiredFeatures(Set<VulkanFeature> requested) {
        return McNativeDeviceFeatures.augment(requested);
    }
}
