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
import verify

DEVICE = 0x7a42970018


def report(**overrides):
    body = {"enabled": True, "attempted": True, "drawsRecorded": 0, "everyFrame": True,
            "product": True, "buildBudgetApplies": False, "rebuildIntervalFrames": 60,
            "renderCalls": 0, "framesComposited": 0, "frameSkips": {"atlas-pending": 6},
            "builds": 2, "buildBudget": 6, "iterations": 3, "topRadius": 1, "depth": 2,
            "voxyNear": 16.0, "voxyFar": 48000.0, "declaredDepthState": [6, 1, 1],
            "depthStateReadBack": False, "instanceMode": True, "results": [], "problems": 0,
            "firstProblem": None, "closeFailures": 0, "leakedScenes": 0, "deviceDiverged": False,
            "readbacksInFlight": 0, "atlasReads": 1, "device": hex(DEVICE), "notes": []}
    body.update(overrides)
    return body


def log_for(stages=verify.LIFECYCLE_STAGES, per_stage=100, start=0):
    lines = ["[native-vk] requested the block atlas (2048x2048) through Blaze3D\n",
             "[native-vk] block atlas read through Blaze3D: 2048x2048\n",
             "[native-vk] hier-LOAD scene #1: 35 sections meshed, top radius 1, depth 2\n"]
    composited = start
    for i, stage in enumerate(stages):
        lines.append(f"[voxy-harness] stage={stage}\n")
        lines.append(f"[native-vk] hier frames entering stage {stage}: composited={composited}"
                     f" skipped=6 builds={1 if i < 9 else 2}\n")
        if i == 9:
            lines.append("[native-vk] hier-LOAD scene #2: 63 sections meshed, top radius 1, depth 2\n")
        composited += per_stage
    return "".join(lines), composited


class RenderGateTest(unittest.TestCase):

    def run_gate(self, mutate=None, log=None, files=(), device=DEVICE):
        text, total = log_for()
        body = report(framesComposited=total, renderCalls=total + 6)
        if mutate:
            mutate(body)
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            (out / "native-hier-load.json").write_text(json.dumps(body))
            for name in files:
                (out / name).write_text("{}")
            return verify.native_render_result(out, text if log is None else log(text), device)

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

    def test_the_report_must_be_the_clean_product_path(self):
        for field, value in (("product", False), ("everyFrame", False), ("enabled", False),
                             ("buildBudgetApplies", True), ("instanceMode", False),
                             ("drawsRecorded", 1), ("results", [{}]), ("problems", 1),
                             ("firstProblem", "x"), ("notes", ["x"]), ("closeFailures", 1),
                             ("leakedScenes", 1), ("deviceDiverged", True),
                             ("readbacksInFlight", 1), ("attempted", False), ("product", 1)):
            self.assertRefused(self.run_gate(mutate=lambda b, f=field, v=value: b.update({f: v})),
                               f"says {field}=")

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
            return text + "[native-vk] hier frames entering stage warmup: composited=1400 skipped=6 builds=2\n"
        self.assertRefused(self.run_gate(log=twice), "enters a stage twice")
        def order(text):
            a = "[native-vk] hier frames entering stage turn: composited=100 skipped=6 builds=1\n"
            b = "[native-vk] hier frames entering stage travel: composited=200 skipped=6 builds=1\n"
            return text.replace(a, "@@").replace(b, a).replace("@@", b)
        self.assertRefused(self.run_gate(log=order), "not in the harness's stage order")
        def fall(text):
            return text.replace("entering stage edit: composited=500", "entering stage edit: composited=350")
        self.assertRefused(self.run_gate(log=fall), "running totals fall")
        # the nether may composite nothing (no ingested sections there yet)
        def nether(text):
            return text.replace("entering stage overworld: composited=1000", "entering stage overworld: composited=900")
        self.assertTrue(self.run_gate(log=nether)["success"])


if __name__ == "__main__":
    unittest.main()
