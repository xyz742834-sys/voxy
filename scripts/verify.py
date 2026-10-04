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
from pixel_oracle import read_rgb, mismatches, top_rows_rgb, png_size

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


def native_environment_result(output):
    """Independently reject preferences, fallback, partial observations and missing images."""
    result = {"success": False, "failures": [], "scope": "Minecraft Vulkan environment; no Voxy LoD acceptance"}
    expected = {"warmup", "turn", "travel", "return", "edit", "remove", "resize", "reload", "nether", "overworld", "reconnect"}
    try:
        evidence = json.loads((output / "native-result.json").read_text())
        cases = evidence["checkpoints"]
        if evidence.get("complete") is not True or evidence.get("success") is not True or evidence.get("failures") != []:
            raise ValueError("Native environment scenario did not complete cleanly")
        if len(cases) != 11 or {c["stage"] for c in cases} != expected:
            raise ValueError("Missing or duplicate native lifecycle checkpoints")
        devices = set()
        for case in cases:
            renderer = case["renderer"]
            if (renderer.get("mcUsesVulkan") is not True
                or renderer.get("backendClass") != "com.mojang.blaze3d.vulkan.VulkanDevice"
                or renderer.get("notes") != [] or not renderer.get("vkDevice") or not renderer.get("vkInstance")
                or not renderer.get("vmaAllocator") or any(renderer.get(q, -1) < 0 for q in
                    ("graphicsQueueFamily", "computeQueueFamily", "transferQueueFamily"))):
                raise ValueError(f"{case['stage']}: native Vulkan device not established")
            devices.add(renderer["vkDevice"])
            for role in ("colour", "depth"):
                attachment = renderer.get(role)
                if not attachment or not attachment.get("vkImage") or not attachment.get("vkImageView") or min(attachment.get("width", 0), attachment.get("height", 0)) <= 0:
                    raise ValueError(f"{case['stage']}: invalid native {role} attachment")
            read_rgb(output / (case["stage"] + ".png"),
                expected_size=(renderer["colour"]["width"], renderer["colour"]["height"]), validate_only=True)
        if len(devices) != 1:
            raise ValueError("Minecraft device changed within the lifecycle run")
        result.update(success=True, checkpoints=cases, voxy_integration_status=evidence.get("voxyIntegrationStatus"))
    except (OSError, ValueError, KeyError, TypeError, zlib.error, struct.error) as exc:
        result["failures"].append(str(exc))
    return result


def source_fingerprints():
    names = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT).decode().split("\0")
    return {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in names
        if name and (ROOT / name).is_file()
        and (name.startswith(("src/", "scripts/")) or name in ("build.gradle", "gradle.properties"))}


def native_marker_result(output, checkpoints):
    """Independently confirm the bounded native draw actually reached Minecraft's frame.

    The in-client report says a draw was recorded; this decodes the top of every captured
    screenshot and looks for the marker's exact colour in the box the vertex shader covers
    (NDC -0.98..-0.78 on both axes, i.e. the first 1%..11% of width and height). A report
    without pixels, or pixels without a report, both fail.
    """
    result = {"success": False, "failures": [],
              "scope": "bounded marker draw recorded into Minecraft's own Vulkan frame"}
    marker = (255, 0, 255)
    try:
        report = json.loads((output / "native-marker-draw.json").read_text())
        result["report"] = report
        if not report.get("enabled"):
            raise ValueError("the marker draw was not enabled")
        if not report.get("pipelineLive"):
            raise ValueError("no marker pipeline was live on Minecraft's device")
        if report.get("drawsRecorded", 0) < 1:
            raise ValueError("no draw was recorded into Minecraft's command buffer")
        if report.get("notes"):
            raise ValueError(f"the marker draw reported notes: {report['notes']}")
        if tuple(report.get("markerRgb") or ()) != marker:
            raise ValueError("the reported marker colour is not the one checked here")
        # The shader writes (255, 0, 255) but Minecraft's final composition darkens it
        # (measured: (235, 0, 235)), so the check is a bounded neighbourhood of the drawn
        # colour rather than equality, and the values actually found are recorded.
        def is_marker(px):
            return px[0] >= 200 and px[1] <= 60 and px[2] >= 200
        found, colours, skipped = {}, {}, {}
        result["marker_pixels"], result["marker_colour_in_frame"] = found, colours
        result["frames_without_level_content"] = skipped
        for case in checkpoints:
            png = output / (case["stage"] + ".png")
            width, height = png_size(png)
            x0, x1 = int(0.012 * width), int(0.108 * width)
            y0, y1 = int(0.012 * height), int(0.108 * height)
            # Decode only the scanlines the marker box can touch; a full screenshot
            # reconstructed in Python would cost a minute per image.
            rows, decoded_size = top_rows_rgb(png, y1 + 1)
            if decoded_size != (width, height) or len(rows) <= y1:
                raise ValueError(f"{case['stage']}: decoded {len(rows)} rows of {decoded_size}, need {y1 + 1}")
            hits, sample, brightest = 0, None, 0
            for y in range(y0, y1):
                for x in range(x0, x1):
                    px = rows[y][x]
                    brightest = max(brightest, px[0] + px[1] + px[2])
                    if is_marker(px):
                        hits += 1
                        sample = px
            box = max(1, (x1 - x0) * (y1 - y0))
            found[case["stage"]] = hits
            colours[case["stage"]] = list(sample) if sample else None
            if hits >= box // 4:
                continue
            # The draw only happens on frames where the level is actually rendered. A
            # checkpoint captured during a dimension change lands on an essentially black
            # frame (measured: the nether checkpoint's whole box was (5, 2, 2)), and that
            # says nothing about whether the draw works. Record such a frame instead of
            # counting it either way — and require most checkpoints to be real, so a run
            # cannot pass by calling every frame empty.
            if brightest <= 30:
                skipped[case["stage"]] = brightest
                continue
            raise ValueError(f"{case['stage']}: only {hits} of {box} marker pixels present"
                             f" in a frame that has content (brightest box pixel sum {brightest})")
        positives = sum(1 for stage, hits in found.items() if stage not in skipped)
        if positives < 8:
            raise ValueError(f"only {positives} of {len(found)} checkpoints carried the marker;"
                             f" frames without level content: {skipped}")
        result.update(success=True)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        result["failures"].append(str(exc))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only", choices=("all", "gpu", "live", "native", "required"), default="all")
    parser.add_argument("--seconds", type=int, default=10, help="dwell per live scenario; increase for a soak")
    parser.add_argument("--timeout", type=int, default=1200, help="wall-clock limit per Gradle stage")
    parser.add_argument("--online", action="store_true", help="allow Gradle dependency downloads")
    parser.add_argument("--vk-lib", default="/opt/homebrew/lib/libvulkan.dylib")
    parser.add_argument("--wait-lock", action="store_true", help="queue behind another runner in this checkout")
    parser.add_argument("--graphics-backend", choices=("default", "opengl", "vulkan"), default="default",
        help="Minecraft's preferredGraphicsBackend for the live stage. 'vulkan' asks Minecraft for its own "
             "Vulkan backend, which Voxy cannot render through yet, so the live stage is then judged by the "
             "native probe instead of the GL-hosted checkpoint gate")
    args = parser.parse_args()
    if args.seconds < 1 or args.timeout < 1:
        parser.error("seconds and timeout must be positive")
    # Gradle build outputs and Loom's launch configuration are shared between runs.
    (ROOT / ".gradle").mkdir(exist_ok=True)
    lock = (ROOT / ".gradle" / "voxy-harness.lock").open("w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | (0 if args.wait_lock else fcntl.LOCK_NB))
    except BlockingIOError:
        print("Another verification run is active in this checkout", file=sys.stderr)
        return 1
    timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S-%fZ")
    output = ROOT / "build" / "harness" / timestamp
    output.mkdir(parents=True)
    summary = {"success": False, "host": platform.platform(), "revision": subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(), "output": str(output), "stages": {},
        "scope": "Standalone Vulkan GPU/analytic pixel/arena recovery gates plus GL-hosted live liveness/lifecycle/mesh updates; Minecraft-native integration and live visual parity remain unproven"}
    summary["graphics_backend_preference"] = args.graphics_backend
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
        if args.only in ("all", "gpu", "required"):
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
        if args.only in ("all", "live", "required"):
            game = output / "game"
            game.mkdir()
            (game / ".voxy-harness").write_text(timestamp)
            (game / "options.txt").write_text(f'preferredGraphicsBackend:"{args.graphics_backend}"\nonboardAccessibility:false\ntutorialStep:none\npauseOnLostFocus:false\nrenderDistance:8\nsimulationDistance:5\nmaxFps:60\nenableVsync:false\n')
            # options.txt only expresses a preference; Minecraft's own --graphicsBackend launch
            # argument is what actually forces the backend, so pass both and let the probe report
            # which one Minecraft really ran.
            result = run_stage("live", ["runHarnessClient", *common, f"-PharnessRunDir={game}",
                f"-PharnessSeconds={args.seconds}",
                f"-PharnessGraphicsBackend={args.graphics_backend}"], output, args.timeout)
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
            save()
        if args.only in ("native", "required"):
            native_output = output / "native"
            native_output.mkdir()
            game = native_output / "game"
            game.mkdir()
            (game / ".voxy-harness").write_text(timestamp)
            (game / "options.txt").write_text('preferredGraphicsBackend:"vulkan"\nonboardAccessibility:false\ntutorialStep:none\npauseOnLostFocus:false\nrenderDistance:8\nsimulationDistance:5\nmaxFps:60\nenableVsync:false\n')
            native_common = [a for a in common if not a.startswith("-PharnessOutput=")]
            # ⚠ options.txt is only a preference and Minecraft overrode it back to "default" on this
            # host (measured: it ran com.mojang.blaze3d.opengl.GlDevice with the file asking for
            # vulkan). Minecraft's own --graphicsBackend launch argument is what actually forces the
            # backend ("Graphics backend forced to vulkan by launch argument, in-game preferred
            # graphics backend setting is ignored"), so the native stage passes it.
            result = run_stage("native", ["runHarnessClient", *native_common,
                f"-PharnessOutput={native_output}", f"-PharnessRunDir={game}",
                f"-PharnessSeconds={args.seconds}", "-PharnessNative=true",
                "-PharnessGraphicsBackend=vulkan", "-PharnessNativeMarker=true",
                "-PharnessNativeFeatures=true"], output, args.timeout)
            result["gate"] = native_environment_result(native_output)
            result["success"] &= result["gate"]["success"]
            result["marker"] = native_marker_result(native_output, result["gate"].get("checkpoints") or [])
            result["success"] &= result["marker"]["success"]
            text = (output / "native.log").read_text(errors="replace")
            result["diagnostics"] = [line.strip() for line in text.splitlines()
                if re.search(r"\[vk-validation\]|Validation (Error|Warning)|SYNC-HAZARD-|VUID-", line)]
            result["success"] &= not result["diagnostics"]
            result["scope"] = "Minecraft-native Vulkan environment only; no Voxy LoD acceptance"
            result["voxy_integration_status"] = "BLOCKED_UNIMPLEMENTED"
            result["validation_layer_loader_evidence"] = [line.strip() for line in text.splitlines()
                if "VK_LAYER_KHRONOS_validation" in line and "Insert" in line]
            result["success"] &= bool(result["validation_layer_loader_evidence"])
            result["application_errors"] = [line.strip() for line in text.splitlines() if "/ERROR]" in line and "(Voxy)" in line]
            expected_errors = ("Minecraft is not using the OpenGL backend; Voxy's Vulkan path still ",
                               "Voxy is unsupported on your system.")
            result["unexpected_application_errors"] = [line for line in result["application_errors"]
                if not any(message in line for message in expected_errors)]
            result["success"] &= not result["unexpected_application_errors"]
            summary["stages"]["native_environment"] = result
            save()
        summary["success"] = bool(summary["stages"]) and all(r["success"] for r in summary["stages"].values())
        final_fingerprints = source_fingerprints()
        changed = sorted(name for name in fingerprints.keys() | final_fingerprints.keys()
            if fingerprints.get(name) != final_fingerprints.get(name))
        summary["changed_sources_during_run"] = changed
        summary["final_revision"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        if changed:
            summary["success"] = False
        if args.only == "required":
            summary["executed_gates_success"] = summary["success"]
            summary["required_unproven"] = ["Minecraft-native Voxy LoD draw/composite connection",
                "Live native-world independent reference pixel/depth comparison",
                "Live native-world forced arena exhaustion and multi-frame GPU lifetime recovery"]
            summary["status"] = "INCOMPLETE"
            summary["success"] = False
    except (Exception, KeyboardInterrupt) as exc:
        summary["runner_error"] = str(exc) or type(exc).__name__
    finally:
        save()
    print(f"{'PASS' if summary['success'] else 'FAIL'}: {summary_file}", flush=True)
    return 0 if summary["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
