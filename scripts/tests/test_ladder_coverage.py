"""Round-26 R26-LADDER-GUARD-COVERAGE: one case per ladder predicate whose removal no ladder or
LOAD test detected. Each asserts the predicate's own message, so removing it is detected."""
from pathlib import Path
import json
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify
import test_ladder_gate as tlg
from test_ladder_gate import (GROUND_Y, direction_checkpoints, direction_samples, log_for, report,
                              write_sample_files, DEVICE, EXTENTS)

from test_marker_gate import write_gz_ppm


class LadderGuardCoverageTest(unittest.TestCase):

    def gate(self, mutate=None, mutate_sample=None, no_log=False, **kw):
        body, fields = tlg.LadderGateTest().one(stage="warmup", camera=[1.0, 70.0, 2.0, 10.0, 20.0])
        log = log_for(body["samples"])
        original = json.loads(json.dumps(body["samples"]))
        if mutate:
            mutate(body)
        if mutate_sample:
            mutate_sample(body["samples"][0])
        if no_log:   # the report's own checks, which the log reconciliation would pre-empt
            import tempfile
            with tempfile.TemporaryDirectory() as tmp:
                out = Path(tmp)
                for smp, field in zip(original, fields):   # the files as the run wrote them
                    write_sample_files(out, smp, field)
                (out / "native-depth-ladder.json").write_text(json.dumps(body))
                return verify.native_ladder_result(out, DEVICE, EXTENTS, None)
        return tlg.LadderGateTest().run_gate(body, fields, log=kw.pop("log", log), **kw)

    def refused(self, result, fragment):
        self.assertFalse(result["success"], "passed although: " + fragment)
        self.assertIn(fragment, " ".join(result["failures"]))

    # ---- ladder_report_checks: report fields ----

    def test_report_field_types(self):
        self.refused(self.gate(lambda b: b.update(attempted="yes")), "ladder.attempted is 'yes', not a bool")
        self.refused(self.gate(lambda b: b.update(rungDepths="x")), "ladder.rungDepths is 'x', not a list")

    def test_experiments_that_were_off_recorded_nothing(self):
        self.refused(self.gate(lambda b: b.update(hierLoadEnabled=False, hierLoadDrawsRecorded=1)),
                     "the hierarchical-LOAD probe was off but recorded")
        self.refused(self.gate(lambda b: b.update(realLoadEnabled=False, realLoadDrawsRecorded=1)),
                     "the real-LOAD probe was off but recorded")

    def test_the_frame_scale_is_pinned(self):
        self.refused(self.gate(lambda b: b.update(frameScale=2)), "frameScale=2")

    # ---- ladder_report_checks: each sample ----

    def test_a_sample_must_be_an_object_with_every_field(self):
        self.refused(self.gate(lambda b: b["samples"].append(5)), "is 5, not an object")
        self.refused(self.gate(mutate_sample=lambda s: s.pop("rejectedRect")), "does not state rejectedRect")

    def test_sample_field_types(self):
        self.refused(self.gate(mutate_sample=lambda s: s.update(flipped="no"), no_log=True),
                     ".flipped is 'no', not a bool")
        self.refused(self.gate(mutate_sample=lambda s: s.update(targetWidth=1.5)), ".targetWidth is 1.5, not an int")
        self.refused(self.gate(mutate_sample=lambda s: s.update(rect="x"), no_log=True), ".rect is 'x', not a")
        self.refused(self.gate(mutate_sample=lambda s: s.update(camera=[1.0, 2.0, 3.0]), no_log=True), ".camera is")

    def test_the_rejected_orientation_and_frame_size(self):
        self.refused(self.gate(mutate_sample=lambda s: s.update(rejectedFlipped=s["flipped"])),
                     "rejects the orientation it selected")
        self.refused(self.gate(mutate_sample=lambda s: s.update(targetWidth=0)), "states a 0x")

    def test_counts_are_well_formed_and_cover_the_band(self):
        def clear(s):
            s["counts"]["clear"] = -1
        self.refused(self.gate(mutate_sample=clear, no_log=True), ".counts.clear is")
        def rungs(s):
            s["counts"]["rungs"] = s["counts"]["rungs"][:-1]
        self.refused(self.gate(mutate_sample=rungs, no_log=True), ".counts.rungs is")
        def total(s):
            s["counts"]["low"] += 1
        self.refused(self.gate(mutate_sample=total, no_log=True), "pixels but its band holds")

    def test_the_log_camera_must_be_the_reports(self):
        def moved(s):
            s["camera"] = [9.0, 70.0, 2.0, 10.0, 20.0]
        self.refused(self.gate(mutate_sample=moved), "says camera")

    # ---- recount_ladder_sample ----

    def test_the_rejected_crop_is_retained_at_its_size(self):
        body, fields = tlg.LadderGateTest().one()
        s = body["samples"][0]
        self.refused(tlg.LadderGateTest().run_gate(body, fields, rejected=[[(0, 0, 0)]]),
                     "the rejected ladder crop")
        result = tlg.LadderGateTest().run_gate(body, fields, drop=())
        self.assertTrue(result["success"], result["failures"])
        # the crop file itself missing
        import tempfile
        from verify import native_ladder_result
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            write_sample_files(out, s, fields[0])
            (out / s["rejectedFile"]).unlink()
            (out / "native-depth-ladder.json").write_text(json.dumps(body))
            self.refused(native_ladder_result(out, DEVICE, EXTENTS, log_for(body["samples"])),
                         "the retained rejected-orientation ladder crop")

    # ---- ladder_z_direction ----

    # round-27: the two predicates round 26's tests left uncovered are reachable (a small frame
    # extent; a ground so high that float precision makes the two cameras equal) — tested here.

    def test_a_band_smaller_than_a_thumbnail_block_is_refused(self):
        import tempfile
        saved = (tlg.FULL_W, tlg.FULL_H)
        try:
            tlg.FULL_W, tlg.FULL_H = 64, 32
            s, field = tlg.sample(stage="warmup", camera=[1.0, 70.0, 2.0, 10.0, 20.0])
            body = report(samples=[s])
            with tempfile.TemporaryDirectory() as tmp:
                out = Path(tmp)
                write_sample_files(out, s, field)
                (out / "native-depth-ladder.json").write_text(json.dumps(body))
                self.refused(verify.native_ladder_result(out, DEVICE, {(64, 32)}, log_for([s])),
                             "covers no whole thumbnail block")
        finally:
            tlg.FULL_W, tlg.FULL_H = saved

    def test_two_looks_whose_cameras_coincide_are_refused(self):
        ground = 1e20   # +12 and +108 vanish in float precision: the cameras are equal
        cases, samples = [], []
        for stage, off, rung, at in (("descend", 12, 4, 2), ("ascend", 108, 2, 4)):
            y = ground + off + verify.PLAYER_EYE_HEIGHT
            cases.append({"stage": stage, "groundY": ground, "playerY": ground + off,
                          "cameraY": y, "playerPitch": 90.0})
            rungs = [0] * verify.LADDER_RUNGS
            rungs[rung] = 1
            samples.append({"stage": stage, "at": at, "camera": [0.5, y, 0.5, 90.0, 0.0],
                            "low": 0, "clear": 0, "rungs": rungs})
        with self.assertRaises(ValueError) as caught:
            verify.ladder_z_direction(samples, cases)
        self.assertIn("is not below the ascend", str(caught.exception))

    def test_the_direction_needs_both_checkpoints(self):
        pairs = direction_samples()
        body = report(samples=[s for s, _ in pairs])
        fields = [f for _, f in pairs]
        no_descend = [c for c in direction_checkpoints() if c["stage"] != "descend"]
        self.refused(tlg.LadderGateTest().run_gate(body, fields, checkpoints=no_descend),
                     "no descend checkpoint")


if __name__ == "__main__":
    unittest.main()
