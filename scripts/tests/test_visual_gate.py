import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zlib

sys.path.insert(0, str(Path(__file__).parents[1]))
from verify import VISUAL_TESTS, visual_recovery_result
from pixel_oracle import read_rgb, mismatches


def png(path, pixels):
    def chunk(kind, payload):
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    raw = b"".join(b"\0" + bytes(v for p in pixels[y * 256:(y + 1) * 256] for v in p) for y in range(192))
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 256, 192, 8, 2, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


class VisualGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name)
        self.directory = self.output / "visual-recovery"
        self.directory.mkdir()
        (self.output / "junit").mkdir()
        self.report = self.output / "junit" / "TEST-me.cortex.voxy.vk.VkVisualRecoveryTest.xml"
        suite = ET.Element("testsuite")
        for name in VISUAL_TESTS:
            ET.SubElement(suite, "testcase", classname="me.cortex.voxy.vk.VkVisualRecoveryTest", name=name + "()")
        self.report.write_text(ET.tostring(suite, encoding="unicode"))
        self.expected = [(192, 0, 55) if 64 <= x < 128 and 48 <= y < 96 else (0, 0, 0)
                         for y in range(192) for x in range(256)]
        good = {"colorMismatches": 0, "depthMismatches": 0, "coveredPixels": 3072, "maxDepthError": 0.0}
        for name in ("analytic", "before-pressure", "recovered-0", "recovered-1", "recovered-2",
                     "mirrored-control", "missing-control", "color-control", "depth-control"):
            actual, metrics = list(self.expected), dict(good)
            if name == "mirrored-control":
                actual = [(192, 0, 55) if 128 <= x < 192 and 48 <= y < 96 else (0, 0, 0)
                          for y in range(192) for x in range(256)]
                metrics.update(colorMismatches=6144, depthMismatches=6144, maxDepthError=0.1)
            elif name == "missing-control":
                actual = [(0, 0, 0)] * (256 * 192)
                metrics.update(colorMismatches=3072, depthMismatches=3072, coveredPixels=0, maxDepthError=0.1)
            elif name == "color-control":
                actual = [(191, 0, 55) if p != (0, 0, 0) else p for p in actual]
                metrics["colorMismatches"] = 3072
            elif name == "depth-control":
                metrics.update(depthMismatches=3072, maxDepthError=0.1)
            difference = [(255, 0, 0) if a != b or (name == "depth-control" and b != (0, 0, 0)) else (0, 0, 0)
                          for a, b in zip(actual, self.expected)]
            (self.directory / (name + ".json")).write_text(json.dumps({"pixels": metrics}))
            png(self.directory / (name + "-actual.png"), actual)
            png(self.directory / (name + "-expected.png"), self.expected)
            png(self.directory / (name + "-diff.png"), difference)
            depth_bytes = bytearray()
            for y in range(192):
                for x in range(256):
                    drawn = 64 <= x < 128 and 96 <= y < 144
                    if name == "mirrored-control":
                        drawn = 128 <= x < 192 and 96 <= y < 144
                    depth = 0.0 if not drawn or name == "missing-control" else 0.2 if name == "depth-control" else 0.1
                    depth_bytes.extend(struct.pack("<f", depth))
            (self.directory / (name + "-depth-f32le.bin")).write_bytes(depth_bytes)
        cycles = [{"cycle": i, "capacityBytes": 3072, "usedBefore": 3072, "usedAfterReclaim": 2048,
                   "usedAfterRelease": 1024, "usedAfterRecovery": 3072,
                   "retryBytes": 2048, "reclaimAttempts": 1, "newRequests": 1, "rejectedTotal": i + 1,
                   "pixels": good} for i in range(3)]
        self.pressure = self.directory / "pressure.json"
        self.pressure.write_text(json.dumps({"complete": True, "success": True, "cycles": cycles}))

    def test_complete_gate_and_independent_png_decode(self):
        self.assertEqual(0, mismatches(read_rgb(self.directory / "analytic-actual.png")))
        self.assertTrue(visual_recovery_result(self.output)["success"])

    def test_corrupt_actual_png_cannot_hide_behind_green_metrics(self):
        pixels = list(self.expected)
        pixels[0] = (1, 0, 0)
        png(self.directory / "analytic-actual.png", pixels)
        self.assertFalse(visual_recovery_result(self.output)["success"])

    def test_missing_diff_fails(self):
        (self.directory / "analytic-diff.png").unlink()
        self.assertFalse(visual_recovery_result(self.output)["success"])

    def test_skipped_required_test_fails_even_with_complete_artifacts(self):
        suite = ET.parse(self.report).getroot()
        ET.SubElement(suite.find("testcase"), "skipped")
        self.report.write_text(ET.tostring(suite, encoding="unicode"))
        self.assertFalse(visual_recovery_result(self.output)["success"])

    def test_counter_without_freed_bytes_fails(self):
        evidence = json.loads(self.pressure.read_text())
        evidence["cycles"][0]["usedAfterReclaim"] = 3072
        self.pressure.write_text(json.dumps(evidence))
        self.assertFalse(visual_recovery_result(self.output)["success"])

    def test_incomplete_pressure_fails(self):
        evidence = json.loads(self.pressure.read_text())
        evidence["complete"] = False
        self.pressure.write_text(json.dumps(evidence))
        self.assertFalse(visual_recovery_result(self.output)["success"])

    def test_truncated_png_fails(self):
        path = self.directory / "analytic-actual.png"
        path.write_bytes(path.read_bytes()[:-5])
        self.assertFalse(visual_recovery_result(self.output)["success"])


if __name__ == "__main__":
    unittest.main()
