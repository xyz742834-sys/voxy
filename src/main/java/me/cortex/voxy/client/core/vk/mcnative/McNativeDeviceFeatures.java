package me.cortex.voxy.client.core.vk.mcnative;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * <b>Minecraft が device 作成時に要求する機能集合へ、Voxy が要る分を足す</b>。
 *
 * <p>実測 (docs/ai/vulkan-native-integration-survey.md): MoltenVK は Voxy が要求する
 * 8 機能すべてに対応しているが、Minecraft は自分が使う分しか有効化しないため、
 * {@code drawIndirectFirstInstance} / {@code shaderInt64} /
 * {@code fragmentStoresAndAtomics} / {@code vertexPipelineStoresAndAtomics} の 4 つが
 * 無効のままになる。Voxy のシェーダはこれらを使うので、MC の device 上で動かすには
 * device 作成時に足す以外の方法が無い。
 *
 * <p>⚠ <b>オフセットは推測も逆算もしない — 実験で決める</b>。{@link VulkanFeature} は
 * {@code (pNext 構造体, 名前, オフセット)} の record だが、そのオフセットが
 * {@code VkPhysicalDeviceFeatures2} の先頭基準なのか {@code features} メンバ基準なのかは
 * 実装詳細で、MC 自身の値からはどちらにも読める (逆算で決めた最初の実装は、
 * 実機で {@code VK_ERROR_FEATURE_NOT_PRESENT} を出して Minecraft の起動を止めた —
 * 16 バイトずれて別のフィールドを要求していた)。そこで候補オフセットに<b>実際に書き込み</b>、
 * LWJGL のアクセサで「狙った 1 フィールドだけが立ったか」を確かめてから採用する。
 * 確かめられなければ<b>その機能は足さない</b> (fail closed)。
 *
 * <p>⚠ 既定で無効。{@code -Dvoxy.native.features=true} のときだけ足す。
 * Minecraft 自身の device 作成に手を入れる以上、ネイティブ経路を試す人だけが
 * 影響を受けるようにしてある。
 */
public final class McNativeDeviceFeatures {
    private McNativeDeviceFeatures() {}

    /** これを true にしたときだけ MC の device 要求に足す。 */
    public static final String FLAG = "voxy.native.features";

    /**
     * Voxy が要る VK10 機能のうち、MC が有効化しないもの。
     * 値は LWJGL が生成した {@code VkPhysicalDeviceFeatures} 内のバイトオフセット。
     */
    private static final Map<String, Integer> WANTED = new LinkedHashMap<>();
    static {
        WANTED.put("drawIndirectFirstInstance", VkPhysicalDeviceFeatures.DRAWINDIRECTFIRSTINSTANCE);
        WANTED.put("shaderInt64", VkPhysicalDeviceFeatures.SHADERINT64);
        WANTED.put("fragmentStoresAndAtomics", VkPhysicalDeviceFeatures.FRAGMENTSTORESANDATOMICS);
        WANTED.put("vertexPipelineStoresAndAtomics", VkPhysicalDeviceFeatures.VERTEXPIPELINESTORESANDATOMICS);
    }

    /** VK10 機能の pNext 構造体を借りるために使う、MC 側の VK10 機能の名前。 */
    private static final String CALIBRATION = "multiDrawIndirect";

    private static final List<String> ADDED = new ArrayList<>();
    private static final List<String> NOTES = new ArrayList<>();
    private static boolean attempted;

    /**
     * MC が渡そうとしている機能集合に Voxy の分を足したものを返す。
     * フラグが無効、校正できない、既に入っている — どの場合も<b>元の集合をそのまま返す</b>。
     *
     * @param requested MC が組み立てた機能集合 (変更しない)
     */
    public static Set<VulkanFeature> augment(Set<VulkanFeature> requested) {
        attempted = true;
        if (!Boolean.getBoolean(FLAG) && !McNativeRender.on()) return requested;
        if (requested == null) {
            note("Minecraft passed no feature set");
            return null;
        }
        try {
            VulkanPNextStruct struct = vk10StructOf(requested);
            if (struct == null) {
                note("could not borrow Minecraft's VK10 feature struct; adding nothing");
                return requested;
            }
            var existing = new LinkedHashSet<String>();
            for (VulkanFeature feature : requested) {
                String name = nameOf(feature);
                if (name != null) existing.add(name);
            }
            var augmented = new LinkedHashSet<VulkanFeature>(requested);
            for (var entry : WANTED.entrySet()) {
                if (existing.contains(entry.getKey())) continue;
                VulkanFeature feature = verifiedFeature(struct, entry.getKey(), entry.getValue());
                if (feature == null) continue;      // 確かめられなければ足さない
                augmented.add(feature);
                ADDED.add(entry.getKey());
            }
            if (!ADDED.isEmpty()) {
                Logger.info("[native-vk] asked Minecraft's device for " + ADDED
                    + " (supported by this device, not requested by Minecraft)");
            }
            return augmented;
        } catch (Throwable t) {
            note("could not augment Minecraft's device features: " + t);
            return requested;
        }
    }

    /**
     * <b>オフセットを推測ではなく実験で決める</b>。
     *
     * <p>{@link VulkanFeature} のオフセットが {@code VkPhysicalDeviceFeatures2} の先頭から
     * なのか、その中の {@code features} メンバからなのかは実装詳細で、どちらにも読める。
     * そこで候補をひとつずつ<b>実際に書いてみて</b>、LWJGL 側のアクセサで
     * 「狙ったフィールドだけが立ったか」を確かめ、合格したものだけを返す。
     * どの候補も通らなければ null を返す (fail closed)。
     *
     * @param lwjglOffset {@code VkPhysicalDeviceFeatures} 内のバイトオフセット
     */
    private static VulkanFeature verifiedFeature(VulkanPNextStruct struct, String name, int lwjglOffset) {
        long[] candidates = {VkPhysicalDeviceFeatures2.FEATURES + lwjglOffset, lwjglOffset};
        for (long candidate : candidates) {
            try (MemoryStack stack = stackPush()) {
                var probe = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
                var feature = new VulkanFeature(struct, name, candidate);
                feature.set(probe, true);
                // 立っているべき 1 ビットが立ち、他が漏れていないこと。
                if (!bitAt(probe, lwjglOffset)) continue;
                int set = 0;
                for (int offset = 0; offset < VkPhysicalDeviceFeatures.SIZEOF; offset += 4) {
                    if (bitAt(probe, offset)) set++;
                }
                if (set != 1) {
                    note(name + ": candidate offset " + candidate + " set " + set + " fields; skipping");
                    continue;
                }
                note(name + ": offset " + candidate + " verified by read-back");
                return feature;
            } catch (Throwable t) {
                note(name + ": candidate offset " + candidate + " threw " + t);
            }
        }
        note(name + ": no offset could be verified; not adding it");
        return null;
    }

    /** {@code VkPhysicalDeviceFeatures2} の features メンバ内 {@code offset} のビット。 */
    private static boolean bitAt(VkPhysicalDeviceFeatures2 features2, int offset) {
        return MemoryUtil.memGetInt(features2.address() + VkPhysicalDeviceFeatures2.FEATURES + offset) != 0;
    }

    /** MC が VK10 機能に使っている pNext 構造体を借りる (sType が features2 のもの)。 */
    private static VulkanPNextStruct vk10StructOf(Set<VulkanFeature> requested) {
        for (VulkanFeature feature : requested) {
            if (CALIBRATION.equals(nameOf(feature))) return structOf(feature);
        }
        for (VulkanFeature feature : VulkanBackend.REQUIRED_DEVICE_FEATURES) {
            if (CALIBRATION.equals(nameOf(feature))) return structOf(feature);
        }
        return null;
    }

    /** 何を足したか / なぜ足さなかったか。証跡に残す。 */
    public record Status(boolean enabled, boolean attempted, List<String> added, List<String> notes) {}

    public static Status status() {
        synchronized (NOTES) {
            return new Status(Boolean.getBoolean(FLAG) || McNativeRender.on(), attempted, List.copyOf(ADDED), List.copyOf(NOTES));
        }
    }

    private static void note(String note) {
        synchronized (NOTES) {
            if (!NOTES.contains(note)) NOTES.add(note);
        }
        Logger.warn("[native-vk] " + note);
    }

    // ---- record の中身を名前に依存せず読む (マッピングで accessor 名が変わっても動くように)

    private static String nameOf(VulkanFeature feature) {
        Object value = readFieldOfType(feature, String.class);
        return value instanceof String s ? s : null;
    }

    private static Long offsetOf(VulkanFeature feature) {
        Object value = readFieldOfType(feature, long.class);
        return value instanceof Long l ? l : null;
    }

    private static VulkanPNextStruct structOf(VulkanFeature feature) {
        Object value = readFieldOfType(feature, VulkanPNextStruct.class);
        return value instanceof VulkanPNextStruct s ? s : null;
    }

    /**
     * ⚠ round-2 review N1: 曖昧さと static を拒否する。候補が 2 本以上あれば諦める —
     * どちらかを選べば黙って間違う。
     */
    private static Object readFieldOfType(Object owner, Class<?> type) {
        Field only = null;
        for (Field f : owner.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (!type.isAssignableFrom(f.getType()) && f.getType() != type) continue;
            if (only != null) {
                note(owner.getClass().getName() + " has more than one non-static "
                    + type.getSimpleName() + " field; refusing to guess");
                return null;
            }
            only = f;
        }
        if (only == null) return null;
        for (Field f : new Field[] {only}) {
            try {
                f.setAccessible(true);
                return f.get(owner);
            } catch (Throwable ignored) {
                // 次の候補へ
            }
        }
        return null;
    }
}
