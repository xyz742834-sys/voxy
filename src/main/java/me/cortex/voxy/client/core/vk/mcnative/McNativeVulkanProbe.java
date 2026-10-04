package me.cortex.voxy.client.core.vk.mcnative;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Minecraft 自身の Vulkan バックエンドに実際に到達できるかを測る診断</b>
 * (docs/ai/project-goal.md の次の優先項目の第一歩)。
 *
 * <p>何も描かず、何も作らず、何も破棄しない。{@code Prefer Vulkan} が
 * 選ばれているかを「設定値」ではなく<b>実体</b>で確かめ、
 * 次の一歩 (MC のコマンドバッファへ有界な draw を記録する) の判断に必要な
 * 事実だけを集める:
 *
 * <ul>
 *   <li>MC が本当に {@link VulkanDevice} を使っているか。
 *       {@code VoxyClient.Backend.VULKAN} でも {@code preferredGraphicsBackend} でもない —
 *       バックエンドの実型で判定する [docs/ai/project-goal.md "Required evidence"]</li>
 *   <li>{@code VkDevice} / キューファミリ / VMA アロケータ — Voxy が自前の
 *       device を作るのをやめて<b>MC のものを採用</b>できるか</li>
 *   <li>主レンダーターゲットの色/深度の <b>生の {@code VkImage} / {@code VkImageView}</b>。
 *       GL テクスチャ ID も IOSurface も経由しない
 *       [docs/ai/vulkan-native-integration-survey.md]</li>
 * </ul>
 *
 * <p>⚠ <b>決して例外を投げない</b>。GL バックエンドでも、device がまだ無くても、
 * マッピングが変わっていても、「取れなかった」という事実を {@link Report#notes} に
 * 残して返るだけである。Voxy の既存経路 (GL / IOSurface) には一切触らない。
 *
 * <p>⚠ バックエンド実体 ({@link GpuDeviceBackend}) は {@link GpuDevice} の
 * <b>private フィールド</b>で、取得子が無い ({@code CommandEncoder.backend()} も protected)。
 * ここでは mixin ではなく<b>型でフィールドを探す</b>リフレクションを使う —
 * フィールド名はマッピングで変わるが、<b>型は変わらない</b>ので再マップに依存しない。
 */
public final class McNativeVulkanProbe {
    private McNativeVulkanProbe() {}

    /** 一度でも測ったか。描画スレッドからのみ触る。 */
    private static boolean probed;
    private static Report last;

    /** 色/深度アタッチメント 1 枚の素性。{@code vkImage == 0} なら Vulkan 実体を取れていない。 */
    public record Attachment(String role, String label, long vkImage, long vkImageView,
                             String format, int width, int height, int mipLevels, int usage) {}

    /**
     * 測定結果。{@link #mcUsesVulkan} が false のとき、他のフィールドは
     * 「取れなかった」ことを表す既定値 (0 / null) である。
     */
    public record Report(boolean mcUsesVulkan, String backendDescription, String backendClass,
                         String deviceName, String vendorName, String driverInfo,
                         long vkDevice, long vkInstance, long vmaAllocator,
                         int graphicsQueueFamily, int computeQueueFamily, int transferQueueFamily,
                         Attachment colour, Attachment depth, List<String> notes) {}

    /** 現在の状態を測る。描画スレッドから呼ぶこと。例外は投げない。 */
    public static Report probe() {
        GpuDevice device = null;
        RenderTarget target = null;
        List<String> notes = new ArrayList<>();
        try {
            device = RenderSystem.tryGetDevice();
        } catch (Throwable t) {
            notes.add("RenderSystem.tryGetDevice() threw " + t);
        }
        try {
            var mc = Minecraft.getInstance();
            if (mc != null && mc.gameRenderer != null) target = mc.gameRenderer.mainRenderTarget();
        } catch (Throwable t) {
            notes.add("main render target unavailable: " + t);
        }
        return probe(device, target, notes);
    }

    /**
     * 任意の device / target について測る。どちらも null でよい。
     * (MC を起動せずに「取れなかった」経路を試せるようにしてある。)
     */
    public static Report probe(GpuDevice device, RenderTarget target) {
        return probe(device, target, new ArrayList<>());
    }

    private static Report probe(GpuDevice device, RenderTarget target, List<String> notes) {
        String description = "";
        try {
            description = RenderSystem.getBackendDescription();
        } catch (Throwable t) {
            notes.add("RenderSystem.getBackendDescription() threw " + t);
        }
        if (device == null) {
            notes.add("no GpuDevice yet (called before the renderer was initialized?)");
            return unavailable(description, notes);
        }
        GpuDeviceBackend backend = backendOf(device, notes);
        if (backend == null) return unavailable(description, notes);
        if (!(backend instanceof VulkanDevice vk)) {
            notes.add("Minecraft is not on its Vulkan backend; the active backend is "
                + backend.getClass().getName());
            return new Report(false, description, backend.getClass().getName(),
                null, null, null, 0, 0, 0, -1, -1, -1, null, null, List.copyOf(notes));
        }

        long vkDevice = 0, vkInstance = 0, vma = 0;
        int gq = -1, cq = -1, tq = -1;
        String name = null, vendor = null, driver = null;
        try { vkDevice = vk.vkDevice().address(); } catch (Throwable t) { notes.add("vkDevice(): " + t); }
        try { vkInstance = vk.instance().vkInstance().address(); } catch (Throwable t) { notes.add("instance(): " + t); }
        try { vma = vk.vma(); } catch (Throwable t) { notes.add("vma(): " + t); }
        try { gq = vk.graphicsQueue().queueFamilyIndex(); } catch (Throwable t) { notes.add("graphicsQueue(): " + t); }
        try { cq = vk.computeQueue().queueFamilyIndex(); } catch (Throwable t) { notes.add("computeQueue(): " + t); }
        try { tq = vk.transferQueue().queueFamilyIndex(); } catch (Throwable t) { notes.add("transferQueue(): " + t); }
        try {
            var info = vk.getDeviceInfo();
            name = info.name();
            vendor = info.vendorName();
            driver = info.driverInfo();
        } catch (Throwable t) {
            notes.add("getDeviceInfo(): " + t);
        }

        Attachment colour = null, depth = null;
        if (target == null) {
            notes.add("no main render target to inspect");
        } else {
            colour = attachmentOf("colour", safeView(target, true, notes), notes);
            depth = attachmentOf("depth", safeView(target, false, notes), notes);
        }
        return new Report(true, description, vk.getClass().getName(), name, vendor, driver,
            vkDevice, vkInstance, vma, gq, cq, tq, colour, depth, List.copyOf(notes));
    }

    private static Report unavailable(String description, List<String> notes) {
        return new Report(false, description, null, null, null, null,
            0, 0, 0, -1, -1, -1, null, null, List.copyOf(notes));
    }

    private static GpuTextureView safeView(RenderTarget target, boolean colour, List<String> notes) {
        try {
            return colour ? target.getColorTextureView() : target.getDepthTextureView();
        } catch (Throwable t) {
            notes.add((colour ? "colour" : "depth") + " view unavailable: " + t);
            return null;
        }
    }

    /**
     * <b>{@link GpuDevice} が包んでいる実装を取り出す</b>。
     *
     * <p>フィールド名ではなく<b>型</b>で探す: {@code backend} という名前は
     * マッピング次第で変わるが、{@link GpuDeviceBackend} 型であることは変わらない。
     * 見つからなければ null を返し、理由を {@code notes} に残す (例外は投げない)。
     */
    static GpuDeviceBackend backendOf(GpuDevice device, List<String> notes) {
        if (device instanceof GpuDeviceBackend self) return self;   // 将来、包まなくなったとき
        for (Class<?> c = device.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!GpuDeviceBackend.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(device);
                    if (value instanceof GpuDeviceBackend backend) return backend;
                } catch (Throwable t) {
                    notes.add("could not read " + c.getSimpleName() + "." + f.getName() + ": " + t);
                }
            }
        }
        notes.add("no GpuDeviceBackend-typed field on " + device.getClass().getName()
            + " (the wrapper's shape changed?)");
        return null;
    }

    private static Attachment attachmentOf(String role, GpuTextureView view, List<String> notes) {
        if (view == null) return null;
        GpuTexture texture = null;
        try { texture = view.texture(); } catch (Throwable t) { notes.add(role + " texture(): " + t); }
        long image = 0, imageView = 0;
        if (view instanceof VulkanGpuTextureView vkView) {
            try { imageView = vkView.vkImageView(); } catch (Throwable t) { notes.add(role + " vkImageView(): " + t); }
        } else {
            notes.add(role + " view is " + view.getClass().getName() + ", not a Vulkan view");
        }
        if (texture instanceof VulkanGpuTexture vkTexture) {
            try { image = vkTexture.vkImage(); } catch (Throwable t) { notes.add(role + " vkImage(): " + t); }
        } else if (texture != null) {
            notes.add(role + " texture is " + texture.getClass().getName() + ", not a Vulkan texture");
        }
        String format = null;
        int width = 0, height = 0, mips = 0, usage = 0;
        if (texture != null) {
            try {
                format = String.valueOf(texture.getFormat());
                width = texture.getWidth(0);
                height = texture.getHeight(0);
                mips = texture.getMipLevels();
                usage = texture.usage();
            } catch (Throwable t) {
                notes.add(role + " texture properties: " + t);
            }
        }
        return new Attachment(role, texture == null ? null : safeLabel(texture, notes),
            image, imageView, format, width, height, mips, usage);
    }

    private static String safeLabel(GpuTexture texture, List<String> notes) {
        try { return texture.getLabel(); } catch (Throwable t) { notes.add("getLabel(): " + t); return null; }
    }

    /** 人が読む形。ログと証跡の両方で使う。 */
    public static String render(Report r) {
        var sb = new StringBuilder();
        sb.append("Minecraft graphics backend: ").append(r.backendDescription() == null ? "" : r.backendDescription())
            .append("\n  minecraft uses its vulkan backend: ").append(r.mcUsesVulkan())
            .append("\n  backend class: ").append(r.backendClass());
        if (r.mcUsesVulkan()) {
            sb.append("\n  device: ").append(r.deviceName())
                .append(" / vendor ").append(r.vendorName())
                .append(" / driver ").append(r.driverInfo())
                .append("\n  VkInstance=0x").append(Long.toHexString(r.vkInstance()))
                .append(" VkDevice=0x").append(Long.toHexString(r.vkDevice()))
                .append(" vma=0x").append(Long.toHexString(r.vmaAllocator()))
                .append("\n  queue families: graphics=").append(r.graphicsQueueFamily())
                .append(" compute=").append(r.computeQueueFamily())
                .append(" transfer=").append(r.transferQueueFamily());
            appendAttachment(sb, r.colour());
            appendAttachment(sb, r.depth());
        }
        for (String note : r.notes()) sb.append("\n  note: ").append(note);
        return sb.toString();
    }

    private static void appendAttachment(StringBuilder sb, Attachment a) {
        sb.append("\n  ");
        if (a == null) {
            sb.append("attachment: (not inspected)");
            return;
        }
        sb.append(a.role()).append(": label=").append(a.label())
            .append(" VkImage=0x").append(Long.toHexString(a.vkImage()))
            .append(" VkImageView=0x").append(Long.toHexString(a.vkImageView()))
            .append(" format=").append(a.format())
            .append(" ").append(a.width()).append("x").append(a.height())
            .append(" mips=").append(a.mipLevels())
            .append(" usage=0x").append(Integer.toHexString(a.usage()));
    }

    /**
     * セッションにつき一度だけ測ってログに出し、
     * {@code -Dvoxy.harness.output=<dir>} が指定されていれば
     * {@code native-vulkan-probe.json} として証跡に残す
     * (scripts/verify.py の live ステージが拾えるようにするため)。
     */
    public static void probeOnce() {
        if (probed) return;
        probed = true;
        try {
            Report r = probe();
            last = r;
            Logger.info("[native-vk] " + render(r));
            writeEvidence(r);
        } catch (Throwable t) {
            Logger.warn("[native-vk] the native Vulkan probe failed: " + t);
        }
    }

    /** 直近の測定結果。まだ測っていなければ null。 */
    public static Report last() { return last; }

    private static void writeEvidence(Report r) {
        String dir = System.getProperty("voxy.harness.output");
        if (dir == null || dir.isBlank()) return;
        try {
            Path out = Path.of(dir);
            Files.createDirectories(out);
            Files.writeString(out.resolve("native-vulkan-probe.json"), json(r), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Logger.warn("[native-vk] could not write the probe evidence: " + t);
        }
    }

    /** 依存を増やさない最小の JSON。数値は 10 進、ハンドルは文字列の 16 進で出す。 */
    public static String json(Report r) {
        var sb = new StringBuilder("{\n");
        sb.append("  \"mcUsesVulkan\": ").append(r.mcUsesVulkan()).append(",\n");
        sb.append("  \"backendDescription\": ").append(quote(r.backendDescription())).append(",\n");
        sb.append("  \"backendClass\": ").append(quote(r.backendClass())).append(",\n");
        sb.append("  \"deviceName\": ").append(quote(r.deviceName())).append(",\n");
        sb.append("  \"vendorName\": ").append(quote(r.vendorName())).append(",\n");
        sb.append("  \"driverInfo\": ").append(quote(r.driverInfo())).append(",\n");
        sb.append("  \"vkInstance\": ").append(quote(hex(r.vkInstance()))).append(",\n");
        sb.append("  \"vkDevice\": ").append(quote(hex(r.vkDevice()))).append(",\n");
        sb.append("  \"vmaAllocator\": ").append(quote(hex(r.vmaAllocator()))).append(",\n");
        sb.append("  \"graphicsQueueFamily\": ").append(r.graphicsQueueFamily()).append(",\n");
        sb.append("  \"computeQueueFamily\": ").append(r.computeQueueFamily()).append(",\n");
        sb.append("  \"transferQueueFamily\": ").append(r.transferQueueFamily()).append(",\n");
        sb.append("  \"colour\": ").append(json(r.colour())).append(",\n");
        sb.append("  \"depth\": ").append(json(r.depth())).append(",\n");
        sb.append("  \"notes\": [");
        for (int i = 0; i < r.notes().size(); i++) {
            sb.append(i == 0 ? "\n    " : ",\n    ").append(quote(r.notes().get(i)));
        }
        sb.append(r.notes().isEmpty() ? "]\n}" : "\n  ]\n}");
        return sb.toString();
    }

    private static String json(Attachment a) {
        if (a == null) return "null";
        return "{\"role\": " + quote(a.role())
            + ", \"label\": " + quote(a.label())
            + ", \"vkImage\": " + quote(hex(a.vkImage()))
            + ", \"vkImageView\": " + quote(hex(a.vkImageView()))
            + ", \"format\": " + quote(a.format())
            + ", \"width\": " + a.width()
            + ", \"height\": " + a.height()
            + ", \"mipLevels\": " + a.mipLevels()
            + ", \"usage\": " + quote("0x" + Integer.toHexString(a.usage()))
            + "}";
    }

    private static String hex(long v) { return "0x" + Long.toHexString(v); }

    private static String quote(String s) {
        if (s == null) return "null";
        var sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
