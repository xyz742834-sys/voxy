"""The product launch gate: Voxy on Minecraft's Vulkan backend under voxy.native.render alone.

The launch is not judged per pixel (the ladder launch judges the same path); this checks that the
switch alone ran the native path through the lifecycle with a clean probe report, every frame
accounted for, builds and atlas reads reconciled with the log, and frames composited in every
required stage.
"""
from pathlib import Path
import json
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
            "sectionCapacity": 8192, "maxMeshed": 7000, "geometryReclaimed": 0, "geometryRejected": 0,
            "geometryEverExhausted": False,
            "voxyNear": 16.0, "voxyFar": 48000.0, "declaredDepthState": [6, 1, 1],
            "depthStateReadBack": False, "instanceMode": True, "results": [], "problems": 0,
            "firstProblem": None, "closeFailures": 0, "leakedScenes": 0, "deviceDiverged": False,
            "readbacksInFlight": 0, "atlasReads": 1, "device": hex(DEVICE), "notes": []}
    body.update(overrides)
    return body


def log_for(stages=verify.LIFECYCLE_STAGES, per_stage=100, start=0):
    lines = [LOADER, "[native-vk] requested the block atlas (2048x2048) through Blaze3D\n",
             "[native-vk] block atlas read through Blaze3D: 2048x2048\n",
             "[native-vk] hier-LOAD scene #1: streaming render distance 17, sections -1..0\n"]
    composited = start
    for i, stage in enumerate(stages):
        lines.append(f"[voxy-harness] stage={stage}\n")
        lines.append(f"[native-vk] hier frames entering stage {stage}: composited={composited}"
                     f" skipped=6 builds={1 if i < 9 else 2}\n")
        if i == 9:
            lines.append("[native-vk] hier-LOAD scene #2: streaming render distance 17, sections -1..0\n")
        composited += per_stage
    return "".join(lines), composited


class RenderGateTest(unittest.TestCase):

    def run_gate(self, mutate=None, log=None, files=(), device=DEVICE, command=None, env=None,
                 gate=None):
        text, total = log_for()
        body = report(framesComposited=total, renderCalls=total + 6)
        if mutate:
            mutate(body)
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            (out / "native-hier-load.json").write_text(json.dumps(body))
            e = environment()
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

    def test_the_product_scene_runs_at_the_default_capacity(self):
        self.assertRefused(self.run_gate(mutate=lambda b: b.update(sectionCapacity=4096)),
                           "capacity of 4096 sections, not 8192")

    def test_the_pressure_launch_reclaims(self):
        pressure = COMMAND + [f"-PharnessNativeSectionCapacity={verify.PRESSURE_CAPACITY}"]
        def under(b):
            b.update(sectionCapacity=verify.PRESSURE_CAPACITY, geometryReclaimed=900)
        ok = self.run_gate(mutate=under, command=pressure, gate=verify.native_pressure_result)
        self.assertTrue(ok["success"], ok["failures"])
        self.assertEqual(ok["reclaimed"], 900)
        self.assertRefused(self.run_gate(mutate=lambda b: under(b) or b.update(geometryReclaimed=0),
                                         command=pressure, gate=verify.native_pressure_result),
                           "put no pressure on the scene")
        self.assertRefused(self.run_gate(mutate=under, gate=verify.native_pressure_result),
                           "lacks")
        self.assertRefused(self.run_gate(command=pressure, gate=verify.native_pressure_result),
                           "not 4096")

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
            return text + "[voxy-harness] stage=warmup\n[native-vk] hier frames entering stage warmup: composited=1400 skipped=6 builds=2\n"
        self.assertRefused(self.run_gate(log=twice), "enters a stage twice")
        # round-27: a snapshot belongs to the harness stage current when it was written
        def early(text):
            line = "[native-vk] hier frames entering stage edit: composited=500 skipped=6 builds=1\n"
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
            a = "[native-vk] hier frames entering stage turn: composited=100 skipped=6 builds=1\n"
            b = "[native-vk] hier frames entering stage travel: composited=200 skipped=6 builds=1\n"
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
