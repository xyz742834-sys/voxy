"""The hierarchical-LOAD gate: Voxy's hierarchical pipeline composited into Minecraft's frame.

VkHierarchicalScene renders into Voxy's own target with Voxy's own projection of Minecraft's
camera; its depth is reprojected into Minecraft's space; a native composite writes its colour into
Minecraft's LOADed frame by Voxy's GL rule (only where Minecraft's depth is still clear). Each
judged sample is recounted pixel by pixel with real-LOAD's helper in clear-only mode; skips are
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
                              direction_samples, far_sample, full_ladder_package, sample)


class HierLoadGateTest(unittest.TestCase):

    def run_gate(self, hier_violate=None, hier_status=None, mutate_ladder=None, mutate_hier=None,
                 mutate_files=None, log=None, require=True, depth_kind="sweep", terrain=False,
                 real=False, frames=False, require_frames=None, pairs=None, far_depths=None):
        # the two straight-down ground looks hold no clear pixel; Voxy's GL rule shows it only on
        # clear pixels, so a later look with clear sky between clouds carries the must-show side
        if pairs is None:
            pairs = direction_samples() + [sample(at=3480, kind="clouds", stage="reconnect"), far_sample()]
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            body, tl, text = full_ladder_package(out, pairs, terrain=terrain, real=real, hier=True,
                                                 hier_violate=hier_violate, hier_status=hier_status,
                                                 depth_kind=depth_kind, hier_frames=frames,
                                                 far_depths=far_depths)
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
                                        require_hier_load=require,
                                        require_hier_frames=frames if require_frames is None
                                        else require_frames)

    def assertRefused(self, result, fragment):
        self.assertFalse(result["success"], "passed although: " + fragment)
        self.assertIn(fragment, " ".join(result["failures"]))

    def test_the_composite_that_composes_per_pixel_passes(self):
        result = self.run_gate()
        self.assertTrue(result["success"], result["failures"])
        hl = result["hier_load"]
        self.assertTrue(hl["enabled"])
        self.assertEqual(hl["judged"], 4)
        self.assertEqual(hl["farSamples"], 1)
        self.assertGreater(hl["beyondMinecraftFarInFarLook"], 0)
        self.assertGreater(hl["expectVisible"], 0)
        self.assertGreater(hl["expectHidden"], 0)
        self.assertIn("hierarchical pipeline composited natively", result["answer"])

    def test_the_far_look_must_show_terrain_beyond_minecrafts_far_plane(self):
        # no far look at all
        result = self.run_gate(pairs=direction_samples() + [sample(at=3480, kind="clouds", stage="reconnect")])
        self.assertRefused(result, "0 judged hierarchical-LOAD sample(s) in the far look")
        # a far look whose terrain lies inside the far plane: consistent depths, no far pixel
        inside = verify.HIER_REPROJECT_EPS * 4
        self.assertRefused(self.run_gate(far_depths=(inside, inside)),
                           "0 Voxy pixel(s) beyond Minecraft's far plane")

    def test_alongside_terrain_and_real_load_every_sample_goes_to_one_experiment(self):
        # three samples outside horizon go terrain, real, hier: the hierarchy gets only the last,
        # a ground look with no clear pixel, so nothing may show -> its gate must say so
        ground = direction_samples() + [sample(at=3480, kind="near", stage="reconnect")]
        self.assertRefused(self.run_gate(terrain=True, real=True, pairs=ground), "decided nothing")

    def test_pixel_violations_fail(self):
        for violate, fragment in (("visible", "visibleWhereHidden=1"),
                                  ("hidden", "hiddenWhereVisible=1"),
                                  ("other", "other=1"),
                                  ("changed", "changedWhereNoGeometry=1")):
            self.assertRefused(self.run_gate(hier_violate=violate), fragment)

    def test_occlusion_and_visibility_are_both_required(self):
        # the hierarchy gets only an all-clear look: everything may show, nothing is hidden
        sky = direction_samples() + [sample(at=3480, kind="zero", stage="reconnect")]
        self.assertRefused(self.run_gate(terrain=True, real=True, pairs=sky), "occlusion is untested")

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
        # the scene streams like Voxy's GL renderer (RenderDistanceTracker at ceil(distance + 1))
        def far_line(text):
            return text.replace("streaming render distance 17", "streaming render distance 9")
        self.assertRefused(self.run_gate(log=far_line), "build line streams at 9")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(streamRenderDistance=18)),
                           "streams at 18 columns")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(streaming=False)), "streaming=False")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(maxTopLevels=0)), "nothing was streamed in")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(maxBoundSections=0)), "no near cut")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(vanillaBound=None)), "no near cut")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(lightmapsApplied=0)), "synthetic lighting")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(postPass=None)), "fog and fade are not applied")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(sectionRenderDistance=None)),
                           "sectionRenderDistance=None")
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

    def test_every_frame_rendering_is_reconciled_with_the_log(self):
        ok = self.run_gate(frames=True)
        self.assertTrue(ok["success"], ok["failures"])
        self.assertEqual(ok["hier_load"]["frames"]["framesComposited"], 4996)   # 5000 draws, 4 handed
        self.assertEqual(self.run_gate()["hier_load"]["frames"], {"everyFrame": False})
        self.assertRefused(self.run_gate(frames=False, require_frames=True),
                           "everyFrame=false")
        self.assertRefused(self.run_gate(frames=True, require_frames=False),
                           "did not enable it")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(framesComposited=5)),
                           "with every-frame rendering off")
        self.assertRefused(self.run_gate(frames=True, mutate_hier=lambda r: r.update(framesComposited=0)),
                           "composited no frame")
        self.assertRefused(self.run_gate(frames=True, mutate_hier=lambda r: r.update(framesComposited=150)),
                           "fall, or exceed")
        self.assertRefused(self.run_gate(frames=True, mutate_hier=lambda r: r.pop("frameSkips")),
                           "does not state frameSkips")
        self.assertRefused(self.run_gate(frames=True, mutate_hier=lambda r: r.update(everyFrame="yes")),
                           "hierLoad.everyFrame is 'yes'")
        self.assertRefused(self.run_gate(frames=True, mutate_hier=lambda r: r.update(frameSkips={"bored": 2})),
                           "not a skip reason")
        self.assertTrue(self.run_gate(frames=True, mutate_hier=lambda r: r.update(
            frameSkips={"atlas-pending": 2}, framesComposited=r["framesComposited"] - 2))["success"])
        # round-25 R25-FRAME-ACCOUNTING: composited + skipped + handed must equal the ladder's frames
        self.assertRefused(self.run_gate(frames=True, mutate_hier=lambda r: r.update(framesComposited=10 ** 12)),
                           "but the ladder drew on 5000")
        self.assertRefused(self.run_gate(frames=True, mutate_hier=lambda r: r.update(frameSkips={"nothing-meshed": 10 ** 12})),
                           "but the ladder drew on 5000")
        def drop(text):
            return "".join(l for l in text.splitlines(keepends=True) if "hier frames before draw 3240" not in l)
        self.assertRefused(self.run_gate(frames=True, log=drop), "every handed sample logs one")
        def stall(text):
            return text.replace("composited=200", "composited=100")
        self.assertRefused(self.run_gate(frames=True, log=stall), "no frame was composited between")
        def fall(text):
            return text.replace("composited=200", "composited=50")
        self.assertRefused(self.run_gate(frames=True, log=fall), "fall, or exceed")
        def twice(text):
            return text + "[native-vk] hier frames before draw 3240: composited=200\n"
        self.assertRefused(self.run_gate(frames=True, log=twice), "two hier-frames lines")

    def test_the_scene_must_run_voxys_raster_cull(self):
        # round-24 R24-HIER-CULL
        for mode in ("ALL_VISIBLE", None, "cull"):
            self.assertRefused(self.run_gate(mutate_hier=lambda r, m=mode: r["results"][0].update(visibility=m)),
                               "the raster cull and temporal pass did not run")
        self.assertEqual(verify.HIER_LOAD_VISIBILITY, "CULL")

    def test_each_hierarchical_guard_round_24_found_undetected(self):
        # round-24 R24-HIER-GUARD-COVERAGE: one case per predicate no hierarchy test detected
        def twice(text):
            line = next(l for l in text.splitlines(keepends=True) if "hier load at draw 3240" in l)
            return text + line
        self.assertRefused(self.run_gate(log=twice), "two hier-load lines for draw 3240")
        self.assertRefused(self.run_gate(mutate_files=lambda o: (o / "native-hier-load.json").unlink()),
                           "native-hier-load.json is not retained")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(results={})),
                           "hierLoad.results is {}")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(firstProblem=3)),
                           "hierLoad.firstProblem is 3")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(atlasReads=0), log=lambda t: None),
                           "never read the block atlas")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][0].update(sceneBuild=2)),
                           "but the probe built 1")
        def status(text):
            return text.replace("hier load at draw 3240 status=judged", "hier load at draw 3240 status=atlas-pending")
        self.assertRefused(self.run_gate(log=status), "says status 'atlas-pending' but the report says 'judged'")
        # a frame extent is a real-LOAD skip key, never a hierarchical one
        self.assertRefused(self.run_gate(hier_status={0: "no-camera-this-frame"},
                                         mutate_hier=lambda r: r["results"][0].update(frameExtent=[1, 1])),
                           "carries ['frameExtent']")

    def test_the_shared_load_helpers_guard_hierarchical_samples_too(self):
        """Round-27 R24-HIER-GUARD-COVERAGE: 27 shared-helper predicates (skip provenance, judged
        entry facts, per-sample judgement) were detected only by real-LOAD tests. One case each,
        on the hierarchical fixture, asserting the predicate's own message."""
        from test_ladder_gate import write_gz_f32
        R = lambda i, **kw: (lambda r: r["results"][i].update(**kw))
        def pop(i, key):
            return lambda r: r["results"][i].pop(key)
        def log_drop(fragment):
            return lambda t: "".join(l for l in t.splitlines(keepends=True) if fragment not in l)
        pend = {0: "atlas-pending"}
        # -- skip provenance (real_load_skip_provenance) --
        self.assertRefused(self.run_gate(hier_status=pend, mutate_hier=R(0, bogus=1)),
                           "carries ['bogus'], which a skip never has")
        # the shared helper's own extra-key guard is unreachable from here, and stays so: the
        # hierarchical gate refuses first against a subset of real-LOAD's skip keys
        self.assertLess(verify.HIER_LOAD_SKIP_KEYS, verify.REAL_LOAD_SKIP_KEYS)
        self.assertRefused(self.run_gate(hier_status=pend, log=lambda t: None),
                           "cannot be corroborated without the log")
        self.assertRefused(self.run_gate(hier_status=pend, log=lambda t: t.replace(
                               "hier load at draw 3000 status=atlas-pending\n",
                               "hier load at draw 3000 status=atlas-pending extra=1\n")),
                           "log line for skipped draw 3000 is missing or carries more")
        self.assertRefused(self.run_gate(hier_status=pend, mutate_hier=R(0, buildsSoFar=5)),
                           "says 5 build(s) so far")
        self.assertRefused(self.run_gate(hier_status=pend, mutate_hier=pop(0, "cameraCapture")),
                           "does not state cameraCapture")
        self.assertRefused(self.run_gate(hier_status=pend, mutate_hier=R(0, atlasState=2)),
                           "the atlas state was 2")
        self.assertRefused(self.run_gate(hier_status={0: "no-camera-this-frame"},
                                         mutate_hier=lambda r: r["results"][0].update(
                                             previousCapture=r["results"][0]["cameraCapture"] - 1)),
                           "the capture count moved")
        def bracket(text):
            line = next(l for l in text.splitlines(keepends=True) if "hier load at draw 3000 " in l)
            inst = ("[native-vk] native instance at frame {f} stage=descend factory=true instance=true"
                    " engine=true live=true activeSections=1 renderer=false ingest=true"
                    " cameraCaptures={f} storedNearCamera=1\n")
            return text.replace(line, inst.format(f=60) + line + inst.format(f=120))
        self.assertRefused(self.run_gate(hier_status={0: "no-camera-this-frame"}, log=bracket),
                           "show a capture every frame")
        self.assertRefused(self.run_gate(hier_status={0: "camera-extent-mismatch"}),
                           "which the ladder sample does not support")
        def live_engine(text):
            line = next(l for l in text.splitlines(keepends=True) if "hier load at draw 3000 " in l)
            inst = ("[native-vk] native instance at frame 60 stage=descend factory=true instance=true"
                    " engine=true live=true activeSections=1 renderer=false ingest=true"
                    " cameraCaptures=60 storedNearCamera=1\n")
            return text.replace(line, inst + line)
        self.assertRefused(self.run_gate(hier_status={0: "no-world-engine"}, log=live_engine),
                           "shows a live engine")
        self.assertRefused(self.run_gate(hier_status={0: "build-budget-spent"}),
                           "the build budget was spent after")
        # -- judged entry facts (load_judged_entry_checks) --
        self.assertRefused(self.run_gate(mutate_hier=R(0, projectionAdjusted="no")),
                           "does not say whether Minecraft's projection was adjusted")
        self.assertRefused(self.run_gate(mutate_hier=R(0, stage="turn")),
                           "says stage 'turn' but the ladder sample says 'descend'")
        self.assertRefused(self.run_gate(mutate_hier=R(0, mcProjection=[1.0] * 15)),
                           "does not publish both 16-entry projections")
        self.assertRefused(self.run_gate(mutate_hier=R(0, farPlane=1.0)), "reports farPlane=1.0")
        self.assertRefused(self.run_gate(mutate_hier=R(0, cameraCapture=0)), "has cameraCapture=0")
        self.assertRefused(self.run_gate(mutate_hier=pop(0, "engineId")), "does not state engineId")
        def second_read(text):
            line = "[native-vk] block atlas read through Blaze3D: 2048x2048\n"
            return text.replace(line, line + line, 1)
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(atlasReads=2), log=second_read),
                           "but 2 atlas read(s) are logged before it")
        def rebuilt(r):   # a second build before draw 3000, the next sample back on the first
            r.update(builds=2)
            r["results"][0].update(sceneBuild=2)
        def second_scene(text):
            first = "[native-vk] hier-LOAD scene #1: streaming render distance 17, sections -1..0\n"
            return text.replace(first, first + first.replace("#1", "#2"), 1)
        self.assertRefused(self.run_gate(mutate_hier=rebuilt, log=second_scene),
                           "older than the previous sample's")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][1].update(
                               engineId=r["results"][0]["engineId"] + 1)),
                           "engineId changed but the same scene")
        # -- per-sample judgement (judge_load_sample) --
        def rewrite(name_field, write):
            def mutate(o):
                hl = json.loads((o / "native-hier-load.json").read_text())
                write(o / hl["results"][0][name_field])
            return mutate
        tiny = lambda path: write_gz_ppm(path, [[(0, 0, 0)] * 2] * 2)
        self.assertRefused(self.run_gate(mutate_files=rewrite("file", tiny)),
                           "crop is 2x2 but the ladder crop is")
        self.assertRefused(self.run_gate(mutate_files=rewrite("referenceFile", tiny)),
                           "reference crops are 2x2")
        def out_of_range(path):
            depth, (w, h) = verify.read_f32_gz(path)
            depth[0][0] = 1.5
            write_gz_f32(path, depth)
        self.assertRefused(self.run_gate(mutate_files=rewrite("referenceDepthFile", out_of_range)),
                           "holds values outside [0, 1]")
        self.assertRefused(self.run_gate(mutate_files=rewrite("frameFile", tiny)),
                           "thumbnail is too small for the crop")
        # a band smaller than one thumbnail block (only at tiny extents): judged directly
        with tempfile.TemporaryDirectory() as tmp:
            o = Path(tmp)
            px = [[(10, 20, 30)] * 3 for _ in range(3)]
            for name in ("native-hier-load-9.ppm.gz", "native-hier-load-reference-9.ppm.gz",
                         "native-hier-load-frame-9.ppm.gz", "ladder-9.ppm.gz"):
                write_gz_ppm(o / name, px)
            write_gz_f32(o / "native-hier-load-depth-9.f32.gz", [[0.0] * 3 for _ in range(3)])
            entry = {"file": "native-hier-load-9.ppm.gz", "frameFile": "native-hier-load-frame-9.ppm.gz",
                     "referenceFile": "native-hier-load-reference-9.ppm.gz",
                     "referenceDepthFile": "native-hier-load-depth-9.f32.gz"}
            with self.assertRaisesRegex(ValueError, "covers no whole thumbnail block"):
                verify.judge_load_sample(o, {"sample": "ladder-9.ppm.gz", "rect": [1, 1, 4, 4]},
                                         entry, 9, False, {}, None, verify.HIER_LOAD_SPEC)
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][0].update(
                               referenceSet=r["results"][0]["referenceSet"] + 1)),
                           "reports referenceSet=")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][0].update(
                               expectVisible=r["results"][0]["expectVisible"] + 1)),
                           "but the retained crops say")
        self.assertRefused(self.run_gate(depth_kind="none", mutate_hier=R(0, minDepth=0.5)),
                           "reports minDepth=0.5 with no geometry")

    def test_voxys_own_projection_and_the_reprojection_are_rederived(self):
        from test_ladder_gate import write_gz_f32
        def first_judged(o):
            hl = json.loads((o / "native-hier-load.json").read_text())
            return next(e for e in hl["results"] if e["status"] == "judged")
        def rewrite_raw(fn):
            def mutate(o):
                e = first_judged(o)
                raw, _ = verify.read_f32_gz(o / e["voxyDepthFile"])
                ref, _ = verify.read_f32_gz(o / e["referenceDepthFile"])
                write_gz_f32(o / e["voxyDepthFile"], fn(raw, ref))
            return mutate
        ok = self.run_gate()
        self.assertTrue(ok["success"], ok["failures"])
        self.assertIn("beyondMinecraftFar", ok["hier_load"])
        # rendered with Minecraft's own projection: not Voxy's near/far
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][0].update(
            voxyProjection=list(r["results"][0]["projection"]))), "is not Voxy's projection of its raw")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][0].update(voxyProjection=[1.0] * 3)),
                           "not 16 numbers")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r.update(voxyFar=2048.0)), "renders with far 2048.0")
        # round-26 R26-PROJECTION-EQUIVALENCE: near follows the vanilla distance, extra transforms kept
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][0].update(voxyNear=8.0)),
                           "gives Voxy's near 16.0")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][0].update(vanillaRenderDistance=32.0)),
                           "gives Voxy's near 8.0")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][0].update(sodiumChunkRenderDisabled=None)),
                           "sodiumChunkRenderDisabled=None")
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][0].update(rawProjection=[0.0] * 15)),
                           "rawProjection=")
        def bobbed(r):   # the frame's projection carries an extra transform over the raw one,
            e = r["results"][0]   # which the published voxyProjection ignored
            e["rawProjection"] = verify._mat_mul(e["projection"],
                                                 [1, 0, 0, 0, 0, 1, 0.01, 0, 0, -0.01, 1, 0, 0, 0, 0, 1])
        self.assertRefused(self.run_gate(mutate_hier=bobbed), "with this frame's extra transforms")
        self.assertEqual((verify.voxy_near(32, False), verify.voxy_near(48, False), verify.voxy_near(128, True)),
                         (8.0, 16.0, 0.1))
        self.assertRefused(self.run_gate(mutate_hier=lambda r: r["results"][0].update(voxyDepthFile="x.f32.gz")),
                           "names voxyDepthFile='x.f32.gz'")
        # the composite wrote Voxy's depth unreprojected
        self.assertRefused(self.run_gate(mutate_files=rewrite_raw(lambda raw, ref: ref)), "reprojects to")
        def hole(raw, ref):
            raw = [list(r) for r in raw]
            j = next(j for j, r in enumerate(ref) if any(d > 0 for d in r))
            raw[j][next(i for i, d in enumerate(ref[j]) if d > 0)] = 0.0
            return raw
        self.assertRefused(self.run_gate(mutate_files=rewrite_raw(hole)), "has no Voxy depth but reference depth")
        def over(raw, ref):
            return [[2.0 if d > 0 else 0.0 for d in r] for r in raw]
        self.assertRefused(self.run_gate(mutate_files=rewrite_raw(over)), "is outside (0, 1]")
        def small(raw, ref):
            return [r[: len(r) // 2] for r in raw]
        self.assertRefused(self.run_gate(mutate_files=rewrite_raw(small)), "Voxy's depth crop is")


if __name__ == "__main__":
    unittest.main()
