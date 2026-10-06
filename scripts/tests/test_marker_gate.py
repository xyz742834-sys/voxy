"""The marker gate must reject the counterexamples the round-1 review got past it.

The reviewer did not argue about the gate; it encoded valid PNGs and showed the real
`native_marker_result` returning success on frames that proved nothing — one far-colour
pixel instead of the 60/40 split, no depth attachment at all, and three lifecycle
checkpoints excused because their marker box was dark while the rest of the frame was
bright. Each of those is a test here, and each must now fail.
"""
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify import native_marker_result, native_proof_files_result

WIDTH, HEIGHT = 960, 540
NEAR, FAR, REJECTED = (255, 0, 255), (0, 255, 255), (255, 255, 0)
BACKGROUND = (100, 100, 100)
STAGES = ("warmup", "turn", "travel", "return", "edit", "remove",
          "resize", "reload", "nether", "overworld", "reconnect")
GEOMETRY = {"box": [-0.98, 0.98, -0.78, 0.78], "nearSplitX": -0.86,
            "controlStrip": [-0.98, 0.76, -0.78, 0.72]}


def to_pixels(x, y, width=WIDTH, height=HEIGHT):
    return int((x + 1.0) * 0.5 * width), int((1.0 - y) * 0.5 * height)


def write_png(path, pixels):
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for px in row:
            raw.extend(px)

    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", len(pixels[0]), len(pixels), 8, 2, 0, 0, 0)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
                     + chunk(b"IDAT", zlib.compress(bytes(raw))) + chunk(b"IEND", b""))


def frame(near_fill=NEAR, far_fill=FAR, control_fill=REJECTED, box_fill=None,
          background=BACKGROUND, lone_far_pixel=False):
    """A frame with the box split 60/40 and the control strip filled, unless overridden."""
    pixels = [[background] * WIDTH for _ in range(HEIGHT)]
    x0, y0 = to_pixels(*GEOMETRY["box"][:2])
    x1, y1 = to_pixels(*GEOMETRY["box"][2:])
    sx, _ = to_pixels(GEOMETRY["nearSplitX"], GEOMETRY["box"][1])
    cx0, cy0 = to_pixels(*GEOMETRY["controlStrip"][:2])
    cx1, cy1 = to_pixels(*GEOMETRY["controlStrip"][2:])
    for y in range(y0, y1):
        for x in range(x0, x1):
            if box_fill is not None:
                pixels[y][x] = box_fill
            else:
                pixels[y][x] = near_fill if x < sx else far_fill
    if lone_far_pixel:
        for y in range(y0, y1):
            for x in range(x0, x1):
                pixels[y][x] = NEAR
        pixels[y0][x1 - 1] = FAR
    if control_fill is not None:
        for y in range(cy0, cy1):
            for x in range(cx0, cx1):
                pixels[y][x] = control_fill
    return pixels


def report(**overrides):
    base = {"enabled": True, "pipelineLive": True, "drawsRecorded": 3000, "notes": [],
            "readback": {"attempted": True, "completed": True, "near": 9690, "far": 6365,
                         "rejectedInBox": 0, "control": 3230, "boxArea": 16055,
                         "controlArea": 3230, "note": None, "timesClean": 7,
                         "timesWithAProblem": 0, "firstProblem": None},
            "markerRgb": list(NEAR), "farRgb": list(FAR), "rejectedRgb": list(REJECTED),
            "depthAttached": True, "depthVkFormat": 126, "geometry": GEOMETRY,
            "device": "0xabc"}
    base.update(overrides)
    return base


class MarkerGateTest(unittest.TestCase):
    def run_gate(self, frames, marker_report, draws=None, covered=None):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            (out / "native-marker-draw.json").write_text(json.dumps(marker_report))
            for stage, pixels in frames.items():
                write_png(out / (stage + ".png"), pixels)
            checkpoints = []
            for i, stage in enumerate(frames):
                checkpoints.append({"stage": stage,
                                    "markerDraws": draws[stage] if draws else (i + 1) * 100,
                                    "frameCoveredByGui": bool(covered and stage in covered)})
            return native_marker_result(out, checkpoints)

    def test_a_correct_frame_passes(self):
        result = self.run_gate({s: frame() for s in STAGES}, report())
        self.assertTrue(result["success"], result["failures"])

    def test_one_far_pixel_instead_of_the_split_is_rejected(self):
        result = self.run_gate({s: frame(lone_far_pixel=True) for s in STAGES}, report())
        self.assertFalse(result["success"])
        self.assertIn("half of the box", " ".join(result["failures"]))

    def test_no_depth_attachment_is_rejected(self):
        result = self.run_gate({s: frame() for s in STAGES}, report(depthAttached=False))
        self.assertFalse(result["success"])
        self.assertIn("depth was not attached", " ".join(result["failures"]))

    def test_frames_showing_nothing_are_recorded_and_bounded(self):
        """A composited frame can be covered by Minecraft, so a few may carry nothing — the
        readback is what proves the draw. Too many, though, means the draw stopped."""
        frames = {s: frame() for s in STAGES}
        for stage in ("nether", "overworld", "reconnect"):
            frames[stage] = frame(box_fill=(0, 0, 0), control_fill=(0, 0, 0))
        result = self.run_gate(frames, report())
        self.assertTrue(result["success"], result["failures"])
        self.assertEqual(3, len(result["frames_without_level_content"]))

        frames["reload"] = frame(box_fill=(0, 0, 0), control_fill=(0, 0, 0))
        result = self.run_gate(frames, report())
        self.assertFalse(result["success"])
        self.assertIn("at most three", " ".join(result["failures"]))

    def test_a_partially_drawn_frame_is_still_a_failure(self):
        """A frame that shows the near quad but not the far one is not a covered frame — it
        is a depth or draw problem, and must not be waved through."""
        frames = {s: frame() for s in STAGES}
        frames["edit"] = frame(far_fill=BACKGROUND)
        result = self.run_gate(frames, report())
        self.assertFalse(result["success"])
        self.assertIn("half of the box", " ".join(result["failures"]))

    def test_frames_the_draw_never_ran_for_are_excused_up_to_the_limit(self):
        """The implementation says how many draws it had recorded; a frame whose count did
        not advance simply had no draw in it (a dimension change, for instance)."""
        frames = {s: frame() for s in STAGES}
        frames["nether"] = [[(2, 2, 2)] * WIDTH for _ in range(HEIGHT)]
        draws = {s: (i + 1) * 100 for i, s in enumerate(STAGES)}
        draws["nether"] = draws["reload"]          # no progress across the nether frame
        self.assertTrue(self.run_gate(frames, report(), draws)["success"])
        frames["overworld"] = [[(2, 2, 2)] * WIDTH for _ in range(HEIGHT)]
        draws["overworld"] = draws["reload"]
        self.assertTrue(self.run_gate(frames, report(), draws)["success"],
                        "two excused frames are within the limit")
        for stage in ("reconnect", "reload"):
            frames[stage] = [[(2, 2, 2)] * WIDTH for _ in range(HEIGHT)]
            draws[stage] = draws["remove"]
        result = self.run_gate(frames, report(), draws)
        self.assertFalse(result["success"])
        self.assertIn("at most three", " ".join(result["failures"]))

    def test_the_readback_is_what_carries_the_proof_not_the_screenshots(self):
        """With every captured frame empty, the gate must fail however good the readback is —
        the screenshots are a sanity check on the scenario, not decoration."""
        frames = {s: [[(2, 2, 2)] * WIDTH for _ in range(HEIGHT)] for s in STAGES}
        result = self.run_gate(frames, report())
        self.assertFalse(result["success"])

    def test_a_gui_covered_frame_is_excused_with_the_implementations_reason(self):
        """Voxy records before Minecraft's GUI, so a loading overlay hides the marker. The
        implementation says when that happened; the gate does not guess from the image."""
        frames = {s: frame() for s in STAGES}
        frames["nether"] = frame(near_fill=(5, 2, 2), far_fill=(5, 2, 2), control_fill=(33, 33, 33))
        result = self.run_gate(frames, report(), covered={"nether"})
        self.assertTrue(result["success"], result["failures"])
        self.assertIn("nether", result["frames_without_level_content"])

    def test_a_checkpoint_without_a_draw_count_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            (out / "native-marker-draw.json").write_text(json.dumps(report()))
            for stage in STAGES:
                write_png(out / (stage + ".png"), frame())
            result = native_marker_result(out, [{"stage": s} for s in STAGES])
        self.assertFalse(result["success"])
        self.assertIn("how many marker draws", " ".join(result["failures"]))

    def test_a_missing_control_strip_means_the_third_draw_is_unproven(self):
        result = self.run_gate({s: frame(control_fill=None) for s in STAGES}, report())
        self.assertFalse(result["success"])
        self.assertIn("third draw", " ".join(result["failures"]))

    def test_the_rejected_colour_inside_the_box_is_rejected(self):
        result = self.run_gate({s: frame(box_fill=REJECTED) for s in STAGES}, report())
        self.assertFalse(result["success"])
        self.assertIn("depth must reject", " ".join(result["failures"]))

    def test_a_missing_or_failed_readback_is_rejected(self):
        """The readback of Minecraft's own colour image is the authoritative proof."""
        no_readback = report()
        del no_readback["readback"]
        result = self.run_gate({s: frame() for s in STAGES}, no_readback)
        self.assertFalse(result["success"])
        self.assertIn("never read back", " ".join(result["failures"]))

        failed = report(readback={"attempted": True, "completed": False, "near": 0, "far": 0,
                                  "rejectedInBox": 0, "control": 0, "boxArea": 0,
                                  "controlArea": 0, "note": "classify failed", "timesClean": 7, "timesWithAProblem": 0,
                               "firstProblem": None})
        result = self.run_gate({s: frame() for s in STAGES}, failed)
        self.assertFalse(result["success"])
        self.assertIn("did not complete", " ".join(result["failures"]))

    def test_the_readback_finding_the_rejected_colour_is_rejected(self):
        bad = report(readback={"attempted": True, "completed": True, "near": 9690, "far": 6365,
                               "rejectedInBox": 7, "control": 3230, "boxArea": 16055,
                               "controlArea": 3230,
                               "note": "the rejected colour overlaps the rows the depth-tested"
                                       " box occupies, so depth is not working", "timesClean": 7, "timesWithAProblem": 0,
                               "firstProblem": None})
        result = self.run_gate({s: frame() for s in STAGES}, bad)
        self.assertFalse(result["success"])
        self.assertIn("depth is not working", " ".join(result["failures"]))

    def test_a_readback_note_of_any_kind_is_rejected(self):
        bad = report(readback={"attempted": True, "completed": True, "near": 9690, "far": 6365,
                               "rejectedInBox": 0, "control": 3230, "boxArea": 16055,
                               "controlArea": 3230,
                               "note": "the near and far quads are not side by side", "timesClean": 7, "timesWithAProblem": 0,
                               "firstProblem": None})
        result = self.run_gate({s: frame() for s in STAGES}, bad)
        self.assertFalse(result["success"])
        self.assertIn("not side by side", " ".join(result["failures"]))

    def test_the_readback_without_a_control_strip_is_rejected(self):
        bad = report(readback={"attempted": True, "completed": True, "near": 9690, "far": 6365,
                               "rejectedInBox": 0, "control": 0, "boxArea": 16055,
                               "controlArea": 0, "note": None, "timesClean": 7, "timesWithAProblem": 0,
                               "firstProblem": None})
        result = self.run_gate({s: frame() for s in STAGES}, bad)
        self.assertFalse(result["success"])
        self.assertIn("third draw is unproven", " ".join(result["failures"]))

    def test_the_readback_missing_one_half_of_the_box_is_rejected(self):
        bad = report(readback={"attempted": True, "completed": True, "near": 16000, "far": 0,
                               "rejectedInBox": 0, "control": 3230, "boxArea": 16000,
                               "controlArea": 3230, "note": None, "timesClean": 7, "timesWithAProblem": 0,
                               "firstProblem": None})
        result = self.run_gate({s: frame() for s in STAGES}, bad)
        self.assertFalse(result["success"])
        self.assertIn("too few near/far", " ".join(result["failures"]))

    def test_a_single_readback_is_not_enough(self):
        """Round-2 review B4: one readback says nothing about the rest of the lifecycle."""
        bad = report(readback={"attempted": True, "completed": True, "near": 9690, "far": 6365,
                               "rejectedInBox": 0, "control": 3230, "boxArea": 16055,
                               "controlArea": 3230, "note": None, "timesClean": 1,
                               "timesWithAProblem": 0, "firstProblem": None})
        result = self.run_gate({s: frame() for s in STAGES}, bad)
        self.assertFalse(result["success"])
        self.assertIn("across the run", " ".join(result["failures"]))

    def test_any_readback_problem_during_the_run_is_rejected(self):
        bad = report(readback={"attempted": True, "completed": True, "near": 9690, "far": 6365,
                               "rejectedInBox": 0, "control": 3230, "boxArea": 16055,
                               "controlArea": 3230, "note": None, "timesClean": 6,
                               "timesWithAProblem": 1,
                               "firstProblem": "the near quad fills 0 of 9792 pixels"})
        result = self.run_gate({s: frame() for s in STAGES}, bad)
        self.assertFalse(result["success"])
        self.assertIn("found a problem", " ".join(result["failures"]))

    def test_a_yellow_box_is_never_excused(self):
        """Round-2 review B4: a yellow box with an absent control strip used to be skipped."""
        frames = {s: frame() for s in STAGES}
        frames["nether"] = frame(box_fill=REJECTED, control_fill=None)
        result = self.run_gate(frames, report(), covered={"nether"})
        self.assertFalse(result["success"])
        self.assertIn("depth must reject", " ".join(result["failures"]))

    def test_missing_geometry_is_rejected(self):
        bad = report()
        del bad["geometry"]
        result = self.run_gate({s: frame() for s in STAGES}, bad)
        self.assertFalse(result["success"])
        self.assertIn("geometry", " ".join(result["failures"]))


class ProofFileGateTest(unittest.TestCase):
    FILES = {
        "native-device-features.json": {"enabled": True, "attempted": True,
            "added": ["drawIndirectFirstInstance", "shaderInt64",
                      "fragmentStoresAndAtomics", "vertexPipelineStoresAndAtomics"],
            "notes": []},
        "native-compute-probe.json": {"attempted": True, "succeeded": True,
            "expected": "0x123456789abcdef", "readBack": "0x123456789abcdef",
            "queueFamily": 3, "notes": []},
        "native-adopted-context.json": {"enabled": True, "attempted": True, "adopted": True,
            "queueFamily": 0, "device": "0x79b569e018", "provenByVoxyBufferAndShader": True,
            "readBack": "0x123456789abcdef", "notes": []},
        "native-real-shader.json": {"attempted": True, "succeeded": True,
            "quadOrdinalsChecked": 189, "mismatches": 0, "firstMismatch": None, "notes": []},
        "native-marker-draw.json": {"enabled": True, "pipelineLive": True,
            "drawsRecorded": 3000, "device": "0x79b569e018", "notes": []},
        "native-vulkan-probe.json": {"mcUsesVulkan": True, "vkDevice": "0x79b569e018",
            "notes": []},
    }
    CHECKPOINTS = [{"stage": s, "renderer": {"vkDevice": 522734657560}} for s in STAGES]

    def run_gate(self, overrides=None, drop=(), checkpoints=None):
        files = {k: dict(v) for k, v in self.FILES.items()}
        for name, patch in (overrides or {}).items():
            files[name].update(patch)
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            for name, body in files.items():
                if name in drop:
                    continue
                (out / name).write_text(json.dumps(body))
            return native_proof_files_result(
                out, self.CHECKPOINTS if checkpoints is None else checkpoints)

    def test_complete_proofs_pass(self):
        result = self.run_gate()
        self.assertTrue(result["success"], result["failures"])

    def test_each_missing_file_fails(self):
        for name in self.FILES:
            with self.subTest(name=name):
                self.assertFalse(self.run_gate(drop=(name,))["success"])

    def test_a_failed_probe_fails_even_though_it_only_logged_a_warning(self):
        self.assertFalse(self.run_gate({"native-real-shader.json": {"succeeded": False}})["success"])
        self.assertFalse(self.run_gate({"native-real-shader.json": {"mismatches": 3}})["success"])
        self.assertFalse(self.run_gate({"native-compute-probe.json": {"succeeded": False}})["success"])
        self.assertFalse(self.run_gate({"native-adopted-context.json": {"adopted": False}})["success"])
        self.assertFalse(self.run_gate(
            {"native-adopted-context.json": {"provenByVoxyBufferAndShader": False}})["success"])

    def test_partial_feature_requests_fail(self):
        self.assertFalse(self.run_gate(
            {"native-device-features.json": {"added": ["shaderInt64"]}})["success"])

    def test_notes_on_any_proof_fail(self):
        self.assertFalse(self.run_gate(
            {"native-compute-probe.json": {"notes": ["something was skipped"]}})["success"])

    def test_a_hex_handle_matches_the_same_decimal_handle(self):
        """The probe files spell handles as 0x-hex, the checkpoints as integers."""
        result = self.run_gate({"native-adopted-context.json": {"device": "0x79b569e018"}},
                               checkpoints=[{"stage": s, "renderer": {"vkDevice": 522734657560}}
                                            for s in STAGES])
        self.assertTrue(result["success"], result["failures"])

    def test_a_device_the_lifecycle_never_saw_fails(self):
        result = self.run_gate(checkpoints=[{"stage": s, "renderer": {"vkDevice": 999}}
                                            for s in STAGES])
        self.assertFalse(result["success"])
        self.assertIn("but the lifecycle", " ".join(result["failures"]))

    def test_an_unreadable_device_handle_fails_instead_of_skipping_the_check(self):
        result = self.run_gate({"native-adopted-context.json": {"device": "not-a-handle"}})
        self.assertFalse(result["success"])
        self.assertIn("not a device handle", " ".join(result["failures"]))

    def test_a_null_adopted_device_fails_instead_of_skipping_the_check(self):
        """Round-2 review B3: a null identity used to skip the comparison entirely."""
        result = self.run_gate({"native-adopted-context.json": {"device": None}})
        self.assertFalse(result["success"])

    def test_a_proof_naming_a_different_device_fails(self):
        for name in ("native-marker-draw.json", "native-vulkan-probe.json"):
            with self.subTest(name=name):
                field = "vkDevice" if "probe" in name else "device"
                result = self.run_gate({name: {field: "0x1234"}})
                self.assertFalse(result["success"])
                self.assertIn("but the lifecycle", " ".join(result["failures"]))

    def test_a_missing_field_fails_instead_of_defaulting(self):
        files = {k: dict(v) for k, v in self.FILES.items()}
        del files["native-real-shader.json"]["mismatches"]
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            for name, body in files.items():
                (out / name).write_text(json.dumps(body))
            result = native_proof_files_result(out, self.CHECKPOINTS)
        self.assertFalse(result["success"])
        self.assertIn("does not state mismatches", " ".join(result["failures"]))

    def test_checkpoints_seeing_two_devices_fail(self):
        mixed = [{"stage": s, "renderer": {"vkDevice": 522734657560 if i else 999}}
                 for i, s in enumerate(STAGES)]
        result = self.run_gate(checkpoints=mixed)
        self.assertFalse(result["success"])
        self.assertIn("more than one device", " ".join(result["failures"]))


if __name__ == "__main__":
    unittest.main()
