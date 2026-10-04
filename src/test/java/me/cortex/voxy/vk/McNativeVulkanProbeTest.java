package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.mcnative.McNativeVulkanProbe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link McNativeVulkanProbe} の<b>失敗しない</b>性質と報告の形を固定する。
 *
 * <p>本物の {@code GpuDevice} は Minecraft の起動が要るのでここでは作れない。
 * 代わりに、この診断で一番大事な性質 —
 * <b>何も無い状況でも例外を投げず、取れなかった事実を残して返る</b> —
 * と、JSON/テキストの形を検証する。実機の値そのものは
 * {@code scripts/verify.py} の live ステージが
 * {@code native-vulkan-probe.json} として残す。
 */
public class McNativeVulkanProbeTest {

    @Test
    void probingWithoutADeviceReportsWhyInsteadOfThrowing() {
        var report = McNativeVulkanProbe.probe(null, null);

        assertFalse(report.mcUsesVulkan(), "no device cannot mean Minecraft is on Vulkan");
        assertNull(report.backendClass());
        assertEquals(0, report.vkDevice());
        assertEquals(-1, report.graphicsQueueFamily(), "an unknown queue family must not read as family 0");
        assertNull(report.colour());
        assertNull(report.depth());
        assertTrue(report.notes().stream().anyMatch(n -> n.contains("no GpuDevice")),
            () -> "the report must say why it could not look: " + report.notes());
    }

    @Test
    void theReportIsImmutableAndRendersEveryHandle() {
        var report = new McNativeVulkanProbe.Report(true, "Vulkan MoltenVK",
            "com.mojang.blaze3d.vulkan.VulkanDevice", "Apple M4", "Apple", "MoltenVK 1.2",
            0x1234L, 0xabcdL, 0x55L, 0, 0, 1,
            new McNativeVulkanProbe.Attachment("colour", "main colour", 0xc01L, 0xc02L,
                "RGBA8_UNORM", 1920, 1080, 1, 0x3),
            new McNativeVulkanProbe.Attachment("depth", "main depth", 0xd01L, 0xd02L,
                "DEPTH32_FLOAT", 1920, 1080, 1, 0x1),
            List.of("a note"));

        assertThrows(UnsupportedOperationException.class, () -> report.notes().add("no"),
            "notes must not be mutable after the fact");

        String text = McNativeVulkanProbe.render(report);
        assertTrue(text.contains("minecraft uses its vulkan backend: true"), text);
        assertTrue(text.contains("VkDevice=0x1234"), text);
        assertTrue(text.contains("VkInstance=0xabcd"), text);
        assertTrue(text.contains("colour: label=main colour VkImage=0xc01 VkImageView=0xc02"), text);
        assertTrue(text.contains("depth: label=main depth VkImage=0xd01 VkImageView=0xd02"), text);
        assertTrue(text.contains("queue families: graphics=0 compute=0 transfer=1"), text);
        assertTrue(text.contains("note: a note"), text);
    }

    @Test
    void theEvidenceJsonIsWellFormedAndEscapes() {
        var report = new McNativeVulkanProbe.Report(false, "OpenGL 4.6", "com.mojang.blaze3d.opengl.GlDevice",
            null, null, null, 0, 0, 0, -1, -1, -1, null, null,
            List.of("backend is \"GlDevice\"", "line\nbreak"));

        String json = McNativeVulkanProbe.json(report);
        assertTrue(json.startsWith("{"), json);
        assertTrue(json.endsWith("}"), json);
        assertTrue(json.contains("\"mcUsesVulkan\": false"), json);
        assertTrue(json.contains("\"colour\": null"), json);
        assertTrue(json.contains("\\\"GlDevice\\\""), "quotes inside a note must be escaped: " + json);
        assertTrue(json.contains("line\\nbreak"), "newlines inside a note must be escaped: " + json);
        assertEquals(count(json, '{'), count(json, '}'), "braces must balance: " + json);
    }

    @Test
    void anEmptyNoteListStillProducesValidJson() {
        var report = new McNativeVulkanProbe.Report(false, "", null, null, null, null,
            0, 0, 0, -1, -1, -1, null, null, List.of());
        String json = McNativeVulkanProbe.json(report);
        assertTrue(json.contains("\"notes\": []"), json);
        assertEquals(count(json, '['), count(json, ']'), json);
    }

    private static long count(String s, char c) {
        return s.chars().filter(ch -> ch == c).count();
    }
}
