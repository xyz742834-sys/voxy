package me.cortex.voxy.client.core.vk.mcnative;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Minecraft 自身の Vulkan バックエンドへ到達するための最小の窓</b>。
 *
 * <p>公開 API では届かない 2 箇所だけをここで扱う:
 *
 * <ul>
 *   <li>{@link GpuDevice} が包んでいる {@link GpuDeviceBackend} — private フィールドで取得子が無い
 *       ({@code CommandEncoder.backend()} も protected)</li>
 *   <li>{@link RenderPass} が包んでいるバックエンドの {@code VkCommandBuffer} —
 *       {@code VulkanRenderPass.commandBuffer} / {@code commandBuffer()} はどちらも private</li>
 * </ul>
 *
 * <p>どちらも<b>フィールド名ではなく型で</b>探す。名前はマッピングで変わるが型は変わらないので、
 * mixin も refmap も要らず、マッピング更新で静かに壊れることもない。見つからなければ null を
 * 返し、理由を呼び出し側の {@code notes} に残す — <b>例外は投げない</b>。
 *
 * <p>Sodium も同じ場所に手を伸ばしている ({@code VulkanRenderPassAccessor.sodium$getCommandBuffer})
 * が、他 mod の mixin インターフェースに依存したくないのでここでは自前で取る
 * [docs/ai/vulkan-native-integration-survey.md]。
 */
public final class McNativeVulkan {
    private McNativeVulkan() {}

    /** 一度見つけた経路は覚える。毎フレーム触るので、反射の走査は最初の一回だけにする。 */
    private static Field cachedBackendField;
    private static Field cachedPassBackendField;
    private static Field cachedCommandBufferField;

    /**
     * MC が今 Vulkan バックエンドで動いていれば、その {@link VulkanDevice}。
     * GL バックエンドでも device 未初期化でも null を返す。
     */
    public static VulkanDevice device(List<String> notes) {
        GpuDevice device;
        try {
            device = RenderSystem.tryGetDevice();
        } catch (Throwable t) {
            notes.add("RenderSystem.tryGetDevice() threw " + t);
            return null;
        }
        if (device == null) {
            notes.add("no GpuDevice yet");
            return null;
        }
        GpuDeviceBackend backend = backendOf(device, notes);
        if (backend instanceof VulkanDevice vk) return vk;
        if (backend != null) notes.add("the active backend is " + backend.getClass().getName());
        return null;
    }

    /** 便利版。理由を捨てる。 */
    public static VulkanDevice device() { return device(new ArrayList<>()); }

    /**
     * MC の<b>実行中の</b>コマンドエンコーダ。
     * {@code VulkanDevice.createCommandEncoder()} は名前に反して<b>生成せず</b>、
     * device が持っている 1 つを返す (確認済 — 当該メソッドは {@code commandEncoder}
     * フィールドをそのまま返すだけ) ので、MC が今使っているものと同一である。
     */
    public static VulkanCommandEncoder encoder(VulkanDevice device) {
        return device.createCommandEncoder();
    }

    /** {@link GpuDevice} が包んでいる実装。{@code GpuDeviceBackend} 型のフィールドを探す。 */
    public static GpuDeviceBackend backendOf(GpuDevice device, List<String> notes) {
        if (device instanceof GpuDeviceBackend self) return self;   // 将来、包まなくなったとき
        Field cached = cachedBackendField;
        if (cached != null) {
            Object value = read(cached, device, notes);
            if (value instanceof GpuDeviceBackend backend) return backend;
            cachedBackendField = null;
        }
        Field found = fieldOfType(device.getClass(), GpuDeviceBackend.class);
        if (found == null) {
            notes.add("no GpuDeviceBackend-typed field on " + device.getClass().getName()
                + " (the wrapper's shape changed?)");
            return null;
        }
        Object value = read(found, device, notes);
        if (value instanceof GpuDeviceBackend backend) {
            cachedBackendField = found;
            return backend;
        }
        return null;
    }

    /**
     * <b>MC が今開いているレンダーパスのコマンドバッファ</b>。
     *
     * <p>ここへ raw な {@code vkCmd*} を積めば、MC 自身のレンダーパス内 —
     * つまり MC の色/深度がバインドされ、レイアウト遷移も MC が済ませた状態 — で描ける。
     * 返り値が null のときは「取れなかった」のであって「描いてよい」ではない。
     */
    public static VkCommandBuffer commandBufferOf(RenderPass pass, List<String> notes) {
        if (pass == null) {
            notes.add("no render pass to record into");
            return null;
        }
        Object backend = pass;
        Field passBackend = cachedPassBackendField != null ? cachedPassBackendField
            : fieldOfType(pass.getClass(), com.mojang.blaze3d.systems.RenderPassBackend.class);
        if (passBackend == null) {
            notes.add("no RenderPassBackend-typed field on " + pass.getClass().getName());
            return null;
        }
        Object value = read(passBackend, pass, notes);
        if (value == null) return null;
        cachedPassBackendField = passBackend;
        backend = value;

        Field cmdField = cachedCommandBufferField != null ? cachedCommandBufferField
            : fieldOfType(backend.getClass(), VkCommandBuffer.class);
        if (cmdField == null) {
            notes.add("no VkCommandBuffer-typed field on " + backend.getClass().getName()
                + " (not a Vulkan render pass?)");
            return null;
        }
        Object cmd = read(cmdField, backend, notes);
        if (cmd instanceof VkCommandBuffer buffer) {
            cachedCommandBufferField = cmdField;
            return buffer;
        }
        return null;
    }

    private static Field fieldOfType(Class<?> owner, Class<?> type) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) return f;
            }
        }
        return null;
    }

    private static Object read(Field f, Object owner, List<String> notes) {
        try {
            f.setAccessible(true);
            return f.get(owner);
        } catch (Throwable t) {
            notes.add("could not read " + f.getDeclaringClass().getSimpleName() + "." + f.getName() + ": " + t);
            return null;
        }
    }
}
