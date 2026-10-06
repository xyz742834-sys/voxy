"""The marker gate must reject the counterexamples the round-1 review got past it.

The reviewer did not argue about the gate; it encoded valid PNGs and showed the real
`native_marker_result` returning success on frames that proved nothing — one far-colour
pixel instead of the 60/40 split, no depth attachment at all, and three lifecycle
checkpoints excused because their marker box was dark while the rest of the frame was
bright. Each of those is a test here, and each must now fail.
"""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify
from pixel_oracle import top_rows_rgb
from verify import (native_marker_result, native_proof_files_result,
                    retain_native_evidence, replay_evidence)

WIDTH, HEIGHT = 960, 540
NEAR, FAR, REJECTED = (255, 0, 255), (0, 255, 255), (255, 255, 0)
BACKGROUND = (100, 100, 100)
STAGES = ("warmup", "turn", "travel", "return", "edit", "remove",
          "resize", "reload", "nether", "overworld", "reconnect")
GEOMETRY = {"box": [-0.98, 0.98, -0.78, 0.78], "nearSplitX": -0.86,
            "controlStrip": [-0.98, 0.76, -0.78, 0.72],
            "depthTestedPassCell": [-0.98, 0.70, -0.78, 0.66]}


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


def union_rect():
    """The rect the implementation retains as its raw sample: box + cell + control strip."""
    xs, ys = [], []
    for key in ("box", "controlStrip", "depthTestedPassCell"):
        ax, ay, bx, by = GEOMETRY[key]
        xs += [int((min(ax, bx) + 1.0) * 0.5 * WIDTH), int((max(ax, bx) + 1.0) * 0.5 * WIDTH)]
        ys += [int((1.0 - max(ay, by)) * 0.5 * HEIGHT), int((1.0 - min(ay, by)) * 0.5 * HEIGHT)]
    return [min(xs), min(ys), max(xs), max(ys)]


def write_ppm(path, rows):
    body = bytearray()
    for row in rows:
        for px in row:
            body.extend(px[:3])
    path.write_bytes(f"P6\n{len(rows[0])} {len(rows)}\n255\n".encode("ascii") + bytes(body))


def sample_from(frame_pixels):
    """Crop the union rect out of a frame, as the implementation's raw sample would be."""
    x0, y0, x1, y1 = union_rect()
    return [row[x0:x1] for row in frame_pixels[y0:y1]]


def counts_in(sample):
    """Recount the sample the way the gate does, to build self-consistent fixtures."""
    x0, y0, _, _ = union_rect()
    def tally(key, want, split=None, right=False):
        ax, ay, bx, by = GEOMETRY[key]
        sx0 = int(((split if split is not None and right else ax) + 1.0) * 0.5 * WIDTH) - x0
        sx1 = int(((split if split is not None and not right else bx) + 1.0) * 0.5 * WIDTH) - x0
        sy0 = int((1.0 - max(ay, by)) * 0.5 * HEIGHT) - y0
        sy1 = int((1.0 - min(ay, by)) * 0.5 * HEIGHT) - y0
        hits = 0
        for y in range(max(0, sy0), min(len(sample), sy1)):
            for x in range(max(0, sx0), min(len(sample[0]), sx1)):
                if all(abs(sample[y][x][i] - want[i]) <= 60 for i in range(3)):
                    hits += 1
        return hits
    return {"near": tally("box", NEAR, GEOMETRY["nearSplitX"]),
            "far": tally("box", FAR, GEOMETRY["nearSplitX"], right=True),
            "cell": tally("depthTestedPassCell", REJECTED),
            "rejectedInBox": tally("box", REJECTED)}


def frame(near_fill=NEAR, far_fill=FAR, control_fill=REJECTED, box_fill=None,
          background=BACKGROUND, lone_far_pixel=False, cell_fill=REJECTED):
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
    if cell_fill is not None:
        ex0, ey0 = to_pixels(*GEOMETRY["depthTestedPassCell"][:2])
        ex1, ey1 = to_pixels(*GEOMETRY["depthTestedPassCell"][2:])
        for y in range(ey0, ey1):
            for x in range(ex0, ex1):
                pixels[y][x] = cell_fill
    return pixels


def region_area(key):
    """The pixel area a geometry region resolves to, the way the implementation computes it.

    ⚠ Round-5 review B1: the gate now requires the report's boxArea/controlArea to agree with
    the published geometry, because a shrunken crop was otherwise undetectable from the numbers
    alone. The fixture used to carry invented areas, which is exactly what that check rejects.
    """
    ax, ay, bx, by = GEOMETRY[key]
    x0 = int((min(ax, bx) + 1.0) * 0.5 * WIDTH)
    x1 = int((max(ax, bx) + 1.0) * 0.5 * WIDTH)
    y0 = int((1.0 - max(ay, by)) * 0.5 * HEIGHT)
    y1 = int((1.0 - min(ay, by)) * 0.5 * HEIGHT)
    return (x1 - x0) * (y1 - y0)


def report(**overrides):
    base = {"enabled": True, "pipelineLive": True, "drawsRecorded": 3000, "notes": [],
            "targetWidth": WIDTH, "targetHeight": HEIGHT,
            "readback": {"attempted": True, "completed": True, "near": 9690, "far": 6365,
                         "rejectedInBox": 0, "control": 3230,
                         "boxArea": region_area("box"),
                         "controlArea": region_area("depthTestedPassCell"),
                         "note": None, "timesClean": 7, "timesWithAProblem": 0,
                         "firstProblem": None, "sampleAtDraw": 2900,
                         "closeFailures": 0, "leakedPipelines": 0, "leakBudget": 3,
                         "failureBudget": 3, "autoAgree": True},
            "markerRgb": list(NEAR), "farRgb": list(FAR), "rejectedRgb": list(REJECTED),
            "depthAttached": True, "depthVkFormat": 126, "geometry": GEOMETRY,
            "device": "0xabc"}
    patch = overrides.pop("readback", None)
    base.update(overrides)
    if patch is not None:
        # ⚠ A readback override MERGES onto the fixture rather than replacing it, so a test
        # about one field does not silently drop every other required field — and so adding a
        # required field does not quietly weaken seven unrelated tests. Pass
        # readback_exact=... when a test is specifically about a field being absent.
        merged = dict(base["readback"])
        merged.update(patch)
        # ⚠ Round-5 review R5-TEST: `autoAgree` rewrites the aggregates from the sample, which
        # silently erased the very counts a test had set — the test then passed for the wrong
        # reason, or passed outright. A test that states a count means it.
        if {"near", "far", "control", "rejectedInBox"} & set(patch):
            merged.pop("autoAgree", None)
            merged["autoCounts"] = False
        base["readback"] = merged
    exact = base.pop("readback_exact", None)
    if exact is not None:
        base["readback"] = exact
    return base


class MarkerGateTest(unittest.TestCase):
    def run_gate(self, frames, marker_report, draws=None, covered=None, sample_from_stage=None):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            report = dict(marker_report)
            rb = report.get("readback")
            if isinstance(rb, dict) and rb.get("sampleFile") != "":
                stage = sample_from_stage or next(iter(frames))
                sample = sample_from(frames[stage])
                write_ppm(out / "sample.ppm", sample)
                counts = counts_in(sample)
                rb = dict(rb)
                rb.setdefault("sampleFile", "sample.ppm")
                rb["sampleRect"] = union_rect()
                rb["flipped"] = False
                rb.pop("autoCounts", None)
                if rb.pop("autoAgree", False):   # the default fixture: make it agree
                    rb.update(near=counts["near"], far=counts["far"], control=counts["cell"],
                              rejectedInBox=counts["rejectedInBox"])
                report["readback"] = rb
            (out / "native-marker-draw.json").write_text(json.dumps(report))
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
        self.assertIn("too few near/far", " ".join(result["failures"]))

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
        result = self.run_gate(frames, report(), sample_from_stage="warmup")
        self.assertFalse(result["success"])
        self.assertIn("not being depth-tested against anything", " ".join(result["failures"]))

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
        """With every captured frame empty, the gate must fail however good the readback is.

        ⚠ Round-5 review R5-TEST: this used to build the raw sample from the SAME empty frames,
        so `autoAgree` zeroed the counts and the gate failed with "too few near/far pixels" —
        before any screenshot was examined. It passed a broken screenshot gate. The sample now
        comes from a good frame while every captured frame is empty, which is the situation the
        name describes: an unimpeachable readback and nothing visible in any frame.
        """
        frames = {s: [[(2, 2, 2)] * WIDTH for _ in range(HEIGHT)] for s in STAGES}
        frames["good"] = frame()
        result = self.run_gate(frames, report(), sample_from_stage="good")
        self.assertFalse(result["success"])
        joined = " ".join(result["failures"])
        self.assertNotIn("too few near/far", joined,
                         "the readback must be accepted, so the failure has to come from the"
                         " screenshots: " + joined)
        self.assertIn("carried no marker", joined)

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
            pixels = frame()
            sample = sample_from(pixels)
            write_ppm(out / "sample.ppm", sample)
            counts = counts_in(sample)
            rep = report()
            rep["readback"] = dict(rep["readback"], sampleFile="sample.ppm",
                                   sampleRect=union_rect(), flipped=False,
                                   near=counts["near"], far=counts["far"],
                                   control=counts["cell"], rejectedInBox=counts["rejectedInBox"])
            rep["readback"].pop("autoAgree", None)
            (out / "native-marker-draw.json").write_text(json.dumps(rep))
            for stage in STAGES:
                write_png(out / (stage + ".png"), pixels)
            result = native_marker_result(out, [{"stage": s} for s in STAGES])
        self.assertFalse(result["success"])
        self.assertIn("how many marker draws", " ".join(result["failures"]))

    def test_a_missing_control_strip_means_the_third_draw_is_unproven(self):
        result = self.run_gate({s: frame(control_fill=None) for s in STAGES}, report())
        self.assertFalse(result["success"])
        self.assertIn("unproven", " ".join(result["failures"]))

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
                               "rejectedInBox": 7, "control": 3230, "boxArea": region_area("box"),
                               "controlArea": region_area("depthTestedPassCell"),
                               "note": "the rejected colour overlaps the rows the depth-tested"
                                       " box occupies, so depth is not working", "timesClean": 7, "timesWithAProblem": 0,
                               "firstProblem": None})
        result = self.run_gate({s: frame() for s in STAGES}, bad)
        self.assertFalse(result["success"])
        self.assertIn("depth is not working", " ".join(result["failures"]))

    def test_a_readback_note_of_any_kind_is_rejected(self):
        bad = report(readback={"attempted": True, "completed": True, "near": 9690, "far": 6365,
                               "rejectedInBox": 0, "control": 3230, "boxArea": region_area("box"),
                               "controlArea": region_area("depthTestedPassCell"),
                               "note": "the near and far quads are not side by side", "timesClean": 7, "timesWithAProblem": 0,
                               "firstProblem": None})
        result = self.run_gate({s: frame() for s in STAGES}, bad)
        self.assertFalse(result["success"])
        self.assertIn("not side by side", " ".join(result["failures"]))

    def test_the_readback_without_a_control_strip_is_rejected(self):
        bad = report(readback={"attempted": True, "completed": True, "near": 9690, "far": 6365,
                               "rejectedInBox": 0, "control": 0})
        result = self.run_gate({s: frame() for s in STAGES}, bad)
        self.assertFalse(result["success"])
        self.assertIn("unproven", " ".join(result["failures"]))

    def test_the_readback_missing_one_half_of_the_box_is_rejected(self):
        bad = report(readback={"near": 16000, "far": 0})
        result = self.run_gate({s: frame() for s in STAGES}, bad)
        self.assertFalse(result["success"])
        self.assertIn("too few near/far", " ".join(result["failures"]))

    def test_a_single_readback_is_not_enough(self):
        """Round-2 review B4: one readback says nothing about the rest of the lifecycle."""
        bad = report(readback={"attempted": True, "completed": True, "near": 9690, "far": 6365,
                               "rejectedInBox": 0, "control": 3230, "boxArea": region_area("box"),
                               "controlArea": region_area("depthTestedPassCell"), "note": None, "timesClean": 1, "timesWithAProblem": 0,
                               "firstProblem": None})
        result = self.run_gate({s: frame() for s in STAGES}, bad)
        self.assertFalse(result["success"])
        self.assertIn("across the run", " ".join(result["failures"]))

    def test_any_readback_problem_during_the_run_is_rejected(self):
        bad = report(readback={"attempted": True, "completed": True, "near": 9690, "far": 6365,
                               "rejectedInBox": 0, "control": 3230, "boxArea": region_area("box"),
                               "controlArea": region_area("depthTestedPassCell"), "note": None, "timesClean": 6, "timesWithAProblem": 1,
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

    def test_a_zero_target_size_is_rejected(self):
        """Round-4: the failure path wrote 0x0, which would make every recounted region empty."""
        for field in ("targetWidth", "targetHeight"):
            bad = report()
            bad[field] = 0
            result = self.run_gate({s: frame() for s in STAGES}, bad)
            self.assertFalse(result["success"], field)
            self.assertIn(field, " ".join(result["failures"]))

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
            "notes": ["drawIndirectFirstInstance: offset 44 verified by read-back",
                      "shaderInt64: offset 160 verified by read-back",
                      "fragmentStoresAndAtomics: offset 128 verified by read-back",
                      "vertexPipelineStoresAndAtomics: offset 124 verified by read-back"]},
        "native-compute-probe.json": {"attempted": True, "succeeded": True,
            "expected": "0x123456789abcdef", "readBack": "0x123456789abcdef",
            "queueFamily": 3, "device": "0x79b569e018", "notes": []},
        "native-adopted-context.json": {"enabled": True, "attempted": True, "adopted": True,
            "queueFamily": 0, "device": "0x79b569e018", "provenByVoxyBufferAndShader": True,
            "readBack": "0x123456789abcdef", "notes": []},
        "native-real-shader.json": {"attempted": True, "succeeded": True,
            "quadOrdinalsChecked": 189, "mismatches": 0, "firstMismatch": None,
            "device": "0x79b569e018", "notes": []},
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
                field = "vkDevice" if name == "native-vulkan-probe.json" else "device"
                result = self.run_gate({name: {field: "0x1234"}})
                self.assertFalse(result["success"])
                self.assertIn("but the lifecycle", " ".join(result["failures"]))

    def test_a_feature_note_that_is_not_a_verification_fails(self):
        result = self.run_gate({"native-device-features.json":
                                {"notes": ["could not find multiDrawIndirect; adding nothing"]}})
        self.assertFalse(result["success"])
        self.assertIn("not a read-back verification", " ".join(result["failures"]))

    def test_vacuous_feature_verification_fails(self):
        """Round-5 B3: an empty notes list passed, and so did a note NEGATING verification."""
        self.assertFalse(self.run_gate({"native-device-features.json": {"notes": []}})["success"])
        negation = self.run_gate({"native-device-features.json":
            {"notes": ["FAILED: not verified by read-back"]}})
        self.assertFalse(negation["success"])
        self.assertIn("not a read-back verification", " ".join(negation["failures"]))
        partial = self.run_gate({"native-device-features.json":
            {"notes": ["shaderInt64: offset 160 verified by read-back"]}})
        self.assertFalse(partial["success"], "one note cannot stand for four features")
        self.assertIn("are not the ones requested", " ".join(partial["failures"]))

    def test_an_unattempted_adoption_fails(self):
        """Round-5 B3: `attempted` was never read, so a proof that never ran still passed."""
        result = self.run_gate({"native-adopted-context.json": {"attempted": False}})
        self.assertFalse(result["success"])
        self.assertIn("never attempted", " ".join(result["failures"]))

    def test_jointly_empty_sentinel_values_fail(self):
        """Round-5 B3: expected=="" and readBack=="" compared equal and passed."""
        result = self.run_gate({"native-compute-probe.json": {"expected": "", "readBack": ""},
                                "native-adopted-context.json": {"readBack": ""}})
        self.assertFalse(result["success"])
        self.assertIn("sentinel", " ".join(result["failures"]))

    def test_a_null_device_handle_fails(self):
        """Round-5 B3: all-zero handles agreed with each other and passed this helper."""
        zeroed = {name: {"device": "0x0"} for name in (
            "native-compute-probe.json", "native-adopted-context.json",
            "native-real-shader.json", "native-marker-draw.json")}
        zeroed["native-vulkan-probe.json"] = {"vkDevice": "0x0"}
        result = self.run_gate(zeroed, checkpoints=[
            {"stage": s, "renderer": {"vkDevice": 0}} for s in STAGES])
        self.assertFalse(result["success"])
        self.assertIn("null device handle", " ".join(result["failures"]))

    def test_a_missing_first_mismatch_key_fails(self):
        """Round-5 B3: firstMismatch was read with .get(), so deleting it escaped the check."""
        files = {k: dict(v) for k, v in self.FILES.items()}
        del files["native-real-shader.json"]["firstMismatch"]
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            for name, body in files.items():
                (out / name).write_text(json.dumps(body))
            result = native_proof_files_result(out, self.CHECKPOINTS)
        self.assertFalse(result["success"])
        self.assertIn("firstMismatch", " ".join(result["failures"]))

    def test_marker_and_probe_failure_flags_are_checked_here_too(self):
        """Round-5 B3: this helper loaded them for handles only, and replay calls it alone."""
        for patch, expect in (({"native-marker-draw.json": {"enabled": False}}, "not enabled"),
                              ({"native-marker-draw.json": {"pipelineLive": False}}, "live"),
                              ({"native-marker-draw.json": {"notes": ["x"]}}, "reported notes"),
                              ({"native-vulkan-probe.json": {"mcUsesVulkan": False}}, "Vulkan backend"),
                              ({"native-vulkan-probe.json": {"notes": ["x"]}}, "reported notes")):
            result = self.run_gate(patch)
            self.assertFalse(result["success"], patch)
            self.assertIn(expect, " ".join(result["failures"]))

    def test_an_adopted_proof_reading_back_the_wrong_value_fails(self):
        result = self.run_gate({"native-adopted-context.json": {"readBack": "0xdeadbeef"}})
        self.assertFalse(result["success"])
        self.assertIn("not the expected", " ".join(result["failures"]))

    def test_a_named_mismatch_fails_even_when_the_count_is_zero(self):
        result = self.run_gate({"native-real-shader.json":
                                {"firstMismatch": "ordinal 7: GPU (1,2) vs CPU (3,4)"}})
        self.assertFalse(result["success"])
        self.assertIn("names a mismatch", " ".join(result["failures"]))

    def test_a_proof_that_was_never_attempted_fails(self):
        for name in ("native-compute-probe.json", "native-real-shader.json"):
            with self.subTest(name=name):
                result = self.run_gate({name: {"attempted": False}})
                self.assertFalse(result["success"])

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




class EvidenceRetentionTest(unittest.TestCase):
    """Round-4 review B1: retention failed silently, and kept nothing authoritative.

    The reviewer called the committed gates on a retained directory and they failed for want of
    the full screenshots — which are too large to commit and were only ever supporting. What the
    verdict actually rests on is the readback of Minecraft's own colour image, and none of its
    pixels were kept. These tests require the raw sample to be retained, require a run that
    cannot retain it to fail, and require the retained directory to replay on its own.
    """

    def populate(self, native_output, with_sample=True):
        pixels = frame()
        rb = {"attempted": True, "completed": True, "rejectedInBox": 0,
              "boxArea": region_area("box"),
              "controlArea": region_area("depthTestedPassCell"), "note": None,
              "timesClean": 7, "timesWithAProblem": 0,
              "firstProblem": None, "flipped": False, "sampleAtDraw": 2900,
              "sampleRect": union_rect(), "closeFailures": 0, "failureBudget": 3,
              "leakedPipelines": 0, "leakBudget": 3}
        sample = sample_from(pixels)
        counts = counts_in(sample)
        rb.update(near=counts["near"], far=counts["far"], control=counts["cell"],
                  sampleFile="native-marker-sample-2900.ppm" if with_sample else None)
        marker = report(readback=rb,
                        device=ProofFileGateTest.FILES["native-marker-draw.json"]["device"])
        marker["readback"].pop("autoAgree", None)
        (native_output / "native-marker-draw.json").write_text(json.dumps(marker))
        for stage in STAGES:
            write_png(native_output / (stage + ".png"), pixels)
        if with_sample:
            write_ppm(native_output / "native-marker-sample-2900.ppm", sample)
        for name, body in ProofFileGateTest.FILES.items():
            if name == "native-marker-draw.json":
                continue
            (native_output / name).write_text(json.dumps(body))
        return marker

    def retain(self, with_sample=True):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        native_output = root / "native"
        native_output.mkdir()
        self.populate(native_output, with_sample)
        (root / "native.log").write_text("log\n")
        (root / "summary.json").write_text(json.dumps({"stages": {"native_environment":
            {"gate": {"checkpoints": ProofFileGateTest.CHECKPOINTS}}}}))
        original = verify.ROOT
        verify.ROOT = root
        self.addCleanup(lambda: setattr(verify, "ROOT", original))
        kept = retain_native_evidence(root, native_output, "run", {"revision": "abc"})
        return root, kept

    def test_the_raw_colour_sample_is_retained_and_hashed(self):
        _, kept = self.retain()
        self.assertNotIn("error", kept)
        self.assertEqual(kept["raw_colour_samples"], ["native-marker-sample-2900.ppm"])
        self.assertIn("native-marker-sample-2900.ppm", kept["files"])

    def test_every_crop_states_its_origin_rather_than_implying_it(self):
        """⚠ Round-5 review R5-TEST: this checked the parent size and that the origin had two
        entries, so a hardcoded [0, 0] would have passed. Decode each crop and require the
        pixels at the stated origin to be the pixels of the parent frame there."""
        root, kept = self.retain()
        target = root / "docs" / "ai" / "runs" / "native-evidence" / "run"
        parent = frame()
        self.assertTrue(kept["crops"])
        checked = 0
        for name in kept["crops"]:
            origin = kept["crop_origins"][name]
            self.assertEqual(origin["parent"], [WIDTH, HEIGHT])
            x0, y0 = origin["origin"]
            w, h = origin["size"]
            rows, decoded = top_rows_rgb(target / name, h)
            self.assertEqual(decoded, (w, h), name)
            for y in range(h):
                self.assertEqual(rows[y][:w], parent[y0 + y][x0:x0 + w],
                                 f"{name} row {y} does not match the parent at {(x0, y0)}")
            checked += 1
        self.assertGreater(checked, 0)

    def test_a_run_that_retains_no_raw_sample_reports_an_error(self):
        _, kept = self.retain(with_sample=False)
        self.assertIn("error", kept)
        self.assertIn("authoritative", kept["error"])

    def replay(self, target):
        quiet = io.StringIO()
        with contextlib.redirect_stdout(quiet):
            code = replay_evidence(target)
        return code, json.loads(quiet.getvalue())

    def test_the_retained_directory_replays_on_its_own(self):
        """⚠ Round-5 review R5-TEST: this passed with `recount_marker_sample` stubbed to return
        {} — it asserted on a printed label and a checkpoint count, never on a pixel. Require
        the recount's actual numbers, and require them to agree with the sample on disk."""
        root, _ = self.retain()
        target = root / "docs" / "ai" / "runs" / "native-evidence" / "run"
        code, out = self.replay(target)
        self.assertEqual(code, 0, out)
        self.assertIn("marker report acceptance checks", out["replayed"])
        self.assertEqual(out["checkpoints"], len(STAGES))
        counts = counts_in(sample_from(frame()))
        recount = out["recount"]
        self.assertEqual(recount["near"], counts["near"])
        self.assertEqual(recount["far"], counts["far"])
        self.assertEqual(recount["cell"], counts["cell"])
        self.assertEqual(recount["rejectedInBox"], 0)
        self.assertEqual(recount["nearArea"], recount["near"])

    def test_replay_rejects_a_report_that_records_its_own_failure(self):
        """⚠ Round-5 review B1: these exact mutations all replayed as 0."""
        for patch in ({"readback": {"attempted": False, "completed": False}},
                      {"readback": {"timesClean": 0, "timesWithAProblem": 3,
                                    "note": "FAILED", "firstProblem": "FAILED"}},
                      {"readback": {"closeFailures": 99}},
                      {"readback": {"leakedPipelines": 2}},
                      {"enabled": False}, {"pipelineLive": False},
                      {"drawsRecorded": 0}, {"depthAttached": False},
                      {"notes": ["FAILED"]}):
            root, _ = self.retain()
            target = root / "docs" / "ai" / "runs" / "native-evidence" / "run"
            path = target / "native-marker-draw.json"
            body = json.loads(path.read_text())
            for key, value in patch.items():
                if key == "readback":
                    body["readback"].update(value)
                else:
                    body[key] = value
            path.write_text(json.dumps(body))
            # Re-hash so this is a semantic challenge, not a tampering-detection one.
            manifest = json.loads((target / "MANIFEST.json").read_text())
            manifest["files"]["native-marker-draw.json"] = hashlib.sha256(
                path.read_bytes()).hexdigest()
            (target / "MANIFEST.json").write_text(json.dumps(manifest))
            code, out = self.replay(target)
            self.assertEqual(code, 1, f"{patch} replayed as success: {out}")

    def test_replay_without_a_manifest_fails(self):
        """⚠ Round-5 review B1: removing MANIFEST.json altogether replayed as 0."""
        root, _ = self.retain()
        target = root / "docs" / "ai" / "runs" / "native-evidence" / "run"
        (target / "MANIFEST.json").unlink()
        code, out = self.replay(target)
        self.assertEqual(code, 1)
        self.assertIn("MANIFEST", out["error"])

    def test_losing_the_referenced_sample_fails_retention(self):
        """⚠ Round-5 review B1: duplicating a sample and deleting the referenced one left
        retention reporting no error, so the stage stayed green and replay then failed."""
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        native_output = root / "native"
        native_output.mkdir()
        self.populate(native_output)
        (root / "native.log").write_text("log\n")
        decoy = native_output / "native-marker-sample-1.ppm"
        decoy.write_bytes((native_output / "native-marker-sample-2900.ppm").read_bytes())
        (native_output / "native-marker-sample-2900.ppm").unlink()
        original = verify.ROOT
        verify.ROOT = root
        self.addCleanup(lambda: setattr(verify, "ROOT", original))
        kept = retain_native_evidence(root, native_output, "run", {"revision": "abc"})
        self.assertIn("error", kept)
        self.assertIn("native-marker-sample-2900.ppm", kept["error"])

    def test_replay_fails_when_a_retained_file_was_altered(self):
        root, _ = self.retain()
        target = root / "docs" / "ai" / "runs" / "native-evidence" / "run"
        (target / "native-marker-sample-2900.ppm").write_bytes(b"P6\n1 1\n255\n\x00\x00\x00")
        code, out = self.replay(target)
        self.assertEqual(code, 1)
        self.assertEqual(out["manifest_mismatches"], ["native-marker-sample-2900.ppm"])


class TerrainGateTest(unittest.TestCase):
    """The terrain gate must not accept a frame that proves nothing.

    The claim is narrow and falsifiable: the same synthetic scene, drawn by the same renderer
    into Minecraft's colour image and into Voxy's own target on the same device, is identical
    in RGB. These cases are the ways that claim could be faked — identical blank frames, an
    aggregate that disagrees with the pixels, a missing sample, a swallowed close failure.
    """
    W, H = 64, 40
    CLEAR_RGB = (13, 13, 26)
    PACKED_CLEAR = CLEAR_RGB[0] | (CLEAR_RGB[1] << 8) | (CLEAR_RGB[2] << 16)

    def scene(self, blank=False, differ=0):
        """A frame pair: mostly background with a block of terrain-ish colour."""
        got, want = [], []
        for y in range(self.H):
            a, b = [], []
            for x in range(self.W):
                px = self.CLEAR_RGB if (blank or y < 2) else ((40 + x) & 0xFF, 90, 150)
                a.append(px)
                b.append(px)
            got.append(a)
            want.append(b)
        for i in range(differ):
            got[2 + i // self.W][i % self.W] = (255, 255, 255)
        return got, want

    def write_gz_ppm(self, path, rows):
        import gzip
        body = bytearray()
        for row in rows:
            for px in row:
                body.extend(px[:3])
        with gzip.open(path, "wb") as out:
            out.write(f"P6\n{len(rows[0])} {len(rows)}\n255\n".encode("ascii"))
            out.write(bytes(body))

    def counts(self, got, want):
        differ = native = reference = 0
        for y in range(len(got)):
            for x in range(len(got[0])):
                if got[y][x] != want[y][x]:
                    differ += 1
                if got[y][x] != self.CLEAR_RGB:
                    native += 1
                if want[y][x] != self.CLEAR_RGB:
                    reference += 1
        return differ, native, reference

    def run_gate(self, overrides=None, comparison=None, blank=False, differ=0,
                 drop=(), agree=True):
        got, want = self.scene(blank=blank, differ=differ)
        d, native, reference = self.counts(got, want)
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            name = "native-terrain-sample-900.ppm.gz"
            if "sample" not in drop:
                self.write_gz_ppm(out / name, got)
                self.write_gz_ppm(out / "native-terrain-reference-900.ppm.gz", want)
            c = {"attempted": True, "completed": True,
                 "referenceSet": reference if agree else reference + 1,
                 "nativeSet": native if agree else native,
                 "mismatches": d if agree else 0,
                 "flipped": True, "note": None, "sampleAtDraw": 900, "sampleFile": name}
            c.update(comparison or {})
            report = {"enabled": True, "attempted": True, "built": True, "drawsRecorded": 1200,
                      "targetWidth": self.W, "targetHeight": self.H, "colourVkFormat": 37,
                      "clearRgb": self.PACKED_CLEAR, "device": "0xabc", "timesClean": 5,
                      "timesWithAProblem": 0, "firstProblem": None, "closeFailures": 0,
                      "failureBudget": 3, "leakedProbes": 0, "leakBudget": 3,
                      "notes": [], "comparison": c}
            report.update(overrides or {})
            for key in drop:
                report.pop(key, None)
            (out / "native-terrain-probe.json").write_text(json.dumps(report))
            return verify.native_terrain_result(out)

    def test_an_identical_pair_with_real_content_passes(self):
        result = self.run_gate()
        self.assertTrue(result["success"], result["failures"])
        self.assertEqual(result["recompare"]["mismatches"], 0)
        self.assertGreater(result["recompare"]["nativeSet"], 1000)

    def test_two_identical_blank_frames_prove_nothing(self):
        result = self.run_gate(blank=True)
        self.assertFalse(result["success"])
        self.assertIn("non-background", " ".join(result["failures"]))

    def test_differing_pixels_fail_even_when_the_aggregate_says_zero(self):
        result = self.run_gate(differ=30, agree=False)
        self.assertFalse(result["success"])
        self.assertIn("differ", " ".join(result["failures"]))

    def test_an_aggregate_that_disagrees_with_the_pixels_fails(self):
        result = self.run_gate(agree=False)
        self.assertFalse(result["success"])
        self.assertIn("does not match the pixels", " ".join(result["failures"]))

    def test_a_missing_sample_fails(self):
        result = self.run_gate(drop=("sample",))
        self.assertFalse(result["success"])
        self.assertIn("missing", " ".join(result["failures"]))

    def test_a_probe_that_never_built_fails(self):
        result = self.run_gate({"built": False})
        self.assertFalse(result["success"])
        self.assertIn("never built", " ".join(result["failures"]))

    def test_any_note_or_problem_fails(self):
        self.assertFalse(self.run_gate({"notes": ["could not adopt"]})["success"])
        self.assertFalse(self.run_gate({"timesWithAProblem": 1})["success"])
        self.assertFalse(self.run_gate(comparison={"note": "nothing reached the frame"})["success"])

    def test_an_unclosed_readback_buffer_fails(self):
        result = self.run_gate({"closeFailures": 1})
        self.assertFalse(result["success"])
        self.assertIn("could not be closed", " ".join(result["failures"]))

    def test_a_leaked_probe_fails(self):
        """Round-5 R4-L1: a leak that is counted but tolerated is still an unbounded leak."""
        result = self.run_gate({"leakedProbes": 1})
        self.assertFalse(result["success"])
        self.assertIn("leaked", " ".join(result["failures"]))

    def test_a_comparison_that_never_completed_fails(self):
        result = self.run_gate(comparison={"completed": False})
        self.assertFalse(result["success"])
        self.assertIn("did not complete", " ".join(result["failures"]))

    def test_a_missing_field_fails_instead_of_defaulting(self):
        for field in ("enabled", "built", "closeFailures", "clearRgb", "notes",
                      "leakedProbes", "leakBudget"):
            result = self.run_gate(drop=(field,))
            self.assertFalse(result["success"], field)
            self.assertIn(field, " ".join(result["failures"]))

    def test_a_sample_not_bound_to_its_capture_fails(self):
        """Round-5 B4: the file name carries the draw the copy was registered at."""
        result = self.run_gate(comparison={"sampleAtDraw": 4321})
        self.assertFalse(result["success"])
        self.assertIn("named for draw", " ".join(result["failures"]))

    def test_samples_whose_size_contradicts_the_report_fail(self):
        result = self.run_gate({"targetWidth": self.W + 1})
        self.assertFalse(result["success"])
        self.assertIn("but the probe drew to", " ".join(result["failures"]))


# ⚠ Round-5 review R5-TEST: this guard used to sit in the middle of the file, so running it
# directly exited before the later test classes were even defined. Discovery found them;
# `python3 scripts/tests/test_marker_gate.py` silently did not. It belongs at the end.
if __name__ == "__main__":
    unittest.main()
