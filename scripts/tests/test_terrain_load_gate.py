"""The terrain-LOAD gate: Voxy's real terrain pipeline against Minecraft's loaded depth.

A separate probe from the ladder draws a synthetic depth sweep through VkTerrainRenderer into a
pass that LOADs Minecraft's colour and depth, with Voxy's own depth state (writes on), after the
ladder's readbacks of the same frame, and a third readback is retained. The gate decides every
band pixel from two independent measurements — the ladder's bracket of Minecraft's depth and
Voxy's own reference depth of the same scene — and requires zero violations.

The fixtures paint a reference DEPTH over the band (no geometry, a near panel, a far panel, a
stripe inside Minecraft's bracket) and derive the third crop from it with the fixture's own
statement of the rule, so a gate that reads the rule wrongly cannot pass them. Each case is a
way the evidence could lie: a pixel shown where it must be hidden or hidden where it must show,
a pixel of neither colour, a changed pixel without geometry, counts or log lines the crops
contradict, a report retained behind an off flag or missing behind an on one, a declared depth
state that is not Voxy's, a claim to have read the state back, a pass count the sample count
contradicts, a reference the recount contradicts, an orphaned file, and an experiment whose
pixels never had both determinate kinds.
"""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify
from verify import native_ladder_result
from test_marker_gate import write_gz_ppm
from test_ladder_gate import (DEVICE, EXTENTS, LADDER_COMMAND, TERRAIN_REF, direction_checkpoints,
                              direction_samples, full_ladder_package, terrain_after, terrain_depth,
                              terrain_entry, terrain_log_for, write_gz_f32)
import test_ladder_gate


class TerrainLoadGateTest(unittest.TestCase):

    def run_gate(self, violate=None, depth_kind="sweep", coexist=True, terrain=True,
                 mutate_ladder=None, mutate_terrain=None, mutate_files=None, log=None,
                 require=True, pairs=None):
        pairs = direction_samples() if pairs is None else pairs
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            body, tl, text = full_ladder_package(out, pairs, violate=violate,
                                                 depth_kind=depth_kind, coexist=coexist,
                                                 terrain=terrain)
            if mutate_ladder:
                mutate_ladder(body)
            if mutate_terrain and tl is not None:
                mutate_terrain(tl)
                (out / "native-terrain-load.json").write_text(json.dumps(tl))
            if mutate_files:
                mutate_files(out, tl)
            (out / "native-depth-ladder.json").write_text(json.dumps(body))
            return native_ladder_result(out, DEVICE, EXTENTS, text if log is None else log(text),
                                        direction_checkpoints(), require_coexist=coexist,
                                        require_terrain_load=require)

    def assertRefused(self, result, fragment):
        self.assertFalse(result["success"], "passed although: " + fragment)
        self.assertIn(fragment, " ".join(result["failures"]))

    def test_voxys_terrain_that_composes_per_pixel_passes(self):
        result = self.run_gate()
        self.assertTrue(result["success"], result["failures"])
        tl = result["terrain_load"]
        self.assertTrue(tl["enabled"])
        self.assertEqual(tl["mixedSamples"], 2, "both looks hold near and far panels")
        for sample in tl["samples"]:
            self.assertEqual(sample["other"], 0)
            self.assertGreater(sample["expectVisible"], 0)
            self.assertGreater(sample["expectHidden"], 0)
            self.assertGreater(sample["noGeometry"], 0)
            self.assertEqual(sample["changedWhereNoGeometry"], 0)
        # the near look has pixels inside its own bracket: undetermined, counted, not violations
        self.assertGreater(tl["samples"][0]["undetermined"], 0)
        self.assertIn("Voxy's terrain pipeline in a LOADed pass composed per pixel", result["answer"])

    def test_without_the_coexist_quad_the_previous_readback_is_the_ladders(self):
        result = self.run_gate(coexist=False)
        self.assertTrue(result["success"], result["failures"])
        self.assertFalse(result["coexist"]["enabled"])
        self.assertTrue(result["terrain_load"]["enabled"])

    def test_a_pixel_shown_where_voxy_is_farther_fails(self):
        self.assertRefused(self.run_gate(violate="visible"), "appears at 1 pixel(s)")

    def test_a_pixel_hidden_where_voxy_is_nearer_fails(self):
        self.assertRefused(self.run_gate(violate="hidden"), "missing at 1 pixel(s)")

    def test_a_pixel_of_neither_colour_fails(self):
        self.assertRefused(self.run_gate(violate="other"), "neither Voxy's reference colour")

    def test_a_changed_pixel_without_geometry_fails(self):
        self.assertRefused(self.run_gate(violate="changed"), "without Voxy geometry changed")

    def test_published_counts_the_crops_contradict_fail(self):
        def lie(tl):
            tl["results"][0]["visible"] += 1
            tl["results"][0]["hidden"] -= 1
        self.assertRefused(self.run_gate(mutate_terrain=lie), "but the retained crops say")

    def test_a_log_line_the_crops_contradict_fails(self):
        def lie(text):
            lines = text.splitlines(keepends=True)
            for i, line in enumerate(lines):
                if "terrain load at draw" in line:
                    lines[i] = line.replace(" visible=", " visible=1", 1)
                    break
            return "".join(lines)
        self.assertRefused(self.run_gate(log=lie), "terrain-load line for draw")

    def test_missing_duplicate_or_extra_log_lines_fail(self):
        def drop(text):
            return "".join(l for l in text.splitlines(keepends=True) if "terrain load at draw 3240" not in l)
        self.assertRefused(self.run_gate(log=drop), "terrain-load lines for draws")

        def dup(text):
            line = next(l for l in text.splitlines(keepends=True) if "terrain load at draw 3000" in l)
            return text + line
        self.assertRefused(self.run_gate(log=dup), "two terrain-load lines")

    def test_the_experiment_cannot_be_reported_off_when_the_launch_enabled_it(self):
        # off everywhere: the launch required it
        result = self.run_gate(terrain=False, require=True)
        self.assertRefused(result, "enabled the terrain-LOAD experiment but the ladder says")
        # the ladder says off but a report/files/log lines were retained
        result = self.run_gate(mutate_ladder=lambda b: b.update(terrainLoadEnabled=False,
                                                                 terrainLoadDrawsRecorded=0),
                               require=False)
        self.assertRefused(result, "retained although the ladder says the experiment was off")

        def strip_report(out, tl):
            (out / "native-terrain-load.json").unlink()
        result = self.run_gate(mutate_ladder=lambda b: b.update(terrainLoadEnabled=False,
                                                                 terrainLoadDrawsRecorded=0),
                               mutate_files=strip_report, require=False)
        self.assertRefused(result, "terrain-load lines for draws")
        # off and nothing retained: fine when the launch did not require it
        result = self.run_gate(terrain=False, require=False)
        self.assertTrue(result["success"], result["failures"])
        self.assertFalse(result["terrain_load"]["enabled"])
        # off in the ladder, passes recorded anyway
        result = self.run_gate(terrain=False, require=False,
                               mutate_ladder=lambda b: b.update(terrainLoadDrawsRecorded=2))
        self.assertRefused(result, "was off but recorded 2 pass(es)")

    def test_a_missing_report_behind_an_enabled_flag_fails(self):
        def strip(out, tl):
            (out / "native-terrain-load.json").unlink()
        self.assertRefused(self.run_gate(mutate_files=strip), "native-terrain-load.json is not retained")

    def test_each_report_level_check_is_live(self):
        cases = [
            (lambda t: t.update(enabled=False), "enabled=False"),
            (lambda t: t.update(built=False), "built=False"),
            (lambda t: t.update(ladderEnabled=False), "ran without the ladder"),
            (lambda t: t.update(scene="boundaryCases"), "its source lays out"),
            (lambda t: t.update(declaredDepthState=[6, 1, 0]), "not Voxy's"),
            (lambda t: t.update(declaredDepthState=[1, 1, 1]), "not Voxy's"),
            (lambda t: t.update(depthStateReadBack=True), "claims to have read its depth state back"),
            (lambda t: t.update(colourFormat=44), "colour format is 44"),
            (lambda t: t.update(depthFormat=130), "depth format is 130"),
            (lambda t: t.update(mvp=[1.0] * 15), "not 16 finite numbers"),
            (lambda t: t.update(mvp=[float("nan")] + [1.0] * 15), "not 16 finite numbers"),
            (lambda t: t.update(drawCount=0), "not the 10 the depth sweep issues"),
            (lambda t: t.update(referenceSet=0), "drew nothing"),
            (lambda t: t.update(notes=["FAILED"]), "reported notes"),
            (lambda t: t.update(problems=1), "problems=1"),
            (lambda t: t.update(closeFailures=1), "closeFailures=1"),
            (lambda t: t.update(leakedScenes=1), "leakedScenes=1"),
            (lambda t: t.update(firstProblem="x"), "firstProblem='x'"),
            (lambda t: t.update(deviceDiverged=True), "device diverged"),
            (lambda t: t.update(readbacksInFlight=1), "never completed"),
            (lambda t: t.update(device="0xdead"), "names device 0xdead"),
            (lambda t: t.update(drawsRecorded=1), "recorded 1 pass(es)"),
            (lambda t: t.pop("scene"), "does not state scene"),
            (lambda t: t.update(results=t["results"][:1]), "every sample must carry one"),
            (lambda t: t.update(results=t["results"] + [dict(t["results"][0])]), "repeat a draw"),
            (lambda t: t.update(results=t["results"] + ["junk"]), "malformed"),
            (lambda t: t["results"][0].update(visible=-1), "visible is -1"),
            (lambda t: t["results"][0].update(file="native-terrain-load-9.ppm.gz"), "names file="),
            (lambda t: t["results"][0].update(referenceDepthFile="x.f32.gz"),
             "names referenceDepthFile="),
            (lambda t: t["results"][0].update(minDepth=0.25), "reports minDepth=0.25"),
            (lambda t: t["results"][0].update(maxDepth=None), "reports maxDepth=None"),
        ]
        for mutate, fragment in cases:
            self.assertRefused(self.run_gate(mutate_terrain=mutate), fragment)
        # the ladder's own view of the pass count must agree with the probe's
        self.assertRefused(self.run_gate(mutate_ladder=lambda b: b.update(terrainLoadDrawsRecorded=1)),
                           "the ladder saw 1 terrain-LOAD pass(es)")
        self.assertRefused(self.run_gate(mutate_ladder=lambda b: b.pop("terrainLoadEnabled")),
                           "does not state terrainLoadEnabled")

    def test_a_field_of_the_wrong_type_fails(self):
        for mutate, fragment in ((lambda t: t.update(enabled="true"), "not a bool"),
                                 (lambda t: t.update(drawsRecorded=2.5), "not an int"),
                                 (lambda t: t.update(results={}), "not a")):
            self.assertRefused(self.run_gate(mutate_terrain=mutate), fragment)

    def test_a_missing_or_wrong_sized_file_fails(self):
        for name, fragment in (("native-terrain-load-3000.ppm.gz", "is missing"),
                               ("native-terrain-load-frame-3000.ppm.gz", "is missing")):
            def strip(out, tl, name=name):
                (out / name).unlink()
            self.assertRefused(self.run_gate(mutate_files=strip), fragment)

        def strip_reference(out, tl):
            (out / tl["results"][0]["referenceFile"]).unlink()
        self.assertRefused(self.run_gate(mutate_files=strip_reference), "is missing")

        def shrink(out, tl):
            path = out / tl["results"][0]["file"]
            rows, _ = verify.read_ppm_gz(path)
            write_gz_ppm(path, rows[:-1])
        self.assertRefused(self.run_gate(mutate_files=shrink), "but the ladder crop is")

        def shrink_depth(out, tl):
            path = out / tl["results"][0]["referenceDepthFile"]
            rows, _ = verify.read_f32_gz(path)
            write_gz_f32(path, rows[:-1])
        self.assertRefused(self.run_gate(mutate_files=shrink_depth), "not the band's")

        def corrupt_depth(out, tl):
            path = out / tl["results"][0]["referenceDepthFile"]
            rows, _ = verify.read_f32_gz(path)
            rows[0][0] = 2.0
            write_gz_f32(path, rows)
        self.assertRefused(self.run_gate(mutate_files=corrupt_depth), "outside [0, 1]")

    def test_a_crop_that_does_not_match_its_thumbnail_fails(self):
        def scene_thumb(out, tl):
            path = out / tl["results"][0]["frameFile"]
            rows, _ = verify.read_ppm_gz(path)
            write_gz_ppm(path, [[test_ladder_gate.SCENE] * len(rows[0]) for _ in rows])
        self.assertRefused(self.run_gate(mutate_files=scene_thumb), "did not come from there")

    def test_an_orphaned_terrain_load_file_fails(self):
        def extra(out, tl):
            write_gz_ppm(out / "native-terrain-load-77.ppm.gz", [[TERRAIN_REF]])
        self.assertRefused(self.run_gate(mutate_files=extra), "belong to no listed result")

    def test_a_reference_depth_that_contradicts_the_crops_fails(self):
        """Swapping the near panel's depth for a far one makes the shown pixels violations."""
        def swap(out, tl):
            entry = tl["results"][0]
            rows, _ = verify.read_f32_gz(out / entry["referenceDepthFile"])
            rows = [[1e-6 if d == 0.5 else d for d in row] for row in rows]
            write_gz_f32(out / entry["referenceDepthFile"], rows)
        result = self.run_gate(mutate_files=swap)
        self.assertFalse(result["success"])
        failures = " ".join(result["failures"])
        self.assertTrue("retained crops say" in failures or "appears at" in failures, failures)

    def test_an_experiment_without_both_kinds_decides_nothing(self):
        self.assertRefused(self.run_gate(depth_kind="near-only"), "decided nothing per pixel")

    def test_a_band_without_geometry_fails(self):
        def empty(out, tl):
            entry = tl["results"][0]
            rows, _ = verify.read_f32_gz(out / entry["referenceDepthFile"])
            write_gz_f32(out / entry["referenceDepthFile"], [[0.0] * len(rows[0]) for _ in rows])
        result = self.run_gate(mutate_files=empty)
        self.assertFalse(result["success"])
        # Round-18 R18-TEST-TERRAINLOAD: with every count, extremum and log line honestly
        # saying "no geometry" (and a reference set that claims one pixel, so the earlier
        # referenceSet=0 refusal does not fire first), only the geometry guard itself refuses
        self.assertRefused(self.run_gate(depth_kind="none",
                                         mutate_terrain=lambda t: t.update(referenceSet=1)),
                           "holds no geometry in the band")
        # an honest empty reference is refused earlier, by the reference-set guard
        self.assertRefused(self.run_gate(depth_kind="none"), "drew nothing")
        # ... and an extremum published where there is no geometry is refused by its own guard
        def claim(tl):
            tl["referenceSet"] = 1
            tl["results"][0]["minDepth"] = 0.5
        self.assertRefused(self.run_gate(depth_kind="none", mutate_terrain=claim),
                           "with no geometry in the band")

    def test_terrain_load_files_retained_while_off_fail(self):
        """Round-18 R18-TEST-TERRAINLOAD: the orphan guard on the off branch had no test."""
        def stray(out, tl):
            write_gz_ppm(out / "native-terrain-load-77.ppm.gz", [[TERRAIN_REF]])
        result = self.run_gate(terrain=False, require=False, mutate_files=stray)
        self.assertRefused(result, "retained although the experiment was off")

    def test_the_published_view_and_scene_facts_are_pinned(self):
        """Round-18 R18-TERRAIN-METADATA: a zero matrix, a fictitious eye, a 1x1 extent, a
        draw count and reference set of 1 and near 999 all passed."""
        cases = [
            (lambda t: t.update(mvp=[0.0] * 16), "mvp[0]"),
            (lambda t: t.update(eye=[1.0, 2.0, 3.0]), "eye is"),
            (lambda t: t.update(centre=[0.0, 0.0, 0.0]), "centre is"),
            (lambda t: t.update(width=1, height=1), "scene is 1x1"),
            (lambda t: t.update(drawCount=1), "drawCount=1"),
            (lambda t: t.update(referenceSet=1), "referenceSet=1 but"),
            (lambda t: t.update(near=999.0), "near is 999.0"),
            (lambda t: t.update(far=10.0), "far is 10.0"),
            (lambda t: t.update(fovDegrees=90), "fovDegrees is 90"),
            (lambda t: t.update(fitMargin=0.5), "fitMargin is 0.5"),
            (lambda t: t.pop("near"), "does not state near"),
        ]
        for mutate, fragment in cases:
            self.assertRefused(self.run_gate(mutate_terrain=mutate), fragment)
        # the recomputed matrix matches the retained run's published one
        import json as _json
        run = Path(__file__).resolve().parents[2] / "docs" / "ai" / "runs" / "native-evidence"
        newest = sorted(p for p in run.iterdir() if (p / "ladder" / "native-terrain-load.json").is_file())
        if newest:
            report_body = _json.loads((newest[-1] / "ladder" / "native-terrain-load.json").read_text())
            want = verify.terrain_load_expected_mvp(report_body["width"], report_body["height"])
            for got, exp in zip(report_body["mvp"], want):
                self.assertLess(abs(got - exp), 1e-4 * max(1.0, abs(exp)))

    def test_the_expectation_rule_matches_the_java_rule(self):
        z = verify.EXPECTED_LADDER_DEPTHS
        e = verify.terrain_load_expectation
        LOW, BASE, R0 = verify.LADDER_LOW, verify.LADDER_BASE, verify.LADDER_RUNG0
        self.assertEqual(e(LOW, z[0]), 1)
        self.assertEqual(e(LOW, z[0] / 2), 0)
        self.assertEqual(e(BASE, z[0]), 1)
        self.assertEqual(e(BASE, z[0] * 0.999), -1)
        for i in range(verify.LADDER_RUNGS - 1):
            self.assertEqual(e(R0 + i, z[i]), -1)
            self.assertEqual(e(R0 + i, z[i + 1]), 1)
            self.assertEqual(e(R0 + i, (z[i] + z[i + 1]) / 2), 0)
        last = R0 + verify.LADDER_RUNGS - 1
        self.assertEqual(e(last, z[-1]), -1)
        self.assertEqual(e(last, 0.5), 0)
        self.assertEqual(e(last, 1.0), 1)
        self.assertEqual(e(verify.LADDER_OTHER, 0.5), 0)
        self.assertEqual(verify.TERRAIN_LOAD_DEPTH_STATE, [6, 1, 1])
        self.assertEqual(verify.TERRAIN_LOAD_SCENE, "depthSweep")


if __name__ == "__main__":
    unittest.main()
