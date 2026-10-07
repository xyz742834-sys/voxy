"""The depth-ladder gate must reject anything that is not a measurement of Minecraft's depth.

The ladder draws eight columns at known NDC depths, compare LESS, depth writes OFF, against
Minecraft's own LOADed depth, with an ALWAYS control stripe co-located above every column. The
surviving prefix bounds Minecraft's depth in that band; it never says which way is nearer.

Each case here is a way the previous reviews got past a gate: an aggregate the pixels do not
support, a sample bound to the wrong capture, an orientation the recount guessed, a published
reference the producer controls, a contaminated run passed off as a measurement, and a gate the
replay did not call.
"""
import contextlib
import hashlib
import io
import json
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

# ⚠ Full-frame size matters: the bands are resolved through the implementation's own
# NDC-to-pixel formula over this size, then shifted into the crop. Do not shrink it to make
# the suite faster without checking every band still resolves to a non-empty rectangle.
FULL_W, FULL_H = 960, 540
RUNGS = verify.LADDER_RUNGS
D = verify.EXPECTED_LADDER_DEPTHS
BANDS = verify.EXPECTED_LADDER_BANDS
MAGENTA, CYAN, BACKGROUND = (255, 0, 255), (0, 255, 255), (90, 90, 90)
# The device the shared lifecycle-checkpoint fixture names; the ladder must name the same one.
DEVICE = verify.device_handle_of(
    test_marker_gate.ProofFileGateTest.CHECKPOINTS[0]["renderer"]["vkDevice"], "fixture", "vkDevice")


def px_rect(ax, ay, bx, by, flipped):
    """The implementation's own NDC-to-pixel resolution (McNativeDepthLadder.fillOf)."""
    x0 = int((min(ax, bx) + 1.0) * 0.5 * FULL_W)
    x1 = int((max(ax, bx) + 1.0) * 0.5 * FULL_W)
    top, bottom = max(ay, by), min(ay, by)
    if flipped:
        y0 = int((1.0 + bottom) * 0.5 * FULL_H)
        y1 = int((1.0 + top) * 0.5 * FULL_H)
    else:
        y0 = int((1.0 - top) * 0.5 * FULL_H)
        y1 = int((1.0 - bottom) * 0.5 * FULL_H)
    return x0, y0, x1, y1


def sample_rect(flipped):
    """The crop the implementation retains: band x range, inline control top to control bottom."""
    band, inline, control = BANDS["band"], BANDS["inlineControlBand"], BANDS["controlBand"]
    return list(px_rect(band[0], inline[1], band[2], control[3], flipped))


def column_x(i):
    band = BANDS["band"]
    return (band[0] + (band[2] - band[0]) * i / RUNGS,
            band[0] + (band[2] - band[0]) * (i + 1) / RUNGS)


def crop(survivors=0, flipped=False, missing_inline=(), missing_control=False):
    """A crop as the implementation would retain it, drawn from the pinned geometry."""
    rect = sample_rect(flipped)
    w, h = rect[2] - rect[0], rect[3] - rect[1]
    rows = [[BACKGROUND] * w for _ in range(h)]

    def paint(area, colour):
        x0, y0, x1, y1 = area
        for y in range(y0 - rect[1], y1 - rect[1]):
            row = rows[y]
            for x in range(x0 - rect[0], x1 - rect[0]):
                row[x] = colour

    band, inline, control = BANDS["band"], BANDS["inlineControlBand"], BANDS["controlBand"]
    for i in range(RUNGS):
        x0, x1 = column_x(i)
        if i not in missing_inline:
            paint(px_rect(x0, inline[1], x1, inline[3], flipped), CYAN)
        if i < survivors:
            paint(px_rect(x0, band[1], x1, band[3], flipped), MAGENTA)
    if not missing_control:
        paint(px_rect(*control, flipped), CYAN)
    return rows, rect


def report(survivors=0, flipped=False, at=2, **overrides):
    depths = verify.EXPECTED_LADDER_DEPTHS
    survived = [i < survivors for i in range(RUNGS)]
    body = {"enabled": True, "attempted": True, "completed": True, "drawsRecorded": 3460,
            "rungs": RUNGS, "depthWritesEnabled": False, "zConventionMeasuredHere": False,
            "rungDepths": list(depths), "rungSurvived": survived,
            "rungFill": [1.0 if ok else 0.0 for ok in survived],
            "inlineControlFill": [1.0] * RUNGS, "inlineControlBand": BANDS["inlineControlBand"],
            "controlFill": 1.0,
            "lowerBound": depths[survivors - 1] if survivors else None,
            "upperBound": depths[survivors] if survivors < RUNGS else None,
            "note": None, "problems": 0, "firstProblem": None, "closeFailures": 0,
            "leakedPipelines": 0, "deviceDiverged": False,
            "sampleFile": f"native-depth-ladder-{at}.ppm.gz",
            "sampleRect": sample_rect(flipped), "sampleAtDraw": at, "flipped": flipped,
            "targetWidth": FULL_W, "targetHeight": FULL_H,
            "terrainProbeEnabled": False, "terrainDrawsRecorded": 0,
            "markerDrawEnabled": False, "markerDrawsRecorded": 0,
            "band": BANDS["band"], "controlBand": BANDS["controlBand"],
            "device": hex(DEVICE), "notes": []}
    body.update(overrides)
    return body


class LadderGateTest(unittest.TestCase):
    def run_gate(self, body=None, rows=None, drop=(), no_sample=False, expected_device=DEVICE,
                 sample_name=None):
        body = report() if body is None else body
        rows = crop() if rows is None else rows
        for key in drop:
            body.pop(key, None)
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            if not no_sample and (sample_name or body.get("sampleFile")):
                write_gz_ppm(out / (sample_name or body["sampleFile"]), rows[0])
            (out / "native-depth-ladder.json").write_text(json.dumps(body))
            return native_ladder_result(out, expected_device)

    def assertFails(self, result, *fragments):
        self.assertFalse(result["success"], result.get("answer"))
        joined = " ".join(result["failures"])
        for fragment in fragments:
            self.assertIn(fragment, joined)

    # ---- the measurement that was actually made, and the shape of a positive one ----

    def test_all_rungs_rejected_is_the_measured_outcome_and_passes(self):
        """What the isolated run produced: every LESS rung empty, every control filled."""
        result = self.run_gate()
        self.assertTrue(result["success"], result["failures"])
        self.assertEqual(result["bounds"], {"lower": None, "upper": D[0]})
        self.assertIn(f"<= {D[0]}", result["answer"])
        self.assertIn("unmeasured", result["answer"])
        self.assertFalse(result["z_convention_measured"])
        self.assertEqual(result["recount"]["survived"], [False] * RUNGS)
        self.assertEqual(result["recount"]["inlineControlFill"], [1.0] * RUNGS)

    def test_a_surviving_prefix_bounds_the_depth_from_both_sides(self):
        result = self.run_gate(report(survivors=3), crop(survivors=3))
        self.assertTrue(result["success"], result["failures"])
        self.assertEqual(result["bounds"], {"lower": D[2], "upper": D[3]})
        self.assertIn(f"in ({D[2]}, {D[3]}]", result["answer"])

    def test_every_rung_surviving_gives_only_a_lower_bound(self):
        result = self.run_gate(report(survivors=RUNGS), crop(survivors=RUNGS))
        self.assertTrue(result["success"], result["failures"])
        self.assertEqual(result["bounds"], {"lower": D[7], "upper": None})

    # ---- orientation: the recount must not guess ----

    def test_a_flipped_sample_passes_when_the_orientation_is_published(self):
        result = self.run_gate(report(survivors=2, flipped=True), crop(survivors=2, flipped=True))
        self.assertTrue(result["success"], result["failures"])
        self.assertEqual(result["bounds"], {"lower": D[1], "upper": D[2]})
        self.assertTrue(result["recount"]["flipped"])

    def test_lying_about_the_orientation_fails(self):
        """The crop was taken flipped; the report says it was not. The bands then resolve to
        the wrong rows, and the recount must notice rather than agree by accident."""
        rows = crop(survivors=2, flipped=True)
        body = report(survivors=2, flipped=False)
        body["sampleRect"] = rows[1]
        # Resolved in the wrong orientation, the bands fall outside the crop; the gate must
        # refuse rather than clamp them to fit.
        self.assertFails(self.run_gate(body, rows), "does not contain the whole")

    def test_a_missing_orientation_fails_instead_of_defaulting(self):
        self.assertFails(self.run_gate(drop=("flipped",)), "flipped")

    # ---- the control stripes are what make an empty rung mean something ----

    def test_an_inline_control_below_the_floor_in_the_report_fails(self):
        body = report()
        body["inlineControlFill"][3] = 0.5
        self.assertFails(self.run_gate(body), "inline always-pass control above rung 3")

    def test_an_inline_control_missing_from_the_pixels_fails(self):
        """The report claims the column was drawn; the crop shows it was not."""
        self.assertFails(self.run_gate(report(), crop(missing_inline=(5,))),
                         "inline control above rung 5")

    def test_a_missing_separate_control_band_fails(self):
        self.assertFails(self.run_gate(report(controlFill=0.1)), "separate control band")
        self.assertFails(self.run_gate(report(), crop(missing_control=True)),
                         "separate control band")

    # ---- the surviving set must be a prefix, and the bounds must come from it ----

    def test_survivors_that_are_not_a_prefix_fail(self):
        body = report(survivors=2)
        body["rungSurvived"][5] = True
        body["rungFill"][5] = 1.0
        self.assertFails(self.run_gate(body), "survived after an earlier rung failed")

    def test_bounds_disagreeing_with_the_surviving_set_fail(self):
        self.assertFails(self.run_gate(report(survivors=3, lowerBound=D[0]),
                                       crop(survivors=3)), "lowerBound")
        self.assertFails(self.run_gate(report(survivors=3, upperBound=None),
                                       crop(survivors=3)), "upperBound")
        self.assertFails(self.run_gate(report(lowerBound=0.5)), "no rung justifies it")

    def test_a_survived_flag_contradicting_its_fill_fails(self):
        body = report()
        body["rungSurvived"][0] = True
        body["lowerBound"] = D[0]
        body["upperBound"] = D[1]
        self.assertFails(self.run_gate(body), "says survived=True but its fill is 0.0")

    def test_published_survivors_the_pixels_do_not_show_fail(self):
        """Three survivors in the report, none in the crop — and the other way round."""
        self.assertFails(self.run_gate(report(survivors=3), crop(survivors=0)),
                         "finds survivors")
        self.assertFails(self.run_gate(report(survivors=0), crop(survivors=3)),
                         "finds survivors")

    def test_fills_that_disagree_with_the_pixels_fail(self):
        body = report()
        body["rungFill"] = [0.3] * RUNGS  # still "not survived", but not what the pixels say
        self.assertFails(self.run_gate(body), "does not match the pixels")

    # ---- the sample must be the capture the report describes ----

    def test_a_sample_named_for_a_different_draw_fails(self):
        self.assertFails(self.run_gate(report(sampleAtDraw=7)), "named for draw '2'")

    def test_a_sample_not_bound_to_a_positive_capture_fails(self):
        self.assertFails(self.run_gate(report(at=0)), "sampleAtDraw is 0")

    def test_a_capture_beyond_the_recorded_draws_fails(self):
        self.assertFails(self.run_gate(report(at=5000)), "only 3460 ladder draws")

    def test_a_missing_sample_fails(self):
        self.assertFails(self.run_gate(no_sample=True), "missing")
        self.assertFails(self.run_gate(report(sampleFile=None)), "retained no raw sample")

    def test_a_one_by_one_image_fails(self):
        """Round 7's last counterexample shape: a tiny image where a crop should be."""
        self.assertFails(self.run_gate(report(), ([[(0, 0, 0)]], sample_rect(False))),
                         "is 1x1")

    def test_a_crop_that_does_not_contain_the_bands_fails(self):
        """A shrunken crop with a rect adjusted to match must not be measured as if whole."""
        rows, rect = crop()
        shrunk = [row[20:] for row in rows]
        body = report()
        body["sampleRect"] = [rect[0] + 20, rect[1], rect[2], rect[3]]
        self.assertFails(self.run_gate(body, (shrunk, body["sampleRect"])),
                         "does not contain the whole")

    # ---- what the ladder may never claim ----

    def test_depth_writes_enabled_fails(self):
        self.assertFails(self.run_gate(report(depthWritesEnabled=True)),
                         "measured its own depth")

    def test_claiming_the_convention_fails(self):
        self.assertFails(self.run_gate(report(zConventionMeasuredHere=True)),
                         "cannot establish")

    # ---- contamination: a run with the terrain probe or marker is not a measurement ----

    def test_a_terrain_contaminated_run_fails(self):
        self.assertFails(self.run_gate(report(terrainProbeEnabled=True)),
                         "clears Minecraft's depth")
        self.assertFails(self.run_gate(report(terrainDrawsRecorded=1)),
                         "clears Minecraft's depth")

    def test_a_marker_contaminated_run_fails(self):
        self.assertFails(self.run_gate(report(markerDrawEnabled=True)), "writes depth")
        self.assertFails(self.run_gate(report(markerDrawsRecorded=3)), "writes depth")

    # ---- references the producer controls are not references ----

    def test_published_depths_that_differ_from_the_source_fail(self):
        body = report()
        body["rungDepths"][0] = D[0] * 2
        self.assertFails(self.run_gate(body), "not the")

    def test_published_bands_that_differ_from_the_source_fail(self):
        for label in ("band", "inlineControlBand", "controlBand"):
            body = report()
            body[label] = [v + 0.05 for v in body[label]]
            self.assertFails(self.run_gate(body), label, "not a reference")

    def test_a_wrong_number_of_rungs_fails(self):
        self.assertFails(self.run_gate(report(rungs=4)), "4 rungs")

    # ---- identity and lifetime ----

    def test_a_null_or_foreign_device_fails(self):
        self.assertFails(self.run_gate(report(device="0x0")), "null device handle")
        self.assertFails(self.run_gate(report(device="0xdead")), "checkpoints saw")
        self.assertFails(self.run_gate(report(device="not-a-handle")), "not a device handle")

    def test_notes_problems_leaks_or_divergence_fail(self):
        self.assertFails(self.run_gate(report(notes=["FAILED"])), "notes")
        self.assertFails(self.run_gate(report(problems=1)), "problems=1")
        self.assertFails(self.run_gate(report(closeFailures=1)), "closeFailures=1")
        self.assertFails(self.run_gate(report(leakedPipelines=1)), "leakedPipelines=1")
        self.assertFails(self.run_gate(report(deviceDiverged=True)), "diverged")

    def test_a_ladder_that_never_completed_fails(self):
        self.assertFails(self.run_gate(report(completed=False, note="not measured")),
                         "never completed")
        self.assertFails(self.run_gate(report(attempted=False)), "never completed")
        self.assertFails(self.run_gate(report(enabled=False)), "not enabled")
        self.assertFails(self.run_gate(report(drawsRecorded=0)), "no ladder draw")

    def test_a_ladder_rejecting_its_own_frame_fails(self):
        self.assertFails(self.run_gate(report(note="the surviving rungs are not a prefix")),
                         "rejects its own measurement")

    def test_a_missing_field_fails_instead_of_defaulting(self):
        for field in ("enabled", "attempted", "completed", "drawsRecorded", "rungs",
                      "depthWritesEnabled", "zConventionMeasuredHere", "rungDepths",
                      "rungSurvived", "rungFill", "inlineControlFill", "controlFill",
                      "problems", "closeFailures", "leakedPipelines", "deviceDiverged",
                      "notes", "sampleAtDraw", "flipped", "targetWidth", "targetHeight",
                      "terrainProbeEnabled", "terrainDrawsRecorded", "markerDrawEnabled",
                      "markerDrawsRecorded", "band", "inlineControlBand", "controlBand",
                      "device"):
            result = self.run_gate(drop=(field,))
            self.assertFalse(result["success"], field)
            self.assertIn(field, " ".join(result["failures"]))

    def test_a_field_of_the_wrong_type_fails(self):
        self.assertFails(self.run_gate(report(flipped="false")), "flipped")
        self.assertFails(self.run_gate(report(sampleAtDraw="2")), "sampleAtDraw")
        self.assertFails(self.run_gate(report(terrainDrawsRecorded=False)),
                         "terrainDrawsRecorded")


class LadderRetentionTest(unittest.TestCase):
    """The ladder's own launch must be retained under ladder/ and must replay with the same
    gate, tied to that launch's checkpoints — or replay must say it was not retained."""

    def build(self, survivors=0, flipped=False, with_sample=True, with_ladder=True):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        native_output = root / "native"
        native_output.mkdir()
        helper = test_marker_gate.EvidenceRetentionTest()
        helper.populate(native_output)
        (root / "native.log").write_text("log\n")
        checkpoints = test_marker_gate.ProofFileGateTest.CHECKPOINTS
        stage = {"gate": {"checkpoints": checkpoints}}
        if with_ladder:
            ladder_output = root / "native-ladder"
            ladder_output.mkdir()
            body = report(survivors=survivors, flipped=flipped)
            if with_sample:
                write_gz_ppm(ladder_output / body["sampleFile"],
                             crop(survivors=survivors, flipped=flipped)[0])
            (ladder_output / "native-depth-ladder.json").write_text(json.dumps(body))
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
        target, kept = self.build(survivors=3)
        self.assertNotIn("error", kept)
        self.assertEqual(kept["ladder"]["sample"], "ladder/native-depth-ladder-2.ppm.gz")
        for name in ("ladder/native-depth-ladder.json", "ladder/native-depth-ladder-2.ppm.gz",
                     "ladder/native-result.json", "ladder/native-ladder.log"):
            self.assertIn(name, kept["files"])
            self.assertTrue((target / name).is_file(), name)

    def test_the_retained_ladder_replays_with_its_bounds(self):
        target, _ = self.build(survivors=3, flipped=True)
        code, out = self.replay(target)
        self.assertEqual(code, 0, out)
        self.assertEqual(out["ladder"]["bounds"], {"lower": D[2], "upper": D[3]})
        self.assertEqual(out["ladder"]["recount"]["survived"],
                         [True, True, True] + [False] * (RUNGS - 3))
        self.assertTrue(out["ladder"]["recount"]["flipped"])
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

    def test_replay_rejects_a_ladder_that_records_its_own_failure(self):
        """Every mutation the stage gate rejects must be rejected on replay too."""
        for patch in ({"terrainProbeEnabled": True}, {"terrainDrawsRecorded": 9},
                      {"markerDrawEnabled": True}, {"depthWritesEnabled": True},
                      {"zConventionMeasuredHere": True}, {"flipped": True},
                      {"completed": False}, {"problems": 1}, {"notes": ["FAILED"]},
                      {"device": "0xdead"}, {"lowerBound": 0.5},
                      {"rungSurvived": [True] + [False] * (RUNGS - 1),
                       "rungFill": [1.0] + [0.0] * (RUNGS - 1),
                       "lowerBound": D[0], "upperBound": D[1]}):
            target, _ = self.build()
            path = target / "ladder" / "native-depth-ladder.json"
            body = json.loads(path.read_text())
            body.update(patch)
            path.write_text(json.dumps(body))
            self.rehash(target, "ladder/native-depth-ladder.json")
            code, out = self.replay(target)
            self.assertEqual(code, 1, f"{patch} replayed as success: {out}")

    def test_losing_the_referenced_ladder_sample_fails_retention(self):
        _, kept = self.build(with_sample=False)
        self.assertIn("error", kept)
        self.assertIn("native-depth-ladder-2.ppm.gz", kept["error"])

    def test_a_ladder_sample_outside_the_manifest_is_rejected(self):
        """A crop dropped in beside the evidence is not evidence the run produced."""
        target, _ = self.build()
        manifest = json.loads((target / "MANIFEST.json").read_text())
        del manifest["files"]["ladder/native-depth-ladder-2.ppm.gz"]
        (target / "MANIFEST.json").write_text(json.dumps(manifest))
        code, out = self.replay(target)
        self.assertEqual(code, 1)
        self.assertIn("not a manifest member", out["error"])

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
        self.assertIn("checkpoints saw", out["error"])

    def test_an_altered_ladder_crop_fails_the_manifest(self):
        target, _ = self.build()
        (target / "ladder" / "native-depth-ladder-2.ppm.gz").write_bytes(b"garbage")
        code, out = self.replay(target)
        self.assertEqual(code, 1)
        self.assertEqual(out["manifest_mismatches"], ["ladder/native-depth-ladder-2.ppm.gz"])


if __name__ == "__main__":
    unittest.main()
