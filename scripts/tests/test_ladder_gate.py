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
# Fingerprinted once, from the real repository, before any test swaps verify.ROOT for a
# temporary directory: replay now requires the retained fingerprint to match the checkout.
REAL_FINGERPRINTS = verify.source_fingerprints()
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
            elif kind == "near":      # ground ~8-12 blocks away: bracket 4, (2^-8, 2^-6]
                d = 0.0045 + 0.004 * ((x + y) % 7) / 7
            elif kind == "far":       # the same ground ~105-125 blocks away: bracket 2
                d = 0.00042 + 0.00005 * ((x + y) % 7) / 7
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


def thumbnail(selected, rect, rejected, rejected_rect):
    """The quarter-scale thumbnail of a full frame that holds the two crops at their rects
    over scene colour: the integer block mean the implementation retains."""
    scale = verify.LADDER_FRAME_SCALE
    frame = [[SCENE] * FULL_W for _ in range(FULL_H)]
    for crop, r in ((selected, rect), (rejected, rejected_rect)):
        for y, row in enumerate(crop):
            frame[r[1] + y][r[0]:r[0] + len(row)] = row
    thumb = []
    for by in range(FULL_H // scale):
        trow = []
        for bx in range(FULL_W // scale):
            sums = [0, 0, 0]
            for y in range(by * scale, (by + 1) * scale):
                for x in range(bx * scale, (bx + 1) * scale):
                    for k in range(3):
                        sums[k] += frame[y][x][k]
            trow.append(tuple(v // (scale * scale) for v in sums))
        thumb.append(trow)
    return thumb


GROUND_Y = 70
CAMERA_FOR = {"descend": [0.5, GROUND_Y + verify.DESCEND_ABOVE_GROUND + verify.PLAYER_EYE_HEIGHT,
                          0.5, 90.0, 0.0],
              "ascend": [0.5, GROUND_Y + verify.ASCEND_ABOVE_GROUND + verify.PLAYER_EYE_HEIGHT,
                         0.5, 90.0, 0.0]}


def direction_checkpoints():
    """Lifecycle checkpoints with the two straight-down stages' ground height recorded."""
    base = json.loads(json.dumps(test_marker_gate.ProofFileGateTest.CHECKPOINTS))
    for case in base:
        case["renderer"]["colour"] = {"vkImage": 15, "vkImageView": 16,
                                      "width": FULL_W, "height": FULL_H}
    out = []
    for case in base:
        out.append(case)
    for stage in ("descend", "ascend"):
        extra = json.loads(json.dumps(base[0]))
        extra["stage"] = stage
        extra["groundY"] = GROUND_Y
        extra["cameraY"] = CAMERA_FOR[stage][1]
        out.append(extra)
    return out


def sample(at=2, flipped=False, kind="gradient", field=None, stage="warmup", camera=None):
    w, h = band_size(flipped)
    field = depth_field(w, h, kind) if field is None else field
    rect = verify.ladder_band_rect(FULL_W, FULL_H, flipped)
    rejected_rect = verify.ladder_band_rect(FULL_W, FULL_H, not flipped)
    rw, rh = rejected_rect[2] - rejected_rect[0], rejected_rect[3] - rejected_rect[1]
    body = {"at": at, "flipped": flipped, "targetWidth": FULL_W, "targetHeight": FULL_H,
            "rect": rect, "counts": counts_of(field), "rejectedFlipped": not flipped,
            "rejectedRect": rejected_rect, "rejectedOther": rw * rh,
            "file": f"native-depth-ladder-{at}.ppm.gz",
            "rejectedFile": f"native-depth-ladder-rejected-{at}.ppm.gz",
            "frameFile": f"native-depth-ladder-frame-{at}.ppm.gz",
            "stage": stage,
            "camera": list(camera) if camera is not None else [0.5, 121.62, 0.5, 30.0, 0.0]}
    return body, field


def direction_samples(near_kind="near", far_kind="far", near_at=3000, far_at=3240):
    """The two straight-down looks, as the ladder would sample them."""
    return [sample(at=near_at, kind=near_kind, stage="descend", camera=CAMERA_FOR["descend"]),
            sample(at=far_at, kind=far_kind, stage="ascend", camera=CAMERA_FOR["ascend"])]


def report(samples=None, **overrides):
    body = {"enabled": True, "attempted": True, "completed": True, "drawsRecorded": 5000,
            "rungs": RUNGS, "depthWritesEnabled": False, "zConventionMeasuredHere": False,
            "rungDepths": list(D), "band": list(verify.EXPECTED_LADDER_BAND),
            "palette": [list(c) for c in verify.EXPECTED_LADDER_PALETTE],
            "readbackInterval": 240, "sampleLimit": 24, "frameScale": 4,
            "pipelineStates": [[1, 1, 0], [7, 1, 0], [4, 1, 0]],
            "samples": samples if samples is not None else [sample()[0]],
            "problems": 0, "firstProblem": None, "closeFailures": 0, "leakedPipelines": 0,
            "deviceDiverged": False, "terrainProbeEnabled": False, "terrainDrawsRecorded": 0,
            "markerDrawEnabled": False, "markerDrawsRecorded": 0, "device": hex(DEVICE),
            "notes": []}
    body.update(overrides)
    return body


def write_sample_files(out, body, field, selected=None, rejected=None, frame_from=None):
    """Write the two crops and the frame thumbnail. The thumbnail is built from the crops at
    the rects the body states unless `frame_from` gives other (rects) to anchor to."""
    sel, rej = crops(field, body.get("flipped", False))
    sel = selected if selected is not None else sel
    rej = rejected if rejected is not None else rej
    if body.get("file"):
        write_gz_ppm(out / body["file"], sel)
    if body.get("rejectedFile"):
        write_gz_ppm(out / body["rejectedFile"], rej)
    if body.get("frameFile"):
        rect, rrect = frame_from or (body.get("rect"), body.get("rejectedRect"))
        if rect and rrect:
            write_gz_ppm(out / body["frameFile"], thumbnail(sel, rect, rej, rrect))


def log_for(samples):
    """Log lines in the implementation's format, derived from the samples' own fields."""
    lines = []
    for s in samples:
        c = s.get("counts") or {}
        rungs = " ".join(f"r{i}={v}" for i, v in enumerate(c.get("rungs") or []))
        lines.append(f"[native-vk] depth ladder sample at draw {s.get('at', 0)}"
                     f" flipped={str(s.get('flipped', False)).lower()} counts=[anomaly="
                     f"{c.get('anomaly')} low={c.get('low')} {rungs} other={c.get('other')}]\n")
    return "".join(lines)


class LadderGateTest(unittest.TestCase):
    def run_gate(self, body=None, fields=None, drop=(), skip_files=False, expected_device=DEVICE,
                 expected_extents=EXTENTS, selected=None, rejected=None, frame_from=None,
                 log=None, extra_files=(), checkpoints=None):
        if body is None:
            s, field = sample()
            body, fields = report(samples=[s]), [field]
        for key in drop:
            body.pop(key, None)
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            if not skip_files:
                for s, field in zip(body.get("samples") or [], fields or []):
                    write_sample_files(out, s, field, selected, rejected, frame_from)
            for name, field in extra_files:
                write_sample_files(out, name, field)
            (out / "native-depth-ladder.json").write_text(json.dumps(body))
            return native_ladder_result(out, expected_device, expected_extents,
                                        log_for(body.get("samples") or []) if log is None else log,
                                        checkpoints)

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
        self.assertIn("not judged here", result["answer"])
        self.assertFalse(result["z_convention_measured"])
        self.assertNotIn("bounds", result)
        self.assertNotIn("z_direction", result)

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

    def test_a_coordinated_orientation_lie_fails_on_the_frame(self):
        """Round-9: flip both booleans AND recompute both rectangles, same crop bytes. The
        retained frame thumbnail still holds the crops where they really were."""
        body, fields = self.one(flipped=True, kind="clouds")
        truth = (list(body["samples"][0]["rect"]), list(body["samples"][0]["rejectedRect"]))
        s = body["samples"][0]
        s["flipped"], s["rejectedFlipped"] = False, True
        s["rect"] = verify.ladder_band_rect(FULL_W, FULL_H, False)
        s["rejectedRect"] = verify.ladder_band_rect(FULL_W, FULL_H, True)
        self.assertFails(self.run_gate(body, fields, frame_from=truth),
                         "did not come from there")

    def test_a_missing_or_wrong_sized_frame_thumbnail_fails(self):
        body, fields = self.one()
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            write_sample_files(out, body["samples"][0], fields[0])
            (out / body["samples"][0]["frameFile"]).unlink()
            (out / "native-depth-ladder.json").write_text(json.dumps(body))
            self.assertFails(native_ladder_result(out, DEVICE, EXTENTS, log_for(body["samples"])),
                             "thumbnail")
        # a thumbnail of the wrong size for the stated frame
        body, fields = self.one()
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            write_sample_files(out, body["samples"][0], fields[0])
            write_gz_ppm(out / body["samples"][0]["frameFile"], [[SCENE] * 10 for _ in range(10)])
            (out / "native-depth-ladder.json").write_text(json.dumps(body))
            self.assertFails(native_ladder_result(out, DEVICE, EXTENTS, log_for(body["samples"])),
                             "thumbnail is 10x10")

    def test_a_retained_crop_the_report_omits_fails(self):
        """Round-9: a report listing one of two retained samples passed."""
        s1, f1 = sample(at=2)
        s2, f2 = sample(at=242, kind="clouds")
        body = report(samples=[s1])
        self.assertFails(self.run_gate(body, [f1], extra_files=[(s2, f2)],
                                       log=log_for([s1])), "belong to no listed sample")

    def test_a_logged_sample_the_report_omits_fails(self):
        s1, f1 = sample(at=2)
        s2, f2 = sample(at=242, kind="clouds")
        body = report(samples=[s1])
        self.assertFails(self.run_gate(body, [f1], log=log_for([s1, s2])), "logged samples")
        self.assertFails(self.run_gate(body, [f1], log=""), "logged samples")

    def test_log_details_that_disagree_with_the_report_fail(self):
        """Round-10 R10-LOG-DETAILS: orientation and counts in the log drifted unnoticed."""
        body, fields = self.one()
        text = log_for(body["samples"]).replace("flipped=false", "flipped=true")
        self.assertFails(self.run_gate(body, fields, log=text), "flipped/counts")
        body, fields = self.one()
        low = body["samples"][0]["counts"]["low"]
        text = log_for(body["samples"]).replace(f"low={low}", "low=999999")
        self.assertFails(self.run_gate(body, fields, log=text), "flipped/counts")
        body, fields = self.one()
        text = f"[native-vk] depth ladder sample at draw {body['samples'][0]['at']} \n"
        self.assertFails(self.run_gate(body, fields, log=text), "orientation and counts")

    def test_the_pinned_constants_are_the_literal_ones(self):
        """Round-9: the fixtures share the gate's constants, so doubling them in memory kept
        the positive case green. The literal values are the contract with the Java side."""
        self.assertEqual(verify.EXPECTED_LADDER_DEPTHS,
                         [1.52587890625e-05, 6.103515625e-05, 0.000244140625, 0.0009765625,
                          0.00390625, 0.015625, 0.0625, 0.25])
        self.assertEqual(verify.EXPECTED_LADDER_BAND, [-0.6, 0.36, 0.6, 0.2])
        self.assertEqual(verify.EXPECTED_LADDER_PALETTE,
                         [[1.0, 1.0, 1.0], [0.5, 0.5, 0.5], [1.0, 0.0, 1.0], [0.0, 1.0, 1.0],
                          [1.0, 1.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0],
                          [1.0, 0.5, 0.0], [0.5, 0.0, 1.0]])
        self.assertEqual(verify.LADDER_FRAME_SCALE, 4)
        self.assertEqual(verify.ladder_band_rect(1708, 960, False), [342, 307, 1366, 384])
        self.assertEqual(verify.ladder_band_rect(1708, 960, True), [342, 576, 1366, 653])

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

    def test_pipeline_states_other_than_the_ladders_fail(self):
        """Round-11 R10-CREATE-TEST: the state read back after creation is published and pinned."""
        for states in ([[7, 1, 0], [7, 1, 0], [4, 1, 0]], [[1, 1, 1], [7, 1, 0], [4, 1, 0]],
                       [[1, 0, 0], [7, 1, 0], [4, 1, 0]], [], None, [[1, 1, 0], [7, 1, 0]]):
            body, fields = self.one()
            body["pipelineStates"] = states
            self.assertFails(self.run_gate(body, fields), "depth states")

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
                      "rejectedFlipped", "rejectedRect", "rejectedOther", "file", "rejectedFile",
                      "frameFile", "stage", "camera"):
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


class LadderDirectionTest(unittest.TestCase):
    """The gate, not the probe, reads the Z direction from the two straight-down looks."""

    def run_direction(self, pairs=None, checkpoints=None, **kw):
        pairs = direction_samples(**kw) if pairs is None else pairs
        body = report(samples=[s for s, _ in pairs])
        fields = [f for _, f in pairs]
        gate = LadderGateTest()
        return gate.run_gate(body, fields,
                             checkpoints=direction_checkpoints() if checkpoints is None else checkpoints)

    def test_the_nearer_look_in_higher_brackets_reads_reverse_z(self):
        result = self.run_direction()
        self.assertTrue(result["success"], result["failures"])
        self.assertIn("larger depth value is nearer", result["z_direction"]["direction"])
        self.assertEqual(result["z_direction"]["near"]["brackets"], [4])
        self.assertEqual(result["z_direction"]["far"]["brackets"], [2])
        self.assertAlmostEqual(result["z_direction"]["near"]["aboveGround"], 13.62)
        self.assertFalse(result["z_convention_measured"])
        self.assertIn("reverse-Z", result["answer"])

    def test_the_nearer_look_in_lower_brackets_reads_conventional_z(self):
        result = self.run_direction(near_kind="far", far_kind="near")
        self.assertTrue(result["success"], result["failures"])
        self.assertIn("smaller depth value is nearer", result["z_direction"]["direction"])

    def test_looks_that_do_not_separate_fail(self):
        result = self.run_direction(near_kind="near", far_kind="near")
        self.assertFalse(result["success"])
        self.assertIn("do not separate", " ".join(result["failures"]))

    def test_a_missing_look_fails(self):
        pairs = direction_samples()[1:]
        result = self.run_direction(pairs=pairs)
        self.assertFalse(result["success"])
        self.assertIn("no ladder sample was taken during descend", " ".join(result["failures"]))

    def test_a_camera_not_where_the_stage_put_it_fails(self):
        pairs = direction_samples()
        pairs[0][0]["camera"][1] += 3.0
        result = self.run_direction(pairs=pairs)
        self.assertFalse(result["success"])
        self.assertIn("descend", " ".join(result["failures"]))
        pairs = direction_samples()
        pairs[1][0]["camera"][3] = 30.0
        result = self.run_direction(pairs=pairs)
        self.assertFalse(result["success"])

    def test_a_look_with_sky_in_it_fails(self):
        pairs = direction_samples(near_kind="clouds")
        result = self.run_direction(pairs=pairs)
        self.assertFalse(result["success"])
        self.assertIn("below the smallest rung", " ".join(result["failures"]))

    def test_a_checkpoint_without_a_ground_height_fails(self):
        checkpoints = direction_checkpoints()
        for case in checkpoints:
            if case["stage"] == "ascend":
                case["groundY"] = None
        result = self.run_direction(checkpoints=checkpoints)
        self.assertFalse(result["success"])
        self.assertIn("no ground height", " ".join(result["failures"]))

    def test_the_last_look_of_each_stage_is_the_one_judged(self):
        """An early sample taken before the ground loaded must not decide the direction."""
        early, _ = sample(at=2900, kind="clouds", stage="descend", camera=CAMERA_FOR["descend"])
        pairs = [(early, depth_field(*band_size(), "clouds"))] + direction_samples()
        result = self.run_direction(pairs=pairs)
        self.assertTrue(result["success"], result["failures"])
        self.assertEqual(result["z_direction"]["near"]["at"], 3000)

    def test_without_checkpoints_no_direction_is_judged(self):
        gate = LadderGateTest()
        pairs = direction_samples()
        result = gate.run_gate(report(samples=[s for s, _ in pairs]), [f for _, f in pairs])
        self.assertTrue(result["success"], result["failures"])
        self.assertNotIn("z_direction", result)
        self.assertIn("not judged here", result["answer"])


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
        (root / "source-sha256.json").write_text(json.dumps(REAL_FINGERPRINTS))
        # The shared checkpoint fixture names a device; the ladder also needs the colour
        # extent each checkpoint observed, which is what pins the sample's frame size.
        checkpoints = direction_checkpoints()
        stage = {"gate": {"checkpoints": checkpoints}}
        if with_ladder:
            ladder_output = root / "native-ladder"
            ladder_output.mkdir()
            if samples is None:
                s, field = sample(kind=kind, flipped=flipped)
                samples = [(s, field)]
            # every retained launch carries the two straight-down looks, which replay judges
            samples = list(samples) + direction_samples()
            body = report(samples=[s for s, _ in samples])
            if with_files:
                for s, field in samples:
                    write_sample_files(ladder_output, s, field)
            (ladder_output / "native-depth-ladder.json").write_text(json.dumps(body))
            if with_own_result:
                (ladder_output / "native-result.json").write_text(json.dumps(
                    {"complete": True, "success": True, "failures": [],
                     "checkpoints": checkpoints}))
            (root / "native-ladder.log").write_text("ladder log\n" + log_for(body["samples"]))
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
        self.assertEqual(kept["ladder"]["samples"][:3],
                         ["ladder/native-depth-ladder-2.ppm.gz",
                          "ladder/native-depth-ladder-rejected-2.ppm.gz",
                          "ladder/native-depth-ladder-frame-2.ppm.gz"])
        for name in ("ladder/native-depth-ladder.json", "ladder/native-depth-ladder-2.ppm.gz",
                     "ladder/native-depth-ladder-rejected-2.ppm.gz",
                     "ladder/native-result.json", "ladder/native-ladder.log"):
            self.assertIn(name, kept["files"])
            self.assertTrue((target / name).is_file(), name)

    def test_the_retained_ladder_replays_with_its_brackets_and_direction(self):
        target, _ = self.build(kind="clouds", flipped=True)
        code, out = self.replay(target)
        self.assertEqual(code, 0, out)
        self.assertIn("larger depth value is nearer", out["ladder"]["z_direction"]["direction"])
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
        self.assertIn("ladder/native-depth-ladder.json", out["error"])

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
                     "source-sha256.json", "ladder/native-result.json",
                     "ladder/native-ladder.log"):
            target, _ = self.build()
            manifest = json.loads((target / "MANIFEST.json").read_text())
            del manifest["files"][name]
            (target / "MANIFEST.json").write_text(json.dumps(manifest))
            code, out = self.replay(target)
            self.assertEqual(code, 1, name)
            self.assertIn(name, out["error"])

    def test_a_required_file_deleted_with_its_entry_is_rejected(self):
        """Round-9 B1: deleting the fingerprint or a log AND its entry replayed as 0."""
        for name in ("source-sha256.json", "native.log", "ladder/native-ladder.log",
                     "ladder/native-result.json"):
            target, _ = self.build()
            (target / name).unlink()
            manifest = json.loads((target / "MANIFEST.json").read_text())
            del manifest["files"][name]
            (target / "MANIFEST.json").write_text(json.dumps(manifest))
            code, out = self.replay(target)
            self.assertEqual(code, 1, name)
            self.assertIn(name, out["error"])

    def test_an_empty_or_disagreeing_source_fingerprint_is_rejected(self):
        """Round-10 B1: a hash-listed `{}` fingerprint replayed as 0. Round-11 B1: so did a
        partial inventory, the real one minus a source, and aliases of one file."""
        real = REAL_FINGERPRINTS
        required = {k: real[k] for k in verify.SOURCE_BINDING_REQUIRED}
        minus_one = dict(real)
        minus_one.pop("src/main/java/me/cortex/voxy/client/core/vk/mcnative/McNativeDepthProbe.java")
        docs = {f"docs/ai/x{i}.md": "22" * 32 for i in range(100)}
        aliases = {**required, **{f"./{'/'.join(['.'] * i)}/scripts/verify.py": real["scripts/verify.py"]
                                  for i in range(1, 100)}}
        for content, fragment in (("{}", "names only 0"),
                                  (json.dumps({"scripts/verify.py": "00" * 32,
                                               **{f"src/x{i}.java": "11" * 32
                                                  for i in range(120)}}),
                                   "does not name build.gradle"),
                                  (json.dumps({**real, "scripts/verify.py": "ab" * 32}),
                                   "disagrees with this checkout"),
                                  (json.dumps({**required, **docs}), "source inventory"),
                                  (json.dumps(minus_one), "source inventory"),
                                  (json.dumps({**real, "src/extra.java": "33" * 32}),
                                   "source inventory"),
                                  (json.dumps(aliases), "normalized")):
            target, _ = self.build()
            (target / "source-sha256.json").write_text(content)
            self.rehash(target, "source-sha256.json")
            code, out = self.replay(target)
            self.assertEqual(code, 1, content[:40])
            self.assertIn(fragment, out["error"])

    def test_a_nested_manifest_is_not_exempt(self):
        """Round-9 B1: the exemption matched by basename, so ladder/MANIFEST.json passed."""
        target, _ = self.build()
        (target / "ladder" / "MANIFEST.json").write_text("anything\n")
        code, out = self.replay(target)
        self.assertEqual(code, 1)
        self.assertIn("ladder/MANIFEST.json", out["error"])

    def test_replay_rejects_a_report_that_omits_a_retained_sample(self):
        """Round-9: 17 of 18 samples dropped from the report replayed as 0."""
        s1, f1 = sample(at=2)
        s2, f2 = sample(at=242, kind="clouds")
        target, _ = self.build(samples=[(s1, f1), (s2, f2)])
        path = target / "ladder" / "native-depth-ladder.json"
        body = json.loads(path.read_text())
        body["samples"] = body["samples"][:1] + body["samples"][2:]
        path.write_text(json.dumps(body))
        self.rehash(target, "ladder/native-depth-ladder.json")
        code, out = self.replay(target)
        self.assertEqual(code, 1)
        self.assertIn("belong to no listed sample", out["error"])

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
