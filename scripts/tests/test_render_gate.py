"""The product launch gate: Voxy on Minecraft's Vulkan backend under voxy.native.render alone.

The launch is not judged per pixel (the ladder launch judges the same path); this checks that the
switch alone ran the native path through the lifecycle with a clean probe report, every frame
accounted for, builds and atlas reads reconciled with the log, and frames composited in every
required stage.
"""
from pathlib import Path
import json
import re
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify

COMMAND = ["gradlew", "runHarnessClient", "-PharnessNative=true", "-PharnessGraphicsBackend=vulkan",
           *verify.RENDER_LAUNCH_FLAGS]
LOADER = "[loader] Insert instance layer VK_LAYER_KHRONOS_validation (libVkLayer_khronos_validation.dylib)\n"


def environment():
    """The launch's own scenario record, as the harness writes it (test_native_gate's shape)."""
    renderer = dict(mcUsesVulkan=True, backendClass="com.mojang.blaze3d.vulkan.VulkanDevice",
                    notes=[], vkDevice=12, vkInstance=13, vmaAllocator=14,
                    graphicsQueueFamily=0, computeQueueFamily=3, transferQueueFamily=3)
    for role in ("colour", "depth"):
        renderer[role] = dict(vkImage=15, vkImageView=16, width=960, height=540)
    checkpoints = [dict(stage=st, renderer=json.loads(json.dumps(renderer)))
                   for st in verify.LIFECYCLE_STAGES]
    for case in checkpoints:
        if case["stage"] in verify.UPDATE_PROBE_STAGES:
            case["updateProbe"] = update_probe(case["stage"])
    return {"complete": True, "success": True, "failures": [], "checkpoints": checkpoints}


def update_probe(stage):
    """The harness's native update observation as a passing run records it (engine 77, scene
    builds 2 as the fixture report; `remove` with its ordered ingest/dirty/mesh chain)."""
    was, now = verify.UPDATE_PROBE_STAGES[stage]
    base = 100 if stage == "edit" else 200
    probe = {"block": list(verify.UPDATE_PROBE_BLOCK),
             "before": {"seq": base, "block": was, "builds": 2, "sceneEngine": 77, "leafMeshVersion": 40,
                        "sectionEmpty": stage == "edit"},
             "after": {"seq": base + 50, "block": now, "builds": 2, "sceneEngine": 77,
                       "leafMeshVersion": 40 if stage == "edit" else 46},
             "ingest": None, "leafDirty": None, "leafMeshed": None}
    if stage == "remove":
        probe.update(ingest={"seq": base + 3, "engine": 77}, leafDirty={"seq": base + 7, "engine": 77},
                     leafMeshed={"seq": base + 20, "engine": 77})
    return probe


DEVICE = 0x7a42970018


def report(**overrides):
    body = {"enabled": True, "attempted": True, "drawsRecorded": 0, "everyFrame": True,
            "product": True, "buildBudgetApplies": False, "rebuildIntervalMs": 1000,
            "renderCalls": 0, "framesComposited": 0, "frameSkips": {"atlas-pending": 6},
            "builds": 2, "buildBudget": 6, "iterations": 3, "streaming": True,
            "sectionRenderDistance": 16.0, "streamRenderDistance": 17, "maxTopLevels": 34,
            "vanillaBound": True, "maxBoundSections": 210, "lightmapsApplied": 480,
            "lightmapReads": 481, "lightmapFailure": None, "postPass": True, "fogMode": "FOG_AND_FADE",
            "sectionCapacity": verify.DEFAULT_SECTION_CAPACITY, "geometryQuads": verify.DEFAULT_GEOMETRY_QUADS, "maxGeometryUsedBytes": 9000000,
            "maxMeshed": 7000, "geometryReclaimed": 0, "geometryRejected": 0,
            "geometryEverExhausted": False,
            "injectSubmitFailureAt": -1, "injectedFailures": 0, "compositedAtInjection": -1,
            "subdivisionPx": 64.0,
            "performance": {"gpu": {"frames": 20000, "meanMs": 2.5, "p50Ms": 2.4, "p95Ms": 3.1, "maxMs": 6.0},
                            "gpuUnavailable": 0,
                            "cpu": {"frames": 20000, "meanMs": 1.2, "p50Ms": 1.1, "p95Ms": 1.9, "maxMs": 9.0},
                            "gpuSpanMeanMs": {n: 0.3 for n in verify.HIER_GPU_SPANS}},
            "voxyNear": 16.0, "voxyFar": 48000.0, "declaredDepthState": [6, 1, 1],
            "depthStateReadBack": False, "instanceMode": True, "results": [], "problems": 0,
            "firstProblem": None, "closeFailures": 0, "leakedScenes": 0, "deviceDiverged": False,
            "meshWorkersDeferred": 0, "meshWorkersReaped": 0, "meshWorkersLeaked": 0, "worldsLeftUnfreed": 0,
            "readbacksInFlight": 0, "atlasReads": 1, "device": hex(DEVICE), "notes": []}
    body.update(overrides)
    return body


def log_for(stages=verify.LIFECYCLE_STAGES, per_stage=100, start=0, cycles=1):
    lines = [LOADER, "[native-vk] requested the block atlas (2048x2048) through Blaze3D\n",
             "[native-vk] block atlas read through Blaze3D: 2048x2048\n",
             "[native-vk] hier-LOAD scene #1: streaming render distance 17, sections -1..0\n"]
    composited = start
    R = verify.LIFECYCLE_STAGES.index("reload")   # the scene is rebuilt at the new size there
    for i, stage in enumerate(stages):
        lines.append(f"[voxy-harness] stage={stage}\n")
        lines.append(f"[native-vk] hier frames entering stage {stage}: composited={composited}"
                     f" skipped=6 builds={1 if i < R + 1 else 2}"
                     f" vkBuffers={0 if i < 1 else 40} vkBufferBytes={0 if i < 1 else 80961384 + (0 if i < R else 3471360)}"
                     f" vkTextures={0 if i < 1 else 10} vkTextureBytes={0 if i < 1 else 600000000} frame={'1708x960' if i < R else '1920x1080'}\n")
        if i == R + 1:
            lines.append("[native-vk] hier-LOAD scene #2: streaming render distance 17, sections -1..0\n")
        composited += per_stage
    # the soak's repeats: the resize alternates the scene between the two sizes
    for c in range(2, cycles + 1):
        for stage in verify.SOAK_STAGES:
            after = verify.LIFECYCLE_STAGES.index(stage) > verify.LIFECYCLE_STAGES.index("resize")
            large = (c % 2 == 1) == after
            lines.append(f"[voxy-harness] stage={stage} cycle={c}\n")
            lines.append(f"[native-vk] hier frames entering stage {stage}: composited={composited}"
                         f" skipped=6 builds=2 vkBuffers=40"
                         f" vkBufferBytes={80961384 + (3471360 if large else 0)} vkTextures=10 vkTextureBytes=600000000"
                         f" frame={'1920x1080' if large else '1708x960'} cycle={c}\n")
            composited += per_stage
    return "".join(lines), composited


class RenderGateTest(unittest.TestCase):

    def run_gate(self, mutate=None, log=None, files=(), device=DEVICE, command=None, env=None,
                 gate=None, cycles=1):
        text, total = log_for(cycles=cycles)
        body = report(framesComposited=total, renderCalls=total + 6)
        if mutate:
            mutate(body)
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            (out / "native-hier-load.json").write_text(json.dumps(body))
            e = environment()
            e.update(cycles=cycles, cycle=cycles)
            if env:
                env(e)
            (out / "native-result.json").write_text(json.dumps(e))
            for name, body in (files.items() if isinstance(files, dict) else
                               ((n, {"enabled": True}) for n in files)):
                (out / name).write_text(json.dumps(body))
            return (gate or verify.native_render_result)(out, text if log is None else log(text), device,
                                                         COMMAND if command is None else command)

    def assertRefused(self, result, fragment):
        self.assertFalse(result["success"], "passed although: " + fragment)
        self.assertIn(fragment, " ".join(result["failures"]))

    def test_the_switch_alone_through_the_lifecycle_passes(self):
        result = self.run_gate()
        self.assertTrue(result["success"], result["failures"])
        self.assertEqual(result["framesComposited"], 1700)
        self.assertEqual(set(result["perStage"]), set(verify.LIFECYCLE_STAGES))
        self.assertNotIn("nether", verify.RENDER_REQUIRED_STAGES)
        self.assertEqual(verify.RENDER_LAUNCH_FLAGS, ("-PharnessNativeRender=true",))

    def test_a_diagnostic_in_the_product_launch_is_refused(self):
        for name in verify.RENDER_FORBIDDEN_FILES:
            self.assertRefused(self.run_gate(files=[name]), "ran diagnostics")
            self.assertRefused(self.run_gate(files={name: {"enabled": False, "attempted": True}}),
                               "ran diagnostics")
            self.assertRefused(self.run_gate(files={name: {}}), "ran diagnostics")
            self.assertRefused(self.run_gate(files={name: []}), "ran diagnostics")
            # an off diagnostic writes its report saying so at shutdown: not a run
            self.assertTrue(self.run_gate(files={name: {"enabled": False, "attempted": False}})["success"])
            # round-27: "attempted" must be stated, and every counter inert
            self.assertRefused(self.run_gate(files={name: {"enabled": False}}), "ran diagnostics")
            for field, value in (("drawsRecorded", 999), ("results", [{}]), ("problems", 1),
                                 ("atlasCloseFailures", 1), ("notes", ["x"]), ("device", "0x1")):
                self.assertRefused(self.run_gate(files={name: {"enabled": False, "attempted": False,
                                                               field: value}}),
                                   f"states {field}=")
        off = {"enabled": False, "attempted": False, "buildBudget": 10, "level": 3, "radius": 4,
               "declaredDepthState": [6, 1, 1], "instanceMode": True, "atlasReads": 1,
               "closeFailures": 0, "results": [], "device": None, "deviceDiverged": False}
        self.assertTrue(self.run_gate(files={"native-real-load.json": off})["success"])
        self.assertRefused(self.run_gate(files={"native-real-load.json": dict(off, atlasReads=2)}),
                           "atlas read(s), the shared atlas")

    def test_the_report_must_be_the_clean_product_path(self):
        for field, value in (("product", False), ("everyFrame", False), ("enabled", False),
                             ("buildBudgetApplies", True), ("instanceMode", False),
                             ("drawsRecorded", 1), ("results", [{}]), ("problems", 1),
                             ("firstProblem", "x"), ("notes", ["x"]), ("closeFailures", 1),
                             ("leakedScenes", 1), ("deviceDiverged", True),
                             ("readbacksInFlight", 1), ("attempted", False), ("product", 1)):
            self.assertRefused(self.run_gate(mutate=lambda b, f=field, v=value: b.update({f: v})),
                               f"says {field}=")

    def test_the_product_scene_streams(self):
        # round-28 R28-GATE-GUARD-COVERAGE: every other streaming fact consistent, streaming off
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(streaming=False)), "says streaming=False")
        self.assertRefused(self.run_gate(mutate=lambda b: b.pop("streaming")), "says streaming=None")
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(streaming=1)), "says streaming=1")
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(maxTopLevels=0)), "nothing was streamed in")
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(streamRenderDistance=5)), "streams at 5 columns")
        # the near cut
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(maxBoundSections=0)), "no near cut")
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(vanillaBound=False)), "no near cut")
        # Minecraft's lightmap
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(lightmapsApplied=0)), "synthetic lighting")
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(lightmapFailure="x")), "synthetic lighting")
        # fog and fade (GL's final blit)
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(postPass=False)), "fog and fade are not applied")
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(fogMode="sometimes")), "fog and fade are not applied")

    def test_retired_scenes_return_their_allocations(self):
        RECONNECT = "entering stage reconnect: composited=1600 skipped=6 builds=2 vkBuffers=40 vkBufferBytes=84432744 vkTextures=10"
        def at_reconnect(new):
            return lambda text: text.replace(RECONNECT, new)
        # the fixture itself grows by exactly two readbacks' worth at the resize, and passes
        result = self.run_gate()
        self.assertTrue(result["success"], result["failures"])
        self.assertRefused(self.run_gate(log=at_reconnect(RECONNECT.replace("vkBuffers=40", "vkBuffers=80"))),
                           "retired scenes are not freed")
        self.assertRefused(self.run_gate(log=at_reconnect(RECONNECT.replace("84432744", "90000000"))),
                           "retired scenes are not freed")
        self.assertRefused(self.run_gate(log=at_reconnect(RECONNECT.replace("vkTextures=10", "vkTextures=20"))),
                           "retired scenes are not freed")
        def silent(text):
            return text.replace(" vkBuffers=40 vkBufferBytes=80961384 vkTextures=10 vkTextureBytes=600000000 frame=1708x960\n", "\n", 1)
        self.assertRefused(self.run_gate(log=silent), "states no allocations")
        # texture memory is judged too (the atlas, render targets): a missing figure, and growth
        def textureless(text):
            return text.replace(" vkTextureBytes=600000000", "", 1)
        self.assertRefused(self.run_gate(log=textureless), "states no texture bytes")
        def texture_leak(text):
            return text.replace("vkTextures=10 vkTextureBytes=600000000 frame=1920x1080\n[voxy-harness] stage=disconnect",
                                "vkTextures=10 vkTextureBytes=600000000 frame=1920x1080\n[voxy-harness] stage=disconnect").replace(
                RECONNECT + " vkTextureBytes=600000000", RECONNECT + " vkTextureBytes=700000000")
        self.assertRefused(self.run_gate(log=texture_leak), "retired scenes are not freed")
        ok = self.run_gate()
        self.assertEqual(ok["memory"], {"bufferBytes": 84432744, "textureBytes": 600000000})
        # a scene-less last snapshot (the level change retired the scene) is not the footprint
        def sceneless_last(text):
            return text.replace(RECONNECT + " vkTextureBytes=600000000 frame=1920x1080",
                                RECONNECT.replace("vkBufferBytes=84432744", "vkBufferBytes=25165968")
                                + " vkTextureBytes=542474624 frame=0x0")
        self.assertEqual(self.run_gate(log=sceneless_last)["memory"],
                         {"bufferBytes": 84432744, "textureBytes": 600000000})
        def sizeless(text):
            return text.replace(" frame=1708x960\n", "\n", 1)
        self.assertRefused(self.run_gate(log=sizeless), "states no frame size")

    def test_native_updates_must_reach_voxys_mesh(self):
        # round-28 R28-NATIVE-UPDATE-OBSERVATION and R29: the removal reaches exactly the probe's
        # level-0 native mesh through the visibility ingest, in order, on the scene's own engine
        def at(stage, f):
            def edit(e):
                for case in e["checkpoints"]:
                    if case["stage"] == stage:
                        f(case["updateProbe"])
            return edit
        def setp(stage, path, value):
            def f(p):
                target = p
                for k in path[:-1]:
                    target = target[k]
                if value is KeyError:
                    target.pop(path[-1])
                else:
                    target[path[-1]] = value
            return at(stage, f)
        ok = self.run_gate()
        self.assertTrue(ok["success"], ok["failures"])
        for stage in ("edit", "remove"):
            for path, value, fragment in (
                    (("block",), [0, 104, 24], "carries no update observation"),
                    (("after",), KeyError, "lacks its before/after state"),
                    # R29-UPDATE-COUNTERS: domains and consistency with the report's builds (2)
                    (("before", "builds"), -5, "before.builds is -5"),
                    (("before", "builds"), 999999, "spans no single scene"),
                    (("after", "builds"), 3, "spans no single scene"),
                    (("before", "seq"), -1, "before.seq is -1"),
                    (("after", "seq"), 1, "out of order"),
                    (("after", "sceneEngine"), 78, "spans no single scene"),
                    (("before", "sceneEngine"), 0, "before.sceneEngine is 0"),
                    (("before", "leafMeshVersion"), -1, "before.leafMeshVersion is -1"),
                    (("after", "leafMeshVersion"), True, "after.leafMeshVersion is True"),
                    (("after", "block"), "minecraft:stone", "probe block went")):
                self.assertRefused(self.run_gate(env=setp(stage, path, value)), fragment)
        self.assertRefused(self.run_gate(env=at("edit", lambda p: p.pop("updateProbe", None) or
                                                 p.clear())), "carries no update observation")
        self.assertRefused(self.run_gate(env=setp("edit", ("before", "sectionEmpty"), False)), "was not all air")
        self.assertRefused(self.run_gate(env=setp("edit", ("before", "sectionEmpty"), None)), "was not all air")
        # R29-UPDATE-PLACEMENT: the placement is not ingested on GL either; edit asks nothing causal
        self.assertTrue(self.run_gate(env=setp("edit", ("after", "leafMeshVersion"), 40))["success"])
        # R29-UPDATE-CAUSALITY: every link present, on the scene's engine, strictly ordered
        for name in ("ingest", "leafDirty", "leafMeshed"):
            self.assertRefused(self.run_gate(env=setp("remove", (name,), None)), f"records no {name} event")
            self.assertRefused(self.run_gate(env=setp("remove", (name, "engine"), 78)), "not the scene's 77")
            self.assertRefused(self.run_gate(env=setp("remove", (name, "seq"), -3)), f"records no {name} event")
            self.assertRefused(self.run_gate(env=setp("remove", (name, "seq"), 260)), "is not after")
        self.assertRefused(self.run_gate(env=setp("remove", ("ingest", "seq"), 150)), "is not after 200")
        self.assertRefused(self.run_gate(env=setp("remove", ("leafDirty", "seq"), 202)), "is not after 203")
        self.assertRefused(self.run_gate(env=setp("remove", ("leafMeshed", "seq"), 206)), "is not after 207")
        # the reviewer's ancestor counterexample: an ancestor grew (20 -> 22) but the probe's own
        # level-0 section was never re-meshed — the leaf's revision and mesh event are what count
        self.assertRefused(self.run_gate(env=setp("remove", ("after", "leafMeshVersion"), 40)), "was not re-meshed")
        self.assertRefused(self.run_gate(env=setp("remove", ("leafMeshed",), None)), "records no leafMeshed event")
        # the report's own builds must be stated
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(builds=0)), "scene build(s)")

    def test_mesher_workers_that_outlive_their_stop_are_accounted(self):
        # round-28 R28-WORKER-SHUTDOWN-BOUND: deferred meshers must all have been freed
        ok = self.run_gate(mutate=lambda b: b.update(meshWorkersDeferred=2, meshWorkersReaped=2))
        self.assertTrue(ok["success"], ok["failures"])
        for fields in ({"meshWorkersLeaked": 1, "meshWorkersDeferred": 1},
                       {"meshWorkersDeferred": 1}, {"meshWorkersReaped": 1},
                       {"meshWorkersDeferred": -1, "meshWorkersReaped": -1},
                       {"meshWorkersLeaked": None}, {"meshWorkersDeferred": True, "meshWorkersReaped": True}):
            self.assertRefused(self.run_gate(mutate=lambda b, f=fields: b.update(f)),
                               "every mesher whose workers outlived their stop")
        self.assertRefused(self.run_gate(mutate=lambda b: b.pop("meshWorkersLeaked")),
                           "meshWorkersLeaked=None")
        for value in (1, None, True, -1):
            self.assertRefused(self.run_gate(mutate=lambda b, v=value: b.update(worldsLeftUnfreed=v)),
                               "past Voxy's bounded shutdown wait")
        self.assertRefused(self.run_gate(mutate=lambda b: b.pop("worldsLeftUnfreed")),
                           "worldsLeftUnfreed=None")

    def assertWithinRun(self, result):
        """Refused by the within-size-run check itself, not the cross-lifecycle one."""
        self.assertRefused(result, "build(s); retired scenes are not freed")
        self.assertTrue(all(f.endswith("build(s); retired scenes are not freed")
                            for f in result["failures"]), result["failures"])

    def test_growth_within_the_first_run_at_a_size_is_refused(self):
        # round-28 R28-GATE-GUARD-COVERAGE: in the product soak, the first 1708x960 run grows 20 %
        # by its end and the later runs at that size are back down, so only the within-run check
        # sees it (the cross-lifecycle one compares the first run's start with the last run's end)
        RESIZE = ("entering stage resize: composited=1000 skipped=6 builds=1 vkBuffers=40"
                  " vkBufferBytes=80961384 vkTextures=10 vkTextureBytes=600000000 frame=1708x960")
        soak = dict(cycles=verify.SOAK_CYCLES, gate=verify.native_product_result,
                    command=COMMAND + [f"-PharnessCycles={verify.SOAK_CYCLES}"])
        ok = self.run_gate(**soak)
        self.assertTrue(ok["success"], ok["failures"])
        def grown(text):
            assert text.count(RESIZE) == 1
            return text.replace(RESIZE, RESIZE.replace("80961384", str(int(80961384 * 1.2))))
        self.assertWithinRun(self.run_gate(log=grown, **soak))
        def textures(text):
            return text.replace(RESIZE, RESIZE.replace("vkTextureBytes=600000000", "vkTextureBytes=720000000"))
        self.assertWithinRun(self.run_gate(log=textures, **soak))
        def counted(text):
            return text.replace(RESIZE, RESIZE.replace("vkBuffers=40", "vkBuffers=41"))
        self.assertWithinRun(self.run_gate(log=counted, **soak))

    def test_a_resize_excuses_only_its_frame_sized_buffers(self):
        # one byte more than two RGBA8 buffers of the 433920 added pixels
        def over(text):
            return text.replace("vkBufferBytes=84432744", "vkBufferBytes=84432745")
        self.assertRefused(self.run_gate(log=over), "RGBA8 buffer(s) of the frame-size change")
        # a buffer added across the resize is still a count increase
        def counted(text):
            return text.replace("vkBuffers=40 vkBufferBytes=84432744", "vkBuffers=41 vkBufferBytes=84432744")
        self.assertRefused(self.run_gate(log=counted), "retired scenes are not freed")
        # growth before the resize is judged against its own run, not hidden behind the resize
        def early(text):
            return text.replace("entering stage resize: composited=1000 skipped=6 builds=1 vkBuffers=40 vkBufferBytes=80961384",
                                "entering stage resize: composited=1000 skipped=6 builds=1 vkBuffers=40 vkBufferBytes=90000000")
        self.assertRefused(self.run_gate(log=early), "retired scenes are not freed")
        # a snapshot without a live scene (0x0) stays in its run: it cannot start one that excuses bytes
        def sceneless(text):
            return text.replace("vkBuffers=40 vkBufferBytes=84432744 vkTextures=10 vkTextureBytes=600000000 frame=1920x1080\n[voxy-harness] stage=disconnect",
                                "vkBuffers=40 vkBufferBytes=84432744 vkTextures=10 vkTextureBytes=600000000 frame=1920x1080\n[voxy-harness] stage=disconnect").replace(
                "entering stage reconnect: composited=1600 skipped=6 builds=2 vkBuffers=40 vkBufferBytes=84432744 vkTextures=10 vkTextureBytes=600000000 frame=1920x1080",
                "entering stage reconnect: composited=1600 skipped=6 builds=2 vkBuffers=40 vkBufferBytes=90000000 vkTextures=10 vkTextureBytes=600000000 frame=0x0")
        self.assertRefused(self.run_gate(log=sceneless), "retired scenes are not freed")

    def test_performance_evidence_is_retained(self):
        ok = self.run_gate()
        self.assertTrue(ok["success"], ok["failures"])
        self.assertEqual(ok["performance"]["gpu"]["p95Ms"], 3.1)
        perf = lambda f: (lambda b: f(b["performance"]))
        self.assertRefused(self.run_gate(mutate=lambda b: b.pop("performance")), "states performance=None")
        self.assertRefused(self.run_gate(mutate=perf(lambda p: p.update(gpuUnavailable=3))),
                           "could not read 3 frame(s)")
        self.assertRefused(self.run_gate(mutate=perf(lambda p: p["gpu"].update(frames=10))),
                           "times gpu frames")
        self.assertRefused(self.run_gate(mutate=perf(lambda p: p["cpu"].update(frames=10))),
                           "times cpu frames")
        self.assertRefused(self.run_gate(mutate=perf(lambda p: p["gpu"].update(p95Ms=1.0))),
                           "not finite, positive and ordered")
        self.assertRefused(self.run_gate(mutate=perf(lambda p: p["cpu"].update(meanMs=float("nan")))),
                           "not finite, positive and ordered")
        self.assertRefused(self.run_gate(mutate=perf(lambda p: p["gpuSpanMeanMs"].pop("cull"))),
                           "not one mean per span")

    def test_the_scene_subdivides_at_voxys_configured_size(self):
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(subdivisionPx=128.0)),
                           "subdivisionPx=128.0")
        self.assertRefused(self.run_gate(mutate=lambda b: b.pop("subdivisionPx")), "subdivisionPx=None")

    def test_the_product_scene_runs_at_the_default_capacity(self):
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(sectionCapacity=4096)),
                           "capacity of 4096 sections")
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(geometryQuads=500000)),
                           "500000 quads")

    def test_the_pressure_launch_reclaims(self):
        pressure = COMMAND + [f"-PharnessNativeSectionCapacity={verify.PRESSURE_CAPACITY}",
                              f"-PharnessNativeGeometryQuads={verify.PRESSURE_QUADS}",
                              f"-PharnessCycles={verify.SOAK_CYCLES}"]
        def under(b):
            b.update(sectionCapacity=verify.PRESSURE_CAPACITY, geometryQuads=verify.PRESSURE_QUADS,
                     geometryReclaimed=900)
        def run(**kw):
            kw.setdefault("cycles", verify.SOAK_CYCLES)
            kw.setdefault("command", pressure)
            kw.setdefault("mutate", under)
            return self.run_gate(gate=verify.native_pressure_result, **kw)
        ok = run()
        self.assertTrue(ok["success"], ok["failures"])
        self.assertEqual(ok["reclaimed"], 900)
        self.assertEqual(ok["cycles"], verify.SOAK_CYCLES)
        self.assertRefused(run(mutate=lambda b: under(b) or b.update(geometryReclaimed=0)),
                           "put no pressure on the scene")
        self.assertRefused(run(command=COMMAND), "lacks")
        self.assertRefused(run(command=pressure[:-1]), "lacks")
        self.assertRefused(run(mutate=None), "not 4096 and 500000")

    def test_the_product_launch_is_a_soak_at_the_default_capacity(self):
        product = COMMAND + [f"-PharnessCycles={verify.SOAK_CYCLES}"]
        def run(**kw):
            kw.setdefault("cycles", verify.SOAK_CYCLES)
            kw.setdefault("command", product)
            return self.run_gate(gate=verify.native_product_result, **kw)
        ok = run()
        self.assertTrue(ok["success"], ok["failures"])
        self.assertEqual(ok["cycles"], verify.SOAK_CYCLES)
        self.assertRefused(run(cycles=1), "not 1..4")
        self.assertRefused(run(command=COMMAND), "lacks")
        self.assertRefused(run(env=lambda e: e.update(cycle=2)), "not 4 of 4")
        # still the product: the default capacity, no diagnostics
        self.assertRefused(run(mutate=lambda b: b.update(sectionCapacity=4096)), "capacity of 4096")

    def test_the_soak_repeats_every_lifecycle(self):
        pressure = COMMAND + [f"-PharnessNativeSectionCapacity={verify.PRESSURE_CAPACITY}",
                              f"-PharnessNativeGeometryQuads={verify.PRESSURE_QUADS}",
                              f"-PharnessCycles={verify.SOAK_CYCLES}"]
        def under(b):
            b.update(sectionCapacity=verify.PRESSURE_CAPACITY, geometryQuads=verify.PRESSURE_QUADS,
                     geometryReclaimed=900)
        def run(**kw):
            kw.setdefault("cycles", verify.SOAK_CYCLES)
            return self.run_gate(gate=verify.native_pressure_result, command=pressure, mutate=under, **kw)
        # one lifecycle is not a soak
        self.assertRefused(run(cycles=1), "not 1..4")
        # the harness must say it ran them all
        self.assertRefused(run(env=lambda e: e.update(cycle=3)), "not 4 of 4")
        # a repeat that skips a stage
        def drop_nether(text):
            out, skip = [], False
            for line in text.splitlines(keepends=True):
                if line == "[voxy-harness] stage=nether cycle=3\n":
                    skip = True
                    continue
                if skip:
                    skip = False
                    continue
                out.append(line)
            return "".join(out)
        self.assertRefused(run(log=drop_nether), "cycle 3 enters")
        # a stage entered twice in one cycle
        def twice(text):
            i = text.index("[voxy-harness] stage=reload cycle=2\n")
            j = text.index("[voxy-harness] stage=nether cycle=2\n")
            return text[:j] + text[i:j] + text[j:]
        self.assertRefused(run(log=twice), "twice in one cycle")
        # a leak of 1 MB per lifecycle: under the 5 % within a cycle, over it across four
        def leak(text):
            out = []
            for line in text.splitlines(keepends=True):
                m = re.search(r"vkBufferBytes=(\d+)(.*) cycle=(\d)", line)
                if m:
                    line = line.replace(f"vkBufferBytes={m.group(1)}",
                                        f"vkBufferBytes={int(m.group(1)) + 1_000_000 * (int(m.group(3)) - 1)}")
                out.append(line)
            return "".join(out)
        self.assertRefused(run(log=leak), "across lifecycles")
        # round-28: a cycle whose stage composited nothing (the sum across cycles hid it)
        def idle(text):
            i = text.index("[voxy-harness] stage=reconnect cycle=3\n")
            line_end = text.index("\n", text.index("entering stage reconnect", i)) + 1
            line = text[text.index("[native-vk] hier frames entering stage reconnect", i):line_end]
            nxt = text.index("[native-vk] hier frames entering stage travel", line_end)
            composited = int(line.split("composited=")[1].split()[0])
            following = text[nxt:text.index("\n", nxt) + 1]
            return text.replace(following, following.replace(
                following.split("composited=")[1].split()[0], str(composited), 1), 1)
        self.assertRefused(run(log=idle), "composited no frame in (stage, cycle)")

    def test_the_injection_launch_carries_on_after_one_failed_submission(self):
        inject = COMMAND + [f"-PharnessNativeInjectSubmitFailureAt={verify.INJECT_AT_FRAME}",
                            "-PharnessNativeSyncControl=true"]
        HAZARD = ("[12:00:00] [Render thread/ERROR] (Minecraft) vkCmdFillBuffer(): WRITE_AFTER_WRITE hazard"
                  " detected. vkCmdFillBuffer writes to dstBuffer VkBuffer 0x3aa2, which was previously"
                  " written by another vkCmdFillBuffer command.\n")
        RECORDED = ("[12:00:00] [Render thread/INFO] (Voxy) [me.cx.vy.ct.ce.vk.me.McNativeSyncControl]:"
                    " [native-vk] synchronization validation control recorded: two unsynchronised fills\n")
        ERROR = ("[12:00:00] [Render thread/ERROR] (Voxy) [me.cx.vy.ct.ce.vk.me.McNativeHierarchicalLoad]:"
                 " [native-vk] the hierarchical-LOAD experiment failed\n")
        def injected(b):
            b.update(injectSubmitFailureAt=verify.INJECT_AT_FRAME, injectedFailures=1,
                     compositedAtInjection=1000, problems=1, firstProblem=verify.INJECTED_FAILURE,
                     notes=[verify.INJECTED_FAILURE], renderCalls=b["renderCalls"] + 1001,
                     frameSkips={"atlas-pending": 6, "frame-failed": 1},
                     framesComposited=b["framesComposited"] + 1000)
        def control(text):
            return text.replace("[voxy-harness] stage=turn\n", HAZARD + RECORDED + "[voxy-harness] stage=turn\n")
        def log(text):
            text = text.replace("[voxy-harness] stage=reload\n", ERROR + "[voxy-harness] stage=reload\n")
            return control(text)
        def run(**kw):
            kw.setdefault("command", inject)
            kw.setdefault("mutate", injected)
            kw.setdefault("log", log)
            return self.run_gate(gate=verify.native_inject_result, **kw)
        ok = run()
        self.assertTrue(ok["success"], ok["failures"])
        self.assertEqual(ok["compositedAfter"], 1700)
        # the product launch refuses the flag, and an injected failure, outright
        self.assertRefused(self.run_gate(command=inject), "enables diagnostics")
        self.assertRefused(self.run_gate(mutate=injected, log=log), "problems=1")
        # exactly one failure: a second error line, a second failed frame, no failed frame
        self.assertRefused(run(log=lambda t: log(log(t).replace(HAZARD + RECORDED, ""))), "not the one injected")
        # the synchronization control: reported exactly once, recorded exactly once
        self.assertRefused(run(log=lambda t: log(t).replace(HAZARD, "")), "synchronization validation is not shown active")
        self.assertRefused(run(log=lambda t: control(log(t))), "synchronization validation is not shown active")
        self.assertRefused(run(log=lambda t: log(t).replace(RECORDED, "")), "synchronization validation is not shown active")
        # the product launch refuses the control's flag and its hazard
        self.assertRefused(self.run_gate(command=COMMAND + ["-PharnessNativeSyncControl=true"]),
                           "enables diagnostics")
        self.assertRefused(self.run_gate(log=control), "validation output")
        self.assertRefused(run(mutate=lambda b: injected(b) or b.update(
            frameSkips={"atlas-pending": 6, "frame-failed": 2}, renderCalls=b["renderCalls"] + 1)),
            "skips 2 frame(s)")
        self.assertRefused(run(mutate=lambda b: injected(b) or b.update(
            frameSkips={"atlas-pending": 6}, renderCalls=b["renderCalls"] - 1)), "not the one injected")
        # it really was injected, and Voxy kept compositing afterwards
        self.assertRefused(run(mutate=lambda b: injected(b) or b.update(injectedFailures=0)),
                           "injected 0 failure(s)")
        self.assertRefused(run(mutate=lambda b: injected(b) or b.update(
            compositedAtInjection=b["framesComposited"] - 10)), "after the injected failure")
        # any other failure is still a failure
        self.assertRefused(run(mutate=lambda b: injected(b) or b.update(firstProblem="x")),
                           "firstProblem")
        self.assertRefused(run(command=COMMAND), "lacks")

    def test_minecraft_logged_validation_is_validation_output(self):
        # Minecraft's debug callback logs the bare text: no SYNC-HAZARD-, no VUID for a hazard
        for line in ("[12:00:00] [Render thread/ERROR] (Minecraft) vkCmdDraw(): READ_AFTER_WRITE hazard"
                     " detected. vkCmdDraw reads VkImage 0x1\n",
                     "[12:00:00] [Render thread/ERROR] (Minecraft) vkQueueSubmit(): pSubmits[0] is bad\n"):
            self.assertRefused(self.run_gate(log=lambda t, l=line: l + t), "validation output")
        # Minecraft's ordinary offline error is not validation output
        offline = "[12:00:00] [Download-3/ERROR] (Minecraft) Failed to retrieve profile key pair\n"
        result = self.run_gate(log=lambda t: offline + t)
        self.assertTrue(result["success"], result["failures"])

    def test_the_log_does_not_say_voxy_is_off(self):
        for message in ("Minecraft is not using the OpenGL backend; Voxy's Vulkan path still needs a"
                        " GL context to composite through. Voxy will disable itself.",
                        "Voxy is unsupported on your system."):
            line = f"[12:00:00] [Render thread/ERROR] (Voxy) [me.cx.vy.ct.VoxyClient]: {message}\n"
            self.assertRefused(self.run_gate(log=lambda t: line + t), "says Voxy is off while it runs")

    def test_rendering_disabled_skips_are_unexplained_in_a_gated_launch(self):
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(
            frameSkips={"atlas-pending": 6, "rendering-disabled": 5000},
            renderCalls=b["renderCalls"] + 5000)), "no gated launch turns Voxy's rendering off")

    def test_every_frame_is_accounted_for(self):
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(renderCalls=b["renderCalls"] + 1)),
                           "accounts for")
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(frameSkips={"build-budget-spent": 6})),
                           "not a reason from")
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(frameSkips={"atlas-pending": 0},
                                                                   renderCalls=b["framesComposited"])),
                           "not a reason from")
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(framesComposited=None)),
                           "framesComposited=None")
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(framesComposited=0, renderCalls=6)),
                           "composited no frame")
        self.assertTrue(self.run_gate(mutate=lambda b: b.update(
            frameSkips={"atlas-pending": 6, "rebuild-wait": 4},
            renderCalls=b["renderCalls"] + 4))["success"])

    def test_the_launch_is_the_switch_alone_and_its_own_scenario_complete(self):
        self.assertRefused(self.run_gate(command=[c for c in COMMAND if "Render" not in c]),
                           "lacks the product switch")
        self.assertRefused(self.run_gate(command=None) if False else self.run_gate(command="x"),
                           "lacks the product switch")
        for prop in verify.RENDER_FORBIDDEN_LAUNCH:
            self.assertRefused(self.run_gate(command=COMMAND + [f"-P{prop}=true"]), "enables diagnostics")
        self.assertRefused(self.run_gate(env=lambda e: e.update(complete=False)), "environment")
        self.assertRefused(self.run_gate(env=lambda e: e.update(checkpoints=e["checkpoints"][:1])),
                           "environment")

    def test_the_log_is_clean(self):
        self.assertRefused(self.run_gate(log=lambda t: t + "VUID-vkCmdDraw-None-08600 bad\n"),
                           "validation output")
        self.assertRefused(self.run_gate(log=lambda t: t.replace(LOADER, "")), "no validation-layer loader")
        self.assertRefused(self.run_gate(log=lambda t: t + "[12:00:00] [Render thread/ERROR] (Voxy) boom\n"),
                           "unexpected Voxy errors")

    def test_identity_builds_and_atlas_reads_are_reconciled(self):
        self.assertRefused(self.run_gate(device=0xdead), "not the checkpoints' device")
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(builds=3)), "build attempts [1, 2]")
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(atlasReads=2)), "atlas read(s)")
        self.assertRefused(self.run_gate(log=lambda t: None), "log is not available")

    def test_frames_must_be_composited_in_every_required_stage(self):
        def stall(text):
            return text.replace("entering stage edit: composited=800", "entering stage edit: composited=900")
        self.assertRefused(self.run_gate(log=stall), "composited no frame in stage(s) ['edit']")
        def drop(text):
            return "".join(l for l in text.splitlines(keepends=True)
                           if "entering stage resize" not in l)
        self.assertRefused(self.run_gate(log=drop), "['resize']")
        def twice(text):
            return text + "[voxy-harness] stage=warmup\n[native-vk] hier frames entering stage warmup: composited=1700 skipped=6 builds=2 vkBuffers=40 vkBufferBytes=84432744 vkTextures=10 vkTextureBytes=600000000 frame=1920x1080\n"
        self.assertRefused(self.run_gate(log=twice), "enters a stage twice")
        # round-27: a snapshot belongs to the harness stage current when it was written
        def early(text):
            line = next(l for l in text.splitlines(keepends=True) if "entering stage edit:" in l)
            return text.replace(line, "").replace("[voxy-harness] stage=edit\n", line + "[voxy-harness] stage=edit\n")
        self.assertRefused(self.run_gate(log=early), "was written during harness stage 'night'")
        def skipped(text):
            return text.replace("entering stage warmup: composited=0 skipped=6", "entering stage warmup: composited=0 skipped=999999999")
        self.assertRefused(self.run_gate(log=skipped), "exceed the final report")
        def built(text):
            return text.replace("entering stage reconnect: composited=1600 skipped=6 builds=2",
                                "entering stage reconnect: composited=1600 skipped=6 builds=9")
        self.assertRefused(self.run_gate(log=built), "exceed the final report")
        def order(text):
            a = next(l for l in text.splitlines(keepends=True) if "entering stage turn:" in l)
            b = next(l for l in text.splitlines(keepends=True) if "entering stage travel:" in l)
            return text.replace(a, "@@").replace(b, a).replace("@@", b)
        self.assertRefused(self.run_gate(log=order), "was written during harness stage")
        def fall(text):
            return text.replace("entering stage edit: composited=800", "entering stage edit: composited=350")
        self.assertRefused(self.run_gate(log=fall), "running totals fall")
        # the nether may composite nothing (no ingested sections there yet)
        def nether(text):
            return text.replace("entering stage overworld: composited=1000", "entering stage overworld: composited=900")
        self.assertTrue(self.run_gate(log=nether)["success"])


if __name__ == "__main__":
    unittest.main()


class NativeLogChecksTest(unittest.TestCase):
    """The runner's per-launch log check: validation output, loader evidence, Voxy errors, and in
    the injection launch exactly one synchronization-control hazard."""

    HAZARD = ("[12:00:00] [Render thread/ERROR] (Minecraft) vkCmdFillBuffer(): WRITE_AFTER_WRITE hazard"
              " detected. vkCmdFillBuffer writes to dstBuffer VkBuffer 0x3aa2.\n")

    def check(self, text, injected=False):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "launch.log"
            path.write_text(LOADER + text)
            result = {"success": True}
            verify.native_log_checks(result, path, injected=injected)
            return result

    def test_a_clean_log_passes(self):
        self.assertTrue(self.check("[12:00:00] [Render thread/INFO] (Voxy) fine\n")["success"])

    def test_a_minecraft_logged_hazard_fails_an_ordinary_launch(self):
        result = self.check(self.HAZARD)
        self.assertFalse(result["success"])
        self.assertEqual(len(result["diagnostics"]), 1)

    def test_the_injection_launch_needs_exactly_its_control_hazard(self):
        ok = self.check(self.HAZARD, injected=True)
        self.assertTrue(ok["success"], ok)
        self.assertEqual(ok["diagnostics"], [])
        self.assertFalse(self.check("", injected=True)["success"])
        self.assertFalse(self.check(self.HAZARD * 2, injected=True)["success"])
        other = "[12:00:00] [Render thread/ERROR] (Minecraft) vkCmdDraw(): READ_AFTER_WRITE hazard detected.\n"
        self.assertFalse(self.check(self.HAZARD + other, injected=True)["success"])


class RenderThumbnailTest(unittest.TestCase):
    """R28-RENDER-THUMBNAIL-VALIDITY (round-28 Codex review): a required checkpoint thumbnail must
    decode and be the quarter-scale of its checkpoint's frame, not merely be a manifest member."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.checkpoints = environment()["checkpoints"]
        k = verify.RENDER_THUMB_SCALE
        for st in verify.LIFECYCLE_STAGES:
            verify.write_rgb_png(self.dir / f"frame-{st}.png", [[(40, 90, 160)] * (960 // k)] * (540 // k))

    def tearDown(self):
        self.tmp.cleanup()

    def check(self):
        verify.check_render_thumbnails(self.dir, "render", self.checkpoints)

    def test_quarter_scale_thumbnails_pass(self):
        self.check()

    def test_an_empty_thumbnail_fails(self):
        (self.dir / "frame-horizon.png").write_bytes(b"")
        with self.assertRaisesRegex(ValueError, "horizon thumbnail does not decode"):
            self.check()

    def test_a_one_pixel_thumbnail_fails(self):
        verify.write_rgb_png(self.dir / "frame-horizon.png", [[(0, 0, 0)]])
        with self.assertRaisesRegex(ValueError, "horizon thumbnail is 1x1, not the quarter-scale 240x135"):
            self.check()

    def test_a_truncated_thumbnail_fails(self):
        path = self.dir / "frame-night.png"
        path.write_bytes(path.read_bytes()[:-20])
        with self.assertRaisesRegex(ValueError, "night thumbnail does not decode"):
            self.check()

    def test_a_missing_thumbnail_fails(self):
        (self.dir / "frame-edit.png").unlink()
        with self.assertRaisesRegex(ValueError, "edit thumbnail does not decode"):
            self.check()

    def test_the_last_checkpoint_of_a_stage_sets_its_size(self):
        # a resize: the stage's last checkpoint says 854x480, so the 960x540 thumbnail is wrong
        later = json.loads(json.dumps(self.checkpoints[-1]))
        later["stage"] = "resize"
        later["renderer"]["colour"].update(width=854, height=480)
        self.checkpoints.append(later)
        with self.assertRaisesRegex(ValueError, "resize thumbnail is 240x135, not the quarter-scale 213x120"):
            self.check()

    def test_a_checkpoint_without_a_frame_size_fails(self):
        del self.checkpoints[0]["renderer"]["colour"]["width"]
        with self.assertRaisesRegex(ValueError, "does not say its frame size"):
            self.check()
