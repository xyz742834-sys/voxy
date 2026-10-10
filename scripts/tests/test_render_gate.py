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
    return {"complete": True, "success": True, "failures": [],
            "checkpoints": [dict(stage=st, renderer=json.loads(json.dumps(renderer)))
                            for st in verify.LIFECYCLE_STAGES]}

DEVICE = 0x7a42970018


def report(**overrides):
    body = {"enabled": True, "attempted": True, "drawsRecorded": 0, "everyFrame": True,
            "product": True, "buildBudgetApplies": False, "rebuildIntervalFrames": 60,
            "renderCalls": 0, "framesComposited": 0, "frameSkips": {"atlas-pending": 6},
            "builds": 2, "buildBudget": 6, "iterations": 3, "streaming": True,
            "sectionRenderDistance": 16.0, "streamRenderDistance": 17, "maxTopLevels": 34,
            "vanillaBound": True, "maxBoundSections": 210, "lightmapsApplied": 480,
            "lightmapReads": 481, "lightmapFailure": None, "postPass": True, "fogMode": "FOG_AND_FADE",
            "sectionCapacity": 8192, "geometryQuads": 4000000, "maxGeometryUsedBytes": 9000000,
            "maxMeshed": 7000, "geometryReclaimed": 0, "geometryRejected": 0,
            "geometryEverExhausted": False,
            "injectSubmitFailureAt": -1, "injectedFailures": 0, "compositedAtInjection": -1,
            "voxyNear": 16.0, "voxyFar": 48000.0, "declaredDepthState": [6, 1, 1],
            "depthStateReadBack": False, "instanceMode": True, "results": [], "problems": 0,
            "firstProblem": None, "closeFailures": 0, "leakedScenes": 0, "deviceDiverged": False,
            "readbacksInFlight": 0, "atlasReads": 1, "device": hex(DEVICE), "notes": []}
    body.update(overrides)
    return body


def log_for(stages=verify.LIFECYCLE_STAGES, per_stage=100, start=0, cycles=1):
    lines = [LOADER, "[native-vk] requested the block atlas (2048x2048) through Blaze3D\n",
             "[native-vk] block atlas read through Blaze3D: 2048x2048\n",
             "[native-vk] hier-LOAD scene #1: streaming render distance 17, sections -1..0\n"]
    composited = start
    for i, stage in enumerate(stages):
        lines.append(f"[voxy-harness] stage={stage}\n")
        lines.append(f"[native-vk] hier frames entering stage {stage}: composited={composited}"
                     f" skipped=6 builds={1 if i < 9 else 2}"
                     f" vkBuffers={0 if i < 1 else 40} vkBufferBytes={0 if i < 1 else 80961384 + (0 if i < 8 else 3471360)}"
                     f" vkTextures={0 if i < 1 else 10} frame={'1708x960' if i < 8 else '1920x1080'}\n")
        if i == 9:
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
                         f" vkBufferBytes={80961384 + (3471360 if large else 0)} vkTextures=10"
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
        self.assertEqual(result["framesComposited"], 1400)
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
        RECONNECT = "entering stage reconnect: composited=1300 skipped=6 builds=2 vkBuffers=40 vkBufferBytes=84432744 vkTextures=10"
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
            return text.replace(" vkBuffers=40 vkBufferBytes=80961384 vkTextures=10 frame=1708x960\n", "\n", 1)
        self.assertRefused(self.run_gate(log=silent), "states no allocations")
        def sizeless(text):
            return text.replace(" frame=1708x960\n", "\n", 1)
        self.assertRefused(self.run_gate(log=sizeless), "states no frame size")

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
            return text.replace("entering stage resize: composited=700 skipped=6 builds=1 vkBuffers=40 vkBufferBytes=80961384",
                                "entering stage resize: composited=700 skipped=6 builds=1 vkBuffers=40 vkBufferBytes=90000000")
        self.assertRefused(self.run_gate(log=early), "retired scenes are not freed")
        # a snapshot without a live scene (0x0) stays in its run: it cannot start one that excuses bytes
        def sceneless(text):
            return text.replace("vkBuffers=40 vkBufferBytes=84432744 vkTextures=10 frame=1920x1080\n[voxy-harness] stage=disconnect",
                                "vkBuffers=40 vkBufferBytes=84432744 vkTextures=10 frame=1920x1080\n[voxy-harness] stage=disconnect").replace(
                "entering stage reconnect: composited=1300 skipped=6 builds=2 vkBuffers=40 vkBufferBytes=84432744 vkTextures=10 frame=1920x1080",
                "entering stage reconnect: composited=1300 skipped=6 builds=2 vkBuffers=40 vkBufferBytes=90000000 vkTextures=10 frame=0x0")
        self.assertRefused(self.run_gate(log=sceneless), "retired scenes are not freed")

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

    def test_the_injection_launch_carries_on_after_one_failed_submission(self):
        inject = COMMAND + [f"-PharnessNativeInjectSubmitFailureAt={verify.INJECT_AT_FRAME}"]
        ERROR = ("[12:00:00] [Render thread/ERROR] (Voxy) [me.cx.vy.ct.ce.vk.me.McNativeHierarchicalLoad]:"
                 " [native-vk] the hierarchical-LOAD experiment failed\n")
        def injected(b):
            b.update(injectSubmitFailureAt=verify.INJECT_AT_FRAME, injectedFailures=1,
                     compositedAtInjection=1000, problems=1, firstProblem=verify.INJECTED_FAILURE,
                     notes=[verify.INJECTED_FAILURE], renderCalls=b["renderCalls"] + 1001,
                     frameSkips={"atlas-pending": 6, "frame-failed": 1},
                     framesComposited=b["framesComposited"] + 1000)
        def log(text):
            text = text.replace("[voxy-harness] stage=reload\n", ERROR + "[voxy-harness] stage=reload\n")
            return text
        def run(**kw):
            kw.setdefault("command", inject)
            kw.setdefault("mutate", injected)
            kw.setdefault("log", log)
            return self.run_gate(gate=verify.native_inject_result, **kw)
        ok = run()
        self.assertTrue(ok["success"], ok["failures"])
        self.assertEqual(ok["compositedAfter"], 1400)
        # the product launch refuses the flag, and an injected failure, outright
        self.assertRefused(self.run_gate(command=inject), "enables diagnostics")
        self.assertRefused(self.run_gate(mutate=injected, log=log), "problems=1")
        # exactly one failure: a second error line, a second failed frame, no failed frame
        self.assertRefused(run(log=lambda t: log(log(t))), "not the one injected")
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
            return text.replace("entering stage edit: composited=500", "entering stage edit: composited=600")
        self.assertRefused(self.run_gate(log=stall), "composited no frame in stage(s) ['edit']")
        def drop(text):
            return "".join(l for l in text.splitlines(keepends=True)
                           if "entering stage resize" not in l)
        self.assertRefused(self.run_gate(log=drop), "['resize']")
        def twice(text):
            return text + "[voxy-harness] stage=warmup\n[native-vk] hier frames entering stage warmup: composited=1400 skipped=6 builds=2 vkBuffers=40 vkBufferBytes=84432744 vkTextures=10 frame=1920x1080\n"
        self.assertRefused(self.run_gate(log=twice), "enters a stage twice")
        # round-27: a snapshot belongs to the harness stage current when it was written
        def early(text):
            line = next(l for l in text.splitlines(keepends=True) if "entering stage edit:" in l)
            return text.replace(line, "").replace("[voxy-harness] stage=edit\n", line + "[voxy-harness] stage=edit\n")
        self.assertRefused(self.run_gate(log=early), "was written during harness stage 'horizon'")
        def skipped(text):
            return text.replace("entering stage warmup: composited=0 skipped=6", "entering stage warmup: composited=0 skipped=999999999")
        self.assertRefused(self.run_gate(log=skipped), "exceed the final report")
        def built(text):
            return text.replace("entering stage reconnect: composited=1300 skipped=6 builds=2",
                                "entering stage reconnect: composited=1300 skipped=6 builds=9")
        self.assertRefused(self.run_gate(log=built), "exceed the final report")
        def order(text):
            a = next(l for l in text.splitlines(keepends=True) if "entering stage turn:" in l)
            b = next(l for l in text.splitlines(keepends=True) if "entering stage travel:" in l)
            return text.replace(a, "@@").replace(b, a).replace("@@", b)
        self.assertRefused(self.run_gate(log=order), "was written during harness stage")
        def fall(text):
            return text.replace("entering stage edit: composited=500", "entering stage edit: composited=350")
        self.assertRefused(self.run_gate(log=fall), "running totals fall")
        # the nether may composite nothing (no ingested sections there yet)
        def nether(text):
            return text.replace("entering stage overworld: composited=1000", "entering stage overworld: composited=900")
        self.assertTrue(self.run_gate(log=nether)["success"])


if __name__ == "__main__":
    unittest.main()
