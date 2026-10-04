#!/usr/bin/env python3
"""Run every verification stage, preserve evidence, and reject false-green GPU runs.

The JSON summary is the input for an agent's fix -> rerun cycle. No diagnostics are
globally allowlisted, and this runner never rewrites source code or existing saves.
"""
import argparse
import datetime
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import subprocess
import struct
import sys
import time
import xml.etree.ElementTree as ET
import zlib
from pixel_oracle import read_rgb, mismatches

ROOT = Path(__file__).resolve().parents[1]
DIAGNOSTIC = re.compile(r"\[vk-validation\]\s*\[([^\]]+)\]")
KNOWN_SKIP = ("me.cortex.voxy.vk.VkBarriersTest", "missingBarrierIsDetected")
CONTROL = ("me.cortex.voxy.vk.VkBarriersTest", "plainBufferHazardIsNowDetected")
VISUAL_TESTS = {
    "analyticColorAndDepthMatchEveryPixel", "mirroredImageIsRejectedByIndependentReference",
    "missingDrawIsRejectedByIndependentReference", "wrongAtlasColorIsRejectedByIndependentReference",
    "wrongDepthIsRejectedEvenWithMatchingColor", "tinyArenaReclaimsRejectsAndRerequestsWithIdenticalPixels",
}


def diagnostics(text):
    return [line.strip() for line in text.splitlines() if "[vk-validation]" in line]


def junit_result(directory):
    files = sorted(directory.glob("TEST-*.xml"))
    result = {"tests": 0, "failures": [], "skips": [], "known_gaps": [], "diagnostics": []}
    controls = 0
    active_validation = False
    if not files:
        result["failures"].append("No JUnit XML reports were produced")
    for file in files:
        try:
            suite = ET.parse(file).getroot()
        except ET.ParseError as exc:
            result["failures"].append(f"Malformed report {file.name}: {exc}")
            continue
        for case in suite.findall("testcase"):
            result["tests"] += 1
            key = (case.get("classname"), case.get("name", "").removesuffix("()"))
            if case.find("skipped") is not None:
                target = "known_gaps" if key == KNOWN_SKIP else "skips"
                result[target].append(".".join(key))
            if case.find("failure") is not None or case.find("error") is not None:
                result["failures"].append(".".join(key))
            if key == CONTROL and case.find("skipped") is None and case.find("failure") is None and case.find("error") is None:
                controls += 1
            for tag in ("system-out", "system-err"):
                text = case.findtext(tag, "")
                active_validation |= "validation=true syncValidation=true" in text
                for message in diagnostics(text):
                    # One deliberate hazard, scoped to the actual negative control and operation.
                    ids = DIAGNOSTIC.findall(message)
                    if key == CONTROL and ids == ["SYNC-HAZARD-WRITE-AFTER-WRITE"] and "vkCmdFillBuffer" in message:
                        continue
                    result["diagnostics"].append({"test": ".".join(key), "message": message})
        for tag in ("system-out", "system-err"):
            text = suite.findtext(tag, "")
            active_validation |= "validation=true syncValidation=true" in text
            result["diagnostics"].extend({"test": "suite setup/teardown", "message": m} for m in diagnostics(text))
    if controls != 1:
        result["failures"].append("The synchronization validation negative control did not execute and pass exactly once")
    if not active_validation:
        result["failures"].append("No evidence of active Vulkan and synchronization validation")
    if result["skips"]:
        result["failures"].append("Unexpected skipped tests; GPU initialization failures cannot pass this gate")
    if result["diagnostics"]:
        result["failures"].append("Unexpected Vulkan validation diagnostics")
    result["success"] = not result["failures"]
    return result


def visual_recovery_result(output):
    """Require executed tests, real pressure evidence and independently decoded pixels."""
    result = {"success": False, "failures": [], "images": {}, "sha256": {},
              "scope": "Standalone Vulkan analytic opaque fixture and bounded geometry arena recovery; not Minecraft-native integration or live GL parity"}
    directory = output / "visual-recovery"

    def require(condition, message):
        if not condition:
            raise ValueError(message)

    def matching(pixels):
        return (pixels["colorMismatches"] == pixels["depthMismatches"] == 0
                and pixels["coveredPixels"] == 3072 and math.isfinite(pixels["maxDepthError"])
                and 0 <= pixels["maxDepthError"] <= 1e-6)

    try:
        suite = ET.parse(output / "junit" / "TEST-me.cortex.voxy.vk.VkVisualRecoveryTest.xml").getroot()
        cases = suite.findall("testcase")
        require(len(cases) == len(VISUAL_TESTS) and {c.get("name", "").removesuffix("()") for c in cases} == VISUAL_TESTS,
                "Missing or duplicate required visual/recovery tests")
        require(all(c.get("classname") == "me.cortex.voxy.vk.VkVisualRecoveryTest" and
                    all(c.find(tag) is None for tag in ("skipped", "failure", "error")) for c in cases),
                "A required visual/recovery test did not execute and pass")
        expected_errors = {"analytic": 0, "before-pressure": 0, "mirrored-control": 6144,
                           "missing-control": 3072, "color-control": 3072, "depth-control": 0,
                           **{f"recovered-{i}": 0 for i in range(3)}}
        for name, errors in expected_errors.items():
            evidence = json.loads((directory / (name + ".json")).read_text())
            pixels = evidence["pixels"]
            require(pixels["colorMismatches"] == errors, f"{name}: incorrect color metrics")
            if name in ("analytic", "before-pressure") or name.startswith("recovered-"):
                require(matching(pixels), f"{name}: color/depth does not match the reference")
            else:
                expected_depth = 6144 if name == "mirrored-control" else 0 if name == "color-control" else 3072
                expected_covered = 0 if name == "missing-control" else 3072
                require(pixels["depthMismatches"] == expected_depth and pixels["coveredPixels"] == expected_covered,
                        f"{name}: negative control was ineffective")
                require(math.isfinite(pixels["maxDepthError"]), f"{name}: invalid depth metrics")
            actual_errors = mismatches(read_rgb(directory / (name + "-actual.png")))
            require(actual_errors == errors, f"{name}: actual PNG disagrees with independent Python reference")
            depth_bytes = (directory / (name + "-depth-f32le.bin")).read_bytes()
            require(len(depth_bytes) == 256 * 192 * 4, f"{name}: incomplete depth readback")
            depth_errors = 0
            for i, (depth,) in enumerate(struct.iter_unpack("<f", depth_bytes)):
                y, x = divmod(i, 256)  # raw framebuffer rows run from bottom to top
                expected_depth = 0.1 if 64 <= x < 128 and 96 <= y < 144 else 0.0
                require(math.isfinite(depth), f"{name}: non-finite GPU depth")
                depth_errors += abs(depth - expected_depth) > 1e-6
            require(depth_errors == pixels["depthMismatches"], f"{name}: actual GPU depth disagrees with metrics")
            require(mismatches(read_rgb(directory / (name + "-expected.png"))) == 0,
                    f"{name}: generated reference PNG violates the analytic contract")
            difference = read_rgb(directory / (name + "-diff.png"))
            require(all(p in ((0, 0, 0), (255, 0, 0)) for p in difference), f"{name}: invalid diff image")
            require(sum(p == (255, 0, 0) for p in difference) == max(errors, pixels["depthMismatches"]),
                    f"{name}: diff PNG disagrees with metrics")
            result["images"][name] = {"color_mismatches": actual_errors, "depth_mismatches": pixels["depthMismatches"]}
        pressure = json.loads((directory / "pressure.json").read_text())
        require(pressure.get("complete") is True and pressure.get("success") is True, "Pressure scenario did not complete")
        cycles = pressure["cycles"]
        require(len(cycles) == 3, "Expected three pressure/recovery cycles")
        for i, cycle in enumerate(cycles):
            require(cycle["cycle"] == i and cycle["capacityBytes"] == cycle["usedBefore"] == 3072
                    and cycle["usedAfterReclaim"] == 2048 and cycle["retryBytes"] == 2048
                    and cycle["usedAfterRelease"] == 1024 and cycle["usedAfterRecovery"] == 3072
                    and 1 <= cycle["reclaimAttempts"] <= 4 and cycle["newRequests"] > 0
                    and cycle["rejectedTotal"] == i + 1 and matching(cycle["pixels"]),
                    f"Cycle {i}: missing real exhaustion/reclamation/re-request/recovery evidence")
        result["pressure"] = pressure
        for path in sorted(directory.iterdir()):
            if path.suffix in (".json", ".png", ".bin"):
                result["sha256"][path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        result["success"] = True
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, zlib.error) as exc:
        result["failures"].append(str(exc))
    return result


def run_stage(name, arguments, output, timeout):
    logfile = output / (name + ".log")
    command = [str(ROOT / "gradlew"), "--console=plain", *arguments]
    print(f"[{name}] running; log: {logfile}", flush=True)
    started = time.monotonic()
    timed_out = False
    with logfile.open("w") as log:
        process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            process.wait(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt) as exc:
            timed_out = isinstance(exc, subprocess.TimeoutExpired)
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            if not timed_out:
                raise
    result = {"command": command, "exit_code": process.returncode, "timeout": timed_out,
              "seconds": round(time.monotonic() - started, 2), "log": str(logfile),
              "success": process.returncode == 0 and not timed_out}
    print(f"[{name}] exit={process.returncode}, timeout={timed_out}", flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only", choices=("all", "gpu", "live"), default="all")
    parser.add_argument("--seconds", type=int, default=10, help="dwell per live scenario; increase for a soak")
    parser.add_argument("--timeout", type=int, default=1200, help="wall-clock limit per Gradle stage")
    parser.add_argument("--online", action="store_true", help="allow Gradle dependency downloads")
    parser.add_argument("--vk-lib", default="/opt/homebrew/lib/libvulkan.dylib")
    args = parser.parse_args()
    if args.seconds < 1 or args.timeout < 1:
        parser.error("seconds and timeout must be positive")
    # Gradle build outputs and Loom's launch configuration are shared between runs.
    (ROOT / ".gradle").mkdir(exist_ok=True)
    lock = (ROOT / ".gradle" / "voxy-harness.lock").open("w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        print("Another verification run is active in this checkout", file=sys.stderr)
        return 1
    timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S-%fZ")
    output = ROOT / "build" / "harness" / timestamp
    output.mkdir(parents=True)
    summary = {"success": False, "host": platform.platform(), "revision": subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(), "output": str(output), "stages": {},
        "scope": "Standalone Vulkan GPU/analytic pixel/arena recovery gates plus GL-hosted live liveness/lifecycle/mesh updates; Minecraft-native integration and live visual parity remain unproven"}
    summary["selection"] = args.only
    summary["scenario_seconds"] = args.seconds
    summary["worktree_status"] = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)
    fingerprints = {}
    for name in subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT).decode().split("\0"):
        path = ROOT / name
        if name and path.is_file() and (name.startswith(("src/", "scripts/")) or name in ("build.gradle", "gradle.properties")):
            fingerprints[name] = hashlib.sha256(path.read_bytes()).hexdigest()
            snapshot = output / 'source' / name
            snapshot.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, snapshot)
    (output / "source-sha256.json").write_text(json.dumps(fingerprints, indent=2) + "\n")
    (output / "tracked-changes.patch").write_bytes(subprocess.check_output(["git", "diff", "--binary", "HEAD"], cwd=ROOT))
    summary_file = output / "summary.json"

    def save():
        summary_file.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n")

    save()
    common = ([] if args.online else ["--offline"]) + [f"-PvkLibname={args.vk_lib}",
        "-PvkValidation=true", "-PvkSyncEnv=true", f"-PharnessOutput={output}"]
    try:
        if args.only in ("all", "gpu"):
            result = run_stage("junit", ["test", "--rerun-tasks", *common], output, args.timeout)
            result["gate"] = junit_result(output / "junit")
            result["success"] &= result["gate"]["success"]
            result["visual_recovery"] = visual_recovery_result(output)
            result["success"] &= result["visual_recovery"]["success"]
            summary["stages"]["junit"] = result
            save()
            result = run_stage("interop", ["interopCompositeCheck", *common], output, args.timeout)
            text = (output / "interop.log").read_text(errors="replace")
            result["diagnostics"] = diagnostics(text)
            result["success"] &= not result["diagnostics"] and "ALL CHECKS PASSED" in text and "validation=true syncValidation=true" in text
            summary["stages"]["interop"] = result
            if (ROOT / "build" / "vk-test-output").exists():
                shutil.copytree(ROOT / "build" / "vk-test-output", output / "gpu-images")
            save()
        if args.only in ("all", "live"):
            game = output / "game"
            game.mkdir()
            (game / ".voxy-harness").write_text(timestamp)
            (game / "options.txt").write_text('preferredGraphicsBackend:"default"\nonboardAccessibility:false\ntutorialStep:none\npauseOnLostFocus:false\nrenderDistance:8\nsimulationDistance:5\nmaxFps:60\nenableVsync:false\n')
            result = run_stage("live", ["runHarnessClient", *common, f"-PharnessRunDir={game}",
                f"-PharnessSeconds={args.seconds}"], output, args.timeout)
            live = output / "live-result.json"
            result["gate"] = json.loads(live.read_text()) if live.exists() else {"success": False, "failures": ["No live result; startup failed or timed out"]}
            result["success"] &= result["gate"].get("complete", False) and result["gate"]["success"]
            checkpoints = result["gate"].get("checkpoints", [])
            result["missing_images"] = [c["stage"] for c in checkpoints if not (output / (c["stage"] + ".png")).is_file()]
            result["success"] &= len(checkpoints) == 11 and not result["missing_images"]
            # Includes shutdown diagnostics, after the in-client result was written.
            result["diagnostics"] = diagnostics((output / "live.log").read_text(errors="replace"))
            result["application_errors"] = [line.strip() for line in (output / "live.log").read_text(errors="replace").splitlines()
                if "/ERROR]" in line and "(Voxy)" in line and "[vk-validation]" not in line]
            result["success"] &= not result["diagnostics"] and not result["application_errors"]
            summary["stages"]["live"] = result
        summary["success"] = bool(summary["stages"]) and all(r["success"] for r in summary["stages"].values())
    except (Exception, KeyboardInterrupt) as exc:
        summary["runner_error"] = str(exc) or type(exc).__name__
    finally:
        save()
    print(f"{'PASS' if summary['success'] else 'FAIL'}: {summary_file}", flush=True)
    return 0 if summary["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
