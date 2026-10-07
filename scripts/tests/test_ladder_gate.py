"""The per-pixel depth-ladder gate must reject anything that is not a measurement.

The ladder draws, over ONE band with depth writes off: an ALWAYS base, a GREATER control at
the smallest rung, then eight LESS rungs ascending, each in its own colour. A pixel's final
colour therefore encodes the bracket its Minecraft depth falls in. The gate recounts every
retained crop pixel by pixel in the published orientation, requires the rejected orientation's
crop to be mostly scene, and never emits a band-wide bound or a convention.

Each case here is a way a previous review got past a gate: an aggregate the pixels do not
support, a sample bound to the wrong capture, an orientation the recount guessed, a published
reference the producer controls (depths, band, palette, frame extent), NaN where a number was
expected, a contaminated run passed off as a measurement, and a gate the replay did not call.
The fixtures paint a synthetic DEPTH FIELD and derive the colours from it with the same rule
the GPU applies, so the test cannot pass a gate that reads the colours wrongly.
"""
import contextlib
import hashlib
import io
import json
import math
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify
from verify import native_ladder_result, retain_native_evidence, replay_evidence
import test_marker_gate
from test_marker_gate import write_gz_ppm

# ⚠ Full-frame size matters: the band is resolved through the implementation's own
# NDC-to-pixel formula over this size. Do not shrink it without checking the band still
# resolves to a non-empty rectangle at every orientation.
FULL_W, FULL_H = 960, 540
EXTENTS = {(FULL_W, FULL_H)}
RUNGS = verify.LADDER_RUNGS
D = verify.EXPECTED_LADDER_DEPTHS
PALETTE = [tuple(int(round(c * 255)) for c in colour) for colour in verify.EXPECTED_LADDER_PALETTE]
BASE, LOW, RUNG0 = verify.LADDER_BASE, verify.LADDER_LOW, verify.LADDER_RUNG0
SCENE = (60, 120, 30)  # grass-ish: a channel in the gap, so "other"
# The device the shared lifecycle-checkpoint fixture names; the ladder must name the same one.
DEVICE = verify.device_handle_of(
    test_marker_gate.ProofFileGateTest.CHECKPOINTS[0]["renderer"]["vkDevice"], "fixture", "vkDevice")


def depth_field(width, height, kind="gradient"):
    """A synthetic per-pixel depth field over the band, in the depth test's own terms."""
    rows = []
    for y in range(height):
        row = []
        for x in range(width):
            if kind == "gradient":
                # left to right: from below the smallest rung to above the largest
                t = x / max(1, width - 1)
                d = 2.0 ** (-18 + 18 * t)
            elif kind == "zero":
                d = 0.0
            elif kind == "clouds":
                d = 3e-5 if (x // 40 + y // 7) % 2 else 0.0
            else:
                raise ValueError(kind)
            row.append(d)
        rows.append(row)
    return rows


def colour_of(d):
    """What the GPU leaves at a pixel of depth d: GREATER control, else the last LESS rung."""
    if d < D[0]:
        return PALETTE[LOW]
    passed = [i for i in range(RUNGS) if D[i] < d]
    return PALETTE[RUNG0 + passed[-1]] if passed else PALETTE[BASE]


def counts_of(field):
    counts = {"anomaly": 0, "low": 0, "rungs": [0] * RUNGS, "other": 0}
    for row in field:
        for d in row:
            c = colour_of(d)
            if c == PALETTE[BASE]:
                counts["anomaly"] += 1
            elif c == PALETTE[LOW]:
                counts["low"] += 1
            else:
                counts["rungs"][PALETTE.index(c) - RUNG0] += 1
    return counts


def crops(field, flipped=False):
    """The selected crop (the band, painted from the field) and the rejected crop (scene)."""
    selected = [[colour_of(d) for d in row] for row in field]
    rejected_rect = verify.ladder_band_rect(FULL_W, FULL_H, not flipped)
    rw, rh = rejected_rect[2] - rejected_rect[0], rejected_rect[3] - rejected_rect[1]
    rejected = [[SCENE] * rw for _ in range(rh)]
    return selected, rejected


def band_size(flipped=False):
    rect = verify.ladder_band_rect(FULL_W, FULL_H, flipped)
    return rect[2] - rect[0], rect[3] - rect[1]


def sample(at=2, flipped=False, kind="gradient", field=None):
    w, h = band_size(flipped)
    field = depth_field(w, h, kind) if field is None else field
    rect = verify.ladder_band_rect(FULL_W, FULL_H, flipped)
    rejected_rect = verify.ladder_band_rect(FULL_W, FULL_H, not flipped)
    rw, rh = rejected_rect[2] - rejected_rect[0], rejected_rect[3] - rejected_rect[1]
    body = {"at": at, "flipped": flipped, "targetWidth": FULL_W, "targetHeight": FULL_H,
            "rect": rect, "counts": counts_of(field), "rejectedFlipped": not flipped,
            "rejectedRect": rejected_rect, "rejectedOther": rw * rh,
            "file": f"native-depth-ladder-{at}.ppm.gz",
            "rejectedFile": f"native-depth-ladder-rejected-{at}.ppm.gz"}
    return body, field


def report(samples=None, **overrides):
    body = {"enabled": True, "attempted": True, "completed": True, "drawsRecorded": 5000,
            "rungs": RUNGS, "depthWritesEnabled": False, "zConventionMeasuredHere": False,
            "rungDepths": list(D), "band": list(verify.EXPECTED_LADDER_BAND),
            "palette": [list(c) for c in verify.EXPECTED_LADDER_PALETTE],
            "readbackInterval": 240, "sampleLimit": 24,
            "samples": samples if samples is not None else [sample()[0]],
            "problems": 0, "firstProblem": None, "closeFailures": 0, "leakedPipelines": 0,
            "deviceDiverged": False, "terrainProbeEnabled": False, "terrainDrawsRecorded": 0,
            "markerDrawEnabled": False, "markerDrawsRecorded": 0, "device": hex(DEVICE),
            "notes": []}
    body.update(overrides)
    return body


def write_sample_files(out, body, field, selected=None, rejected=None):
    sel, rej = crops(field, body.get("flipped", False))
    if body.get("file"):
        write_gz_ppm(out / body["file"], selected if selected is not None else sel)
    if body.get("rejectedFile"):
        write_gz_ppm(out / body["rejectedFile"], rejected if rejected is not None else rej)


class LadderGateTest(unittest.TestCase):
    def run_gate(self, body=None, fields=None, drop=(), skip_files=False, expected_device=DEVICE,
                 expected_extents=EXTENTS, selected=None, rejected=None):
        if body is None:
            s, field = sample()
            body, fields = report(samples=[s]), [field]
        for key in drop:
            body.pop(key, None)
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            if not skip_files:
                for s, field in zip(body.get("samples") or [], fields or []):
                    write_sample_files(out, s, field, selected, rejected)
            (out / "native-depth-ladder.json").write_text(json.dumps(body))
            return native_ladder_result(out, expected_device, expected_extents)

    def one(self, **kw):
        """A report with one sample built from kw, plus its field."""
        s, field = sample(**kw)
        return report(samples=[s]), [field]

    def assertFails(self, result, *fragments):
        self.assertFalse(result["success"], result.get("answer"))
        joined = " ".join(result["failures"])
        for fragment in fragments:
            self.assertIn(fragment, joined)

    # ---- positive cases: the gate reads a depth field back exactly ----

    def test_a_gradient_field_is_bracketed_pixel_by_pixel(self):
        body, fields = self.one()
        result = self.run_gate(body, fields)
        self.assertTrue(result["success"], result["failures"])
        recount = result["samples"][0]
        self.assertEqual(recount["rungs"], body["samples"][0]["counts"]["rungs"])
        self.assertEqual(recount["low"], body["samples"][0]["counts"]["low"])
        self.assertEqual(recount["other"], 0)
        self.assertGreater(recount["low"], 0)
        self.assertTrue(all(n > 0 for n in recount["rungs"]), recount["rungs"])
        self.assertIn("which direction is nearer is unmeasured", result["answer"])
        self.assertFalse(result["z_convention_measured"])
        self.assertNotIn("bounds", result)

    def test_a_cleared_field_is_all_low_and_still_a_measurement(self):
        """Every pixel below the smallest rung: the GREATER control catches all of them."""
        body, fields = self.one(kind="zero")
        result = self.run_gate(body, fields)
        self.assertTrue(result["success"], result["failures"])
        w, h = band_size()
        self.assertEqual(result["samples"][0]["low"], w * h)
        self.assertEqual(result["samples"][0]["rungs"], [0] * RUNGS)

    def test_a_flipped_sample_passes_when_the_orientation_is_published(self):
        body, fields = self.one(flipped=True, kind="clouds")
        result = self.run_gate(body, fields)
        self.assertTrue(result["success"], result["failures"])
        self.assertTrue(result["samples"][0]["flipped"])

    def test_several_samples_are_each_recounted(self):
        s1, f1 = sample(at=2, kind="clouds")
        s2, f2 = sample(at=242, kind="gradient")
        result = self.run_gate(report(samples=[s1, s2]), [f1, f2])
        self.assertTrue(result["success"], result["failures"])
        self.assertEqual([s["at"] for s in result["samples"]], [2, 242])
        self.assertIn("draw 242", result["answer"])

    # ---- the pixels decide, not the report ----

    def test_counts_the_pixels_do_not_support_fail(self):
        body, fields = self.one()
        body["samples"][0]["counts"]["rungs"][3] += 5
        body["samples"][0]["counts"]["low"] -= 5
        self.assertFails(self.run_gate(body, fields), "does not match the pixels")

    def test_a_scene_pixel_inside_the_band_fails(self):
        """One pixel outside the palette means the band was not drawn there."""
        body, fields = self.one()
        sel, rej = crops(fields[0])
        sel[3][7] = SCENE
        body["samples"][0]["counts"]["other"] = 1
        body["samples"][0]["counts"]["low"] -= 1
        self.assertFails(self.run_gate(body, fields, selected=sel), "outside the palette")

    def test_an_anomaly_pixel_fails(self):
        """A pixel that passed neither LESS nor GREATER is a depth path that did not decide."""
        body, fields = self.one()
        sel, rej = crops(fields[0])
        sel[2][2] = PALETTE[BASE]
        body["samples"][0]["counts"]["anomaly"] = 1
        body["samples"][0]["counts"]["low"] -= 1
        self.assertFails(self.run_gate(body, fields, selected=sel), "neither the LESS rungs")

    def test_a_rejected_orientation_that_also_looks_drawn_fails(self):
        body, fields = self.one()
        rw, rh = band_size(True)
        painted = [[PALETTE[LOW]] * rw for _ in range(rh)]
        body["samples"][0]["rejectedOther"] = 0
        self.assertFails(self.run_gate(body, fields, rejected=painted), "both orientations")

    def test_lying_about_the_orientation_fails(self):
        """The crops were taken flipped; the report says they were not."""
        body, fields = self.one(flipped=True, kind="clouds")
        body["samples"][0]["flipped"] = False
        body["samples"][0]["rejectedFlipped"] = True
        self.assertFails(self.run_gate(body, fields), "rect")

    def test_a_rejected_other_count_the_pixels_contradict_fails(self):
        body, fields = self.one()
        body["samples"][0]["rejectedOther"] -= 1
        self.assertFails(self.run_gate(body, fields), "outside the palette, not the published")

    # ---- the sample must be the capture the report describes ----

    def test_a_sample_named_for_a_different_draw_fails(self):
        body, fields = self.one(at=242)
        body["samples"][0]["file"] = "native-depth-ladder-2.ppm.gz"
        self.assertFails(self.run_gate(body, fields), "not the crop of draw 242")

    def test_a_capture_outside_the_recorded_draws_fails(self):
        body, fields = self.one(at=0)
        self.assertFails(self.run_gate(body, fields), "not bound to any capture")
        body, fields = self.one(at=9000)
        self.assertFails(self.run_gate(body, fields), "only 5000 ladder draws")

    def test_samples_out_of_order_fail(self):
        s1, f1 = sample(at=242)
        s2, f2 = sample(at=2)
        self.assertFails(self.run_gate(report(samples=[s1, s2]), [f1, f2]), "does not follow")

    def test_a_missing_crop_fails(self):
        body, fields = self.one()
        self.assertFails(self.run_gate(body, fields, skip_files=True), "is missing")

    def test_no_samples_fail(self):
        self.assertFails(self.run_gate(report(samples=[]), []), "retained no sample")

    def test_a_crop_of_the_wrong_size_fails(self):
        body, fields = self.one()
        self.assertFails(self.run_gate(body, fields, selected=[[PALETTE[LOW]]]), "is 1x1")

    # ---- references the producer controls are not references ----

    def test_a_frame_extent_no_checkpoint_saw_fails(self):
        """Round-8: a self-consistent tiny frame stood in for the real one."""
        body, fields = self.one()
        self.assertFails(self.run_gate(body, fields, expected_extents={(1920, 1080)}),
                         "no lifecycle checkpoint")

    def test_a_rect_that_is_not_the_band_fails(self):
        body, fields = self.one()
        body["samples"][0]["rect"][0] += 1
        self.assertFails(self.run_gate(body, fields), "the band resolves to")

    def test_published_depths_band_or_palette_that_differ_from_the_source_fail(self):
        body, fields = self.one()
        body["rungDepths"][0] *= 2
        self.assertFails(self.run_gate(body, fields), "its source lays out")
        body, fields = self.one()
        body["band"] = [v + 0.05 for v in body["band"]]
        self.assertFails(self.run_gate(body, fields), "not a reference")
        body, fields = self.one()
        body["palette"][2] = [0.0, 0.0, 0.0]
        self.assertFails(self.run_gate(body, fields), "palette")

    def test_nan_anywhere_fails(self):
        """Round-8: NaN passed every tolerance comparison."""
        for patch in ({"rungDepths": [math.nan] * RUNGS}, {"band": [math.nan] * 4},
                      {"palette": [[math.nan] * 3] * 10}):
            body, fields = self.one()
            body.update(patch)
            text = json.dumps(body)
            self.assertIn("NaN", text)
            with tempfile.TemporaryDirectory() as tmp:
                out = Path(tmp)
                write_sample_files(out, body["samples"][0], fields[0])
                (out / "native-depth-ladder.json").write_text(text)
                result = native_ladder_result(out, DEVICE, EXTENTS)
            self.assertFalse(result["success"], patch)

    def test_a_wrong_number_of_rungs_fails(self):
        body, fields = self.one()
        body["rungs"] = 4
        self.assertFails(self.run_gate(body, fields), "4 rungs")

    # ---- what the ladder may never claim ----

    def test_depth_writes_enabled_fails(self):
        body, fields = self.one()
        body["depthWritesEnabled"] = True
        self.assertFails(self.run_gate(body, fields), "measured its own depth")

    def test_claiming_the_convention_or_a_bound_fails(self):
        for patch in ({"zConventionMeasuredHere": True}, {"reversedZ": True},
                      {"upperBound": 0.1}, {"lowerBound": None}):
            body, fields = self.one()
            body.update(patch)
            self.assertFails(self.run_gate(body, fields))

    # ---- contamination ----

    def test_a_terrain_or_marker_contaminated_run_fails(self):
        for patch, fragment in (({"terrainProbeEnabled": True}, "clears Minecraft's depth"),
                                ({"terrainDrawsRecorded": 1}, "clears Minecraft's depth"),
                                ({"markerDrawEnabled": True}, "writes depth"),
                                ({"markerDrawsRecorded": 3}, "writes depth")):
            body, fields = self.one()
            body.update(patch)
            self.assertFails(self.run_gate(body, fields), fragment)

    # ---- identity and lifetime ----

    def test_a_null_or_foreign_device_fails(self):
        for device, fragment in (("0x0", "null device handle"), ("0xdead", "checkpoints saw"),
                                 ("nope", "not a device handle")):
            body, fields = self.one()
            body["device"] = device
            self.assertFails(self.run_gate(body, fields), fragment)

    def test_a_decimal_device_is_the_same_device(self):
        body, fields = self.one()
        body["device"] = str(DEVICE)
        self.assertTrue(self.run_gate(body, fields)["success"])

    def test_notes_problems_leaks_or_divergence_fail(self):
        for patch, fragment in (({"notes": ["FAILED"]}, "notes"), ({"problems": 1}, "problems=1"),
                                ({"closeFailures": 1}, "closeFailures=1"),
                                ({"leakedPipelines": 1}, "leakedPipelines=1"),
                                ({"deviceDiverged": True}, "diverged")):
            body, fields = self.one()
            body.update(patch)
            self.assertFails(self.run_gate(body, fields), fragment)

    def test_a_ladder_that_never_ran_fails(self):
        for patch, fragment in (({"completed": False}, "never completed"),
                                ({"attempted": False}, "never completed"),
                                ({"enabled": False}, "not enabled"),
                                ({"drawsRecorded": 0}, "no ladder draw")):
            body, fields = self.one()
            body.update(patch)
            self.assertFails(self.run_gate(body, fields), fragment)

    def test_a_missing_field_fails_instead_of_defaulting(self):
        for field in ("enabled", "attempted", "completed", "drawsRecorded", "rungs",
                      "depthWritesEnabled", "zConventionMeasuredHere", "rungDepths", "band",
                      "palette", "samples", "problems", "closeFailures", "leakedPipelines",
                      "deviceDiverged", "notes", "terrainProbeEnabled", "terrainDrawsRecorded",
                      "markerDrawEnabled", "markerDrawsRecorded", "device"):
            body, fields = self.one()
            result = self.run_gate(body, fields, drop=(field,))
            self.assertFalse(result["success"], field)
            self.assertIn(field, " ".join(result["failures"]))
        for field in ("at", "flipped", "targetWidth", "targetHeight", "rect", "counts",
                      "rejectedFlipped", "rejectedRect", "rejectedOther", "file", "rejectedFile"):
            body, fields = self.one()
            body["samples"][0].pop(field)
            result = self.run_gate(body, fields)
            self.assertFalse(result["success"], field)
            self.assertIn(field, " ".join(result["failures"]))

    def test_a_field_of_the_wrong_type_fails(self):
        body, fields = self.one()
        body["samples"][0]["flipped"] = "false"
        self.assertFails(self.run_gate(body, fields), "flipped")
        body, fields = self.one()
        body["samples"][0]["at"] = "2"
        self.assertFails(self.run_gate(body, fields), "at")
        body, fields = self.one()
        body["terrainDrawsRecorded"] = False
        self.assertFails(self.run_gate(body, fields), "terrainDrawsRecorded")


class LadderRetentionTest(unittest.TestCase):
    """The ladder's own launch must be retained under ladder/ and must replay with the same
    gate, tied to that launch's own checkpoints — or replay must say it was not retained."""

    def build(self, kind="gradient", flipped=False, with_files=True, with_ladder=True,
              with_own_result=True, samples=None):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        native_output = root / "native"
        native_output.mkdir()
        helper = test_marker_gate.EvidenceRetentionTest()
        helper.populate(native_output)
        (root / "native.log").write_text("log\n")
        # The shared checkpoint fixture names a device; the ladder also needs the colour
        # extent each checkpoint observed, which is what pins the sample's frame size.
        checkpoints = json.loads(json.dumps(test_marker_gate.ProofFileGateTest.CHECKPOINTS))
        for case in checkpoints:
            case["renderer"]["colour"] = {"vkImage": 15, "vkImageView": 16,
                                          "width": FULL_W, "height": FULL_H}
        stage = {"gate": {"checkpoints": checkpoints}}
        if with_ladder:
            ladder_output = root / "native-ladder"
            ladder_output.mkdir()
            if samples is None:
                s, field = sample(kind=kind, flipped=flipped)
                samples = [(s, field)]
            body = report(samples=[s for s, _ in samples])
            if with_files:
                for s, field in samples:
                    write_sample_files(ladder_output, s, field)
            (ladder_output / "native-depth-ladder.json").write_text(json.dumps(body))
            if with_own_result:
                (ladder_output / "native-result.json").write_text(json.dumps(
                    {"complete": True, "success": True, "failures": [],
                     "checkpoints": checkpoints}))
            (root / "native-ladder.log").write_text("ladder log\n")
            stage["ladder_run"] = {"environment": {"checkpoints": checkpoints}}
        (root / "summary.json").write_text(json.dumps(
            {"stages": {"native_environment": stage}}))
        original = verify.ROOT
        verify.ROOT = root
        self.addCleanup(lambda: setattr(verify, "ROOT", original))
        kept = retain_native_evidence(root, native_output, "run", {"revision": "abc"})
        return root / "docs" / "ai" / "runs" / "native-evidence" / "run", kept

    def replay(self, target):
        quiet = io.StringIO()
        with contextlib.redirect_stdout(quiet):
            code = replay_evidence(target)
        return code, json.loads(quiet.getvalue())

    def rehash(self, target, name):
        manifest = json.loads((target / "MANIFEST.json").read_text())
        manifest["files"][name] = hashlib.sha256((target / name).read_bytes()).hexdigest()
        (target / "MANIFEST.json").write_text(json.dumps(manifest))

    def test_the_ladder_launch_is_retained_and_hashed(self):
        target, kept = self.build()
        self.assertNotIn("error", kept)
        self.assertEqual(kept["ladder"]["samples"],
                         ["ladder/native-depth-ladder-2.ppm.gz",
                          "ladder/native-depth-ladder-rejected-2.ppm.gz"])
        for name in ("ladder/native-depth-ladder.json", "ladder/native-depth-ladder-2.ppm.gz",
                     "ladder/native-depth-ladder-rejected-2.ppm.gz",
                     "ladder/native-result.json", "ladder/native-ladder.log"):
            self.assertIn(name, kept["files"])
            self.assertTrue((target / name).is_file(), name)

    def test_the_retained_ladder_replays_with_its_brackets(self):
        target, _ = self.build(kind="clouds", flipped=True)
        code, out = self.replay(target)
        self.assertEqual(code, 0, out)
        recount = out["ladder"]["samples"][0]
        self.assertTrue(recount["flipped"])
        self.assertGreater(recount["rungs"][0], 0)
        self.assertGreater(recount["low"], 0)
        self.assertEqual(recount["other"], 0)
        self.assertTrue(any("depth-ladder" in step for step in out["replayed"]))
        self.assertFalse(any("depth ladder" in step for step in out["not_replayed"]))

    def test_a_run_without_a_ladder_says_so_in_replay(self):
        target, kept = self.build(with_ladder=False)
        self.assertNotIn("error", kept)
        self.assertIsNone(kept["ladder"])
        code, out = self.replay(target)
        self.assertEqual(code, 0, out)
        self.assertNotIn("ladder", out)
        self.assertTrue(any("depth ladder" in step for step in out["not_replayed"]))

    def test_a_summary_that_records_a_ladder_launch_requires_its_report(self):
        """Round-8: removing the report (and its manifest entry) replayed as 0."""
        target, _ = self.build()
        (target / "ladder" / "native-depth-ladder.json").unlink()
        manifest = json.loads((target / "MANIFEST.json").read_text())
        del manifest["files"]["ladder/native-depth-ladder.json"]
        (target / "MANIFEST.json").write_text(json.dumps(manifest))
        code, out = self.replay(target)
        self.assertEqual(code, 1)
        self.assertIn("not retained", out["error"])

    def test_replay_rejects_a_ladder_that_records_its_own_failure(self):
        """Every mutation the stage gate rejects must be rejected on replay too."""
        for patch in ({"terrainProbeEnabled": True}, {"terrainDrawsRecorded": 9},
                      {"markerDrawEnabled": True}, {"depthWritesEnabled": True},
                      {"zConventionMeasuredHere": True}, {"completed": False},
                      {"problems": 1}, {"notes": ["FAILED"]}, {"device": "0xdead"},
                      {"upperBound": 0.0625}, {"rungDepths": [math.nan] * RUNGS}):
            target, _ = self.build()
            path = target / "ladder" / "native-depth-ladder.json"
            body = json.loads(path.read_text())
            body.update(patch)
            path.write_text(json.dumps(body))
            self.rehash(target, "ladder/native-depth-ladder.json")
            code, out = self.replay(target)
            self.assertEqual(code, 1, f"{patch} replayed as success: {out}")

    def test_replay_rejects_a_sample_whose_orientation_or_counts_lie(self):
        for mutate in (lambda s: s.update(flipped=True, rejectedFlipped=False),
                       lambda s: s["counts"]["rungs"].__setitem__(0, s["counts"]["rungs"][0] + 1),
                       lambda s: s.update(targetWidth=80, targetHeight=100)):
            target, _ = self.build()
            path = target / "ladder" / "native-depth-ladder.json"
            body = json.loads(path.read_text())
            mutate(body["samples"][0])
            path.write_text(json.dumps(body))
            self.rehash(target, "ladder/native-depth-ladder.json")
            code, out = self.replay(target)
            self.assertEqual(code, 1, out)

    def test_losing_a_referenced_ladder_crop_fails_retention(self):
        _, kept = self.build(with_files=False)
        self.assertIn("error", kept)
        self.assertIn("native-depth-ladder-2.ppm.gz", kept["error"])

    def test_a_ladder_crop_outside_the_manifest_is_rejected(self):
        """A crop dropped in beside the evidence is not evidence the run produced."""
        target, _ = self.build()
        manifest = json.loads((target / "MANIFEST.json").read_text())
        del manifest["files"]["ladder/native-depth-ladder-rejected-2.ppm.gz"]
        (target / "MANIFEST.json").write_text(json.dumps(manifest))
        code, out = self.replay(target)
        self.assertEqual(code, 1)
        self.assertIn("not manifest members", out["error"])

    def test_a_core_file_outside_the_manifest_is_rejected(self):
        """Round-8 B1: reports, summary, source fingerprint and log could be unlisted."""
        for name in ("native-marker-draw.json", "summary.json", "native.log",
                     "ladder/native-result.json", "ladder/native-ladder.log"):
            target, _ = self.build()
            manifest = json.loads((target / "MANIFEST.json").read_text())
            del manifest["files"][name]
            (target / "MANIFEST.json").write_text(json.dumps(manifest))
            code, out = self.replay(target)
            self.assertEqual(code, 1, name)
            self.assertIn(name, out["error"])

    def test_a_ladder_whose_launch_saw_another_device_is_rejected(self):
        """The ladder is a second process; its identity is tied to ITS checkpoints."""
        target, _ = self.build()
        summary_path = target / "summary.json"
        summary = json.loads(summary_path.read_text())
        ladder_stage = summary["stages"]["native_environment"]["ladder_run"]
        for case in ladder_stage["environment"]["checkpoints"]:
            case["renderer"]["vkDevice"] = 77
        summary_path.write_text(json.dumps(summary))
        self.rehash(target, "summary.json")
        code, out = self.replay(target)
        self.assertEqual(code, 1)
        # the own result no longer agrees with the summary copy, which is caught first
        self.assertIn("own checkpoints differ", out["error"])

    def test_the_ladder_launchs_own_result_must_agree_with_the_summary(self):
        """Round-8: replay consumed only the summary copy of the checkpoints."""
        target, _ = self.build()
        own_path = target / "ladder" / "native-result.json"
        own = json.loads(own_path.read_text())
        own["checkpoints"][0]["renderer"]["vkDevice"] = 77
        own_path.write_text(json.dumps(own))
        self.rehash(target, "ladder/native-result.json")
        code, out = self.replay(target)
        self.assertEqual(code, 1)
        self.assertIn("own checkpoints differ", out["error"])

    def test_an_altered_ladder_crop_fails_the_manifest(self):
        target, _ = self.build()
        (target / "ladder" / "native-depth-ladder-2.ppm.gz").write_bytes(b"garbage")
        code, out = self.replay(target)
        self.assertEqual(code, 1)
        self.assertEqual(out["manifest_mismatches"], ["ladder/native-depth-ladder-2.ppm.gz"])


if __name__ == "__main__":
    unittest.main()
