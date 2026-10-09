"""The native-instance gate: on Minecraft's Vulkan backend Voxy's instance (world engine,
storage, ingest) ran without any render path and ingested real sections.

This is the dependency the next experiment rests on (real sections through Voxy's terrain
pipeline into Minecraft's LOADed pass). The report is the probe's own record; the gate requires
a world engine to have existed, sections to have been held, no VoxyRenderSystem ever created,
no backend chosen, the samples on the probe's interval and in order, the aggregates to agree
with the samples, and the log's lines to agree with the report. It says nothing about drawing.
"""
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify
from verify import native_instance_result, retain_native_evidence, replay_evidence
import test_marker_gate
from test_marker_gate import ProofFileGateTest, REAL_FINGERPRINTS

INSTANCE_COMMAND = ["gradlew", "runHarnessClient", "-PharnessNative=true",
                    "-PharnessGraphicsBackend=vulkan", "-PharnessNativeInstance=true"]


def sample(frame, stage="warmup", active=12, engine=True, renderer=False, live=None,
           ingest=True):
    return {"frame": frame, "stage": stage, "factorySet": True, "instancePresent": True,
            "enginePresent": engine, "engineLive": engine if live is None else live,
            "activeSections": active if engine else 0, "rendererCreated": renderer,
            "ingestEnabled": ingest}


def report(samples=None, **overrides):
    samples = [sample(1, "create", active=0), sample(60, "warmup", active=3),
               sample(120, "turn", active=12), sample(180, "travel", active=9)] \
        if samples is None else samples
    dicts = [s for s in samples if isinstance(s, dict)]
    body = {"enabled": True, "backend": None,
            "frames": max((s["frame"] for s in dicts), default=0),
            "engineEverPresent": any(s["enginePresent"] for s in dicts),
            "rendererEverCreated": any(s["rendererCreated"] for s in dicts),
            "maxActiveSections": max((s["activeSections"] for s in dicts), default=0),
            "sampleInterval": verify.INSTANCE_SAMPLE_INTERVAL,
            "samples": samples, "notes": []}
    body.update(overrides)
    return body


def log_for(samples):
    def flag(s, k):
        return str(s.get(k)).lower()
    return "".join(
        f"[native-vk] native instance at frame {s.get('frame')} stage={s.get('stage')}"
        f" factory={flag(s, 'factorySet')} instance={flag(s, 'instancePresent')}"
        f" engine={flag(s, 'enginePresent')} live={flag(s, 'engineLive')}"
        f" activeSections={s.get('activeSections')} renderer={flag(s, 'rendererCreated')}"
        f" ingest={flag(s, 'ingestEnabled')}\n" for s in samples if isinstance(s, dict))


class InstanceGateTest(unittest.TestCase):

    def run_gate(self, body=None, required=True, log=None, write=True):
        body = report() if body is None else body
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            if write:
                (out / "native-instance.json").write_text(json.dumps(body))
            text = log_for(body.get("samples") or []) if log is None else log
            return native_instance_result(out, required, text)

    def assertRefused(self, result, fragment):
        self.assertFalse(result["success"], "passed although: " + fragment)
        self.assertIn(fragment, " ".join(result["failures"]))

    def test_an_engine_with_sections_and_no_renderer_passes(self):
        result = self.run_gate()
        self.assertTrue(result["success"], result["failures"])
        self.assertTrue(result["enabled"])
        self.assertEqual(result["max_active_sections"], 12)
        self.assertIn("without a render path", result["answer"])

    def test_a_missing_report_fails_when_the_launch_enabled_the_mode(self):
        self.assertRefused(self.run_gate(write=False), "not retained")
        result = self.run_gate(write=False, required=False)
        self.assertTrue(result["success"])
        self.assertFalse(result["enabled"])

    def test_a_report_that_says_off_fails_when_the_launch_enabled_the_mode(self):
        self.assertRefused(self.run_gate(report(enabled=False)), "says enabled=false")
        # off and nothing recorded is fine when not required
        off = report(enabled=False, samples=[], frames=0, engineEverPresent=False,
                     maxActiveSections=0)
        result = self.run_gate(off, required=False, log="")
        self.assertTrue(result["success"], result["failures"])
        self.assertRefused(self.run_gate(report(enabled=False), required=False),
                           "recorded while disabled")

    def test_a_renderer_or_a_backend_fails(self):
        samples = [sample(1, active=0), sample(60, active=5, renderer=True)]
        self.assertRefused(self.run_gate(report(samples=samples)), "VoxyRenderSystem was created")
        self.assertRefused(self.run_gate(report(backend="VULKAN")), "names 'VULKAN'")
        self.assertRefused(self.run_gate(report(notes=["a VoxyRenderSystem exists"])),
                           "reported notes")

    def test_no_engine_or_no_sections_fails(self):
        none = [sample(1, engine=False), sample(60, engine=False)]
        self.assertRefused(self.run_gate(report(samples=none)), "no sample saw a world engine")
        empty = [sample(1, active=0), sample(60, active=0)]
        self.assertRefused(self.run_gate(report(samples=empty)), "never held a section")
        gone = [sample(1, active=0), sample(60, active=4), sample(120, engine=False)]
        self.assertRefused(self.run_gate(report(samples=gone)), "has no live world engine")
        dead = [sample(1, active=0), sample(60, active=4, live=False)]
        self.assertRefused(self.run_gate(report(samples=dead)), "has no live world engine")
        no_ingest = [sample(1, active=0), sample(60, active=4, ingest=False)]
        self.assertRefused(self.run_gate(report(samples=no_ingest)), "ingest was disabled")

    def test_aggregates_the_samples_contradict_fail(self):
        self.assertRefused(self.run_gate(report(maxActiveSections=99)), "maxActiveSections=99")
        self.assertRefused(self.run_gate(report(engineEverPresent=False)),
                           "engineEverPresent=False")
        # the report denies a renderer its own sample admits
        hidden = [sample(1, active=0), sample(60, active=5, renderer=True)]
        self.assertRefused(self.run_gate(report(samples=hidden, rendererEverCreated=False)),
                           "rendererEverCreated=False but its samples say True")
        self.assertRefused(self.run_gate(report(frames=10)), "only 10 frames were counted")

    def test_samples_off_the_interval_or_out_of_order_fail(self):
        self.assertRefused(self.run_gate(report(samples=[sample(1, active=0), sample(61, active=2)])),
                           "not on the probe's interval")
        self.assertRefused(self.run_gate(report(samples=[sample(60, active=2), sample(60, active=2)])),
                           "out of order")
        self.assertRefused(self.run_gate(report(sampleInterval=30)), "its source lays out")
        inconsistent = [sample(1, active=0), dict(sample(60, active=2), factorySet=False)]
        self.assertRefused(self.run_gate(report(samples=inconsistent)),
                           "engine without a factory or instance")

    def test_missing_fields_and_wrong_types_fail(self):
        for field in ("enabled", "backend", "frames", "engineEverPresent", "rendererEverCreated",
                      "maxActiveSections", "sampleInterval", "samples", "notes"):
            body = report()
            body.pop(field)
            self.assertRefused(self.run_gate(body), field)
        for field in ("frame", "stage", "factorySet", "instancePresent", "enginePresent",
                      "engineLive", "activeSections", "rendererCreated", "ingestEnabled"):
            body = report()
            body["samples"][1].pop(field)
            self.assertRefused(self.run_gate(body), field)
        self.assertRefused(self.run_gate(report(enabled="true")), "not a bool")
        self.assertRefused(self.run_gate(report(frames=2.5)), "not an int")
        self.assertRefused(self.run_gate(report(samples=[sample(1, active=0), "junk"])),
                           "not an object")
        self.assertRefused(self.run_gate(report(samples=[])), "retained no sample")

    def test_the_log_must_agree_with_the_report(self):
        body = report()
        text = log_for(body["samples"])
        self.assertRefused(self.run_gate(body, log=text.replace("activeSections=12", "activeSections=13")),
                           "log's instance line for frame 120")
        self.assertRefused(self.run_gate(body, log="".join(
            l for l in text.splitlines(keepends=True) if "frame 120 " not in l)),
                           "instance lines for frames")
        first = text.splitlines(keepends=True)[0]
        self.assertRefused(self.run_gate(body, log=text + first), "two instance lines")
        result = self.run_gate(body, log=None)
        self.assertTrue(result["success"], result["failures"])


class InstanceRetentionTest(unittest.TestCase):
    """The report is retained with the main launch and replayed; the launch command decides
    whether it is required."""

    def build(self, with_report=True, command=None, mutate=None):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        native_output = root / "native"
        native_output.mkdir()
        helper = test_marker_gate.EvidenceRetentionTest()
        helper.populate(native_output)
        body = report()
        if mutate:
            mutate(body)
        if with_report:
            (native_output / "native-instance.json").write_text(json.dumps(body))
        (root / "native.log").write_text("log\n" + log_for(body["samples"]))
        (root / "source-sha256.json").write_text(json.dumps(REAL_FINGERPRINTS))
        stage = {"gate": {"checkpoints": ProofFileGateTest.CHECKPOINTS},
                 "command": list(INSTANCE_COMMAND) if command is None else command,
                 "instance": {"enabled": True}}
        (root / "summary.json").write_text(json.dumps({"stages": {"native_environment": stage}}))
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

    def test_the_report_is_retained_and_replays(self):
        target, kept = self.build()
        self.assertIn("native-instance.json", kept["files"])
        code, out = self.replay(target)
        self.assertEqual(code, 0, out)
        self.assertIn("instance", out)
        self.assertTrue(any("native instance mode" in r for r in out["replayed"]), out["replayed"])

    def test_a_missing_report_behind_an_enabling_command_fails_replay(self):
        target, _ = self.build(with_report=False)
        code, out = self.replay(target)
        self.assertEqual(code, 1)
        self.assertIn("native-instance.json", out["error"])

    def test_a_renderer_in_the_retained_report_fails_replay(self):
        target, _ = self.build(mutate=lambda b: b.update(rendererEverCreated=True))
        code, out = self.replay(target)
        self.assertEqual(code, 1)
        self.assertIn("instance gate", out["error"])

    def test_every_gradle_form_of_the_command_requires_the_report(self):
        for token in ("-PharnessNativeInstance=false", "-PharnessNativeInstance",
                      "--project-prop=harnessNativeInstance"):
            target, _ = self.build(with_report=False,
                                   command=["gradlew", "runHarnessClient", token])
            code, out = self.replay(target)
            self.assertEqual(code, 1, token)
            self.assertIn("native-instance.json", out["error"])


if __name__ == "__main__":
    unittest.main()
