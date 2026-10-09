"""The hierarchical-LOAD gate: Voxy's hierarchical pipeline composited into Minecraft's frame.

VkHierarchicalScene renders into Voxy's own target with Minecraft's matrix; a native composite
writes its colour and depth into Minecraft's LOADed frame with Voxy's depth test. Each judged
sample is recounted pixel by pixel with the same rule and helper as real-LOAD; skips are
corroborated with the same rules and this experiment's log formats; the ladder hands samples to
three experiments, the horizon stage only to the real-world ones.
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
                              direction_samples, full_ladder_package)


class HierLoadGateTest(unittest.TestCase):

    def run_gate(self, hier_violate=None, hier_status=None, mutate_ladder=None, mutate_hier=None,
                 mutate_files=None, log=None, require=True, depth_kind="sweep", terrain=False,
                 real=False):
        pairs = direction_samples()
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            body, tl, text = full_ladder_package(out, pairs, terrain=terrain, real=real, hier=True,
                                                 hier_violate=hier_violate, hier_status=hier_status,
                                                 depth_kind=depth_kind)
            if mutate_ladder:
                mutate_ladder(body)
            if mutate_hier:
                hl = json.loads((out / "native-hier-load.json").read_text())
                mutate_hier(hl)
                (out / "native-hier-load.json").write_text(json.dumps(hl))
            if mutate_files:
                mutate_files(out)
            (out / "native-depth-ladder.json").write_text(json.dumps(body))
            return native_ladder_result(out, DEVICE, EXTENTS, text if log is None else log(text),
                                        direction_checkpoints(), require_coexist=True,
                                        require_terrain_load=terrain, require_real_load=real,
                                        require_hier_load=require)

    def assertRefused(self, result, fragment):
        self.assertFalse(result["success"], "passed although: " + fragment)
        self.assertIn(fragment, " ".join(result["failures"]))

    def test_the_composite_that_composes_per_pixel_passes(self):
        result = self.run_gate()
        self.assertTrue(result["success"], result["failures"])
        hl = result["hier_load"]
        self.assertTrue(hl["enabled"])
        self.assertEqual(hl["judged"], 2)
        self.assertGreater(hl["expectVisible"], 0)
        self.assertGreater(hl["expectHidden"], 0)
        self.assertIn("hierarchical pipeline composited natively", result["answer"])

    def test_alongside_terrain_and_real_load_every_sample_goes_to_one_experiment(self):
        result = self.run_gate(terrain=True, real=True, hier_status=None)
        # two samples outside horizon: terrain then real; hier gets none -> its gate must say so
        self.assertRefused(result, "decided nothing")

    def test_pixel_violations_fail(self):
        for violate, fragment in (("visible", "visibleWhereHidden=1"),
                                  ("hidden", "hiddenWhereVisible=1"),
                                  ("other", "other=1"),
                                  ("changed", "changedWhereNoGeometry=1")):
            self.assertRefused(self.run_gate(hier_violate=violate), fragment)

    def test_occlusion_and_visibility_are_both_required(self):
        self.assertRefused(self.run_gate(depth_kind="near-only"), "occlusion is untested")

    def test_skips_are_corroborated_with_this_experiments_log(self):
        # honest skips on the first sample, the second judged with both kinds: passes
        self.assertTrue(self.run_gate(hier_status={0: "atlas-pending"})["success"])
        self.assertTrue(self.run_gate(hier_status={0: "no-camera-this-frame"})["success"])
        def late(text):
            return text.replace("[native-vk] requested the block atlas (2048x2048) through Blaze3D\n", "")
        self.assertRefused(self.run_gate(hier_status={0: "atlas-pending"}, log=late),
                           "no outstanding atlas request")
        self.assertRefused(self.run_gate(hier_status={0: "nothing-meshed"}),
                           "no build that meshed nothing is logged")
        def keys(r):
            r["results"][0]["referenceFile"] = "x"
        self.assertRefused(self.run_gate(hier_status={0: "no-camera-this-frame"}, mutate_hier=keys),
                           "carries ['referenceFile']")
        self.assertRefused(self.run_gate(hier_status={0: "felt-like-it"}), "nor a reason")

    def test_scene_and_iteration_facts_are_reconciled(self):
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][0].update(meshedAtBuild=36)),
                           "the log's build line says")
        # meshing continues after the build: fewer sections now than at build is refused
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][0].update(meshed=34)),
                           "34 meshed sections now and 35 at build")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][0].update(iterationsRun=1)),
                           "ran 1 iteration(s)")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(builds=2)),
                           "shows build attempts [1]")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(iterations=2)),
                           "not what its source lays out")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][0].update(projectionAdjusted=True)),
                           "says projectionAdjusted=True")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][0].update(atlasGeneration=2)),
                           "atlas generation 2 of 1")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][1].update(cameraCapture=1)),
                           "not after the previous judged sample")

    def test_report_level_checks_are_live(self):
        cases = [
            (lambda r: r.update(enabled=False), "enabled=False"),
            (lambda r: r.update(instanceMode=False), "instanceMode=False"),
            (lambda r: r.update(declaredDepthState=[6, 1, 0]), "not Voxy's declared"),
            (lambda r: r.update(notes=["x"]), "notes=['x']"),
            (lambda r: r.update(problems=1), "problems=1"),
            (lambda r: r.update(closeFailures=1), "closeFailures=1"),
            (lambda r: r.update(leakedScenes=1), "leakedScenes=1"),
            (lambda r: r.update(readbacksInFlight=1), "readbacksInFlight=1"),
            (lambda r: r.update(drawsRecorded=1), "recorded 1 pass(es)"),
            (lambda r: r.update(device="0xdead"), "names device 0xdead"),
            (lambda r: r.update(device=None), "names no device"),
            (lambda r: r.update(atlasReads=2), "atlas read(s) but the log shows 1"),
            (lambda r: r.pop("results"), "does not state results"),
            (lambda r: r.update(enabled="true"), "hierLoad.enabled is 'true', not a bool"),
            (lambda r: r.update(builds=1.5), "hierLoad.builds is 1.5, not an int"),
            (lambda r: r["results"].append({"at": "x", "status": "judged"}), "result is malformed"),
            (lambda r: r["results"].append(dict(r["results"][0])), "repeat a draw"),
            (lambda r: r.update(results=r["results"][:1]), "every handed sample must carry one"),
            (lambda r: r["results"][0].update(visible=-1), "visible is -1"),
            (lambda r: r["results"][0].update(file="x.ppm.gz"), "names file="),
            (lambda r: r["results"][0].update(minDepth=0.9), "reports minDepth=0.9"),
        ]
        for mutate, fragment in cases:
            self.assertRefused(self.run_gate(mutate_hier=mutate), fragment)
        self.assertRefused(self.run_gate(mutate_ladder=lambda b: b.update(hierLoadDrawsRecorded=5)),
                           "the ladder saw 5 hierarchical-LOAD pass(es)")
        self.assertRefused(self.run_gate(mutate_ladder=lambda b: b.pop("hierLoadEnabled")),
                           "does not state hierLoadEnabled")

    def test_log_lines_and_files_must_agree(self):
        def drop(text):
            return "".join(l for l in text.splitlines(keepends=True) if "hier load at draw" not in l)
        self.assertRefused(self.run_gate(log=drop), "hier-load lines for draws")
        def counts(text):
            return text.replace("hier load at draw 3240 status=judged geometry=",
                                "hier load at draw 3240 status=judged geometry=1")
        self.assertRefused(self.run_gate(log=counts), "hier-load line for draw 3240 says")
        self.assertRefused(self.run_gate(mutate_files=lambda o: (o / "native-hier-load-3240.ppm.gz").unlink()),
                           "is missing")
        self.assertRefused(self.run_gate(mutate_files=lambda o: write_gz_ppm(o / "native-hier-load-9.ppm.gz", [[TERRAIN_REF]])),
                           "belong to no judged result")

    def test_off_but_evidence_retained_or_required_fails(self):
        def off(b):
            b.update(hierLoadEnabled=False, hierLoadDrawsRecorded=0)
            for sample in b["samples"]:
                sample["experiment"] = None
        self.assertRefused(self.run_gate(mutate_ladder=off, require=False),
                           "retained although the ladder says the experiment was off")
        self.assertRefused(self.run_gate(mutate_ladder=off, require=True),
                           "enabled the hierarchical-LOAD experiment but the ladder says")

    def test_the_hand_off_rule_with_three_experiments_and_the_horizon_stage(self):
        c = verify.ladder_expected_consumers
        self.assertEqual(c(True, True, True, ["warmup", "turn", "travel", "return"]),
                         ["terrainLoad", "realLoad", "hierLoad", "terrainLoad"])
        self.assertEqual(c(True, True, True, ["return", "horizon", "horizon", "edit"]),
                         ["terrainLoad", "realLoad", "hierLoad", "realLoad"])
        self.assertEqual(c(True, False, False, ["horizon"]), ["terrainLoad"])
        self.assertEqual(c(False, False, False, ["horizon", "edit"]), [None, None])
        self.assertIn("-PharnessNativeHierLoad=true", verify.LADDER_LAUNCH_FLAGS)
        self.assertEqual(verify.ladder_launch_requirements(
            ["gradlew", *verify.LADDER_LAUNCH_FLAGS])["hierLoad"], True)


if __name__ == "__main__":
    unittest.main()
