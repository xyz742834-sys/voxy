import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
import struct
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify import native_environment_result


class NativeGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)
        renderer = dict(mcUsesVulkan=True, backendClass="com.mojang.blaze3d.vulkan.VulkanDevice",
                        notes=[], vkDevice=12, vkInstance=13, vmaAllocator=14,
                        graphicsQueueFamily=0, computeQueueFamily=3, transferQueueFamily=3)
        for role in ("colour", "depth"):
            renderer[role] = dict(vkImage=15, vkImageView=16, width=960, height=540)
        stages = "warmup turn travel return edit remove resize reload nether overworld reconnect".split()
        self.evidence = dict(complete=True, success=True, failures=[], voxyIntegrationStatus="BLOCKED_UNIMPLEMENTED",
            checkpoints=[dict(stage=s, renderer=copy.deepcopy(renderer)) for s in stages])
        for stage in stages:
            def chunk(kind, payload):
                return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)
            png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 960, 540, 8, 2, 0, 0, 0))
            png += chunk(b"IDAT", zlib.compress((b"\0" + bytes(960 * 3)) * 540)) + chunk(b"IEND", b"")
            (self.output / (stage + ".png")).write_bytes(png)

    def verdict(self):
        (self.output / "native-result.json").write_text(json.dumps(self.evidence))
        return native_environment_result(self.output)

    def test_environment_success_retains_unimplemented_integration(self):
        result = self.verdict()
        self.assertTrue(result["success"])
        self.assertEqual("BLOCKED_UNIMPLEMENTED", result["voxy_integration_status"])

    def test_fallback_cannot_hide_behind_green_client_status(self):
        self.evidence["checkpoints"][4]["renderer"]["backendClass"] = "com.mojang.blaze3d.opengl.GlDevice"
        self.assertFalse(self.verdict()["success"])

    def test_missing_native_image_view_fails(self):
        self.evidence["checkpoints"][5]["renderer"]["depth"]["vkImageView"] = 0
        self.assertFalse(self.verdict()["success"])

    def test_duplicate_checkpoint_cannot_replace_required_transition(self):
        self.evidence["checkpoints"][-1] = self.evidence["checkpoints"][0]
        self.assertFalse(self.verdict()["success"])

    def test_missing_screenshot_fails(self):
        (self.output / "reload.png").unlink()
        self.assertFalse(self.verdict()["success"])

    def test_png_signature_alone_is_not_image_evidence(self):
        (self.output / "warmup.png").write_bytes(b"\x89PNG\r\n\x1a\n" + bytes(120))
        self.assertFalse(self.verdict()["success"])

    def test_partial_probe_notes_and_device_changes_fail(self):
        renderer = self.evidence["checkpoints"][3]["renderer"]
        renderer["notes"] = ["depth view unavailable"]
        self.assertFalse(self.verdict()["success"])
        renderer["notes"] = []
        renderer["vkDevice"] = 999
        self.assertFalse(self.verdict()["success"])
