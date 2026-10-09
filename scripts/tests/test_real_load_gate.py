"""The real-LOAD gate: real sections, Minecraft's own matrix, Minecraft's LOADed depth.

The ladder hands each sampled frame to ONE experiment that writes Minecraft's depth after its
readbacks (both on: alternately, terrain-LOAD first). Real-LOAD must carry a result for every
sample it was handed — judged, or a reason from a fixed set — and judged samples are recounted
pixel by pixel exactly like terrain-LOAD's. At least one judged sample must hold pixels where
Voxy's real terrain must appear, or the experiment decided nothing.
"""
from pathlib import Path
import json
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify
from verify import native_ladder_result
from test_marker_gate import write_gz_ppm
from test_ladder_gate import (DEVICE, EXTENTS, TERRAIN_REF, direction_checkpoints,
                              direction_samples, full_ladder_package, write_gz_f32)


class RealLoadGateTest(unittest.TestCase):

    def run_gate(self, real_violate=None, real_status=None, mutate_ladder=None, mutate_real=None,
                 mutate_files=None, log=None, require=True, depth_kind="sweep", terrain=True):
        pairs = direction_samples()
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            body, tl, text = full_ladder_package(out, pairs, terrain=terrain, real=True,
                                                 real_violate=real_violate,
                                                 real_status=real_status, depth_kind=depth_kind)
            if mutate_ladder:
                mutate_ladder(body)
            if mutate_real:
                rl = json.loads((out / "native-real-load.json").read_text())
                mutate_real(rl)
                (out / "native-real-load.json").write_text(json.dumps(rl))
            if mutate_files:
                mutate_files(out)
            (out / "native-depth-ladder.json").write_text(json.dumps(body))
            return native_ladder_result(out, DEVICE, EXTENTS, text if log is None else log(text),
                                        direction_checkpoints(), require_coexist=True,
                                        require_terrain_load=terrain, require_real_load=require)

    def assertRefused(self, result, fragment):
        self.assertFalse(result["success"], "passed although: " + fragment)
        self.assertIn(fragment, " ".join(result["failures"]))

    def test_alternating_samples_compose_per_pixel(self):
        result = self.run_gate()
        self.assertTrue(result["success"], result["failures"])
        rl, tl = result["real_load"], result["terrain_load"]
        self.assertTrue(rl["enabled"])
        self.assertEqual([s["at"] for s in rl["samples"]], [3240])     # second sample
        self.assertEqual([s["at"] for s in tl["samples"]], [3000])     # first sample
        self.assertGreater(rl["expectVisible"], 0)
        self.assertIn("real sections drawn with Minecraft's matrix", result["answer"])

    def test_real_load_alone_takes_every_sample(self):
        result = self.run_gate(terrain=False)
        self.assertTrue(result["success"], result["failures"])
        self.assertEqual(len(result["real_load"]["samples"]), 2)

    def test_pixel_violations_fail(self):
        for violate, fragment in (("visible", "visibleWhereHidden=1"),
                                  ("hidden", "hiddenWhereVisible=1"),
                                  ("other", "other=1"),
                                  ("changed", "changedWhereNoGeometry=1")):
            self.assertRefused(self.run_gate(real_violate=violate), fragment)

    def test_skips_are_allowed_only_from_the_fixed_set_and_must_leave_a_judged_sample(self):
        result = self.run_gate(terrain=False, real_status={0: "no-camera-this-frame"})
        self.assertTrue(result["success"], result["failures"])
        self.assertEqual(result["real_load"]["judged"], 1)
        self.assertRefused(self.run_gate(terrain=False, real_status={0: "felt-like-it"}),
                           "not 'judged' nor a reason")
        self.assertRefused(self.run_gate(real_status={1: "nothing-meshed"}),
                           "decided nothing")

    def test_a_handed_sample_without_a_result_or_a_result_for_another_sample_fails(self):
        self.assertRefused(self.run_gate(mutate_real=lambda r: r.update(results=[])),
                           "every handed sample must carry one")
        def extra(r):
            r["results"].append({"at": 3000, "status": "nothing-meshed"})
        self.assertRefused(self.run_gate(mutate_real=extra), "every handed sample must carry one")
        self.assertRefused(self.run_gate(mutate_real=lambda r: r["results"].append(dict(r["results"][0]))),
                           "repeat a draw")

    def test_the_hand_off_rule_is_enforced(self):
        def swap(b):
            b["samples"][0]["experiment"], b["samples"][1]["experiment"] = \
                b["samples"][1]["experiment"], b["samples"][0]["experiment"]
        self.assertRefused(self.run_gate(mutate_ladder=swap), "as the hand-off rule requires")
        self.assertRefused(self.run_gate(mutate_ladder=lambda b: b.pop("realLoadEnabled")),
                           "does not state realLoadEnabled")

    def test_report_level_checks_are_live(self):
        cases = [
            (lambda r: r.update(enabled=False), "enabled=False"),
            (lambda r: r.update(instanceMode=False), "instanceMode=False"),
            (lambda r: r.update(level=2), "level 2"),
            (lambda r: r.update(declaredDepthState=[6, 1, 0]), "not Voxy's declared"),
            (lambda r: r.update(depthStateReadBack=True), "read back: True"),
            (lambda r: r.update(notes=["x"]), "notes=['x']"),
            (lambda r: r.update(problems=1), "problems=1"),
            (lambda r: r.update(leakedScenes=1), "leakedScenes=1"),
            (lambda r: r.update(readbacksInFlight=1), "readbacksInFlight=1"),
            (lambda r: r.update(builds=99), "built 99 scene(s)"),
            (lambda r: r.update(drawsRecorded=2), "recorded 2 pass(es)"),
            (lambda r: r.update(device="0xdead"), "names device 0xdead"),
            (lambda r: r.pop("results"), "does not state results"),
            (lambda r: r["results"][0].update(visible=-1), "visible is -1"),
            (lambda r: r["results"][0].update(visible=r["results"][0]["visible"] + 1),
             "but the retained crops say"),
            (lambda r: r["results"][0].update(referenceSet=1), "reports referenceSet=1"),
            (lambda r: r["results"][0].update(sceneLevel=0), "names scene level 0"),
            (lambda r: r["results"][0].pop("projectionAdjusted"), "adjusted to 0..1"),
            (lambda r: r["results"][0].update(file="x.ppm.gz"), "names file="),
        ]
        for mutate, fragment in cases:
            self.assertRefused(self.run_gate(mutate_real=mutate), fragment)
        self.assertRefused(self.run_gate(mutate_ladder=lambda b: b.update(realLoadDrawsRecorded=5)),
                           "the ladder saw 5 real-LOAD pass(es)")

    def test_a_skipped_sample_that_names_files_fails(self):
        def names(r):
            r["results"].append(None)
        def skip_with_file(r):
            r["results"][0] = {"at": r["results"][0]["at"], "status": "nothing-meshed", "file": "x"}
            r["drawsRecorded"] = 0
        result = self.run_gate(terrain=False, real_status={0: "nothing-meshed"},
                               mutate_real=lambda r: r["results"][0].update(file="x"))
        self.assertRefused(result, "was not judged but names files")

    def test_log_lines_must_agree(self):
        def drop(text):
            return "".join(l for l in text.splitlines(keepends=True) if "real load at draw" not in l)
        self.assertRefused(self.run_gate(log=drop), "real-load lines for draws")
        def status(text):
            return text.replace("real load at draw 3240 status=judged", "real load at draw 3240 status=nothing-meshed")
        self.assertRefused(self.run_gate(log=status), "says status 'nothing-meshed'")
        def counts(text):
            return text.replace("real load at draw 3240 status=judged geometry=", "real load at draw 3240 status=judged geometry=1")
        self.assertRefused(self.run_gate(log=counts), "real-load line for draw 3240 says")
        def dup(text):
            line = next(l for l in text.splitlines(keepends=True) if "real load at draw 3240" in l)
            return text + line
        self.assertRefused(self.run_gate(log=dup), "two real-load lines")

    def test_missing_files_wrong_sizes_and_orphans_fail(self):
        self.assertRefused(self.run_gate(mutate_files=lambda o: (o / "native-real-load-3240.ppm.gz").unlink()),
                           "is missing")
        def shrink(o):
            p = o / "native-real-load-depth-3240.f32.gz"
            rows, _ = verify.read_f32_gz(p)
            write_gz_f32(p, rows[:-1])
        self.assertRefused(self.run_gate(mutate_files=shrink), "not the band's")
        def bad_depth(o):
            p = o / "native-real-load-depth-3240.f32.gz"
            rows, _ = verify.read_f32_gz(p)
            rows[0][0] = -1.0
            write_gz_f32(p, rows)
        self.assertRefused(self.run_gate(mutate_files=bad_depth), "outside [0, 1]")
        def thumb(o):
            p = o / "native-real-load-frame-3240.ppm.gz"
            rows, _ = verify.read_ppm_gz(p)
            write_gz_ppm(p, [[(1, 2, 3)] * len(rows[0]) for _ in rows])
        self.assertRefused(self.run_gate(mutate_files=thumb), "did not come from there")
        self.assertRefused(self.run_gate(mutate_files=lambda o: write_gz_ppm(o / "native-real-load-9.ppm.gz", [[TERRAIN_REF]])),
                           "belong to no judged result")
        def crop_size(o):
            p = o / "native-real-load-3240.ppm.gz"
            rows, _ = verify.read_ppm_gz(p)
            write_gz_ppm(p, rows[:-1])
        self.assertRefused(self.run_gate(mutate_files=crop_size), "but the ladder crop is")

    def test_off_but_evidence_retained_or_required_fails(self):
        # real-LOAD alone, then the ladder says it was off (and handed nobody anything)
        def off(b):
            b.update(realLoadEnabled=False, realLoadDrawsRecorded=0)
            for sample in b["samples"]:
                sample["experiment"] = None
        self.assertRefused(self.run_gate(terrain=False, mutate_ladder=off, require=False),
                           "retained although the ladder says the experiment was off")
        self.assertRefused(self.run_gate(terrain=False, mutate_ladder=off, require=True),
                           "enabled the real-LOAD experiment but the ladder says")
        self.assertRefused(self.run_gate(mutate_files=lambda o: (o / "native-real-load.json").unlink()),
                           "native-real-load.json is not retained")

    def test_the_hand_off_rule_itself(self):
        c = verify.ladder_expected_consumer
        self.assertEqual([c(True, True, i) for i in range(4)],
                         ["terrainLoad", "realLoad", "terrainLoad", "realLoad"])
        self.assertEqual(c(True, False, 3), "terrainLoad")
        self.assertEqual(c(False, True, 0), "realLoad")
        self.assertIsNone(c(False, False, 0))
        self.assertIn("-PharnessNativeRealLoad=true", verify.LADDER_LAUNCH_FLAGS)
        self.assertIn("-PharnessNativeInstance=true", verify.LADDER_LAUNCH_FLAGS)


if __name__ == "__main__":
    unittest.main()
