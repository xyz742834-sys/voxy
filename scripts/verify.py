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
from pixel_oracle import (read_rgb, mismatches, top_rows_rgb, png_size, write_rgb_png,
                          read_ppm, read_ppm_gz, read_pgm16_gz)

ROOT = Path(__file__).resolve().parents[1]

# The 64-bit value McNativeComputeProbe and McNativeVkContext write and read back
# (`0x0123456789abcdefL`). Java prints it with Long.toHexString, which drops the leading zero.
# ⚠ Round-5 review B3: the gate compared `expected` to `readBack` and nothing else, so a pair
# that was jointly empty — or jointly anything — agreed. The sentinel is fixed in the source,
# so the gate requires that value.
INT64_SENTINEL = "0x123456789abcdef"

# VkFormat for 32-bit float depth, which is the only depth layout the probe interprets.
VK_FORMAT_D32_SFLOAT = 126

# The marker's geometry, as McNativeMarkerDraw declares it in source (BOX_X0/X1, BOX_Y0/Y1,
# NEAR_X1 = BOX_X0 + (BOX_X1 - BOX_X0) * 0.6, CONTROL_Y0/Y1, CELL_Y0/Y1).
#
# ⚠ Round-6 review B1: the gate required the retained crop to CONTAIN the published geometry,
# which the producer also publishes — so moving the geometry and the crop together made the
# same partial crop pass. The geometry is a compile-time constant, so the gate asserts the
# constant instead of trusting what it is told. If the source constants change, this must be
# changed deliberately alongside them.
EXPECTED_MARKER_GEOMETRY = {
    "box": [-0.98, 0.98, -0.78, 0.78],
    "nearSplitX": -0.86,
    "controlStrip": [-0.98, 0.76, -0.78, 0.72],
    "depthTestedPassCell": [-0.98, 0.70, -0.78, 0.66],
}


# The marker's three colours, as McNativeMarkerDraw declares them (MARKER_*, FAR_*, REJECTED_*).
#
# ⚠ Round-7 review B1: the geometry was pinned to its source constants but the COLOURS were
# still taken from the report, so the same shifting attack worked on them — a producer could
# publish colours its pixels happen to contain. Both are source constants; both are asserted.
EXPECTED_MARKER_COLOURS = {"markerRgb": [255, 0, 255], "farRgb": [0, 255, 255],
                           "rejectedRgb": [255, 255, 0]}

# Byte offsets of the four features Voxy injects, counted from the start of
# VkPhysicalDeviceFeatures (55 consecutive VkBool32 fields, so field index * 4).
#
# ⚠ Round-7 review B3: a RANGE check still let distinct-but-wrong offsets pass. These are fixed
# by the Vulkan structure layout, and the read-back experiment is supposed to discover exactly
# them — so the gate pins them. The measured run agrees (40 / 100 / 104 / 160). If Voxy ever
# injects a different feature, add its offset here deliberately.
VK_FEATURE_OFFSETS = {
    "drawIndirectFirstInstance": 40,        # field 10
    "vertexPipelineStoresAndAtomics": 100,  # field 25
    "fragmentStoresAndAtomics": 104,        # field 26
    "shaderInt64": 160,                     # field 40
}


def check_marker_colours(report):
    """Require the published colours to be the ones the source actually declares."""
    for key, want in EXPECTED_MARKER_COLOURS.items():
        got = report.get(key)
        if got != want:
            raise ValueError(f"the marker publishes {key}={got!r}, not the {want!r} its source"
                             f" declares")


def check_marker_geometry(geometry):
    """Require the published geometry to be the geometry the source actually declares."""
    if not isinstance(geometry, dict):
        raise ValueError("the marker draw did not publish the geometry to check against")
    for key, want in EXPECTED_MARKER_GEOMETRY.items():
        got = geometry.get(key)
        if isinstance(want, list):
            if (not isinstance(got, list) or len(got) != 4
                    or any(abs(a - b) > 1e-6 for a, b in zip(got, want))):
                raise ValueError(f"the marker geometry publishes {key}={got!r}, not the"
                                 f" {want!r} its source declares")
        else:
            if not isinstance(got, (int, float)) or abs(got - want) > 1e-6:
                raise ValueError(f"the marker geometry publishes {key}={got!r}, not the"
                                 f" {want!r} its source declares")
    extra = set(geometry) - set(EXPECTED_MARKER_GEOMETRY)
    if extra:
        raise ValueError(f"the marker geometry publishes unexpected regions {sorted(extra)};"
                         f" the gate cannot know what they mean")
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


# The harness's checkpointed lifecycle stages (LiveWorldHarness.STAGES minus "create" and
# "disconnect", which checkpoint nothing). "descend" and "ascend" are the Z-direction
# experiment: the same ground looked at straight down from two heights above it.
LIFECYCLE_STAGES = ("warmup", "turn", "travel", "return", "horizon", "edit", "remove", "resize",
                    "reload", "nether", "overworld", "descend", "ascend", "reconnect")
DESCEND_ABOVE_GROUND, ASCEND_ABOVE_GROUND = 12, 108   # LiveWorldHarness.*_ABOVE_GROUND
PLAYER_EYE_HEIGHT = 1.62                              # Minecraft's standing eye height


def native_environment_result(output, require_screenshots=True):
    """Independently reject preferences, fallback, partial observations and missing images.
    `require_screenshots=False` is for retained evidence, which keeps thumbnails, not the frames."""
    result = {"success": False, "failures": [], "scope": "Minecraft Vulkan environment; no Voxy LoD acceptance"}
    expected = set(LIFECYCLE_STAGES)
    try:
        evidence = json.loads((output / "native-result.json").read_text())
        cases = evidence["checkpoints"]
        if evidence.get("complete") is not True or evidence.get("success") is not True or evidence.get("failures") != []:
            raise ValueError("Native environment scenario did not complete cleanly")
        if len(cases) != len(LIFECYCLE_STAGES) or {c["stage"] for c in cases} != expected:
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
            if require_screenshots:
                read_rgb(output / (case["stage"] + ".png"),
                    expected_size=(renderer["colour"]["width"], renderer["colour"]["height"]), validate_only=True)
        if len(devices) != 1:
            raise ValueError("Minecraft device changed within the lifecycle run")
        result.update(success=True, checkpoints=cases, voxy_integration_status=evidence.get("voxyIntegrationStatus"))
    except (OSError, ValueError, KeyError, TypeError, zlib.error, struct.error) as exc:
        result["failures"].append(str(exc))
    return result


def source_fingerprints(root=None):
    root = ROOT if root is None else root
    names = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=root).decode().split("\0")
    return {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in names
        if name and (root / name).is_file()
        and (name.startswith(("src/", "scripts/")) or name in ("build.gradle", "gradle.properties"))}


def marker_report_checks(output, report):
    """Every marker acceptance check that needs only the report and its retained sample.

    ⚠ Round-5 review B1: `--replay-evidence` called the recount and the proof-file helper
    directly and skipped all of this, so a retained report with attempted=false,
    completed=false, timesClean=0, timesWithAProblem=3, enabled=false, drawsRecorded=0,
    depthAttached=false and closeFailures=99 still replayed as 0. The checks that do not
    need the full screenshots live here, so the stage gate and the replay run the same ones.
    """
    checks = {}
    if not report.get("enabled"):
        raise ValueError("the marker draw was not enabled")
    if not report.get("pipelineLive"):
        raise ValueError("no marker pipeline was live on Minecraft's device")
    if report.get("drawsRecorded", 0) < 1:
        raise ValueError("no draw was recorded into Minecraft's command buffer")
    if report.get("notes"):
        raise ValueError(f"the marker draw reported notes: {report['notes']}")
    if not report.get("depthAttached"):
        raise ValueError("depth was not attached, so none of the depth proof applies")
    # ⚠ The failure path used to write 0x0 as the target size, which would make every
    # recounted region degenerate. The size the draw actually saw is the only honest value.
    for field in ("targetWidth", "targetHeight"):
        if not isinstance(report.get(field), int) or report[field] < 1:
            raise ValueError(f"the marker report states {field}={report.get(field)!r},"
                             f" so nothing can be resolved against the image")


    # ⚠ Round-2 review B4: one readback says nothing about the rest of the lifecycle.

    geometry = report.get("geometry") or {}
    check_marker_geometry(geometry)
    check_marker_colours(report)
    box, split, strip = geometry["box"], geometry["nearSplitX"], geometry["controlStrip"]
    # ⚠ The authoritative proof is the readback of Minecraft's own colour image taken
    # right after the draw, not the screenshot: anything Minecraft draws afterwards (its
    # GUI, a loading overlay, post-processing) can hide the marker, so an absent marker in
    # a screenshot does not mean an absent draw. Measured: the nether checkpoint's frame
    # showed nothing while the draw had certainly run.
    rb = report.get("readback")
    if isinstance(rb, dict):
        for field, kind in (("attempted", bool), ("completed", bool), ("near", int),
                            ("far", int), ("rejectedInBox", int), ("control", int),
                            ("boxArea", int), ("controlArea", int), ("timesClean", int),
                            ("timesWithAProblem", int)):
            if field not in rb:
                raise ValueError(f"the readback does not state {field}")
            if not isinstance(rb[field], kind) or isinstance(rb[field], bool) != (kind is bool):
                raise ValueError(f"readback.{field} is {rb[field]!r}, not a {kind.__name__}")
    if not isinstance(rb, dict) or not rb.get("attempted"):
        raise ValueError("Minecraft's colour image was never read back, so the draw is"
                         " only supported by screenshots that its GUI can cover")
    if not rb.get("completed"):
        raise ValueError(f"the colour readback did not complete: {rb.get('note')}")
    # The implementation checks the relationships it can see (near and far side by side
    # in the same rows, the rejected colour present but outside those rows) and reports
    # the first problem it found. The gate requires no problem AND enough pixels: a note
    # of None with trivial counts would prove nothing.
    if rb.get("note"):
        raise ValueError(f"the colour readback rejects the draw: {rb['note']}")
    # ⚠ Round-5 review R4-L1 / B4: these counters existed and nothing read them.
    for field in ("closeFailures", "leakedPipelines", "leakBudget", "failureBudget"):
        if field not in rb:
            raise ValueError(f"the readback does not state {field}")
        if not isinstance(rb[field], int) or isinstance(rb[field], bool):
            raise ValueError(f"readback.{field} is {rb[field]!r}, not an int")
    if rb["closeFailures"]:
        raise ValueError(f"{rb['closeFailures']} readback buffer(s) could not be closed, so"
                         f" a frame's worth of memory was held for the rest of the run")
    if rb["leakedPipelines"]:
        raise ValueError(f"{rb['leakedPipelines']} marker pipeline(s) were leaked because"
                         f" their retirement could not be handed to Minecraft")
    # ⚠ Round-5 review B4: the sample has to be bound to a capture, and that capture has to
    # be one this run actually made.
    at = rb.get("sampleAtDraw")
    if not isinstance(at, int) or isinstance(at, bool) or at < 1:
        raise ValueError(f"readback.sampleAtDraw is {at!r}, so the sample is not bound to any"
                         f" capture")
    if at > report["drawsRecorded"]:
        raise ValueError(f"the sample claims to come from draw {at} but only"
                         f" {report['drawsRecorded']} were recorded")
    if (rb.get("timesClean") or 0) < 3:
        raise ValueError(f"the colour image was only read back cleanly"
                         f" {rb.get('timesClean')} time(s); the proof must hold across the run")
    if rb.get("timesWithAProblem"):
        raise ValueError(f"{rb['timesWithAProblem']} readback(s) found a problem, first:"
                         f" {rb.get('firstProblem')}")
    checks["readback"] = rb
    if rb.get("rejectedInBox"):
        raise ValueError(f"the readback places the colour depth must reject inside the"
                         f" depth-tested box: {rb}")
    if (rb.get("near") or 0) < 500 or (rb.get("far") or 0) < 500:
        raise ValueError(f"the readback found too few near/far pixels to mean anything: {rb}")
    if (rb.get("control") or 0) < 500:
        raise ValueError(f"the readback found only {rb.get('control')} rejected-colour pixels"
                         f" in the cell where the third draw must pass, so that draw is"
                         f" unproven")
    # ⚠ Round-4 review B1/B4: trusting the aggregate means the gate checks numbers the
    # implementation produced. Recount them from the raw sample it retained, with the
    # published geometry, and require agreement. A sample that is missing fails.
    checks["recount"] = recount_marker_sample(output, report, rb)
    # ⚠ Round-6 review B4: only the SELECTED orientation's pixels were retained, so the claim
    # "exactly one orientation matches the expected pattern" could only be taken on the
    # implementation's word. The rejected orientation's pixels are retained too, and this
    # verifies from them that the pattern really is absent there.
    checks["rejected_orientation"] = recount_rejected_orientation(output, report, rb)

    return checks


def native_marker_result(output, checkpoints):
    """Independently confirm the bounded native draw and its depth proof reached the frame.

    Round-1 review B4 rejected the previous version: it accepted a single far-colour pixel
    instead of the 60/40 split, passed with no depth attachment at all, and exempted up to
    three lifecycle checkpoints merely because the marker box was dark while the rest of the
    frame was bright. It also treated "no rejected colour in the box" as proof that the
    rejected draw happened, which it is not — not drawing it looks identical.

    So this version requires, per checkpoint: the near colour filling most of the left part
    of the box, the far colour filling most of the right part, the rejected colour absent
    from the box but PRESENT in its control strip (drawn with compare ALWAYS, so its absence
    means the draw never happened), and depth actually attached. A frame may only be excused
    as having no level content when the whole decoded region is dark, and at most one may be.
    """
    result = {"success": False, "failures": [],
              "scope": "bounded marker draw and depth proof in Minecraft's own Vulkan frame"}
    try:
        report = json.loads((output / "native-marker-draw.json").read_text())
        result["report"] = report
        checks = marker_report_checks(output, report)
        result.update(checks)
        box, split, strip = (report["geometry"]["box"], report["geometry"]["nearSplitX"],
                             report["geometry"]["controlStrip"])
        rb = checks["readback"]
        near_rgb = tuple(report.get("markerRgb") or ())
        far_rgb = tuple(report.get("farRgb") or ())
        rejected_rgb = tuple(report.get("rejectedRgb") or ())
        if len(near_rgb) != 3 or len(far_rgb) != 3 or len(rejected_rgb) != 3:
            raise ValueError("the marker draw did not publish all three colours")

        def near(px):
            return near_rgb[0] - 60 <= px[0] <= 255 and px[1] <= 60 and near_rgb[2] - 60 <= px[2]

        def is_far(px):
            return px[0] <= 60 and px[1] >= far_rgb[1] - 60 and px[2] >= far_rgb[2] - 60

        def is_rejected(px):
            return px[0] >= rejected_rgb[0] - 60 and px[1] >= rejected_rgb[1] - 60 and px[2] <= 60

        def to_pixels(ndc_x, ndc_y, width, height):
            """NDC in the published (final-image) orientation to pixel coordinates."""
            return (int((ndc_x + 1.0) * 0.5 * width), int((1.0 - ndc_y) * 0.5 * height))

        measured, skipped = {}, {}
        previous_draws = -1
        result["depth_proof"], result["frames_without_level_content"] = measured, skipped
        for case in checkpoints:
            stage = case["stage"]
            png = output / (stage + ".png")
            width, height = png_size(png)
            x0, y0 = to_pixels(box[0], box[1], width, height)
            x1, y1 = to_pixels(box[2], box[3], width, height)
            sx, _ = to_pixels(split, box[1], width, height)
            cx0, cy0 = to_pixels(strip[0], strip[1], width, height)
            cx1, cy1 = to_pixels(strip[2], strip[3], width, height)
            bottom = max(y1, cy1)
            rows, decoded = top_rows_rgb(png, bottom + 2)
            if decoded != (width, height) or len(rows) <= bottom:
                raise ValueError(f"{stage}: decoded {len(rows)} rows of {decoded}, need {bottom + 2}")

            def count(predicate, ax0, ay0, ax1, ay1):
                hits = 0
                for y in range(min(ay0, ay1), max(ay0, ay1)):
                    row = rows[y]
                    for x in range(min(ax0, ax1), max(ax0, ax1)):
                        if predicate(row[x]):
                            hits += 1
                return hits

            def area(ax0, ay0, ax1, ay1):
                return max(1, abs(ax1 - ax0) * abs(ay1 - ay0))

            drawn = case.get("markerDraws")
            covered = case.get("frameCoveredByGui")
            if not isinstance(drawn, int) or not isinstance(covered, bool):
                raise ValueError(f"{stage}: the checkpoint does not say how many marker draws had"
                                 f" been recorded and whether Minecraft's GUI covered the frame,"
                                 f" so the frame cannot be judged")
            near_hits = count(near, x0, y0, sx, y1)
            far_hits = count(is_far, sx, y0, x1, y1)
            rejected_in_box = count(is_rejected, x0, y0, x1, y1)
            control_hits = count(is_rejected, cx0, cy0, cx1, cy1)
            measured[stage] = {"near": near_hits, "nearArea": area(x0, y0, sx, y1),
                               "far": far_hits, "farArea": area(sx, y0, x1, y1),
                               "rejectedInBox": rejected_in_box,
                               "control": control_hits, "controlArea": area(cx0, cy0, cx1, cy1),
                               "markerDraws": drawn, "frameCoveredByGui": covered}
            # Two implementation-sourced reasons a frame can say nothing either way: the draw
            # never ran for it, or Minecraft's own GUI covered it (Voxy records at the end of
            # level rendering, so a loading overlay during a dimension change paints over the
            # marker). Neither is inferred from how the image looks — the checkpoint states
            # both. A frame can also simply show nothing because Minecraft composited over
            # it — which is why the readback above, not these screenshots, carries the proof.
            # ⚠ Round-2 review B4: an explicit depth failure must never be excused, whatever
            # the implementation says about the frame.
            if rejected_in_box:
                raise ValueError(f"{stage}: {rejected_in_box} pixels of the colour depth must"
                                 f" reject are in the box of a captured frame")
            if drawn <= previous_draws or covered or (near_hits == 0 and far_hits == 0
                                                       and control_hits == 0):
                skipped[stage] = {"markerDraws": drawn, "previous": previous_draws,
                                  "frameCoveredByGui": covered}
                continue
            previous_draws = drawn
            if control_hits < area(cx0, cy0, cx1, cy1) // 2:
                raise ValueError(f"{stage}: the control strip holds only {control_hits} of"
                                 f" {area(cx0, cy0, cx1, cy1)} rejected-colour pixels, so the"
                                 f" third draw cannot be shown to have happened at all")
            if near_hits < area(x0, y0, sx, y1) // 2:
                raise ValueError(f"{stage}: the near quad covers only {near_hits} of"
                                 f" {area(x0, y0, sx, y1)} pixels of its half of the box")
            if far_hits < area(sx, y0, x1, y1) // 2:
                raise ValueError(f"{stage}: the farther base quad covers only {far_hits} of"
                                 f" {area(sx, y0, x1, y1)} pixels of its half of the box, so the"
                                 f" near quad is not being depth-tested against anything")
        # Supporting evidence, bounded: Minecraft can cover any individual frame, so a few
        # may carry nothing, but if most do then the draw stopped happening and the readback
        # (taken once) would not have noticed.
        if len(skipped) > 3:
            raise ValueError(f"{len(skipped)} of {len(measured)} captured frames carried no"
                             f" marker; at most three may: {skipped}")
        if len(measured) - len(skipped) < 8:
            raise ValueError(f"only {len(measured) - len(skipped)} captured frames carried the"
                             f" marker")
        result.update(success=True)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        result["failures"].append(str(exc))
    return result


def recount_rejected_orientation(output, report, rb):
    """Verify from pixels that the orientation the implementation rejected really fails.

    The composited frame is y-ambiguous, so the implementation measures both orientations and
    requires exactly one to satisfy the expected pattern. That claim is only worth something if
    someone else can check it, which needs the rejected orientation's pixels — round 6's B4
    residual. This reads them and requires the pattern to be ABSENT.
    """
    name = rb.get("rejectedOrientationSample")
    rect = rb.get("rejectedOrientationRect")
    if not name:
        raise ValueError("the rejected orientation's pixels were not retained, so the claim"
                         " that only one orientation matches cannot be checked")
    if not (isinstance(rect, list) and len(rect) == 4):
        raise ValueError("the readback does not say where the rejected orientation's sample"
                         " came from")
    if not isinstance(rb.get("rejectedOrientationFlipped"), bool):
        raise ValueError("the readback does not say which orientation it rejected")
    if rb["rejectedOrientationFlipped"] == bool(rb.get("flipped")):
        raise ValueError("the rejected orientation is the same as the selected one")
    prefix, suffix = "native-marker-rejected-", ".ppm.gz"
    stem = name[len(prefix):-len(suffix)] if name.startswith(prefix) and name.endswith(suffix) else ""
    if not stem.isdigit() or int(stem) != rb.get("sampleAtDraw"):
        raise ValueError(f"the rejected-orientation sample is named for draw {stem!r} but the"
                         f" readback says it was taken at {rb.get('sampleAtDraw')!r}")
    path = output / name
    if not path.is_file():
        raise ValueError(f"the retained rejected-orientation sample {name} is missing")
    rows, (width, height) = read_ppm_gz(path)
    if width != rect[2] - rect[0] or height != rect[3] - rect[1]:
        raise ValueError(f"the rejected-orientation sample is {width}x{height} but its rect"
                         f" says {rect[2] - rect[0]}x{rect[3] - rect[1]}")
    geometry = report["geometry"]
    full_w, full_h = report["targetWidth"], report["targetHeight"]
    flipped = rb["rejectedOrientationFlipped"]

    def region(label, ax, ay, bx, by):
        """Resolve a region into the rejected crop, refusing to shrink it to fit.

        ⚠ Round-7 review B4: this clamped, unlike the selected-crop recount. Replacing the
        opposite sample with a gzipped 1x1 black image and setting its rect to [0,0,1,1] made
        every expected region fall outside the crop; the empty loops returned zero and the gate
        read that as "the pattern is absent". Absence requires covering the whole region, so a
        crop that does not contain it fails instead.
        """
        x0 = int((min(ax, bx) + 1.0) * 0.5 * full_w) - rect[0]
        x1 = int((max(ax, bx) + 1.0) * 0.5 * full_w) - rect[0]
        top, bottom = max(ay, by), min(ay, by)
        if flipped:
            y0 = int((1.0 + bottom) * 0.5 * full_h) - rect[1]
            y1 = int((1.0 + top) * 0.5 * full_h) - rect[1]
        else:
            y0 = int((1.0 - top) * 0.5 * full_h) - rect[1]
            y1 = int((1.0 - bottom) * 0.5 * full_h) - rect[1]
        if x0 < 0 or y0 < 0 or x1 > width or y1 > height:
            raise ValueError(f"the rejected-orientation sample does not contain the whole"
                             f" {label} region: it spans ({x0},{y0})-({x1},{y1}) of a"
                             f" {width}x{height} crop, so its emptiness proves nothing")
        if x1 <= x0 or y1 <= y0:
            raise ValueError(f"the {label} region resolves to an empty rectangle in the"
                             f" rejected-orientation sample")
        return x0, y0, x1, y1

    def tally(area, want):
        x0, y0, x1, y1 = area
        hits = 0
        for y in range(y0, y1):
            row = rows[y]
            for x in range(x0, x1):
                px = row[x]
                if all((px[i] >= 200) if want[i] >= 200 else (px[i] <= 60) for i in range(3)):
                    hits += 1
        return hits, max(1, (x1 - x0) * (y1 - y0))

    box, split = geometry["box"], geometry["nearSplitX"]
    cell, strip = geometry["depthTestedPassCell"], geometry["controlStrip"]
    near_rgb, far_rgb = tuple(report["markerRgb"]), tuple(report["farRgb"])
    rejected_rgb = tuple(report["rejectedRgb"])
    near_hits, near_area = tally(region("near", box[0], box[1], split, box[3]), near_rgb)
    far_hits, far_area = tally(region("far", split, box[1], box[2], box[3]), far_rgb)
    cell_hits, cell_area = tally(region("cell", box[0], cell[1], box[2], cell[3]), rejected_rgb)
    strip_hits, strip_area = tally(region("control strip", box[0], strip[1], box[2], strip[3]),
                                   rejected_rgb)
    box_rejected, _ = tally(region("box", box[0], box[1], box[2], box[3]), rejected_rgb)
    out = {"sample": name, "flipped": flipped, "near": near_hits, "nearArea": near_area,
           "far": far_hits, "farArea": far_area, "cell": cell_hits, "cellArea": cell_area,
           "controlStrip": strip_hits, "controlStripArea": strip_area,
           "rejectedInBox": box_rejected}
    # The depth-failure rule is orientation-free: the rejected colour must be absent from the
    # box whichever orientation is real.
    if box_rejected:
        raise ValueError(f"the rejected orientation holds {box_rejected} pixels of the colour"
                         f" depth must reject inside the box, so depth is not working in"
                         f" whichever orientation is the real one: {out}")
    dense = [near_hits * 10 >= near_area * 8, far_hits * 10 >= far_area * 8,
             cell_hits * 10 >= cell_area * 8, strip_hits * 10 >= strip_area * 8]
    if all(dense):
        raise ValueError(f"the rejected orientation ALSO satisfies the expected pattern, so the"
                         f" image does not say which orientation the draw produced: {out}")
    out["satisfiedRegions"] = sum(dense)
    return out


def recount_marker_sample(output, report, rb):
    """Recount the marker colours from the retained raw sample, independently of the report."""
    name = rb.get("sampleFile")
    if not name:
        raise ValueError("the readback retained no raw colour sample, so its counts cannot be"
                         " checked against anything")
    sample = output / name
    if not sample.is_file():
        raise ValueError(f"the retained raw sample {name} is missing")
    rect = rb.get("sampleRect")
    if not (isinstance(rect, list) and len(rect) == 4):
        raise ValueError("the readback does not say where its sample came from")
    # Round-8 review B1: the selected sample's name was never compared with the capture
    # count, so a report pointing at another capture's crop passed. Bind them.
    prefix, suffix = "native-marker-sample-", ".ppm"
    stem = name[len(prefix):-len(suffix)] if name.startswith(prefix) and name.endswith(suffix) else ""
    if not stem.isdigit() or int(stem) != rb.get("sampleAtDraw"):
        raise ValueError(f"the retained sample is named for draw {stem!r} but the readback"
                         f" says it was taken at {rb.get('sampleAtDraw')!r}")
    rows, (width, height) = read_ppm(sample)
    if width != rect[2] - rect[0] or height != rect[3] - rect[1]:
        raise ValueError(f"the raw sample is {width}x{height} but its rect says"
                         f" {rect[2] - rect[0]}x{rect[3] - rect[1]}")
    geometry = report.get("geometry") or {}
    box, strip = geometry.get("box"), geometry.get("controlStrip")
    cell = geometry.get("depthTestedPassCell")
    split = geometry.get("nearSplitX")
    flipped = bool(rb.get("flipped"))
    # The sample is a crop of the full image, so the geometry is resolved in full-image pixels
    # and then shifted into the crop. Nothing here comes from the implementation's counts.
    full_w = report.get("targetWidth")
    full_h = report.get("targetHeight")
    if not isinstance(full_w, int) or not isinstance(full_h, int):
        raise ValueError("the marker report does not state the target size")

    def to_rect(label, ax, ay, bx, by):
        """Resolve a region into crop coordinates, refusing to shrink it to fit.

        ⚠ Round-5 review B1: this used to clamp each region to the producer's own crop and
        then use the clamped area as the density denominator. Removing the leftmost 20 columns
        of the retained sample and adjusting `sampleRect` and two counts to match gave
        near=10260/10260 — 100% dense, with the omitted columns free to be yellow throughout.
        A crop that does not contain the whole region cannot prove anything about the region,
        so a region that would need clamping fails instead.
        """
        x0 = int((min(ax, bx) + 1.0) * 0.5 * full_w) - rect[0]
        x1 = int((max(ax, bx) + 1.0) * 0.5 * full_w) - rect[0]
        top, bottom = max(ay, by), min(ay, by)
        if flipped:
            y0 = int((1.0 + bottom) * 0.5 * full_h) - rect[1]
            y1 = int((1.0 + top) * 0.5 * full_h) - rect[1]
        else:
            y0 = int((1.0 - top) * 0.5 * full_h) - rect[1]
            y1 = int((1.0 - bottom) * 0.5 * full_h) - rect[1]
        if x0 < 0 or y0 < 0 or x1 > width or y1 > height:
            raise ValueError(f"the retained sample does not contain the whole {label} region:"
                             f" it spans ({x0},{y0})-({x1},{y1}) of a {width}x{height} crop,"
                             f" so its density says nothing about the part that is missing")
        if x1 <= x0 or y1 <= y0:
            raise ValueError(f"the {label} region resolves to an empty rectangle")
        return x0, y0, x1, y1

    near_rgb = tuple(report["markerRgb"])
    far_rgb = tuple(report["farRgb"])
    rejected_rgb = tuple(report["rejectedRgb"])

    def matches(px, want):
        # ⚠ Round-5 review B4: this was `abs(px - want) <= 60`, which accepts 195-199 where
        # the Java side requires >= 200 (isNear/isFar/isRejected). A producer failure landing
        # in that band could look acceptable to the recount. Use the implementation's own rule.
        for i in range(3):
            if want[i] >= 200:
                if px[i] < 200:
                    return False
            elif px[i] > 60:
                return False
        return True

    def tally(area, want):
        x0, y0, x1, y1 = area
        hits = 0
        for y in range(y0, y1):
            row = rows[y]
            for x in range(x0, x1):
                if matches(row[x], want):
                    hits += 1
        return hits, max(1, (x1 - x0) * (y1 - y0))

    near_hits, near_area = tally(to_rect("near", box[0], box[1], split, box[3]), near_rgb)
    far_hits, far_area = tally(to_rect("far", split, box[1], box[2], box[3]), far_rgb)
    cell_hits, cell_area = tally(to_rect("cell", box[0], cell[1], box[2], cell[3]), rejected_rgb)
    strip_hits, strip_area = tally(to_rect("control strip", box[0], strip[1], box[2], strip[3]),
                                   rejected_rgb)
    box_rect = to_rect("box", box[0], box[1], box[2], box[3])
    box_rejected, box_area = tally(box_rect, rejected_rgb)
    # ⚠ Round-5 review B1: the report's own boxArea/controlArea were never checked against the
    # region the recount actually measured, so a shrunken crop could not be detected from the
    # numbers either. They must agree with the geometry resolved here.
    for label, theirs, ours in (("boxArea", rb.get("boxArea"), box_area),
                                ("controlArea", rb.get("controlArea"), cell_area)):
        if theirs != ours:
            raise ValueError(f"the readback reports {label}={theirs} but the published geometry"
                             f" resolves to {ours} pixels in the retained sample")
    out = {"sample": name, "flipped": flipped, "near": near_hits, "nearArea": near_area,
           "far": far_hits, "farArea": far_area, "cell": cell_hits, "cellArea": cell_area,
           "controlStrip": strip_hits, "controlStripArea": strip_area,
           "rejectedInBox": box_rejected, "sampleAtDraw": rb.get("sampleAtDraw")}
    if box_rejected:
        raise ValueError(f"recounting the retained sample finds {box_rejected} pixels of the"
                         f" colour depth must reject inside the box: {out}")
    for label, hits, area in (("near", near_hits, near_area), ("far", far_hits, far_area),
                              ("cell", cell_hits, cell_area),
                              ("control strip", strip_hits, strip_area)):
        if hits * 10 < area * 8:
            raise ValueError(f"recounting the retained sample finds the {label} region only"
                             f" {hits}/{area} filled, so the third draw is unproven"
                             if label in ("cell", "control strip") else
                             f"recounting the retained sample finds the {label} region only"
                             f" {hits}/{area} filled: {out}")
    # And the implementation's own numbers must match what the sample actually holds.
    for label, theirs, ours in (("near", rb.get("near"), near_hits),
                                ("far", rb.get("far"), far_hits),
                                ("cell", rb.get("control"), cell_hits)):
        if theirs != ours:
            raise ValueError(f"the readback reported {label}={theirs} but the retained sample"
                             f" holds {ours}; the aggregate does not match the pixels")
    return out


# The ladder's rung depths, band and palette, derived the same way the source does
# (McNativeDepthLadder: 2^-(16 - 2i), BAND_*, PALETTE).
# Pinned for the same reason as the marker geometry: a producer-published ladder is not a
# reference the gate can check anything against.
# Why these depths: Minecraft clears depth to 0.0 and builds its projection with near/far
# swapped (reverse-Z), so a surface at distance d has depth of order near/d. The first ladder
# was linear in [0.0625, 0.9375] and could not tell a cleared attachment from scene depth.
LADDER_RUNGS = 8
EXPECTED_LADDER_DEPTHS = [2.0 ** -(16 - 2 * i) for i in range(LADDER_RUNGS)]
EXPECTED_LADDER_BAND = [-0.6, 0.36, 0.6, 0.2]
# Palette levels per channel: 0, 0.5, 1. Index 0 = BASE (ALWAYS), 1 = LOW (GREATER at z_0),
# 2.. = rung 0..7 (LESS at z_i, drawn ascending so the surviving colour is max{i: z_i < d}).
# LOW (index 1) was grey (0.5, 0.5, 0.5) until 2026-10-10: Minecraft's fogged terrain quantises
# to the same grey and made a rejected-orientation crop look drawn. Now dark purple.
EXPECTED_LADDER_PALETTE = [[1.0, 1.0, 1.0], [0.5, 0.0, 0.5], [1.0, 0.0, 1.0], [0.0, 1.0, 1.0],
                           [1.0, 1.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0],
                           [1.0, 0.5, 0.0], [0.5, 0.0, 1.0], [1.0, 0.5, 1.0]]
# Index 10 = CLEAR (2026-10-10): Voxy's GREATER_OR_EQUAL at depth 0, drawn after LOW and before the
# rungs, so it survives exactly where Minecraft's depth is the clear value 0 — Minecraft drew
# nothing there. LOW is then 0 < d < z_0. Voxy's GL path composes only on such pixels.
LADDER_BASE, LADDER_LOW, LADDER_RUNG0 = 0, 1, 2
LADDER_CLEAR = LADDER_RUNG0 + 8
LADDER_OTHER = len(EXPECTED_LADDER_PALETTE)


def finite_number(value):
    """A real, finite number: not a bool, not NaN, not infinity.

    Round-8 review R8-LADDER-GATE: NaN passed every tolerance and floor comparison, so NaN
    depths, bands, bounds and fills made the pins and the aggregate checks vacuous.
    """
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(value))


def finite_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def ladder_level(v):
    """The implementation's three-level quantiser (McNativeDepthLadder.level)."""
    if v <= 64:
        return 0
    if 96 <= v <= 160:
        return 1
    if v >= 192:
        return 2
    return -1


def ladder_classify(px):
    """Palette index of a pixel, or LADDER_OTHER (McNativeDepthLadder.classify)."""
    levels = tuple(ladder_level(c) for c in px[:3])
    if -1 in levels:
        return LADDER_OTHER
    for i, colour in enumerate(EXPECTED_LADDER_PALETTE):
        if tuple(round(c * 2) for c in colour) == levels:
            return i
    return LADDER_OTHER


def ladder_band_rect(full_w, full_h, flipped):
    """The band in frame pixels, in the given orientation (McNativeDepthLadder.bandRect).

    Rasterisation covers pixel x when x + 0.5 lies in [left, right), so an edge at e pixels
    starts coverage at ceil(e - 0.5). Truncating instead put one uncovered column inside the
    band and every sample failed with 76 "other" pixels (measured, 2026-10-07).
    """
    band = EXPECTED_LADDER_BAND

    def covered(edge):
        return math.ceil(edge - 0.5)

    x0 = covered((band[0] + 1.0) * 0.5 * full_w)
    x1 = covered((band[2] + 1.0) * 0.5 * full_w)
    top, bottom = max(band[1], band[3]), min(band[1], band[3])
    if flipped:
        y0 = covered((1.0 + bottom) * 0.5 * full_h)
        y1 = covered((1.0 + top) * 0.5 * full_h)
    else:
        y0 = covered((1.0 - top) * 0.5 * full_h)
        y1 = covered((1.0 - bottom) * 0.5 * full_h)
    return [max(0, x0), max(0, y0), min(full_w, x1), min(full_h, y1)]


def device_handle_of(value, name, field):
    """A device handle that is actually a handle: hex in the probe files, int in checkpoints.

    Zero is not one (round-5 review B3: all-zero handles agreed with each other and passed).
    """
    handle = None
    if isinstance(value, int) and not isinstance(value, bool):
        handle = value
    elif isinstance(value, str):
        try:
            handle = int(value, 16) if value.lower().startswith("0x") else int(value)
        except ValueError:
            handle = None
    if handle is None:
        raise ValueError(f"{name}.{field} is {value!r}, which is not a device handle")
    if handle == 0:
        raise ValueError(f"{name}.{field} is a null device handle")
    return handle


LADDER_FRAME_SCALE = 4
VK_COMPARE_OP_LESS, VK_COMPARE_OP_GREATER, VK_COMPARE_OP_GREATER_OR_EQUAL, VK_COMPARE_OP_ALWAYS = 1, 4, 6, 7
# The coexistence quad (McNativeDepthLadder.COEXIST_*): Voxy's compare op (GREATER_OR_EQUAL,
# reverse-Z) at z* = the depth of rung COEXIST_RUNG, in a colour outside the ladder palette.
# With z* equal to a rung value no bracket straddles it: low and rungs below COEXIST_RUNG must
# show the quad (d <= z*), rungs at or above it must not (d > z*).
COEXIST_RUNG = 4
COEXIST_RGB = [0.5, 1.0, 0.5]
LADDER_COEXIST_LOG = re.compile(
    r"depth ladder coexist at draw (\d+) present=(\d+) absent=(\d+) other=(\d+) expectedPass=(\d+)"
    r" expectedFail=(\d+) absentWherePass=(\d+) presentWhereFail=(\d+) unchanged=(\d+)")
LADDER_SAMPLE_LOG = re.compile(r"depth ladder sample at draw (\d+) ")
LADDER_SAMPLE_LOG_DETAIL = re.compile(
    r"depth ladder sample at draw (\d+) flipped=(true|false) counts=\[anomaly=(\d+) low=(\d+)"
    r" clear=(\d+) r0=(\d+) r1=(\d+) r2=(\d+) r3=(\d+) r4=(\d+) r5=(\d+) r6=(\d+) r7=(\d+) other=(\d+)\]"
    r" stage=(\S*) camera=\[(\S+) (\S+) (\S+) (\S+) (\S+)\]")
HARNESS_STAGE_LOG = re.compile(r"\[voxy-harness\] stage=(\w+)")
# The REQUEST line is written when the frame is captured; the sample line when the GPU
# callback runs, which can be after the harness moved on (measured: a sample requested in
# `return` was logged under `edit`). The chronology is anchored to the request line.
LADDER_SAMPLE_REQUEST = re.compile(
    r"depth ladder sample requested at draw (\d+) stage=(\S*) camera=\[(\S+) (\S+) (\S+) (\S+) (\S+)\]")
LADDER_SAMPLE_OR_STAGE = re.compile(
    r"\[voxy-harness\] stage=(\w+)|depth ladder sample requested at draw (\d+) ")


LADDER_TERRAIN_LOAD, LADDER_REAL_LOAD, LADDER_HIER_LOAD = "terrainLoad", "realLoad", "hierLoad"
LADDER_HORIZON_STAGE = "horizon"


def ladder_expected_consumer(terrain, real, index, hier=False, horizon=False):
    """McNativeDepthLadder.assignConsumer: which experiment a sample is handed to. Outside the
    horizon stage samples rotate over the enabled experiments (terrain, real, hierarchical);
    inside it over the enabled real-world ones (real, hierarchical). `index` counts the samples
    handed out under the same branch."""
    if horizon and (real or hier):
        names = [n for n, on in ((LADDER_REAL_LOAD, real), (LADDER_HIER_LOAD, hier)) if on]
    else:
        names = [n for n, on in ((LADDER_TERRAIN_LOAD, terrain), (LADDER_REAL_LOAD, real),
                                 (LADDER_HIER_LOAD, hier)) if on]
    return names[index % len(names)] if names else None


def ladder_expected_consumers(terrain, real, hier, stages):
    """The hand-off for a whole sample list, from each sample's published stage."""
    out, general, horizon_count = [], 0, 0
    for stage in stages:
        horizon = stage == LADDER_HORIZON_STAGE and (real or hier)
        consumer = ladder_expected_consumer(terrain, real, horizon_count if horizon else general,
                                            hier, horizon)
        if consumer is not None:
            if horizon:
                horizon_count += 1
            else:
                general += 1
        out.append(consumer)
    return out


def ladder_report_checks(output, report, expected_device=None, expected_extents=None,
                         retained_crops=None, log_text=None, require_coexist=False):
    """Gate the per-pixel depth ladder: what Minecraft's own depth test says, pixel by pixel.

    Every threshold is drawn over the SAME band with depth writes off, LESS rungs ascending
    after an ALWAYS base and a GREATER positive control, so each pixel's final colour encodes
    the bracket its depth falls in. This measures VALUES per pixel, never a convention, never a
    band-wide bound (round-8 review R8-LADDER-BOUND / R8-LADDER-MECHANISM).

    Shared by the stage gate and --replay-evidence, like terrain_report_checks.
    """
    checks = {}
    for field, kind in (("enabled", bool), ("attempted", bool), ("completed", bool),
                        ("drawsRecorded", int), ("rungs", int), ("depthWritesEnabled", bool),
                        ("zConventionMeasuredHere", bool), ("rungDepths", list),
                        ("band", list), ("palette", list), ("samples", list),
                        ("problems", int), ("closeFailures", int), ("leakedPipelines", int),
                        ("deviceDiverged", bool), ("notes", list),
                        ("terrainProbeEnabled", bool), ("terrainDrawsRecorded", int),
                        ("markerDrawEnabled", bool), ("markerDrawsRecorded", int),
                        ("terrainLoadEnabled", bool), ("terrainLoadDrawsRecorded", int),
                        ("realLoadEnabled", bool), ("realLoadDrawsRecorded", int),
                        ("hierLoadEnabled", bool), ("hierLoadDrawsRecorded", int),
                        ("device", str)):
        if field not in report:
            raise ValueError(f"the depth ladder does not state {field}")
        value = report[field]
        if kind is bool:
            if not isinstance(value, bool):
                raise ValueError(f"ladder.{field} is {value!r}, not a bool")
        elif kind is int:
            if not finite_int(value):
                raise ValueError(f"ladder.{field} is {value!r}, not an int")
        elif not isinstance(value, kind):
            raise ValueError(f"ladder.{field} is {value!r}, not a {kind.__name__}")
    for stray in ("reversedZ", "lowerBound", "upperBound", "zConvention"):
        if stray in report:
            raise ValueError(f"the depth ladder publishes {stray}, which it cannot measure")
    if not report["enabled"]:
        raise ValueError("the depth ladder was not enabled")
    if report["depthWritesEnabled"]:
        raise ValueError("the ladder enabled depth writes, so it measured its own depth rather"
                         " than Minecraft's")
    if report["zConventionMeasuredHere"]:
        raise ValueError("the ladder claims to have measured the Z convention, which brackets"
                         " of depth values cannot establish")
    # The terrain probe CLEARS Minecraft's depth and the marker WRITES its own into it. A run
    # where either was enabled or recorded anything measured something else.
    if report["terrainProbeEnabled"] or report["terrainDrawsRecorded"]:
        raise ValueError(f"the terrain probe was enabled={report['terrainProbeEnabled']} and"
                         f" recorded {report['terrainDrawsRecorded']} draw(s) in the same run;"
                         f" it clears Minecraft's depth, so the ladder did not measure it")
    if report["markerDrawEnabled"] or report["markerDrawsRecorded"]:
        raise ValueError(f"the marker draw was enabled={report['markerDrawEnabled']} and"
                         f" recorded {report['markerDrawsRecorded']} draw(s) in the same run;"
                         f" it writes depth, so the ladder's frame was not Minecraft's alone")
    # The terrain-LOAD probe writes Voxy's depth into Minecraft's attachment, but only after
    # this probe's readbacks of the same frame; terrain_load_checks reconciles its pass count
    # with the sample count. Off, it must have recorded nothing.
    if not report["terrainLoadEnabled"] and report["terrainLoadDrawsRecorded"]:
        raise ValueError(f"the terrain-LOAD probe was off but recorded"
                         f" {report['terrainLoadDrawsRecorded']} pass(es)")
    if not report["hierLoadEnabled"] and report["hierLoadDrawsRecorded"]:
        raise ValueError(f"the hierarchical-LOAD probe was off but recorded"
                         f" {report['hierLoadDrawsRecorded']} pass(es)")
    if not report["realLoadEnabled"] and report["realLoadDrawsRecorded"]:
        raise ValueError(f"the real-LOAD probe was off but recorded"
                         f" {report['realLoadDrawsRecorded']} pass(es)")
    # Each sampled frame is handed to at most ONE experiment that writes Minecraft's depth after
    # the ladder's readbacks (both would otherwise see each other's depth). The rule is fixed:
    # both on -> alternate starting with terrain-LOAD; one on -> that one; none -> nobody.
    hier_on = report.get("hierLoadEnabled") is True
    expected = ladder_expected_consumers(
        report["terrainLoadEnabled"], report["realLoadEnabled"], hier_on,
        [s.get("stage") if isinstance(s, dict) else None for s in report["samples"]])
    for index, sample in enumerate(report["samples"]):
        if not isinstance(sample, dict):
            continue
        want = expected[index]
        if sample.get("experiment", "missing") != want:
            raise ValueError(f"ladder sample {index} (draw {sample.get('at')}) was handed to"
                             f" {sample.get('experiment', 'missing')!r}, not {want!r} as the"
                             f" hand-off rule requires")
    if report["notes"]:
        raise ValueError(f"the depth ladder reported notes: {report['notes']}")
    if report["problems"] or report["closeFailures"] or report["leakedPipelines"]:
        raise ValueError(f"the ladder reports problems={report['problems']},"
                         f" closeFailures={report['closeFailures']},"
                         f" leakedPipelines={report['leakedPipelines']}")
    if report["deviceDiverged"]:
        raise ValueError("Minecraft's device diverged during the ladder run")
    if report["rungs"] != LADDER_RUNGS:
        raise ValueError(f"the ladder has {report['rungs']} rungs, not {LADDER_RUNGS}")
    if not report["attempted"] or not report["completed"]:
        raise ValueError("the ladder never completed a measurement")
    if report["drawsRecorded"] < 1:
        raise ValueError("no ladder draw was recorded into Minecraft's command buffer")
    handle = device_handle_of(report["device"], "ladder", "device")
    if expected_device is not None and handle != expected_device:
        raise ValueError(f"the ladder names device {hex(handle)} but its run's lifecycle"
                         f" checkpoints saw {hex(expected_device)}")
    checks["device"] = handle
    depths = report["rungDepths"]
    if len(depths) != LADDER_RUNGS or any(
            not finite_number(a) or abs(a - b) > 1e-9 + 1e-6 * abs(b)
            for a, b in zip(depths, EXPECTED_LADDER_DEPTHS)):
        raise ValueError(f"the ladder publishes depths {depths}, not the"
                         f" {EXPECTED_LADDER_DEPTHS} its source lays out")
    band = report["band"]
    if len(band) != 4 or any(not finite_number(a) or abs(a - b) > 1e-6
                             for a, b in zip(band, EXPECTED_LADDER_BAND)):
        raise ValueError(f"the ladder publishes band={band}, not the {EXPECTED_LADDER_BAND}"
                         f" its source lays out; a published band is not a reference")
    palette = report["palette"]
    if (len(palette) != len(EXPECTED_LADDER_PALETTE)
            or any(not isinstance(c, list) or len(c) != 3
                   or any(not finite_number(a) or abs(a - b) > 1e-6 for a, b in zip(c, want))
                   for c, want in zip(palette, EXPECTED_LADDER_PALETTE))):
        raise ValueError(f"the ladder publishes a palette that is not the one its source"
                         f" lays out: {palette}")
    # ⚠ Round-11 review R10-CREATE-TEST: the depth state is read back from the create-info
    # after the creator returns and published; a ladder whose pipelines were created with
    # any other state is not a measurement of Minecraft's depth.
    states = report.get("pipelineStates")
    want_states = [[VK_COMPARE_OP_LESS, 1, 0], [VK_COMPARE_OP_ALWAYS, 1, 0],
                   [VK_COMPARE_OP_GREATER, 1, 0], [VK_COMPARE_OP_GREATER_OR_EQUAL, 1, 0]]
    if states != want_states:
        raise ValueError(f"the ladder published post-creation depth states {states!r},"
                         f" not LESS/ALWAYS/GREATER/GREATER_OR_EQUAL with the test on and"
                         f" writes off {want_states}")
    if report.get("frameScale") != LADDER_FRAME_SCALE:
        raise ValueError(f"the ladder publishes frameScale={report.get('frameScale')!r}, not"
                         f" the {LADDER_FRAME_SCALE} its source lays out")
    samples = report["samples"]
    if not samples:
        raise ValueError("the ladder retained no sample, so there is nothing to recount")
    # ⚠ Round-9 review R8-LADDER-GATE: the gate iterated only report.samples, so a report
    # listing one of eighteen retained samples passed, even with an omitted crop corrupted.
    # Every retained crop must belong to a listed sample, and every sample the launch logged
    # must be listed.
    if retained_crops is not None:
        referenced = set()
        for sample in samples:
            if isinstance(sample, dict):
                referenced.update(str(sample.get(k)) for k in ("file", "rejectedFile",
                                                              "frameFile"))
        for entry in report.get("coexist") or []:
            if isinstance(entry, dict):
                referenced.add(str(entry.get("file")))
                referenced.add(str(entry.get("frameFile")))
        stray = sorted(set(retained_crops) - referenced)
        if stray:
            raise ValueError(f"{len(stray)} retained ladder crop(s) belong to no listed sample,"
                             f" so the report omits measurements: {stray[:6]}")
    if log_text is not None:
        logged = sorted(int(m) for m in LADDER_SAMPLE_LOG.findall(log_text))
        listed = sorted(s.get("at") for s in samples if isinstance(s, dict))
        if logged != listed:
            raise ValueError(f"the ladder launch logged samples at draws {logged} but the"
                             f" report lists {listed}")
        # ⚠ Round-10 review R10-LOG-DETAILS: only the draw numbers were reconciled, so the
        # log's orientation and counts could drift from the report. Each logged line must
        # state the orientation and the eleven counts the report publishes for that draw.
        details = {int(m[0]): m[1:] for m in LADDER_SAMPLE_LOG_DETAIL.findall(log_text)}
        # ⚠ Round-13 review R13-Z-BINDING: the report's stage labels and cameras were trusted,
        # so swapping them (report only) reversed the Z direction with every image and the
        # log unchanged. The log is the retained chronology: each sample line states its
        # stage and camera, and the harness's own "stage=" lines say which stage was current
        # when each sample was logged. All three must agree with the report.
        current, stage_of_draw = None, {}
        for m in LADDER_SAMPLE_OR_STAGE.finditer(log_text):
            if m.group(1):
                current = m.group(1)
            else:
                if int(m.group(2)) in stage_of_draw:
                    raise ValueError(f"the ladder log requests draw {m.group(2)} twice")
                stage_of_draw[int(m.group(2))] = current
        requests = {int(m[0]): m[1:] for m in LADDER_SAMPLE_REQUEST.findall(log_text)}
        if sorted(requests) != listed:
            raise ValueError(f"the ladder log holds complete request lines (draw, stage, camera)"
                             f" for draws {sorted(requests)} but the report lists {listed}")
        for sample in samples:
            if not isinstance(sample, dict) or not finite_int(sample.get("at")):
                continue
            line = details.get(sample["at"])
            if line is None:
                raise ValueError(f"the ladder log line for draw {sample['at']} does not state"
                                 f" its orientation, counts, stage and camera")
            # the sample line (callback time) must repeat what the request line (capture
            # time) said about the stage and camera; the stage of record is the request's
            request = requests[sample["at"]]
            if list(line[13:19]) != list(request):
                raise ValueError(f"the ladder log's request for draw {sample['at']} says"
                                 f" stage/camera {list(request)} but its sample line says"
                                 f" {list(line[13:19])}")
            counts = sample.get("counts") or {}
            want = [str(sample.get("flipped")).lower(), str(counts.get("anomaly")),
                    str(counts.get("low")), str(counts.get("clear"))] \
                   + [str(v) for v in (counts.get("rungs") or [])] + [str(counts.get("other"))]
            if list(line[:13]) != want:
                raise ValueError(f"the ladder log for draw {sample['at']} says"
                                 f" flipped/counts {list(line[:13])} but the report says {want}")
            if line[13] != str(sample.get("stage")):
                raise ValueError(f"the ladder log for draw {sample['at']} says stage"
                                 f" {line[13]!r} but the report says {sample.get('stage')!r}")
            logged_camera = []
            for v in line[14:19]:
                try:
                    logged_camera.append(float(v))
                except ValueError:
                    logged_camera.append(math.nan)
            camera = sample.get("camera") or []
            if len(camera) != 5 or any(
                    (a is None) != math.isnan(b) or (a is not None and abs(a - b) > 1e-3)
                    for a, b in zip(camera, logged_camera)):
                raise ValueError(f"the ladder log for draw {sample['at']} says camera"
                                 f" {logged_camera} but the report says {camera}")
            if stage_of_draw.get(sample["at"]) != sample.get("stage"):
                raise ValueError(f"the harness log had stage {stage_of_draw.get(sample['at'])!r}"
                                 f" current when draw {sample['at']} was sampled, but the"
                                 f" report labels it {sample.get('stage')!r}")
    checks["samples"] = []
    last_at = 0
    for index, sample in enumerate(samples):
        if not isinstance(sample, dict):
            raise ValueError(f"ladder sample {index} is {sample!r}, not an object")
        for field, kind in (("at", int), ("flipped", bool), ("targetWidth", int),
                            ("targetHeight", int), ("rect", list), ("counts", dict),
                            ("rejectedFlipped", bool), ("rejectedRect", list),
                            ("rejectedOther", int), ("file", str), ("rejectedFile", str),
                            ("frameFile", str), ("stage", str), ("camera", list)):
            if field not in sample:
                raise ValueError(f"ladder sample {index} does not state {field}")
            value = sample[field]
            if kind is bool:
                if not isinstance(value, bool):
                    raise ValueError(f"ladder sample {index}.{field} is {value!r}, not a bool")
            elif kind is int:
                if not finite_int(value):
                    raise ValueError(f"ladder sample {index}.{field} is {value!r}, not an int")
            elif not isinstance(value, kind):
                raise ValueError(f"ladder sample {index}.{field} is {value!r}, not a"
                                 f" {kind.__name__}")
        at = sample["at"]
        camera = sample["camera"]
        if len(camera) != 5 or any(v is not None and not finite_number(v) for v in camera):
            raise ValueError(f"ladder sample {index}.camera is {camera!r}, not five finite"
                             f" numbers (or nulls)")
        if at < 1:
            raise ValueError(f"ladder sample {index} is at draw {at}, so it is not bound to any"
                             f" capture")
        if at > report["drawsRecorded"]:
            raise ValueError(f"ladder sample {index} claims draw {at} but only"
                             f" {report['drawsRecorded']} ladder draws were recorded")
        if at <= last_at:
            raise ValueError(f"ladder sample {index} at draw {at} does not follow the previous"
                             f" sample at draw {last_at}")
        last_at = at
        if sample["flipped"] == sample["rejectedFlipped"]:
            raise ValueError(f"ladder sample {index} rejects the orientation it selected")
        full_w, full_h = sample["targetWidth"], sample["targetHeight"]
        if full_w < 1 or full_h < 1:
            raise ValueError(f"ladder sample {index} states a {full_w}x{full_h} frame")
        # Round-8 review R8-LADDER-GATE: the extent was a producer-controlled reference, so a
        # geometrically self-consistent tiny crop could stand in for the real frame. It must
        # be an extent the ladder launch's own lifecycle checkpoints observed.
        if expected_extents is not None and (full_w, full_h) not in expected_extents:
            raise ValueError(f"ladder sample {index} claims a {full_w}x{full_h} frame, which no"
                             f" lifecycle checkpoint of its launch observed"
                             f" ({sorted(expected_extents)})")
        for label, flipped, rect in (("rect", sample["flipped"], sample["rect"]),
                                     ("rejectedRect", sample["rejectedFlipped"],
                                      sample["rejectedRect"])):
            want = ladder_band_rect(full_w, full_h, flipped)
            if len(rect) != 4 or any(not finite_int(v) for v in rect) or rect != want:
                raise ValueError(f"ladder sample {index}.{label} is {rect}, but the band"
                                 f" resolves to {want} in a {full_w}x{full_h} frame"
                                 f" (flipped={flipped})")
        counts = sample["counts"]
        for field in ("anomaly", "low", "clear", "other"):
            if not finite_int(counts.get(field)) or counts[field] < 0:
                raise ValueError(f"ladder sample {index}.counts.{field} is"
                                 f" {counts.get(field)!r}")
        rungs = counts.get("rungs")
        if (not isinstance(rungs, list) or len(rungs) != LADDER_RUNGS
                or any(not finite_int(v) or v < 0 for v in rungs)):
            raise ValueError(f"ladder sample {index}.counts.rungs is {rungs!r}")
        rect = sample["rect"]
        area = (rect[2] - rect[0]) * (rect[3] - rect[1])
        total = counts["anomaly"] + counts["low"] + counts["clear"] + counts["other"] + sum(rungs)
        if total != area:
            raise ValueError(f"ladder sample {index} counts {total} pixels but its band holds"
                             f" {area}")
        if counts["other"]:
            raise ValueError(f"ladder sample {index}: {counts['other']} band pixel(s) are"
                             f" outside the palette, so the band was not drawn or something"
                             f" overwrote it; nothing is measured")
        if counts["anomaly"]:
            raise ValueError(f"ladder sample {index}: {counts['anomaly']} pixel(s) passed neither"
                             f" the LESS rungs nor the GREATER control, so the depth test did not"
                             f" decide them")
        rrect = sample["rejectedRect"]
        rarea = (rrect[2] - rrect[0]) * (rrect[3] - rrect[1])
        if sample["rejectedOther"] * 2 < rarea:
            raise ValueError(f"ladder sample {index}: the rejected orientation holds only"
                             f" {sample['rejectedOther']} of {rarea} pixels outside the palette,"
                             f" so both orientations look drawn and neither is identified")
        for label, name, prefix in (("file", sample["file"], "native-depth-ladder-"),
                                    ("rejectedFile", sample["rejectedFile"],
                                     "native-depth-ladder-rejected-"),
                                    ("frameFile", sample["frameFile"],
                                     "native-depth-ladder-frame-")):
            if name != f"{prefix}{at}.ppm.gz":
                raise ValueError(f"ladder sample {index}.{label} is {name!r}, not the crop of"
                                 f" draw {at}")
        checks["samples"].append(recount_ladder_sample(output, sample))
    checks["coexist"] = coexist_checks(output, report, checks["samples"], log_text,
                                       require_coexist)
    return checks


COEXIST_RGB_EXACT = (128, 255, 128)   # what the GPU writes for COEXIST_RGB, measured exact


def launch_enables(command, prop):
    """Whether a retained launch command enabled the Gradle project property `prop`.

    ⚠ Round-16 review R16-COEXIST-LAUNCH-SEMANTICS: replay tested membership of the exact
    `-P<prop>=true` token, but build.gradle switches every harness flag on
    `project.hasProperty(...)`, so any value — `=false`, `=`, none — launches the experiment.
    ⚠ Round-17 review, same finding: Gradle also sets the property from the separated
    `-P <prop>[=v]`, from `--project-prop <prop>[=v]` / `--project-prop=<prop>[=v]`, and from
    the system property `org.gradle.project.<prop>` given as `-Dorg.gradle.project.<prop>[=v]`,
    `-D org.gradle.project.<prop>[=v]`, `--system-prop org.gradle.project.<prop>[=v]` or
    `--system-prop=...` (measured by that review against `./gradlew help` for all nine harness
    switches). All of those forms are read here. A different property that merely shares the
    prefix is another property. Outside the command — gradle.properties (fingerprinted, so a
    change refuses replay) and the environment (`ORG_GRADLE_PROJECT_<prop>`, not retained) —
    is not read, which is why `ladder_launch_requirements` additionally demands the stage's
    own literal tokens.
    """
    tokens = [t for t in (command or []) if isinstance(t, str)]
    system_name = f"org.gradle.project.{prop}"

    def names(value, name):
        return value == name or value.startswith(name + "=")

    i = 0
    while i < len(tokens):
        token = tokens[i]
        following = tokens[i + 1] if i + 1 < len(tokens) else None
        if token in ("-P", "--project-prop"):
            if following is not None and names(following, prop):
                return True
            i += 2
            continue
        if token in ("-D", "--system-prop"):
            if following is not None and names(following, system_name):
                return True
            i += 2
            continue
        if token.startswith("--project-prop="):
            if names(token[len("--project-prop="):], prop):
                return True
        elif token.startswith("-P"):
            if names(token[2:], prop):
                return True
        elif token.startswith("--system-prop="):
            if names(token[len("--system-prop="):], system_name):
                return True
        elif token.startswith("-D"):
            if names(token[2:], system_name):
                return True
        i += 1
    return False


# The literal tokens the stage passes to the ladder launch (see run_stage("native-ladder")).
LADDER_LAUNCH_FLAGS = ("-PharnessNativeDepthLadder=true", "-PharnessNativeCoexist=true",
                       "-PharnessNativeTerrainLoad=true", "-PharnessNativeInstance=true",
                       "-PharnessNativeRealLoad=true", "-PharnessNativeHierLoad=true",
                       "-PharnessNativeHierFrames=true")
# Experiments that write or clear Minecraft's depth and must never be in the ladder launch.
LADDER_LAUNCH_FORBIDDEN = ("harnessNativeTerrain", "harnessNativeMarker", "harnessNativeDepth")


def ladder_launch_requirements(command):
    """What a retained ladder launch command obliges the evidence to hold.

    ⚠ Round-17 review R16-COEXIST-LAUNCH-SEMANTICS residual: modelling Gradle's CLI is not
    the authority; the STAGE is. It always passes the literal LADDER_LAUNCH_FLAGS, so a
    retained ladder command that lacks any of them — or no command at all — is not the stage's
    launch and is refused outright, whatever else it spells. A command that also names an
    experiment that writes or clears Minecraft's depth is refused too. What remains is read
    with Gradle's semantics (launch_enables) and is always "required".
    """
    if not isinstance(command, list) or not command:
        raise ValueError("the retained ladder launch has no command, so what it enabled cannot"
                         " be established; the stage always retains one")
    missing = [flag for flag in LADDER_LAUNCH_FLAGS if flag not in command]
    if missing:
        raise ValueError(f"the retained ladder launch command lacks {missing}, which the stage"
                         f" always passes literally; it is not the stage's ladder launch")
    for prop in LADDER_LAUNCH_FORBIDDEN:
        if launch_enables(command, prop):
            raise ValueError(f"the retained ladder launch command enables -P{prop}, which writes"
                             f" or clears Minecraft's depth; the ladder launch never does")
    return {"coexist": launch_enables(command, "harnessNativeCoexist"),
            "terrainLoad": launch_enables(command, "harnessNativeTerrainLoad"),
            "realLoad": launch_enables(command, "harnessNativeRealLoad"),
            "hierLoad": launch_enables(command, "harnessNativeHierLoad"),
            "hierFrames": launch_enables(command, "harnessNativeHierFrames"),
            "instance": launch_enables(command, "harnessNativeInstance")}


def coexist_checks(output, report, recounts, log_text, required=False):
    """The coexistence quad, per pixel, against the same frame's ladder brackets.

    For every sample the launch took with the experiment on, a second crop of the same band
    was retained after a quad at z* was drawn with Voxy's GREATER_OR_EQUAL compare and no
    depth write. Pixel by pixel: where the first crop says d <= z* the quad must be there
    (exactly its colour); where it says d > z* it must not, and the pixel must be
    byte-identical to the first crop. Zero violations, recounted from both retained crops;
    at least one sample must hold pixels of both kinds, or the experiment decided nothing.

    ⚠ Round-15 review R15-COEXIST-PRESENCE: a report flagged off with no results passed while
    the launch command, the log and the saved results all said the experiment ran. The caller
    says whether the launch enabled it (the stage always does; replay reads the retained
    launch command), and a log that holds coexist lines is reconciled whether or not the
    report admits to them.
    """
    enabled = report.get("coexistEnabled")
    if not isinstance(enabled, bool):
        raise ValueError("the ladder does not state coexistEnabled")
    entries = report.get("coexist")
    if not isinstance(entries, list):
        raise ValueError("the ladder does not list its coexist results")
    logged = {}
    if log_text is not None:
        for m in LADDER_COEXIST_LOG.findall(log_text):
            if int(m[0]) in logged:
                raise ValueError(f"the ladder log holds two coexist lines for draw {m[0]}")
            logged[int(m[0])] = [int(v) for v in m[1:]]
    if required and not enabled:
        raise ValueError("the ladder launch enabled the coexistence experiment but the report"
                         " says coexistEnabled=false; the experiment's evidence is required")
    if not enabled:
        if entries:
            raise ValueError("coexist results are listed although the experiment was off")
        if logged:
            raise ValueError(f"the ladder log holds coexist lines for draws {sorted(logged)} but"
                             f" the report says the experiment was off")
        return {"enabled": False}
    if report.get("coexistRung") != COEXIST_RUNG or report.get("coexistRgb") != COEXIST_RGB:
        raise ValueError(f"the ladder publishes coexistRung={report.get('coexistRung')!r},"
                         f" coexistRgb={report.get('coexistRgb')!r}, not the"
                         f" {COEXIST_RUNG}/{COEXIST_RGB} its source lays out")
    ats = []
    for entry in entries:
        if not isinstance(entry, dict) or not finite_int(entry.get("at")):
            raise ValueError(f"a coexist entry is malformed: {entry!r}")
        ats.append(entry["at"])
    if len(ats) != len(set(ats)):
        raise ValueError(f"the coexist results repeat a draw: {sorted(ats)}")
    by_at = {entry["at"]: entry for entry in entries}
    sample_ats = [r["at"] for r in recounts]
    if sorted(by_at) != sorted(sample_ats):
        raise ValueError(f"coexist results exist for draws {sorted(by_at)} but the ladder"
                         f" sampled draws {sorted(sample_ats)}; every sample must carry one")
    if log_text is not None and sorted(logged) != sorted(sample_ats):
        raise ValueError(f"the ladder log holds coexist lines for draws {sorted(logged)} but"
                         f" the ladder sampled draws {sorted(sample_ats)}")
    out, mixed = [], 0
    for recount in recounts:
        entry = by_at[recount["at"]]
        for field in ("present", "absent", "other", "expectedPass", "expectedFail",
                      "absentWherePass", "presentWhereFail", "unchangedElsewhere"):
            if not finite_int(entry.get(field)) or entry[field] < 0:
                raise ValueError(f"coexist at draw {recount['at']}: {field} is"
                                 f" {entry.get(field)!r}")
        name = entry.get("file")
        if name != f"native-depth-ladder-coexist-{recount['at']}.ppm.gz":
            raise ValueError(f"coexist at draw {recount['at']} names {name!r}, not its crop")
        frame_name = entry.get("frameFile")
        if frame_name != f"native-depth-ladder-coexist-frame-{recount['at']}.ppm.gz":
            raise ValueError(f"coexist at draw {recount['at']} names {frame_name!r}, not its"
                             f" frame thumbnail")
        after_path, before_path = output / name, output / recount["sample"]
        if not after_path.is_file():
            raise ValueError(f"the retained coexist crop {name} is missing")
        after, (aw, ah) = read_ppm_gz(after_path)
        before, (bw, bh) = read_ppm_gz(before_path)
        if (aw, ah) != (bw, bh):
            raise ValueError(f"the coexist crop is {aw}x{ah} but the ladder crop is {bw}x{bh}")
        frame_path = output / frame_name
        if not frame_path.is_file():
            raise ValueError(f"the retained coexist frame thumbnail {frame_name} is missing")
        trows, (tw, th) = read_ppm_gz(frame_path)
        if ladder_anchor_blocks(after, recount["rect"], trows) == 0:
            raise ValueError(f"the coexist crop at draw {recount['at']} covers no whole"
                             f" thumbnail block, so its position cannot be checked")
        counts = {"present": 0, "absent": 0, "other": 0, "expectedPass": 0, "expectedFail": 0,
                  "absentWherePass": 0, "presentWhereFail": 0, "unchangedElsewhere": 0}
        for y in range(ah):
            for x in range(aw):
                px, px0 = tuple(after[y][x][:3]), tuple(before[y][x][:3])
                cls = ladder_classify(px0)
                expect_pass = cls in (LADDER_LOW, LADDER_CLEAR) or (
                    LADDER_RUNG0 <= cls < LADDER_RUNG0 + COEXIST_RUNG)
                expect_fail = LADDER_RUNG0 + COEXIST_RUNG <= cls < LADDER_RUNG0 + LADDER_RUNGS
                counts["expectedPass"] += expect_pass
                counts["expectedFail"] += expect_fail
                # ⚠ Round-15 review R15-COEXIST-RGB: classes were compared, so a quad pixel
                # of another green and an uncovered pixel of another shade both passed. Exact.
                if px == COEXIST_RGB_EXACT:
                    counts["present"] += 1
                    counts["presentWhereFail"] += expect_fail
                else:
                    counts["absent"] += 1
                    if px == px0:
                        counts["unchangedElsewhere"] += 1
                    else:
                        counts["other"] += 1
                    counts["absentWherePass"] += expect_pass
        for field, value in counts.items():
            if entry[field] != value:
                raise ValueError(f"coexist at draw {recount['at']} reports {field}="
                                 f"{entry[field]} but the retained crops say {value}")
        if log_text is not None:
            want = [counts[k] for k in ("present", "absent", "other", "expectedPass",
                                         "expectedFail", "absentWherePass", "presentWhereFail",
                                         "unchangedElsewhere")]
            if logged.get(recount["at"]) != want:
                raise ValueError(f"the ladder log's coexist line for draw {recount['at']} says"
                                 f" {logged.get(recount['at'])} but the crops say {want}")
        if counts["other"]:
            raise ValueError(f"coexist at draw {recount['at']}: {counts['other']} pixel(s) the"
                             f" quad did not cover are not byte-identical to the first crop,"
                             f" so something else changed the band")
        if counts["absentWherePass"] or counts["presentWhereFail"]:
            raise ValueError(f"coexist at draw {recount['at']}: the quad is missing at"
                             f" {counts['absentWherePass']} pixel(s) whose depth is <= z* and"
                             f" present at {counts['presentWhereFail']} pixel(s) whose depth"
                             f" is > z*; Voxy's compare against Minecraft's depth did not"
                             f" compose per pixel")
        if counts["expectedPass"] and counts["expectedFail"]:
            mixed += 1
        out.append({"at": recount["at"], **counts})
    if not mixed:
        raise ValueError("no coexist sample holds pixels on both sides of z*, so the"
                         " experiment decided nothing per pixel")
    return {"enabled": True, "samples": out, "mixedSamples": mixed,
            "z": EXPECTED_LADDER_DEPTHS[COEXIST_RUNG]}


# ---------------- native instance mode: a world engine without a render path ----------------

INSTANCE_LOG = re.compile(r"native instance at frame (\d+) stage=(\S*) factory=(true|false)"
                          r" instance=(true|false) engine=(true|false) live=(true|false)"
                          r" activeSections=(\d+) renderer=(true|false) ingest=(true|false)"
                          r" cameraCaptures=(\d+) storedNearCamera=(\d+)")
INSTANCE_SAMPLE_INTERVAL = 60
INSTANCE_SAMPLE_LIMIT = 256   # McNativeInstanceProbe.SAMPLE_LIMIT


def native_instance_result(output, required=False, log_text=None):
    """Native instance mode: on Minecraft's Vulkan backend Voxy's instance (world engine,
    storage, ingest) ran WITHOUT any render path, and real sections were ingested.

    The dependency the next experiment (real sections through Voxy's terrain pipeline) rests
    on: a world engine must exist and hold sections, and no VoxyRenderSystem (the GL/interop
    renderer) may have been created. Says nothing about the sections' content or drawing.
    """
    result = {"success": False, "failures": [],
              "scope": "a Voxy world engine with ingested sections and no render path on"
                       " Minecraft's Vulkan backend; NOT drawing, NOT content"}
    path = output / "native-instance.json"
    try:
        if not path.is_file():
            if required:
                raise ValueError("the launch enabled native instance mode but"
                                 " native-instance.json is not retained")
            result.update(success=True, enabled=False)
            return result
        report = json.loads(path.read_text())
        for field, kind in (("enabled", bool), ("backend", (str, type(None))), ("frames", int),
                            ("engineEverPresent", bool), ("rendererEverCreated", bool),
                            ("maxActiveSections", int), ("maxStoredNearCamera", int),
                            ("sampleInterval", int),
                            ("samples", list), ("notes", list)):
            if field not in report:
                raise ValueError(f"the instance report does not state {field}")
            value = report[field]
            if kind is bool:
                if not isinstance(value, bool):
                    raise ValueError(f"instance.{field} is {value!r}, not a bool")
            elif kind is int:
                if not finite_int(value):
                    raise ValueError(f"instance.{field} is {value!r}, not an int")
            elif not isinstance(value, kind):
                raise ValueError(f"instance.{field} is {value!r}, not a {kind}")
        if not report["enabled"]:
            if required:
                raise ValueError("the launch enabled native instance mode but the report says"
                                 " enabled=false")
            if report["samples"] or report["frames"]:
                raise ValueError("the instance probe recorded while disabled")
            result.update(success=True, enabled=False)
            return result
        if report["backend"] is not None:
            raise ValueError(f"native instance mode requires no Voxy backend, but the report"
                             f" names {report['backend']!r}; a render path may have been built")
        if report["notes"]:
            raise ValueError(f"the instance probe reported notes: {report['notes']}")
        if report["rendererEverCreated"]:
            raise ValueError("a VoxyRenderSystem was created in native instance mode")
        if report["sampleInterval"] != INSTANCE_SAMPLE_INTERVAL:
            raise ValueError(f"the instance probe samples every {report['sampleInterval']}"
                             f" frames, not the {INSTANCE_SAMPLE_INTERVAL} its source lays out")
        samples = report["samples"]
        if not samples:
            raise ValueError("the instance probe retained no sample")
        last_frame, max_active, max_stored, engine_ever, renderer_ever = 0, 0, 0, False, False
        for index, sample in enumerate(samples):
            if not isinstance(sample, dict):
                raise ValueError(f"instance sample {index} is {sample!r}, not an object")
            for field, kind in (("frame", int), ("stage", str), ("factorySet", bool),
                                ("instancePresent", bool), ("enginePresent", bool),
                                ("engineLive", bool), ("activeSections", int),
                                ("rendererCreated", bool), ("ingestEnabled", bool),
                                ("cameraCaptures", int), ("storedNearCamera", int)):
                if field not in sample:
                    raise ValueError(f"instance sample {index} does not state {field}")
                value = sample[field]
                if kind is bool and not isinstance(value, bool):
                    raise ValueError(f"instance sample {index}.{field} is {value!r}, not a bool")
                if kind is int and not finite_int(value):
                    raise ValueError(f"instance sample {index}.{field} is {value!r}, not an int")
                if kind is str and not isinstance(value, str):
                    raise ValueError(f"instance sample {index}.{field} is {value!r}, not a str")
            if sample["frame"] <= last_frame:
                raise ValueError(f"instance samples are out of order at frame {sample['frame']}")
            if sample["frame"] != 1 and sample["frame"] % INSTANCE_SAMPLE_INTERVAL:
                raise ValueError(f"instance sample at frame {sample['frame']} is not on the"
                                 f" probe's interval")
            if sample["activeSections"] < 0:
                raise ValueError(f"instance sample at frame {sample['frame']} has"
                                 f" activeSections={sample['activeSections']}")
            if sample["enginePresent"] and not (sample["factorySet"] and sample["instancePresent"]):
                raise ValueError(f"instance sample at frame {sample['frame']} has an engine"
                                 f" without a factory or instance")
            if sample["cameraCaptures"] < 0 or (index and sample["cameraCaptures"]
                                                < samples[index - 1].get("cameraCaptures", 0)):
                raise ValueError(f"instance sample at frame {sample['frame']} has"
                                 f" cameraCaptures={sample['cameraCaptures']}, which decreased")
            if sample["storedNearCamera"] < 0:
                raise ValueError(f"instance sample at frame {sample['frame']} has"
                                 f" storedNearCamera={sample['storedNearCamera']}")
            last_frame = sample["frame"]
            max_active = max(max_active, sample["activeSections"])
            max_stored = max(max_stored, sample["storedNearCamera"])
            engine_ever |= sample["enginePresent"]
            renderer_ever |= sample["rendererCreated"]
        if last_frame > report["frames"]:
            raise ValueError(f"the last instance sample is at frame {last_frame} but only"
                             f" {report['frames']} frames were counted")
        # ⚠ Round-19 review R19-INSTANCE-INVENTORY: keeping only two samples (and their log
        # lines) replayed 0. The probe samples frame 1 and every 60th frame, keeping the first
        # SAMPLE_LIMIT; the retained list must be exactly that inventory for the frames counted.
        want = ([1] + list(range(INSTANCE_SAMPLE_INTERVAL, report["frames"] + 1,
                                 INSTANCE_SAMPLE_INTERVAL)))[:INSTANCE_SAMPLE_LIMIT]
        got = [s["frame"] for s in samples]
        if got != want:
            missing = sorted(set(want) - set(got))
            raise ValueError(f"the instance probe counted {report['frames']} frames, so it sampled"
                             f" {len(want)} frame(s), but {len(got)} are retained (missing"
                             f" {missing[:6]}); the inventory is not complete")
        for field, want in (("maxActiveSections", max_active), ("maxStoredNearCamera", max_stored),
                            ("engineEverPresent", engine_ever), ("rendererEverCreated", renderer_ever)):
            if report[field] != want:
                raise ValueError(f"the instance report says {field}={report[field]!r} but its"
                                 f" samples say {want!r}")
        if not engine_ever:
            raise ValueError("no sample saw a world engine: native instance mode did not start"
                             " Voxy's instance for the level")
        # ⚠ 2026-10-10: the active-section count is cache occupancy and read 0 at all 80 samples
        # of a run whose engine had ingested; what the engine holds is what it can load. The
        # probe counts level-0 sections around the camera that hold a non-air block (acquired and
        # released). round-24 R24-INSTANCE-STORED: a non-null acquire alone is not storage — the
        # tracker returns a cached all-air placeholder for a missing section — so only content
        # counts.
        if max_stored < 1:
            raise ValueError("no section near the camera holds a block at any sample: the world"
                             " engine holds nothing ingested")
        final = samples[-1]
        if not final["enginePresent"] or not final["engineLive"]:
            raise ValueError(f"the last sample (frame {final['frame']}, stage"
                             f" {final['stage']!r}) has no live world engine")
        if not final["ingestEnabled"]:
            raise ValueError("ingest was disabled at the last sample")
        if final["cameraCaptures"] < 1:
            raise ValueError("Minecraft's matrices were never captured from the chunk renderer, so"
                             " no native probe can draw with Minecraft's own camera")
        if log_text is not None:
            logged = {}
            for m in INSTANCE_LOG.findall(log_text):
                if int(m[0]) in logged:
                    raise ValueError(f"the log holds two instance lines for frame {m[0]}")
                logged[int(m[0])] = m[1:]
            if sorted(logged) != [s["frame"] for s in samples]:
                raise ValueError(f"the log holds instance lines for frames {sorted(logged)} but"
                                 f" the report lists {[s['frame'] for s in samples]}")
            for sample in samples:
                want = (sample["stage"], str(sample["factorySet"]).lower(),
                        str(sample["instancePresent"]).lower(),
                        str(sample["enginePresent"]).lower(), str(sample["engineLive"]).lower(),
                        str(sample["activeSections"]), str(sample["rendererCreated"]).lower(),
                        str(sample["ingestEnabled"]).lower(), str(sample["cameraCaptures"]),
                        str(sample["storedNearCamera"]))
                if tuple(logged[sample["frame"]]) != want:
                    raise ValueError(f"the log's instance line for frame {sample['frame']} says"
                                     f" {logged[sample['frame']]} but the report says {want}")
        result.update(success=True, enabled=True, samples=len(samples),
                      max_active_sections=max_active, max_stored_near_camera=max_stored,
                      answer=f"a Voxy world engine ran without a render path on Minecraft's"
                             f" Vulkan backend and held up to {max_stored} sections with blocks near the"
                             f" camera (active cache up to {max_active}) across"
                             f" {len(samples)} samples")
    except (OSError, ValueError, KeyError, TypeError) as exc:
        result["failures"].append(f"{type(exc).__name__}: {exc}")
    return result


# ---------------- terrain-LOAD: Voxy's real terrain pipeline against Minecraft's loaded depth ----------------

TERRAIN_LOAD_SCENE = "depthSweep"
# {compareOp, testEnable, writeEnable} VkTerrainRenderer declares: Voxy's GREATER_OR_EQUAL,
# depth test on, depth WRITES ON (this is Voxy's geometry, not a probe quad).
TERRAIN_LOAD_DEPTH_STATE = [VK_COMPARE_OP_GREATER_OR_EQUAL, 1, 1]
TERRAIN_LOAD_COUNTS = ("geometry", "noGeometry", "expectVisible", "expectHidden",
                       "undetermined", "visible", "hidden", "ambiguous", "other",
                       "visibleWhereHidden", "hiddenWhereVisible", "changedWhereNoGeometry")
TERRAIN_LOAD_LOG = re.compile(r"terrain load at draw (\d+) "
                              + " ".join(f"{name}=(\\d+)" for name in TERRAIN_LOAD_COUNTS)
                              + r" depth=\[")
TERRAIN_LOAD_COLOUR_FORMAT, TERRAIN_LOAD_DEPTH_FORMAT = 37, 126   # R8G8B8A8_UNORM, D32_SFLOAT
# The view McNativeTerrainLoad lays out, pinned (round-18 review R18-TERRAIN-METADATA: the
# published eye/matrix/extent/counters were not reconciled with anything).
TERRAIN_LOAD_EYE, TERRAIN_LOAD_CENTRE, TERRAIN_LOAD_UP = [80.0, 8.0, 0.0], [80.0, 2.0, 300.0], [0.0, 1.0, 0.0]
TERRAIN_LOAD_FOV_DEGREES, TERRAIN_LOAD_NEAR, TERRAIN_LOAD_FAR, TERRAIN_LOAD_FIT_MARGIN = 60.0, 0.1, 2000.0, 0.05
TERRAIN_LOAD_DRAW_COUNT = 10            # five depthSweep sections, UP and NORTH faces each
TERRAIN_LOAD_CLEAR_RGB = (13, 13, 26)   # McNativeTerrainLoad.CLEAR as RGBA8 bytes


def terrain_load_cells():
    """SyntheticTerrain.depthSweep().opaqueQuadCells(): five sections (2, 0, k), k in
    {1, 2, 4, 8, 16}, 32 UP quads then 64 NORTH quads, quad k at (k & 31, k >> 5 & 31, k >> 10 & 31)
    in blocks from the section origin (section coordinate times 32), unit cells."""
    cells = []
    for k in (1, 2, 4, 8, 16):
        ox, oy, oz = 2 * 32, 0, k * 32
        for q in range(96):
            px, py, pz = q & 31, (q >> 5) & 31, (q >> 10) & 31
            cells.append((ox + px, oy + py, oz + pz, ox + px + 1, oy + py + 1, oz + pz + 1))
    return cells


def _mat_perspective(fovy_rad, aspect, near, far):
    f = 1.0 / math.tan(fovy_rad * 0.5)
    m = [0.0] * 16
    m[0], m[5], m[10], m[11], m[14] = f / aspect, f, near / (far - near), -1.0, far * near / (far - near)
    return m


def _mat_look_at(eye, centre, up):
    def norm(v):
        l = math.sqrt(sum(c * c for c in v))
        return [c / l for c in v]

    def cross(a, b):
        return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]

    fwd = norm([c - e for c, e in zip(centre, eye)])
    side = norm(cross(fwd, up))
    u = cross(side, fwd)
    dot = lambda a, b: sum(x * y for x, y in zip(a, b))
    m = [0.0] * 16
    m[0], m[4], m[8], m[12] = side[0], side[1], side[2], -dot(side, eye)
    m[1], m[5], m[9], m[13] = u[0], u[1], u[2], -dot(u, eye)
    m[2], m[6], m[10], m[14] = -fwd[0], -fwd[1], -fwd[2], dot(fwd, eye)
    m[15] = 1.0
    return m


def _mat_mul(a, b):
    return [sum(a[k * 4 + row] * b[c * 4 + k] for k in range(4)) for c in range(4) for row in range(4)]


def _mat_project(m, x, y, z):
    cx = m[0] * x + m[4] * y + m[8] * z + m[12]
    cy = m[1] * x + m[5] * y + m[9] * z + m[13]
    cz = m[2] * x + m[6] * y + m[10] * z + m[14]
    cw = m[3] * x + m[7] * y + m[11] * z + m[15]
    return cx / cw, cy / cw, cz / cw


def terrain_load_footprint(m, cells):
    xs, ys, zs = [], [], []
    for c in cells:
        for i in range(8):
            x, y, z = _mat_project(m, c[3 if i & 1 else 0], c[4 if i & 2 else 1], c[5 if i & 4 else 2])
            xs.append(x); ys.append(y); zs.append(z)
    return min(xs), min(ys), max(xs), max(ys), min(zs), max(zs)


def terrain_load_expected_mvp(width, height):
    """McNativeTerrainLoad.mvp(width, height), recomputed: Voxy's perspective (reverse-Z, GL y)
    and lookAt, then the NDC affine map that fits the sweep's footprint inside the ladder band
    with the margin, leaving depth untouched."""
    pv = _mat_mul(_mat_perspective(math.radians(TERRAIN_LOAD_FOV_DEGREES), width / height,
                                   TERRAIN_LOAD_NEAR, TERRAIN_LOAD_FAR),
                  _mat_look_at(TERRAIN_LOAD_EYE, TERRAIN_LOAD_CENTRE, TERRAIN_LOAD_UP))
    x0, y0, x1, y1, _, _ = terrain_load_footprint(pv, terrain_load_cells())
    band = EXPECTED_LADDER_BAND
    bx0, bx1 = min(band[0], band[2]), max(band[0], band[2])
    by0, by1 = min(band[1], band[3]), max(band[1], band[3])
    mx, my = (bx1 - bx0) * TERRAIN_LOAD_FIT_MARGIN, (by1 - by0) * TERRAIN_LOAD_FIT_MARGIN
    sx = (bx1 - bx0 - 2 * mx) / (x1 - x0)
    sy = (by1 - by0 - 2 * my) / (y1 - y0)
    fit = [0.0] * 16
    fit[0], fit[5], fit[10], fit[15] = sx, sy, 1.0, 1.0
    fit[12], fit[13] = (bx0 + mx) - sx * x0, (by0 + my) - sy * y0
    return _mat_mul(fit, pv)


def read_f32_gz(path):
    """A gzipped `VXF32\n<w> <h>\n` header followed by w*h little-endian float32 values."""
    import gzip
    raw = gzip.open(path, "rb").read()
    first = raw.index(b"\n")
    if raw[:first] != b"VXF32":
        raise ValueError(f"{path.name} is not a VXF32 depth crop")
    second = raw.index(b"\n", first + 1)
    w, h = (int(v) for v in raw[first + 1:second].split())
    body = raw[second + 1:]
    if len(body) != w * h * 4:
        raise ValueError(f"{path.name} holds {len(body)} bytes for {w}x{h} float32 values")
    values = struct.unpack("<" + "f" * (w * h), body)
    return [list(values[y * w:(y + 1) * w]) for y in range(h)], (w, h)


def terrain_load_expectation(ladder_class, voxy_depth):
    """1 = Voxy's pixel must appear, -1 = must not, 0 = the bracket does not decide.

    The ladder class gives Minecraft's depth d: LOW is d < z0, BASE is d = z0, rung i is
    z_i < d <= z_{i+1} (the last rung z_7 < d <= 1). Voxy's fragment passes when d_V >= d
    (GREATER_OR_EQUAL), so d_V at or above the bracket's top always passes and d_V at or
    below its bottom always fails. Same rule as McNativeTerrainLoad.expectation.
    """
    z = EXPECTED_LADDER_DEPTHS
    if ladder_class == LADDER_CLEAR:
        return 1   # Minecraft's depth is exactly 0: any Voxy depth passes GREATER_OR_EQUAL
    if ladder_class == LADDER_LOW:
        return 1 if voxy_depth >= z[0] else 0
    if ladder_class == LADDER_BASE:
        return 1 if voxy_depth >= z[0] else -1
    rung = ladder_class - LADDER_RUNG0
    if rung < 0 or rung >= LADDER_RUNGS:
        return 0
    if voxy_depth <= z[rung]:
        return -1
    top = z[rung + 1] if rung + 1 < LADDER_RUNGS else 1.0
    return 1 if voxy_depth >= top else 0


def terrain_load_expectation_clear_only(ladder_class):
    """Voxy's GL composition rule (McNativeTerrainLoad.expectationClearOnly): the GL path draws
    Voxy only where Minecraft's depth is the clear value (a stencil built from Minecraft's depth),
    so Voxy must show on the ladder's CLEAR pixels and nowhere else, whatever its depth."""
    return 1 if ladder_class == LADDER_CLEAR else -1


def terrain_load_judge(before, ladder, after, reference, depth, clear_only=False):
    """Count every band pixel the way McNativeTerrainLoad.judge does, from the retained crops:
    `ladder` classifies Minecraft's depth, `before` is the previous readback (the quad's
    crop when the coexist experiment ran, else the ladder's), `after` the third readback,
    `reference` and `depth` Voxy's own render of the same scene."""
    counts = {name: 0 for name in TERRAIN_LOAD_COUNTS}
    for y, row in enumerate(after):
        for x, px in enumerate(row):
            px = tuple(px[:3])
            px_before = tuple(before[y][x][:3])
            same_as_before = px == px_before
            d = depth[y][x]
            if not (d > 0.0):
                counts["noGeometry"] += 1
                if not same_as_before:
                    counts["changedWhereNoGeometry"] += 1
                continue
            counts["geometry"] += 1
            same_as_ref = px == tuple(reference[y][x][:3])
            cls = ladder_classify(tuple(ladder[y][x][:3]))
            expect = (terrain_load_expectation_clear_only(cls) if clear_only
                      else terrain_load_expectation(cls, d))
            if expect > 0:
                counts["expectVisible"] += 1
            elif expect < 0:
                counts["expectHidden"] += 1
            else:
                counts["undetermined"] += 1
            if same_as_ref and same_as_before:
                counts["ambiguous"] += 1
            elif same_as_ref:
                counts["visible"] += 1
                if expect < 0:
                    counts["visibleWhereHidden"] += 1
            elif same_as_before:
                counts["hidden"] += 1
                if expect > 0:
                    counts["hiddenWhereVisible"] += 1
            else:
                counts["other"] += 1
    return counts


def terrain_load_checks(output, ladder_report, recounts, coexist_enabled, log_text,
                        required=False):
    """Voxy's REAL terrain pipeline, drawn into a pass that LOADs Minecraft's colour and depth
    with Voxy's own depth state (writes on), judged per pixel against two independent
    measurements of the same frame: the ladder's bracket of Minecraft's depth and Voxy's own
    reference depth of the same synthetic scene. A separate probe from the ladder (which never
    writes depth); it records only on sampled frames, after the ladder's readbacks.

    Per pixel with Voxy geometry: depth at or above the bracket's top -> Voxy's reference
    colour, exactly; at or below its bottom -> byte-identical to the previous readback;
    between -> either (counted as undetermined). Pixels without geometry must not change.
    Zero violations, counts recounted from the retained crops and reconciled with the report
    and the log; at least one sample must hold both determinate kinds, or the experiment
    decided nothing.
    """
    enabled = ladder_report.get("terrainLoadEnabled")
    report_path = output / "native-terrain-load.json"
    logged = {}
    if log_text is not None:
        for m in TERRAIN_LOAD_LOG.findall(log_text):
            if int(m[0]) in logged:
                raise ValueError(f"the ladder log holds two terrain-load lines for draw {m[0]}")
            logged[int(m[0])] = [int(v) for v in m[1:]]
    retained = sorted(p.name for p in output.glob("native-terrain-load-*"))
    if required and not enabled:
        raise ValueError("the ladder launch enabled the terrain-LOAD experiment but the ladder"
                         " says terrainLoadEnabled=false; the experiment's evidence is required")
    if not enabled:
        if report_path.is_file():
            raise ValueError("a terrain-LOAD report is retained although the ladder says the"
                             " experiment was off")
        if logged:
            raise ValueError(f"the ladder log holds terrain-load lines for draws {sorted(logged)}"
                             f" but the ladder says the experiment was off")
        if retained:
            raise ValueError(f"terrain-LOAD files are retained although the experiment was off:"
                             f" {retained[:6]}")
        return {"enabled": False}
    if not report_path.is_file():
        raise ValueError("the terrain-LOAD experiment was on but native-terrain-load.json is not"
                         " retained")
    report = json.loads(report_path.read_text())
    for field, kind in (("enabled", bool), ("attempted", bool), ("built", bool),
                        ("drawsRecorded", int), ("width", int), ("height", int),
                        ("colourFormat", int), ("depthFormat", int), ("scene", str),
                        ("eye", list), ("centre", list), ("fovDegrees", (int, float)),
                        ("near", (int, float)), ("far", (int, float)),
                        ("fitMargin", (int, float)), ("mvp", list), ("drawCount", int),
                        ("referenceSet", int), ("declaredDepthState", list),
                        ("depthStateReadBack", bool), ("ladderEnabled", bool),
                        ("results", list), ("problems", int), ("firstProblem", (str, type(None))),
                        ("closeFailures", int), ("leakedScenes", int), ("deviceDiverged", bool),
                        ("readbacksInFlight", int), ("device", str), ("notes", list)):
        if field not in report:
            raise ValueError(f"the terrain-LOAD report does not state {field}")
        value = report[field]
        if kind is bool:
            if not isinstance(value, bool):
                raise ValueError(f"terrainLoad.{field} is {value!r}, not a bool")
        elif kind is int:
            if not finite_int(value):
                raise ValueError(f"terrainLoad.{field} is {value!r}, not an int")
        elif not isinstance(value, kind):
            raise ValueError(f"terrainLoad.{field} is {value!r}, not a {kind}")
    if not report["enabled"] or not report["attempted"] or not report["built"]:
        raise ValueError(f"the terrain-LOAD probe reports enabled={report['enabled']},"
                         f" attempted={report['attempted']}, built={report['built']}")
    if not report["ladderEnabled"]:
        raise ValueError("the terrain-LOAD probe ran without the ladder, so no bracket exists")
    if report["scene"] != TERRAIN_LOAD_SCENE:
        raise ValueError(f"the terrain-LOAD scene is {report['scene']!r}, not the"
                         f" {TERRAIN_LOAD_SCENE!r} its source lays out")
    if report["declaredDepthState"] != TERRAIN_LOAD_DEPTH_STATE:
        raise ValueError(f"the terrain-LOAD probe declares depth state"
                         f" {report['declaredDepthState']!r}, not Voxy's"
                         f" {TERRAIN_LOAD_DEPTH_STATE} (GREATER_OR_EQUAL, test on, writes on)")
    if report["depthStateReadBack"]:
        raise ValueError("the terrain-LOAD probe claims to have read its depth state back from"
                         " pipeline creation, which it does not do (the state is declared)")
    if report["colourFormat"] != TERRAIN_LOAD_COLOUR_FORMAT:
        raise ValueError(f"the terrain-LOAD colour format is {report['colourFormat']}, not"
                         f" {TERRAIN_LOAD_COLOUR_FORMAT}")
    if report["depthFormat"] != TERRAIN_LOAD_DEPTH_FORMAT:
        raise ValueError(f"the terrain-LOAD depth format is {report['depthFormat']}, not the"
                         f" D32_SFLOAT ({TERRAIN_LOAD_DEPTH_FORMAT}) the pipeline declares")
    if len(report["mvp"]) != 16 or not all(finite_number(v) for v in report["mvp"]):
        raise ValueError(f"the terrain-LOAD mvp is not 16 finite numbers: {report['mvp']!r}")
    # ⚠ Round-18 review R18-TERRAIN-METADATA: the published view and scene facts were only
    # type-checked, so a zero matrix, a fictitious eye, a 1x1 extent and counters of 1 passed.
    # The view is pinned to the source's constants, the matrix is recomputed from them, the
    # extent is the last sample's, the draw count is the sweep's, and the reference pixel
    # count is recounted from the last extent's reference crop below.
    for field, want in (("eye", TERRAIN_LOAD_EYE), ("centre", TERRAIN_LOAD_CENTRE)):
        got = report[field]
        if len(got) != 3 or any(not finite_number(a) or abs(a - b) > 1e-6 for a, b in zip(got, want)):
            raise ValueError(f"the terrain-LOAD {field} is {got!r}, not the {want} its source lays out")
    for field, want in (("fovDegrees", TERRAIN_LOAD_FOV_DEGREES), ("near", TERRAIN_LOAD_NEAR),
                        ("far", TERRAIN_LOAD_FAR), ("fitMargin", TERRAIN_LOAD_FIT_MARGIN)):
        got = report[field]
        if not finite_number(got) or abs(got - want) > 1e-6 * max(1.0, abs(want)):
            raise ValueError(f"the terrain-LOAD {field} is {got!r}, not the {want} its source lays out")
    if report["drawCount"] != TERRAIN_LOAD_DRAW_COUNT:
        raise ValueError(f"the terrain-LOAD scene has drawCount={report['drawCount']}, not the"
                         f" {TERRAIN_LOAD_DRAW_COUNT} the depth sweep issues")
    if report["referenceSet"] < 1:
        raise ValueError("the terrain-LOAD reference drew nothing (referenceSet=0)")
    published_samples = [s for s in ladder_report.get("samples") or [] if isinstance(s, dict)]
    if published_samples:
        last = published_samples[-1]
        if (report["width"], report["height"]) != (last.get("targetWidth"), last.get("targetHeight")):
            raise ValueError(f"the terrain-LOAD scene is {report['width']}x{report['height']} but"
                             f" the last ladder sample is {last.get('targetWidth')}x"
                             f"{last.get('targetHeight')}; the scene follows the frame extent")
    expected_mvp = terrain_load_expected_mvp(report["width"], report["height"])
    for index, (got, want) in enumerate(zip(report["mvp"], expected_mvp)):
        if abs(got - want) > 1e-4 * max(1.0, abs(want)):
            raise ValueError(f"the terrain-LOAD mvp[{index}] is {got!r} but the view its source lays"
                             f" out gives {want!r}")
    if report["notes"]:
        raise ValueError(f"the terrain-LOAD probe reported notes: {report['notes']}")
    if (report["problems"] or report["closeFailures"] or report["leakedScenes"]
            or report["firstProblem"] is not None):
        raise ValueError(f"the terrain-LOAD probe reports problems={report['problems']},"
                         f" closeFailures={report['closeFailures']},"
                         f" leakedScenes={report['leakedScenes']},"
                         f" firstProblem={report['firstProblem']!r}")
    if report["deviceDiverged"]:
        raise ValueError("Minecraft's device diverged during the terrain-LOAD run")
    if report["readbacksInFlight"]:
        raise ValueError(f"{report['readbacksInFlight']} terrain-LOAD readback(s) never"
                         f" completed, so the report is not final")
    ladder_device = device_handle_of(ladder_report["device"], "ladder", "device")
    if device_handle_of(report["device"], "terrainLoad", "device") != ladder_device:
        raise ValueError(f"the terrain-LOAD probe names device {report['device']} but the ladder"
                         f" names {ladder_report['device']}")
    sample_ats = [r["at"] for r in recounts]
    if report["drawsRecorded"] != len(sample_ats):
        raise ValueError(f"the terrain-LOAD probe recorded {report['drawsRecorded']} pass(es)"
                         f" but the ladder sampled {len(sample_ats)} frame(s); one pass per"
                         f" sample is required")
    if ladder_report.get("terrainLoadDrawsRecorded") != report["drawsRecorded"]:
        raise ValueError(f"the ladder saw {ladder_report.get('terrainLoadDrawsRecorded')!r}"
                         f" terrain-LOAD pass(es) but the probe reports"
                         f" {report['drawsRecorded']}")
    entries = report["results"]
    ats = []
    for entry in entries:
        if not isinstance(entry, dict) or not finite_int(entry.get("at")):
            raise ValueError(f"a terrain-LOAD result is malformed: {entry!r}")
        ats.append(entry["at"])
    if len(ats) != len(set(ats)):
        raise ValueError(f"the terrain-LOAD results repeat a draw: {sorted(ats)}")
    by_at = {entry["at"]: entry for entry in entries}
    if sorted(by_at) != sorted(sample_ats):
        raise ValueError(f"terrain-LOAD results exist for draws {sorted(by_at)} but the ladder"
                         f" sampled draws {sorted(sample_ats)}; every sample must carry one")
    if log_text is not None and sorted(logged) != sorted(sample_ats):
        raise ValueError(f"the ladder log holds terrain-load lines for draws {sorted(logged)}"
                         f" but the ladder sampled draws {sorted(sample_ats)}")
    referenced = {"native-terrain-load.json"}
    out, mixed = [], 0
    for recount in recounts:
        at = recount["at"]
        entry = by_at[at]
        for field in TERRAIN_LOAD_COUNTS:
            if not finite_int(entry.get(field)) or entry[field] < 0:
                raise ValueError(f"terrain-LOAD at draw {at}: {field} is {entry.get(field)!r}")
        rect = recount["rect"]
        published = next((s for s in ladder_report.get("samples") or []
                          if isinstance(s, dict) and s.get("at") == at), {})
        width, height = published.get("targetWidth"), published.get("targetHeight")
        suffix = f"{width}x{height}-{rect[0]}-{rect[1]}-{rect[2]}-{rect[3]}"
        wanted = {"file": f"native-terrain-load-{at}.ppm.gz",
                  "frameFile": f"native-terrain-load-frame-{at}.ppm.gz",
                  "referenceFile": f"native-terrain-load-reference-{suffix}.ppm.gz",
                  "referenceDepthFile": f"native-terrain-load-depth-{suffix}.f32.gz"}
        for field, name in wanted.items():
            if entry.get(field) != name:
                raise ValueError(f"terrain-LOAD at draw {at} names {field}={entry.get(field)!r},"
                                 f" not {name!r}")
            if not (output / name).is_file():
                raise ValueError(f"the retained terrain-LOAD file {name} is missing")
            referenced.add(name)
        after, (aw, ah) = read_ppm_gz(output / wanted["file"])
        ladder, (lw, lh) = read_ppm_gz(output / recount["sample"])
        if (aw, ah) != (lw, lh):
            raise ValueError(f"the terrain-LOAD crop is {aw}x{ah} but the ladder crop is"
                             f" {lw}x{lh}")
        before_name = (f"native-depth-ladder-coexist-{at}.ppm.gz" if coexist_enabled
                       else recount["sample"])
        # (its size against the ladder crop is already required by coexist_checks, which runs
        # first, or it IS the ladder crop; round 18 found a second check here that no test
        # could reach)
        before, _ = read_ppm_gz(output / before_name)
        reference, (rw, rh) = read_ppm_gz(output / wanted["referenceFile"])
        depth, (dw, dh) = read_f32_gz(output / wanted["referenceDepthFile"])
        if (rw, rh) != (aw, ah) or (dw, dh) != (aw, ah):
            raise ValueError(f"the terrain-LOAD reference crops are {rw}x{rh} and {dw}x{dh},"
                             f" not the band's {aw}x{ah}")
        if any(not (0.0 <= d <= 1.0) or math.isnan(d) for row in depth for d in row):
            raise ValueError(f"the terrain-LOAD reference depth at draw {at} holds values"
                             f" outside [0, 1]")
        thumb, _ = read_ppm_gz(output / wanted["frameFile"])
        if ladder_anchor_blocks(after, rect, thumb) == 0:
            raise ValueError(f"the terrain-LOAD crop at draw {at} covers no whole thumbnail"
                             f" block, so its position cannot be checked")
        counts = terrain_load_judge(before, ladder, after, reference, depth)
        for field, value in counts.items():
            if entry[field] != value:
                raise ValueError(f"terrain-LOAD at draw {at} reports {field}={entry[field]} but"
                                 f" the retained crops say {value}")
        if log_text is not None and logged.get(at) != [counts[k] for k in TERRAIN_LOAD_COUNTS]:
            raise ValueError(f"the ladder log's terrain-load line for draw {at} says"
                             f" {logged.get(at)} but the crops say"
                             f" {[counts[k] for k in TERRAIN_LOAD_COUNTS]}")
        geometry_depths = [d for row in depth for d in row if d > 0.0]
        for field, want in (("minDepth", min(geometry_depths, default=None)),
                            ("maxDepth", max(geometry_depths, default=None))):
            got = entry.get(field)
            if want is None:
                if got is not None:
                    raise ValueError(f"terrain-LOAD at draw {at} reports {field}={got!r} with"
                                     f" no geometry in the band")
            elif not finite_number(got) or abs(got - want) > 1e-6 * max(abs(want), 1e-30):
                raise ValueError(f"terrain-LOAD at draw {at} reports {field}={got!r} but the"
                                 f" reference depth crop says {want!r}")
        if counts["geometry"] == 0:
            raise ValueError(f"terrain-LOAD at draw {at}: the reference holds no geometry in the"
                             f" band, so the pass drew nothing to judge")
        if counts["other"]:
            raise ValueError(f"terrain-LOAD at draw {at}: {counts['other']} pixel(s) are neither"
                             f" Voxy's reference colour nor the previous readback's")
        if counts["changedWhereNoGeometry"]:
            raise ValueError(f"terrain-LOAD at draw {at}: {counts['changedWhereNoGeometry']}"
                             f" pixel(s) without Voxy geometry changed")
        if counts["visibleWhereHidden"] or counts["hiddenWhereVisible"]:
            raise ValueError(f"terrain-LOAD at draw {at}: Voxy's pixel appears at"
                             f" {counts['visibleWhereHidden']} pixel(s) whose depth is at or"
                             f" below Minecraft's bracket and is missing at"
                             f" {counts['hiddenWhereVisible']} pixel(s) whose depth is at or"
                             f" above it; Voxy's terrain did not compose per pixel")
        if counts["expectVisible"] and counts["expectHidden"]:
            mixed += 1
        out.append({"at": at, **counts, **wanted})
    stray = sorted(set(retained) - referenced)
    if stray:
        raise ValueError(f"{len(stray)} retained terrain-LOAD file(s) belong to no listed result:"
                         f" {stray[:6]}")
    # the whole footprint lies inside the band (the scene's JUnit GPU test pins that), so the
    # reference pixel count the probe published must equal the non-background pixels of the
    # last extent's reference crop
    last_reference, _ = read_ppm_gz(output / out[-1]["referenceFile"])
    set_count = sum(1 for row in last_reference for px in row if tuple(px[:3]) != TERRAIN_LOAD_CLEAR_RGB)
    if report["referenceSet"] != set_count:
        raise ValueError(f"the terrain-LOAD scene publishes referenceSet={report['referenceSet']} but"
                         f" its last reference crop holds {set_count} non-background pixel(s)")
    if not mixed:
        raise ValueError("no terrain-LOAD sample holds both pixels that must appear and pixels"
                         " that must not, so the experiment decided nothing per pixel")
    return {"enabled": True, "samples": out, "mixedSamples": mixed,
            "declaredDepthState": TERRAIN_LOAD_DEPTH_STATE, "scene": TERRAIN_LOAD_SCENE}


REAL_LOAD_LEVEL, REAL_LOAD_RADIUS, REAL_LOAD_BUILD_BUDGET = 3, 4, 10
REAL_LOAD_ATLAS_PENDING_STATE = 1     # McNativeAtlas.State.PENDING.ordinal()
# Sections nearer than Minecraft's render distance are not drawn by the real-section scene
# (Voxy does not draw where vanilla terrain draws). The stage's options.txt sets renderDistance:8.
REAL_LOAD_CUT_BLOCKS = 8 * 16
REAL_LOAD_SKIP_KEYS = {"at", "status", "stage", "cameraCapture", "previousCapture", "buildsSoFar",
                       "atlasState", "cameraExtent", "frameExtent"}
REAL_LOAD_LOG_LINE = re.compile(r"real load at draw (\d+) status=(\S+)(.*)$", re.M)
ATLAS_REQUEST_LOG = re.compile(r"requested the block atlas \(")
ATLAS_READY_LOG = re.compile(r"block atlas read through Blaze3D: ")
NOTHING_MESHED_LOG = re.compile(r"real-LOAD: nothing meshed at level \d+ around \[[-\d, ]+\] \(scene #(\d+)\)")
REAL_SCENE_LOG = re.compile(r"real-LOAD scene #(\d+): (\d+) sections, (\d+) quads, (\d+) draws at level"
                            r" (\d+) around \[(-?\d+), (-?\d+), (-?\d+)\] r=(\d+) excludedNear=(\d+)"
                            r" cut=(\d+)")
REAL_LOAD_MAX_QUADS = 2_000_000       # McNativeRealLoad.MAX_QUADS


def real_load_build_attempts(text):
    """Build ordinals attempted in `text`: scenes that meshed and builds that meshed nothing."""
    return sorted([int(m.group(1)) for m in REAL_SCENE_LOG.finditer(text)]
                  + [int(m.group(1)) for m in NOTHING_MESHED_LOG.finditer(text)])


def halve_depth_range(m):
    """VkHostViewport.halveDepthRange on a column-major 16-float matrix (m[c*4+r])."""
    out = list(m)
    for c in range(4):
        out[c * 4 + 2] = (m[c * 4 + 2] + m[c * 4 + 3]) * 0.5
    return out


def real_load_skip_provenance(entry, recount, log_text, ladder_sample, spec=None):
    """Round-20 review R20-REAL-SKIP-PROVENANCE: the reason a sample was not judged must be
    corroborated by what the probe and the log saw, not only taken from a fixed vocabulary.
    `spec` names the experiment's log formats (real-LOAD by default; hierarchical-LOAD shares
    the rules)."""
    spec = spec or REAL_LOAD_SPEC
    L, l = spec["label"], spec["line_label"]
    at, status = entry["at"], entry["status"]
    extra = sorted(set(entry) - REAL_LOAD_SKIP_KEYS)
    if extra:
        raise ValueError(f"{L} skip at draw {at} carries {extra}, which a skip never has")
    if log_text is None:
        raise ValueError(f"{L} skip at draw {at} cannot be corroborated without the log")
    line = next((m for m in spec["line_re"].finditer(log_text) if int(m.group(1)) == at), None)
    if line is None or line.group(3).strip():
        raise ValueError(f"the {l} log line for skipped draw {at} is missing or carries"
                         f" more than its status")
    before = log_text[:line.start()]
    for field in ("previousCapture", "buildsSoFar", "atlasState", "cameraCapture"):
        if not finite_int(entry.get(field)):
            raise ValueError(f"{L} skip at draw {at} does not state {field}")
    # round-21: the build count a skip states is the number of build attempts logged before it
    attempts = spec["attempts"](before)
    if entry["buildsSoFar"] != len(attempts):
        raise ValueError(f"{L} skip at draw {at} says {entry['buildsSoFar']} build(s) so far but"
                         f" the log shows {len(attempts)} attempt(s) before it")
    if status == "atlas-pending":
        if entry["atlasState"] != REAL_LOAD_ATLAS_PENDING_STATE:
            raise ValueError(f"{L} skip at draw {at} says atlas-pending but the atlas state was"
                             f" {entry['atlasState']}")
        if len(ATLAS_REQUEST_LOG.findall(before)) <= len(ATLAS_READY_LOG.findall(before)):
            raise ValueError(f"{L} skip at draw {at} says atlas-pending but the log has no"
                             f" outstanding atlas request at that point")
    elif status == "no-camera-this-frame":
        if entry["previousCapture"] != entry["cameraCapture"]:
            raise ValueError(f"{L} skip at draw {at} says no camera this frame but the capture"
                             f" count moved from {entry['previousCapture']} to {entry['cameraCapture']}")
        # round-21: the instance lines on either side must show a frame without a capture
        earlier = INSTANCE_LOG.findall(before)
        later = INSTANCE_LOG.findall(log_text[line.end():])
        # INSTANCE_LOG groups: frame 0, stage 1, factory 2, instance 3, engine 4, live 5, active 6,
        # renderer 7, ingest 8, cameraCaptures 9, storedNearCamera 10
        if not earlier or not later or \
                int(later[0][9]) - int(earlier[-1][9]) >= int(later[0][0]) - int(earlier[-1][0]):
            raise ValueError(f"{L} skip at draw {at} says no camera this frame but the instance"
                             f" lines around it show a capture every frame")
    elif status == "camera-extent-mismatch":
        cam, frame = entry.get("cameraExtent"), entry.get("frameExtent")
        if not (isinstance(cam, list) and isinstance(frame, list)) or cam == frame or \
                frame != [ladder_sample.get("targetWidth"), ladder_sample.get("targetHeight")] or \
                ladder_sample.get("stage") != "resize":
            raise ValueError(f"{L} skip at draw {at} says the camera extent {cam!r} differs from"
                             f" the frame {frame!r}, which the ladder sample does not support")
    elif status == "no-world-engine":
        lines = INSTANCE_LOG.findall(before)
        # INSTANCE_LOG groups: frame, stage, factory, instance, engine, live, ...
        if not lines or (lines[-1][4] == "true" and lines[-1][5] == "true"):
            raise ValueError(f"{L} skip at draw {at} says no world engine but the last instance"
                             f" line before it shows a live engine")
    elif status == "nothing-meshed":
        previous = [m for m in spec["line_re"].finditer(before)]
        since = before[previous[-1].end():] if previous else before
        empty = spec["nothing_re"].findall(since)
        if not empty or int(empty[-1]) != entry["buildsSoFar"]:
            raise ValueError(f"{L} skip at draw {at} says nothing meshed but no build that meshed"
                             f" nothing is logged before it")
    elif status == "build-budget-spent":
        if entry["buildsSoFar"] != spec["budget"]:
            raise ValueError(f"{L} skip at draw {at} says the build budget was spent after"
                             f" {entry['buildsSoFar']} of {spec['budget']} builds")

REAL_LOAD_SKIPS = ("no-world-engine", "no-camera-this-frame", "camera-extent-mismatch",
                   "nothing-meshed", "build-budget-spent", "atlas-pending")
REAL_LOAD_LOG = re.compile(r"real load at draw (\d+) status=(\S+)(?: "
                           + " ".join(f"{name}=(\\d+)" for name in TERRAIN_LOAD_COUNTS) + ")?")
REAL_LOAD_SPEC = {"label": "real-LOAD", "line_label": "real-load", "prefix": "native-real-load",
                  "line_re": REAL_LOAD_LOG_LINE, "attempts": real_load_build_attempts,
                  "nothing_re": NOTHING_MESHED_LOG, "budget": REAL_LOAD_BUILD_BUDGET}


def load_judged_entry_checks(entry, at, report, log_text, ladder_samples_by_at, previous_judged,
                             spec):
    """The checks every judged real-world LOAD sample shares (real-LOAD, hierarchical-LOAD):
    the published projections against each other and the far plane, the stage, the camera
    capture chronology, the atlas generation against the logged reads, and scene reuse across an
    engine or atlas change. Returns the log text before the sample's own line (for the caller's
    scene-line checks)."""
    L = spec["label"]
    if not isinstance(entry.get("projectionAdjusted"), bool):
        raise ValueError(f"{L} at draw {at} does not say whether Minecraft's projection was"
                         f" adjusted to 0..1 depth")
    # round-20 R20-REAL-METADATA: reconcile the published scene and projection facts
    published = ladder_samples_by_at.get(at, {})
    if entry.get("stage") != published.get("stage"):
        raise ValueError(f"{L} at draw {at} says stage {entry.get('stage')!r} but the ladder"
                         f" sample says {published.get('stage')!r}")
    mc_p, used_p = entry.get("mcProjection"), entry.get("projection")
    if not (isinstance(mc_p, list) and isinstance(used_p, list) and len(mc_p) == 16
            and len(used_p) == 16 and all(finite_number(v) for v in mc_p + used_p)):
        raise ValueError(f"{L} at draw {at} does not publish both 16-entry projections")
    same = all(abs(a - b) <= 1e-6 * max(1.0, abs(b)) for a, b in zip(used_p, mc_p))
    halved = all(abs(a - b) <= 1e-6 * max(1.0, abs(b))
                 for a, b in zip(used_p, halve_depth_range(mc_p)))
    if entry["projectionAdjusted"] != (not same) or not (same or halved):
        raise ValueError(f"{L} at draw {at} says projectionAdjusted="
                         f"{entry['projectionAdjusted']} but its projection is"
                         f" {'Minecraft’s' if same else ('the halved range' if halved else 'neither')}")
    far = entry.get("farPlane")
    want_far = abs(used_p[14] / used_p[10]) if abs(used_p[10]) > 1e-12 else None
    if want_far is None or not finite_number(far) or abs(far - want_far) > 1e-3 * max(1.0, want_far):
        raise ValueError(f"{L} at draw {at} reports farPlane={far!r} but its projection"
                         f" gives {want_far!r}")
    if not finite_int(entry.get("cameraCapture")) or entry["cameraCapture"] < 1:
        raise ValueError(f"{L} at draw {at} has cameraCapture={entry.get('cameraCapture')!r}")
    for field in ("engineId", "sceneBuild", "atlasGeneration"):
        if not finite_int(entry.get(field)):
            raise ValueError(f"{L} at draw {at} does not state {field}")
    if entry["atlasGeneration"] < 1 or entry["atlasGeneration"] > report["atlasReads"]:
        raise ValueError(f"{L} at draw {at} names atlas generation {entry['atlasGeneration']}"
                         f" of {report['atlasReads']} read(s)")
    head = ""
    if log_text is not None:
        own = next((m for m in spec["line_re"].finditer(log_text) if int(m.group(1)) == at), None)
        head = log_text[:own.start()] if own else ""
        # round-21 R21-ATLAS-PROVENANCE: the generation is the number of atlas reads logged
        # before this sample
        reads_before = len(ATLAS_READY_LOG.findall(head))
        if entry["atlasGeneration"] != reads_before:
            raise ValueError(f"{L} at draw {at} names atlas generation"
                             f" {entry['atlasGeneration']} but {reads_before} atlas read(s) are"
                             f" logged before it")
    if previous_judged is not None:
        if entry["cameraCapture"] <= previous_judged["cameraCapture"]:
            raise ValueError(f"{L} at draw {at} has camera capture {entry['cameraCapture']},"
                             f" not after the previous judged sample's")
        if entry["sceneBuild"] < previous_judged["sceneBuild"]:
            raise ValueError(f"{L} at draw {at} uses scene build {entry['sceneBuild']}, older"
                             f" than the previous sample's")
        for field in ("engineId", "atlasGeneration"):
            if entry[field] != previous_judged[field] and \
                    entry["sceneBuild"] == previous_judged["sceneBuild"]:
                raise ValueError(f"{L} at draw {at}: {field} changed but the same scene"
                                 f" (build {entry['sceneBuild']}) was reused")
    return head


def judge_load_sample(output, recount, entry, at, coexist_enabled, logged, log_text, spec):
    """Recount one judged real-world LOAD sample from its retained files — the ladder crop, the
    previous readback (the coexist crop when that ran), the experiment's crop and thumbnail, and
    Voxy's reference colour and depth — and require zero violations. Returns (counts, the file
    names it referenced)."""
    L, l, prefix = spec["label"], spec["line_label"], spec["prefix"]
    wanted = {"file": f"{prefix}-{at}.ppm.gz",
              "frameFile": f"{prefix}-frame-{at}.ppm.gz",
              "referenceFile": f"{prefix}-reference-{at}.ppm.gz",
              "referenceDepthFile": f"{prefix}-depth-{at}.f32.gz"}
    for field, name in wanted.items():
        if entry.get(field) != name:
            raise ValueError(f"{L} at draw {at} names {field}={entry.get(field)!r}, not {name!r}")
        if not (output / name).is_file():
            raise ValueError(f"the retained {L} file {name} is missing")
    after, (aw, ah) = read_ppm_gz(output / wanted["file"])
    ladder, (lw, lh) = read_ppm_gz(output / recount["sample"])
    if (aw, ah) != (lw, lh):
        raise ValueError(f"the {L} crop is {aw}x{ah} but the ladder crop is {lw}x{lh}")
    before_name = (f"native-depth-ladder-coexist-{at}.ppm.gz" if coexist_enabled
                   else recount["sample"])
    before, _ = read_ppm_gz(output / before_name)
    reference, (rw, rh) = read_ppm_gz(output / wanted["referenceFile"])
    depth, (dw, dh) = read_f32_gz(output / wanted["referenceDepthFile"])
    if (rw, rh) != (aw, ah) or (dw, dh) != (aw, ah):
        raise ValueError(f"the {L} reference crops are {rw}x{rh} and {dw}x{dh}, not the"
                         f" band's {aw}x{ah}")
    if any(not (0.0 <= d <= 1.0) or math.isnan(d) for row in depth for d in row):
        raise ValueError(f"the {L} reference depth at draw {at} holds values outside [0, 1]")
    thumb, _ = read_ppm_gz(output / wanted["frameFile"])
    if ladder_anchor_blocks(after, recount["rect"], thumb) == 0:
        raise ValueError(f"the {L} crop at draw {at} covers no whole thumbnail block")
    counts = terrain_load_judge(before, ladder, after, reference, depth,
                                clear_only=spec.get("clear_only", False))
    for field, value in counts.items():
        if entry[field] != value:
            raise ValueError(f"{L} at draw {at} reports {field}={entry[field]} but the"
                             f" retained crops say {value}")
    if log_text is not None and logged[at][1] != [counts[k] for k in TERRAIN_LOAD_COUNTS]:
        raise ValueError(f"the ladder log's {l} line for draw {at} says {logged[at][1]}"
                         f" but the crops say {[counts[k] for k in TERRAIN_LOAD_COUNTS]}")
    geometry_depths = [d for row in depth for d in row if d > 0.0]
    for field, want in (("minDepth", min(geometry_depths, default=None)),
                        ("maxDepth", max(geometry_depths, default=None))):
        got = entry.get(field)
        if want is None:
            if got is not None:
                raise ValueError(f"{L} at draw {at} reports {field}={got!r} with no geometry")
        elif not finite_number(got) or abs(got - want) > 1e-6 * max(abs(want), 1e-30):
            raise ValueError(f"{L} at draw {at} reports {field}={got!r} but the reference"
                             f" depth crop says {want!r}")
    ref_set = sum(1 for row in reference for px in row if tuple(px[:3]) != TERRAIN_LOAD_CLEAR_RGB)
    if entry.get("referenceSet") != ref_set:
        raise ValueError(f"{L} at draw {at} reports referenceSet={entry.get('referenceSet')!r}"
                         f" but its reference crop holds {ref_set}")
    if counts["other"] or counts["changedWhereNoGeometry"] or counts["visibleWhereHidden"] \
            or counts["hiddenWhereVisible"]:
        raise ValueError(f"{L} at draw {at}: other={counts['other']},"
                         f" changedWhereNoGeometry={counts['changedWhereNoGeometry']},"
                         f" visibleWhereHidden={counts['visibleWhereHidden']},"
                         f" hiddenWhereVisible={counts['hiddenWhereVisible']}; Voxy's real"
                         f" terrain did not compose per pixel with Minecraft's matrix")
    return counts, set(wanted.values())


HIER_LOAD_LOG_LINE = re.compile(r"hier load at draw (\d+) status=(\S+)(.*)$", re.M)
HIER_LOAD_LOG = re.compile(r"hier load at draw (\d+) status=(\S+)(?: "
                           + " ".join(f"{name}=(\\d+)" for name in TERRAIN_LOAD_COUNTS) + ")?")
HIER_SCENE_LOG = re.compile(r"hier-LOAD scene #(\d+): streaming render distance (\d+), sections"
                            r" (-?\d+)\.\.(-?\d+)")
HIER_NOTHING_LOG = re.compile(r"hier-LOAD: nothing meshed \(scene #(\d+)\)")
HIER_LOAD_BUILD_BUDGET, HIER_LOAD_ITERATIONS = 6, 3
HIER_LOAD_VISIBILITY = "CULL"
# Voxy's own projection (VoxyRenderSystem.computeProjectionMat): near 16, far 16*3000, reverse Z.
HIER_VOXY_NEAR, HIER_VOXY_FAR = 16.0, 48000.0
# depth_resolve.frag keeps a reprojected depth just inside Minecraft's far plane: FAR + 2/(2^24-1)
HIER_REPROJECT_EPS = 2.0 / ((1 << 24) - 1)
# the reprojection runs in float32 on the GPU with full MVPs; this re-derives it in double from the
# two projections. A wrong or missing reprojection is off by orders of magnitude (near 16 vs 0.05).
HIER_REPROJECT_TOLERANCE = 1e-3


def voxy_near(vanilla_render_distance, sodium_chunk_render_disabled):
    """VoxyRenderSystem.computeProjectionMat's near plane: 8 at vanilla distances up to 32 blocks,
    16 above, 0.1 when Sodium's chunk rendering is disabled."""
    if sodium_chunk_render_disabled:
        return 0.1
    return 8.0 if vanilla_render_distance <= 32.0 else 16.0


def voxy_projection(mc, near=HIER_VOXY_NEAR):
    """Minecraft's reverse-Z 0..1 projection with only its depth row (m22, m32 — column-major
    indices 10, 14) replaced by Voxy's near/far; near and far swap for reverse Z."""
    m = list(mc)
    n, f = HIER_VOXY_FAR, near
    m[10], m[14] = f / (n - f), f * n / (n - f)
    return m


def _mat_invert(m):
    """Inverse of a column-major 4x4 matrix (Gauss-Jordan, partial pivoting)."""
    a = [[m[c * 4 + r] for c in range(4)] + [1.0 if r == k else 0.0 for k in range(4)] for r in range(4)]
    for col in range(4):
        piv = max(range(col, 4), key=lambda r: abs(a[r][col]))
        if abs(a[piv][col]) < 1e-30:
            raise ValueError("a projection is singular")
        a[col], a[piv] = a[piv], a[col]
        p = a[col][col]
        a[col] = [v / p for v in a[col]]
        for r in range(4):
            if r != col and a[r][col] != 0.0:
                fac = a[r][col]
                a[r] = [v - fac * w for v, w in zip(a[r], a[col])]
    return [a[r][4 + c] for c in range(4) for r in range(4)]


def reproject_depth(inv_src, dst, ndc_x, ndc_y, depth):
    """depth_resolve.frag's REPROJECT_DEPTH in double: unproject with the source's inverse,
    project with the destination, no clamp."""
    point = _mat_project(inv_src, ndc_x, ndc_y, depth)
    return _mat_project(dst, *point)[2]


def hier_reprojection_checks(output, entry, at, recount, ladder_sample):
    """Voxy renders with its own projection; the composite (and so the judged reference depth) uses
    that depth rewritten in Minecraft's depth space. Re-derive every reference pixel from Voxy's
    retained raw depth and the two published projections. Returns (pixels clamped just inside
    Minecraft's far plane, the file name)."""
    L = HIER_LOAD_SPEC["label"]
    name = f"native-hier-load-voxydepth-{at}.f32.gz"
    if entry.get("voxyDepthFile") != name or not (output / name).is_file():
        raise ValueError(f"{L} at draw {at} names voxyDepthFile={entry.get('voxyDepthFile')!r}"
                         f" (want {name!r}, retained)")
    proj, vp, raw = entry.get("projection"), entry.get("voxyProjection"), entry.get("rawProjection")
    for label, m in (("projection", proj), ("voxyProjection", vp), ("rawProjection", raw)):
        if not isinstance(m, list) or len(m) != 16 or not all(finite_number(v) for v in m):
            raise ValueError(f"{L} at draw {at} publishes {label}={m!r}, not 16 numbers")
    # round-26 R26-PROJECTION-EQUIVALENCE: VoxyRenderSystem.computeProjectionMat in full — the
    # raw camera projection's depth row replaced (near from the vanilla distance), times the
    # extra transforms this frame's projection carries over the raw one
    distance, sodium_off = entry.get("vanillaRenderDistance"), entry.get("sodiumChunkRenderDisabled")
    if not finite_number(distance) or not isinstance(sodium_off, bool):
        raise ValueError(f"{L} at draw {at} states vanillaRenderDistance={distance!r},"
                         f" sodiumChunkRenderDisabled={sodium_off!r}")
    near = voxy_near(distance, sodium_off)
    if not finite_number(entry.get("voxyNear")) or abs(entry["voxyNear"] - near) > 1e-6:
        raise ValueError(f"{L} at draw {at} renders with near {entry.get('voxyNear')!r}, but a vanilla"
                         f" distance of {distance} blocks gives Voxy's near {near}")
    want = _mat_mul(_mat_mul(voxy_projection(raw, near), _mat_invert(raw)), proj)
    if any(abs(a - b) > 1e-5 * max(1.0, abs(b)) for a, b in zip(vp, want)):
        raise ValueError(f"{L} at draw {at}: voxyProjection is not Voxy's projection of its raw"
                         f" camera projection (near {near} / far {HIER_VOXY_FAR}) with this frame's"
                         f" extra transforms")
    raw, (w, h) = read_f32_gz(output / name)
    ref, (rw, rh) = read_f32_gz(output / entry["referenceDepthFile"])
    if (w, h) != (rw, rh):
        raise ValueError(f"{L} at draw {at}: Voxy's depth crop is {w}x{h}, the reference {rw}x{rh}")
    # the ladder gate has already refused any sample without a positive integer frame extent
    W, H = ladder_sample["targetWidth"], ladder_sample["targetHeight"]
    x0, y0 = recount["rect"][0], recount["rect"][1]
    inv = _mat_invert(vp)
    beyond = 0
    for j in range(h):
        ndc_y = (y0 + j + 0.5) / H * 2.0 - 1.0
        for i in range(w):
            r, d = raw[j][i], ref[j][i]
            if r == 0.0:
                if d != 0.0:
                    raise ValueError(f"{L} at draw {at}: pixel ({x0 + i}, {y0 + j}) has no Voxy"
                                     f" depth but reference depth {d}")
                continue
            if not (0.0 < r <= 1.0):
                raise ValueError(f"{L} at draw {at}: Voxy's depth {r} at ({x0 + i}, {y0 + j}) is"
                                 f" outside (0, 1]")
            v = reproject_depth(inv, proj, (x0 + i + 0.5) / W * 2.0 - 1.0, ndc_y, r)
            if v <= HIER_REPROJECT_EPS:
                beyond += 1
            v = max(HIER_REPROJECT_EPS, v)
            if abs(v - d) > HIER_REPROJECT_TOLERANCE * v:
                raise ValueError(f"{L} at draw {at}: Voxy's depth {r} at ({x0 + i}, {y0 + j})"
                                 f" reprojects to {v} but the reference depth is {d}")
    return beyond, name
HIER_LOAD_SKIP_KEYS = {"at", "status", "stage", "cameraCapture", "previousCapture", "buildsSoFar",
                       "atlasState"}


def hier_stream_problem(report):
    """Why the probe's streaming facts are not Voxy's (None if they are): it streams at
    ceil(sectionRenderDistance + 1) top-level columns, as VoxyRenderSystem.setRenderDistance, and
    held at least one top-level node."""
    distance = report.get("sectionRenderDistance")
    if report.get("streaming") is not True or not finite_number(distance):
        return (f"says streaming={report.get('streaming')!r},"
                f" sectionRenderDistance={distance!r}")
    if report.get("streamRenderDistance") != math.ceil(distance + 1):
        return (f"streams at {report.get('streamRenderDistance')!r} columns, not"
                f" ceil({distance} + 1)")
    if not finite_int(report.get("maxTopLevels")) or report["maxTopLevels"] < 1:
        return f"held {report.get('maxTopLevels')!r} top-level node(s); nothing was streamed in"
    # the near cut (GL's BoundRenderer): Minecraft's built, visible sections bound Voxy's depth
    if report.get("vanillaBound") is not True or not finite_int(report.get("maxBoundSections")) \
            or report["maxBoundSections"] < 1:
        return (f"says vanillaBound={report.get('vanillaBound')!r} with"
                f" {report.get('maxBoundSections')!r} vanilla section(s) in the bound; no near cut")
    # Minecraft's own lightmap (GL Voxy samples it directly), not the synthetic uniform one
    if report.get("lightmapFailure") is not None or not finite_int(report.get("lightmapsApplied")) \
            or report["lightmapsApplied"] < 1:
        return (f"applied {report.get('lightmapsApplied')!r} Minecraft lightmap(s)"
                f" (failure {report.get('lightmapFailure')!r}); Voxy kept the synthetic lighting")
    return None


def hier_load_build_attempts(text):
    return sorted([int(m.group(1)) for m in HIER_SCENE_LOG.finditer(text)]
                  + [int(m.group(1)) for m in HIER_NOTHING_LOG.finditer(text)])


HIER_FRAMES_LOG = re.compile(r"hier frames before draw (\d+): composited=(\d+)")


def hier_frames_checks(report, log_text, entries, required, ladder_draws=None):
    """The every-frame path (voxy.native.hierframes): the hierarchical scene rendered and
    composited on every frame, not only on handed samples. Those frames are not judged per pixel
    (the handed samples are); this reconciles what the probe says it composited with the log:
    one "hier frames before draw N" line per handed sample, counts that never fall, and frames
    composited between every two consecutive judged samples."""
    L = HIER_LOAD_SPEC["label"]
    for field, kind in (("everyFrame", bool), ("framesComposited", int), ("frameSkips", dict)):
        if field not in report:
            raise ValueError(f"the {L} report does not state {field}")
        value = report[field]
        if (kind is bool and not isinstance(value, bool)) or (kind is int and not finite_int(value)) \
                or (kind is dict and not isinstance(value, dict)):
            raise ValueError(f"hierLoad.{field} is {value!r}, not a {kind.__name__}")
    every, composited, skips = report["everyFrame"], report["framesComposited"], report["frameSkips"]
    for reason, n in skips.items():
        if reason not in REAL_LOAD_SKIPS or not finite_int(n) or n < 1:
            raise ValueError(f"hierLoad.frameSkips holds {reason!r}: {n!r}, not a skip reason with a"
                             f" positive count")
    logged = [(int(a), int(n)) for a, n in HIER_FRAMES_LOG.findall(log_text or "")]
    if required and not every:
        raise ValueError(f"the ladder launch enabled every-frame rendering but the {L} probe says"
                         f" everyFrame=false")
    if every and not required:
        raise ValueError(f"the {L} probe says everyFrame=true but the ladder launch did not enable it")
    if not every:
        if composited or skips or logged:
            raise ValueError(f"the {L} probe composited {composited} frame(s), skipped {skips} and"
                             f" logged {len(logged)} frame line(s) with every-frame rendering off")
        return {"everyFrame": False}
    if composited <= 0:
        raise ValueError(f"every-frame rendering was on but the {L} probe composited no frame")
    before = {}
    if log_text is not None:
        for at, n in logged:
            if at in before:
                raise ValueError(f"the ladder log holds two hier-frames lines for draw {at}")
            before[at] = n
        handed = sorted(e["at"] for e in entries)
        if sorted(before) != handed:
            raise ValueError(f"hier-frames lines exist for draws {sorted(before)} but the ladder"
                             f" handed draws {handed}; every handed sample logs one")
        counts = [before[at] for at in handed]
        if counts != sorted(counts) or (counts and counts[-1] > composited):
            raise ValueError(f"the hier-frames counts {counts} fall, or exceed the"
                             f" {composited} frame(s) the probe reports")
        judged = [e["at"] for e in sorted(entries, key=lambda e: e["at"]) if e["status"] == "judged"]
        for a, b in zip(judged, judged[1:]):
            if before[b] <= before[a]:
                raise ValueError(f"no frame was composited between the judged samples at draws {a}"
                                 f" and {b} ({before[a]} then {before[b]})")
    # round-25 R25-FRAME-ACCOUNTING: every frame the level-render tail ran is one of: composited,
    # skipped with a reason, or a handed sample (judged or skipped as a result). The ladder counts
    # the same frames independently (it draws on each, ahead of this probe).
    accounted = composited + sum(skips.values()) + len(entries)
    if ladder_draws is not None and accounted != ladder_draws:
        raise ValueError(f"the {L} probe accounts for {accounted} frame(s) ({composited} composited,"
                         f" {sum(skips.values())} skipped, {len(entries)} handed) but the ladder drew"
                         f" on {ladder_draws}")
    return {"everyFrame": True, "framesComposited": composited, "frameSkips": dict(skips),
            "composedBeforeSample": before}


HIER_LOAD_SPEC = {"label": "hierarchical-LOAD", "line_label": "hier-load", "prefix": "native-hier-load",
                  "line_re": HIER_LOAD_LOG_LINE, "attempts": hier_load_build_attempts,
                  "nothing_re": HIER_NOTHING_LOG, "budget": HIER_LOAD_BUILD_BUDGET,
                  # Voxy's GL rule: shown exactly on CLEAR pixels, hidden on every other
                  "clear_only": True}


def hier_load_checks(output, ladder_report, recounts, coexist_enabled, log_text, required=False,
                     require_frames=False):
    """Voxy's HIERARCHICAL pipeline natively (VkHierarchicalScene: real mapper/bakery,
    NodeManager, HiZ, traversal, prep/cull, table, opaque/temporal/translucent, Voxy's CULL mode),
    driven with Voxy's own projection of Minecraft's camera into Voxy's own target, its depth
    reprojected into Minecraft's space, and composited into a pass that LOADs Minecraft's colour
    and depth by Voxy's GL rule (only where Minecraft's depth is still clear; fragment depth 0,
    GREATER_OR_EQUAL, writes off). Judged per pixel with judge_load_sample in clear-only mode
    against the ladder's CLEAR class of the same frame: Voxy shows on CLEAR pixels where it drew
    and nowhere else; every reference depth re-derived from Voxy's raw depth
    (hier_reprojection_checks); skips corroborated the same way (real_load_skip_provenance with this experiment's log
    formats). At least one judged sample must hold pixels that must appear and one pixels that
    must be hidden."""
    L = HIER_LOAD_SPEC["label"]
    enabled = ladder_report.get("hierLoadEnabled")
    report_path = output / "native-hier-load.json"
    logged = {}
    if log_text is not None:
        for m in HIER_LOAD_LOG.findall(log_text):
            if int(m[0]) in logged:
                raise ValueError(f"the ladder log holds two hier-load lines for draw {m[0]}")
            logged[int(m[0])] = (m[1], [int(v) for v in m[2:] if v != ""])
    retained = sorted(p.name for p in output.glob("native-hier-load-*"))
    if required and not enabled:
        raise ValueError("the ladder launch enabled the hierarchical-LOAD experiment but the ladder"
                         " says hierLoadEnabled=false; the experiment's evidence is required")
    if not enabled:
        if report_path.is_file() or logged or retained:
            raise ValueError("hierarchical-LOAD evidence is retained although the ladder says the"
                             " experiment was off")
        return {"enabled": False}
    if not report_path.is_file():
        raise ValueError("the hierarchical-LOAD experiment was on but native-hier-load.json is not"
                         " retained")
    report = json.loads(report_path.read_text())
    for field, kind in (("enabled", bool), ("attempted", bool), ("drawsRecorded", int),
                        ("builds", int), ("buildBudget", int), ("iterations", int),
                        ("streaming", bool), ("streamRenderDistance", int), ("maxTopLevels", int),
                        ("declaredDepthState", list),
                        ("depthStateReadBack", bool), ("instanceMode", bool), ("results", list),
                        ("problems", int), ("firstProblem", (str, type(None))),
                        ("closeFailures", int), ("leakedScenes", int), ("deviceDiverged", bool),
                        ("readbacksInFlight", int), ("atlasReads", int),
                        ("device", (str, type(None))), ("notes", list)):
        if field not in report:
            raise ValueError(f"the {L} report does not state {field}")
        value = report[field]
        if kind is bool:
            if not isinstance(value, bool):
                raise ValueError(f"hierLoad.{field} is {value!r}, not a bool")
        elif kind is int:
            if not finite_int(value):
                raise ValueError(f"hierLoad.{field} is {value!r}, not an int")
        elif not isinstance(value, kind):
            raise ValueError(f"hierLoad.{field} is {value!r}, not a {kind}")
    if not report["enabled"] or not report["attempted"] or not report["instanceMode"]:
        raise ValueError(f"the {L} probe reports enabled={report['enabled']},"
                         f" attempted={report['attempted']}, instanceMode={report['instanceMode']}")
    if (report["buildBudget"], report["iterations"]) != (HIER_LOAD_BUILD_BUDGET, HIER_LOAD_ITERATIONS):
        raise ValueError(f"the {L} probe runs budget {report['buildBudget']}, {report['iterations']}"
                         f" iteration(s), not what its source lays out")
    stream_problem = hier_stream_problem(report)
    if stream_problem:
        raise ValueError(f"the {L} probe {stream_problem}")
    if report.get("voxyFar") != HIER_VOXY_FAR:
        raise ValueError(f"the {L} probe renders with far {report.get('voxyFar')!r}, not Voxy's"
                         f" {HIER_VOXY_FAR}")
    if report["declaredDepthState"] != TERRAIN_LOAD_DEPTH_STATE or report["depthStateReadBack"]:
        raise ValueError(f"the {L} probe declares {report['declaredDepthState']!r}"
                         f" (read back: {report['depthStateReadBack']}), not Voxy's declared"
                         f" {TERRAIN_LOAD_DEPTH_STATE}")
    if report["notes"] or report["problems"] or report["closeFailures"] or report["leakedScenes"] \
            or report["firstProblem"] is not None or report["deviceDiverged"] or report["readbacksInFlight"]:
        raise ValueError(f"the {L} probe reports notes={report['notes']},"
                         f" problems={report['problems']}, closeFailures={report['closeFailures']},"
                         f" leakedScenes={report['leakedScenes']}, firstProblem={report['firstProblem']!r},"
                         f" deviceDiverged={report['deviceDiverged']},"
                         f" readbacksInFlight={report['readbacksInFlight']}")
    if log_text is not None:
        attempts = hier_load_build_attempts(log_text)
        if attempts != list(range(1, len(attempts) + 1)) or report["builds"] != len(attempts):
            raise ValueError(f"the {L} probe reports {report['builds']} build(s) but the log shows"
                             f" build attempts {attempts}")
        logged_reads = len(ATLAS_READY_LOG.findall(log_text))
        if report["atlasReads"] != logged_reads:
            raise ValueError(f"the {L} probe reports {report['atlasReads']} atlas read(s) but the"
                             f" log shows {logged_reads}")
    entries = report["results"]
    ats = []
    for entry in entries:
        if not isinstance(entry, dict) or not finite_int(entry.get("at")) \
                or not isinstance(entry.get("status"), str):
            raise ValueError(f"a {L} result is malformed: {entry!r}")
        ats.append(entry["at"])
    if len(ats) != len(set(ats)):
        raise ValueError(f"the {L} results repeat a draw: {sorted(ats)}")
    by_at = {e["at"]: e for e in entries}
    sample_ats = [r["at"] for r in recounts]
    if sorted(by_at) != sorted(sample_ats):
        raise ValueError(f"{L} results exist for draws {sorted(by_at)} but the ladder handed it"
                         f" draws {sorted(sample_ats)}; every handed sample must carry one")
    frames = hier_frames_checks(report, log_text, entries, require_frames,
                                ladder_report.get("drawsRecorded"))
    if log_text is not None and sorted(logged) != sorted(sample_ats):
        raise ValueError(f"the ladder log holds hier-load lines for draws {sorted(logged)} but the"
                         f" ladder handed it draws {sorted(sample_ats)}")
    judged = [e for e in entries if e["status"] == "judged"]
    if report["drawsRecorded"] != len(judged):
        raise ValueError(f"the {L} probe recorded {report['drawsRecorded']} pass(es) but judged"
                         f" {len(judged)} sample(s); one pass per judged sample")
    if ladder_report.get("hierLoadDrawsRecorded") != report["drawsRecorded"]:
        raise ValueError(f"the ladder saw {ladder_report.get('hierLoadDrawsRecorded')!r} {L}"
                         f" pass(es) but the probe reports {report['drawsRecorded']}")
    if judged:
        if report["device"] is None:
            raise ValueError(f"the {L} probe judged samples but names no device")
        if device_handle_of(report["device"], "hierLoad", "device") != \
                device_handle_of(ladder_report["device"], "ladder", "device"):
            raise ValueError(f"the {L} probe names device {report['device']} but the ladder names"
                             f" {ladder_report['device']}")
        if report["atlasReads"] < 1:
            raise ValueError(f"the {L} probe judged samples but never read the block atlas")
        builds_used = [e.get("sceneBuild") for e in judged]
        if not all(finite_int(b) and 1 <= b <= report["builds"] for b in builds_used):
            raise ValueError(f"the judged samples name scene builds {builds_used} but the probe"
                             f" built {report['builds']}")
    ladder_samples_by_at = {s.get("at"): s for s in ladder_report.get("samples") or [] if isinstance(s, dict)}
    referenced = {"native-hier-load.json"}
    out, visible_samples, hidden_samples, previous_judged = [], 0, 0, None
    beyond_far = 0
    total = {name: 0 for name in TERRAIN_LOAD_COUNTS}
    for recount in recounts:
        at = recount["at"]
        entry = by_at[at]
        status = entry["status"]
        if log_text is not None and logged[at][0] != status:
            raise ValueError(f"the ladder log's hier-load line for draw {at} says status"
                             f" {logged[at][0]!r} but the report says {status!r}")
        if status != "judged":
            if status not in REAL_LOAD_SKIPS:
                raise ValueError(f"{L} at draw {at} has status {status!r}, not 'judged' nor a"
                                 f" reason from {REAL_LOAD_SKIPS}")
            extra = sorted(set(entry) - HIER_LOAD_SKIP_KEYS)
            if extra:
                raise ValueError(f"{L} skip at draw {at} carries {extra}, which a skip never has")
            real_load_skip_provenance(entry, recount, log_text, ladder_samples_by_at.get(at, {}),
                                      HIER_LOAD_SPEC)
            out.append({"at": at, "status": status})
            continue
        for field in TERRAIN_LOAD_COUNTS:
            if not finite_int(entry.get(field)) or entry[field] < 0:
                raise ValueError(f"{L} at draw {at}: {field} is {entry.get(field)!r}")
        head = load_judged_entry_checks(entry, at, report, log_text, ladder_samples_by_at,
                                        previous_judged, HIER_LOAD_SPEC)
        # round-24 R24-HIER-CULL: the scene must run Voxy's production visibility mode (the raster
        # cull writes visibility, so the temporal pass draws the newly visible subset)
        if entry.get("visibility") != HIER_LOAD_VISIBILITY:
            raise ValueError(f"{L} at draw {at} ran visibility mode {entry.get('visibility')!r}, not"
                             f" {HIER_LOAD_VISIBILITY!r}: the raster cull and temporal pass did not run")
        if entry.get("iterationsRun") != HIER_LOAD_ITERATIONS:
            raise ValueError(f"{L} at draw {at} ran {entry.get('iterationsRun')!r} iteration(s), not"
                             f" {HIER_LOAD_ITERATIONS}")
        # a streaming scene starts empty (meshedAtBuild, normally 0) and serviceRequests meshes
        # what the traversal asks for, so `meshed` (now) can only be at least that
        if not finite_int(entry.get("meshedAtBuild")) or not finite_int(entry.get("meshed")) \
                or entry["meshedAtBuild"] < 0 or entry["meshed"] < entry["meshedAtBuild"]:
            raise ValueError(f"{L} at draw {at} has {entry.get('meshed')!r} meshed sections now and"
                             f" {entry.get('meshedAtBuild')!r} at build")
        if log_text is not None:
            scene = {int(m.group(1)): m for m in HIER_SCENE_LOG.finditer(head)}.get(entry["sceneBuild"])
            want = None if scene is None else int(scene.group(2))
            if want != report["streamRenderDistance"]:
                raise ValueError(f"{L} at draw {at} names scene #{entry['sceneBuild']} but the log's"
                                 f" build line streams at {want!r}, not the report's"
                                 f" {report['streamRenderDistance']}")
        previous_judged = entry
        counts, names = judge_load_sample(output, recount, entry, at, coexist_enabled, logged,
                                          log_text, HIER_LOAD_SPEC)
        referenced.update(names)
        beyond, raw_name = hier_reprojection_checks(output, entry, at, recount,
                                                    ladder_samples_by_at.get(at, {}))
        referenced.add(raw_name)
        beyond_far += beyond
        visible_samples += bool(counts["expectVisible"])
        hidden_samples += bool(counts["expectHidden"])
        for k in total:
            total[k] += counts[k]
        out.append({"at": at, "status": status, "stage": entry.get("stage"), **counts})
    stray = sorted(set(retained) - referenced)
    if stray:
        raise ValueError(f"{len(stray)} retained {L} file(s) belong to no judged result: {stray[:6]}")
    if not visible_samples:
        raise ValueError(f"no judged {L} sample holds a pixel where Voxy's terrain must appear, so"
                         f" the experiment decided nothing")
    if not hidden_samples:
        raise ValueError(f"no judged {L} sample holds a pixel where Voxy's terrain must be hidden"
                         f" behind nearer Minecraft geometry, so occlusion is untested")
    return {"enabled": True, "samples": out, "judged": len(judged),
            "visibleSamples": visible_samples, "hiddenSamples": hidden_samples,
            "expectVisible": total["expectVisible"], "expectHidden": total["expectHidden"],
            "undetermined": total["undetermined"], "geometry": total["geometry"],
            "frames": frames, "beyondMinecraftFar": beyond_far}


# The product launch (voxy.native.render): the native path as normal play would run it, without
# the ladder or any other diagnostic.
RENDER_LAUNCH_FLAGS = ("-PharnessNativeRender=true",)
RENDER_STAGE_LOG = re.compile(r"hier frames entering stage (\w+): composited=(\d+) skipped=(\d+)"
                              r" builds=(\d+)")
RENDER_SKIPS = tuple(r for r in REAL_LOAD_SKIPS if r != "build-budget-spent") + ("rebuild-wait",
                                                                                 "rendering-disabled")
# Launch properties that turn on a diagnostic (or a part of the native path on its own); the
# product launch passes none of them (round-27 R27-RENDER-GATE).
RENDER_FORBIDDEN_LAUNCH = ("harnessNativeDepthLadder", "harnessNativeCoexist",
                           "harnessNativeTerrainLoad", "harnessNativeRealLoad",
                           "harnessNativeHierLoad", "harnessNativeHierFrames",
                           "harnessNativeInstance", "harnessNativeMarker", "harnessNativeTerrain",
                           "harnessNativeDepth", "harnessNativeProbe", "harnessNativeFeatures",
                           "harnessNativeAdopt")
# An off diagnostic's shutdown report may state these descriptors; every other field must be
# false, zero, empty or null.
RENDER_OFF_DESCRIPTORS = {"buildBudget", "level", "radius", "declaredDepthState", "instanceMode",
                          "atlasReads"}
# Lifecycle stages in which normal play must composite Voxy's scene. Not the nether: its build
# meshes nothing until ingest has stored sections there, which the harness does not wait for.
RENDER_REQUIRED_STAGES = tuple(s for s in LIFECYCLE_STAGES if s != "nether")
# Diagnostics that must not run in the product launch (their evidence files).
RENDER_FORBIDDEN_FILES = ("native-depth-ladder.json", "native-real-load.json",
                          "native-terrain-load.json", "native-instance.json",
                          "native-marker-draw.json", "native-terrain-probe.json",
                          "native-depth-probe.json")


def render_off_report_problems(name, body, product):
    """Why a diagnostic's report in the product launch is not an inert off-report."""
    if not isinstance(body, dict):
        return [f"{name} is not an object"]
    problems = []
    for field in ("enabled", "attempted"):
        if body.get(field) is not False:
            problems.append(f"{name} says {field}={body.get(field)!r}")
    for field, value in body.items():
        if field in ("enabled", "attempted") or field in RENDER_OFF_DESCRIPTORS:
            continue
        inert = (value is False or value is None or (finite_int(value) and value == 0)
                 or (isinstance(value, (list, dict)) and not value))
        if not inert:
            problems.append(f"{name} states {field}={value!r}")
    if "atlasReads" in body and body["atlasReads"] != product.get("atlasReads"):
        problems.append(f"{name} counts {body['atlasReads']!r} atlas read(s), the shared atlas"
                        f" {product.get('atlasReads')!r}")
    return problems


def native_render_result(output, log_text, expected_device=None, command=None):
    """The product launch: Voxy on Minecraft's Vulkan backend with only voxy.native.render — its
    device features, adoption, instance and every-frame hierarchical composite (Voxy's GL rule),
    no ladder, no judged samples. Not judged per pixel (the ladder launch judges the same path);
    this checks that the switch alone runs it through the lifecycle: a clean probe report, every
    frame accounted for, builds and atlas reads reconciled with the log, and frames composited in
    every required stage."""
    L = "product render"
    result = {"success": False, "failures": [],
              "scope": "the native path under the product switch alone; per-pixel correctness is"
                       " the ladder launch's"}
    try:
        # round-27 R27-RENDER-GATE: the launch is the product switch alone
        if not isinstance(command, list) or any(f not in command for f in RENDER_LAUNCH_FLAGS):
            raise ValueError(f"the {L} launch command {command!r} lacks the product switch")
        enabling = [prop for prop in RENDER_FORBIDDEN_LAUNCH if launch_enables(command, prop)]
        if enabling:
            raise ValueError(f"the {L} launch command enables diagnostics: {enabling}")
        # its own scenario: complete, every lifecycle checkpoint, one device (the screenshots are
        # checked by the stage; retained evidence keeps thumbnails)
        environment = native_environment_result(output, require_screenshots=False)
        if not environment["success"]:
            raise ValueError(f"the {L} launch's environment: {environment['failures']}")
        report = json.loads((output / "native-hier-load.json").read_text())
        # a diagnostic that is off still writes its report at shutdown (measured: real-LOAD's);
        # it must be inert in every counter, not merely say enabled=false
        present = []
        for name in RENDER_FORBIDDEN_FILES:
            path = output / name
            if path.is_file():
                present += render_off_report_problems(name, json.loads(path.read_text()), report)
        if present:
            raise ValueError(f"the {L} launch ran diagnostics: {present}")
        want = {"enabled": True, "attempted": True, "everyFrame": True, "product": True,
                "buildBudgetApplies": False, "instanceMode": True, "drawsRecorded": 0,
                "results": [], "problems": 0, "firstProblem": None, "notes": [],
                "closeFailures": 0, "leakedScenes": 0, "deviceDiverged": False,
                "readbacksInFlight": 0}
        for field, value in want.items():
            got = report.get(field)
            if got != value or isinstance(got, bool) != isinstance(value, bool):
                raise ValueError(f"the {L} report says {field}={got!r}, not {value!r}")
        composited, skips, calls = (report.get("framesComposited"), report.get("frameSkips"),
                                    report.get("renderCalls"))
        if not finite_int(composited) or not finite_int(calls) or not isinstance(skips, dict):
            raise ValueError(f"the {L} report states framesComposited={composited!r},"
                             f" renderCalls={calls!r}, frameSkips={skips!r}")
        for reason, n in skips.items():
            if reason not in RENDER_SKIPS or not finite_int(n) or n < 1:
                raise ValueError(f"the {L} report skips {n!r} frame(s) for {reason!r}, not a"
                                 f" reason from {RENDER_SKIPS}")
        if composited < 1:
            raise ValueError(f"the {L} launch composited no frame")
        stream_problem = hier_stream_problem(report)
        if stream_problem:
            raise ValueError(f"the {L} probe {stream_problem}")
        if composited + sum(skips.values()) != calls:
            raise ValueError(f"the {L} probe ran {calls} time(s) but accounts for"
                             f" {composited} composited + {sum(skips.values())} skipped")
        if expected_device is not None and device_handle_of(report.get("device"), "render",
                                                            "device") != expected_device:
            raise ValueError(f"the {L} probe names device {report.get('device')!r}, not the"
                             f" checkpoints' device")
        if log_text is None:
            raise ValueError(f"the {L} launch's log is not available")
        log_problems = native_log_problems(log_text)
        if log_problems:
            raise ValueError(f"the {L} launch's log: {log_problems}")
        attempts = hier_load_build_attempts(log_text)
        if not attempts or attempts != list(range(1, len(attempts) + 1)) \
                or report.get("builds") != len(attempts):
            raise ValueError(f"the {L} probe reports {report.get('builds')!r} build(s) but the log"
                             f" shows build attempts {attempts}")
        reads = len(ATLAS_READY_LOG.findall(log_text))
        if report.get("atlasReads") != reads or reads < 1:
            raise ValueError(f"the {L} probe reports {report.get('atlasReads')!r} atlas read(s)"
                             f" but the log shows {reads}")
        lines, current = [], None
        for m in re.finditer(r"\[voxy-harness\] stage=(\w+)|" + RENDER_STAGE_LOG.pattern, log_text):
            if m.group(1):
                current = m.group(1)
                continue
            line = (m.group(2), int(m.group(3)), int(m.group(4)), int(m.group(5)))
            # round-27: a snapshot belongs to the harness stage current when it was written
            if line[0] != current:
                raise ValueError(f"the {L} log's snapshot for stage {line[0]!r} was written during"
                                 f" harness stage {current!r}")
            lines.append(line)
        stages = [line[0] for line in lines]
        if len(stages) != len(set(stages)):
            raise ValueError(f"the {L} log enters a stage twice: {stages}")
        # (each snapshot lies inside its own harness stage, so they follow the harness's order)
        totals = [line[1] for line in lines] + [composited]
        skipped = [line[2] for line in lines] + [sum(skips.values())]
        built = [line[3] for line in lines] + [report["builds"]]
        if totals != sorted(totals) or skipped != sorted(skipped) or built != sorted(built):
            raise ValueError(f"the {L} log's running totals fall or exceed the final report: {lines}")
        growth = {stage: totals[i + 1] - totals[i] for i, stage in enumerate(stages)}
        missing = [st for st in RENDER_REQUIRED_STAGES if growth.get(st, 0) < 1]
        if missing:
            raise ValueError(f"the {L} launch composited no frame in stage(s) {missing}"
                             f" (per stage: {growth})")
        result.update(success=True, framesComposited=composited, frameSkips=dict(skips),
                      builds=report["builds"], perStage=growth,
                      answer=f"the product switch alone ran Voxy's hierarchical scene on Minecraft's"
                             f" Vulkan backend and composited {composited} frame(s), in every"
                             f" required lifecycle stage")
    except (OSError, ValueError, KeyError, TypeError) as exc:
        result["failures"].append(f"{type(exc).__name__}: {exc}")
    return result


def real_load_checks(output, ladder_report, recounts, coexist_enabled, log_text, required=False):
    """REAL sections (Voxy's world engine, meshed at a coarse level around the camera), drawn
    through Voxy's terrain pipeline with MINECRAFT'S OWN matrix into a pass that LOADs
    Minecraft's colour and depth, on the frames the ladder handed to this experiment; judged per
    pixel exactly like terrain-LOAD, against the ladder's bracket and Voxy's own reference
    render of the same frame (same matrix, same resources).

    Every handed sample carries a result: 'judged', or a reason from a fixed set. Judged samples
    must have zero violations; at least one judged sample must hold pixels that must appear
    (Voxy's terrain where Minecraft's depth is farther — over its sky beyond its render
    distance) or the experiment decided nothing. Pixels that must be hidden are counted and
    reported, not required: with the same world ingested, Voxy's surfaces mostly coincide with
    Minecraft's, which is undetermined by construction.
    """
    enabled = ladder_report.get("realLoadEnabled")
    report_path = output / "native-real-load.json"
    logged = {}
    if log_text is not None:
        for m in REAL_LOAD_LOG.findall(log_text):
            if int(m[0]) in logged:
                raise ValueError(f"the ladder log holds two real-load lines for draw {m[0]}")
            logged[int(m[0])] = (m[1], [int(v) for v in m[2:] if v != ""])
    retained = sorted(p.name for p in output.glob("native-real-load-*"))
    if required and not enabled:
        raise ValueError("the ladder launch enabled the real-LOAD experiment but the ladder says"
                         " realLoadEnabled=false; the experiment's evidence is required")
    if not enabled:
        if report_path.is_file() or logged or retained:
            raise ValueError("real-LOAD evidence is retained although the ladder says the"
                             " experiment was off")
        return {"enabled": False}
    if not report_path.is_file():
        raise ValueError("the real-LOAD experiment was on but native-real-load.json is not retained")
    report = json.loads(report_path.read_text())
    for field, kind in (("enabled", bool), ("attempted", bool), ("drawsRecorded", int),
                        ("builds", int), ("buildBudget", int), ("level", int), ("radius", int),
                        ("declaredDepthState", list), ("depthStateReadBack", bool),
                        ("instanceMode", bool), ("results", list), ("problems", int),
                        ("firstProblem", (str, type(None))), ("closeFailures", int),
                        ("leakedScenes", int), ("deviceDiverged", bool), ("readbacksInFlight", int),
                        ("atlasReads", int), ("atlasCloseFailures", int),
                        ("device", (str, type(None))), ("notes", list)):
        if field not in report:
            raise ValueError(f"the real-LOAD report does not state {field}")
        value = report[field]
        if kind is bool:
            if not isinstance(value, bool):
                raise ValueError(f"realLoad.{field} is {value!r}, not a bool")
        elif kind is int:
            if not finite_int(value):
                raise ValueError(f"realLoad.{field} is {value!r}, not an int")
        elif not isinstance(value, kind):
            raise ValueError(f"realLoad.{field} is {value!r}, not a {kind}")
    if not report["enabled"] or not report["attempted"] or not report["instanceMode"]:
        raise ValueError(f"the real-LOAD probe reports enabled={report['enabled']},"
                         f" attempted={report['attempted']}, instanceMode={report['instanceMode']}")
    if (report["level"], report["radius"], report["buildBudget"]) != (
            REAL_LOAD_LEVEL, REAL_LOAD_RADIUS, REAL_LOAD_BUILD_BUDGET):
        raise ValueError(f"the real-LOAD probe meshes level {report['level']} radius"
                         f" {report['radius']} with budget {report['buildBudget']}, not the"
                         f" {REAL_LOAD_LEVEL}/{REAL_LOAD_RADIUS}/{REAL_LOAD_BUILD_BUDGET} its source lays out")
    if report["declaredDepthState"] != TERRAIN_LOAD_DEPTH_STATE or report["depthStateReadBack"]:
        raise ValueError(f"the real-LOAD probe declares {report['declaredDepthState']!r}"
                         f" (read back: {report['depthStateReadBack']}), not Voxy's declared"
                         f" {TERRAIN_LOAD_DEPTH_STATE}")
    if report["notes"] or report["problems"] or report["closeFailures"] or report["leakedScenes"] \
            or report["firstProblem"] is not None or report["deviceDiverged"] or report["readbacksInFlight"]:
        raise ValueError(f"the real-LOAD probe reports notes={report['notes']},"
                         f" problems={report['problems']}, closeFailures={report['closeFailures']},"
                         f" leakedScenes={report['leakedScenes']}, firstProblem={report['firstProblem']!r},"
                         f" deviceDiverged={report['deviceDiverged']},"
                         f" readbacksInFlight={report['readbacksInFlight']}")
    if not (0 <= report["builds"] <= REAL_LOAD_BUILD_BUDGET):
        raise ValueError(f"the real-LOAD probe built {report['builds']} scene(s)")
    if log_text is not None:
        attempts = real_load_build_attempts(log_text)
        if attempts != list(range(1, len(attempts) + 1)) or report["builds"] != len(attempts):
            raise ValueError(f"the real-LOAD probe reports {report['builds']} build(s) but the log"
                             f" shows build attempts {attempts}")
        logged_reads = len(ATLAS_READY_LOG.findall(log_text))
        if report["atlasReads"] != logged_reads:
            raise ValueError(f"the real-LOAD probe reports {report['atlasReads']} atlas read(s) but the"
                             f" log shows {logged_reads}")
    if report["atlasCloseFailures"]:
        raise ValueError(f"the block-atlas readback buffer failed to close"
                         f" {report['atlasCloseFailures']} time(s)")
    entries = report["results"]
    ats = []
    for entry in entries:
        if not isinstance(entry, dict) or not finite_int(entry.get("at")) \
                or not isinstance(entry.get("status"), str):
            raise ValueError(f"a real-LOAD result is malformed: {entry!r}")
        ats.append(entry["at"])
    if len(ats) != len(set(ats)):
        raise ValueError(f"the real-LOAD results repeat a draw: {sorted(ats)}")
    by_at = {e["at"]: e for e in entries}
    sample_ats = [r["at"] for r in recounts]
    if sorted(by_at) != sorted(sample_ats):
        raise ValueError(f"real-LOAD results exist for draws {sorted(by_at)} but the ladder handed"
                         f" it draws {sorted(sample_ats)}; every handed sample must carry one")
    if log_text is not None and sorted(logged) != sorted(sample_ats):
        raise ValueError(f"the ladder log holds real-load lines for draws {sorted(logged)} but the"
                         f" ladder handed it draws {sorted(sample_ats)}")
    judged = [e for e in entries if e["status"] == "judged"]
    if judged:
        # round-20 R20-REAL-METADATA: facts every judged sample rests on
        if report["device"] is None:
            raise ValueError("the real-LOAD probe judged samples but names no device")
        if report["atlasReads"] < 1:
            raise ValueError("the real-LOAD probe judged samples but never read the block atlas")
        builds_used = [e.get("sceneBuild") for e in judged]
        if not all(finite_int(b) and 1 <= b <= report["builds"] for b in builds_used) or \
                max(builds_used) != report["builds"]:
            raise ValueError(f"the judged samples name scene builds {builds_used} but the probe"
                             f" built {report['builds']}")
    if report["drawsRecorded"] != len(judged):
        raise ValueError(f"the real-LOAD probe recorded {report['drawsRecorded']} pass(es) but"
                         f" judged {len(judged)} sample(s); one pass per judged sample")
    if ladder_report.get("realLoadDrawsRecorded") != report["drawsRecorded"]:
        raise ValueError(f"the ladder saw {ladder_report.get('realLoadDrawsRecorded')!r} real-LOAD"
                         f" pass(es) but the probe reports {report['drawsRecorded']}")
    if ladder_report["device"] and report["device"] is not None and \
            device_handle_of(report["device"], "realLoad", "device") != \
            device_handle_of(ladder_report["device"], "ladder", "device"):
        raise ValueError(f"the real-LOAD probe names device {report['device']} but the ladder names"
                         f" {ladder_report['device']}")
    referenced = {"native-real-load.json"}
    out, visible_samples, total = [], 0, {name: 0 for name in TERRAIN_LOAD_COUNTS}
    ladder_samples_by_at = {s.get("at"): s for s in ladder_report.get("samples") or [] if isinstance(s, dict)}
    previous_judged = None
    hidden_samples = 0
    for recount in recounts:
        at = recount["at"]
        entry = by_at[at]
        status = entry["status"]
        if log_text is not None and logged[at][0] != status:
            raise ValueError(f"the ladder log's real-load line for draw {at} says status"
                             f" {logged[at][0]!r} but the report says {status!r}")
        if status != "judged":
            if status not in REAL_LOAD_SKIPS:
                raise ValueError(f"real-LOAD at draw {at} has status {status!r}, not 'judged' nor a"
                                 f" reason from {REAL_LOAD_SKIPS}")
            if any(k in entry for k in ("file", "frameFile", "referenceFile")):
                raise ValueError(f"real-LOAD at draw {at} was not judged but names files")
            real_load_skip_provenance(entry, recount, log_text, ladder_samples_by_at.get(at, {}))
            out.append({"at": at, "status": status})
            continue
        for field in TERRAIN_LOAD_COUNTS:
            if not finite_int(entry.get(field)) or entry[field] < 0:
                raise ValueError(f"real-LOAD at draw {at}: {field} is {entry.get(field)!r}")
        if entry.get("sceneLevel") != REAL_LOAD_LEVEL or not isinstance(entry.get("sceneCentre"), list) \
                or not finite_int(entry.get("sceneSections")) or entry["sceneSections"] < 1 \
                or not finite_int(entry.get("sceneDraws")) or entry["sceneDraws"] < 1:
            raise ValueError(f"real-LOAD at draw {at} names scene level {entry.get('sceneLevel')!r},"
                             f" centre {entry.get('sceneCentre')!r}, sections"
                             f" {entry.get('sceneSections')!r}, draws {entry.get('sceneDraws')!r}")
        head = load_judged_entry_checks(entry, at, report, log_text, ladder_samples_by_at,
                                        previous_judged, REAL_LOAD_SPEC)
        centre = entry.get("sceneCentre")
        if not (isinstance(centre, list) and len(centre) == 3 and all(finite_int(c) for c in centre)):
            raise ValueError(f"real-LOAD at draw {at} names scene centre {centre!r}")
        side = 2 * REAL_LOAD_RADIUS + 1
        if not (1 <= entry["sceneSections"] <= side ** 3) or not finite_int(entry.get("sceneQuads")) \
                or not (1 <= entry["sceneQuads"] <= REAL_LOAD_MAX_QUADS) \
                or entry["sceneDraws"] > 7 * entry["sceneSections"]:
            raise ValueError(f"real-LOAD at draw {at} names {entry['sceneSections']} sections,"
                             f" {entry.get('sceneQuads')!r} quads, {entry['sceneDraws']} draws")
        for field in ("excludedNear", "cutBlocks"):
            if not finite_int(entry.get(field)):
                raise ValueError(f"real-LOAD at draw {at} does not state {field}")
        if entry["cutBlocks"] != REAL_LOAD_CUT_BLOCKS or entry["excludedNear"] < 0:
            raise ValueError(f"real-LOAD at draw {at} cut sections nearer than {entry['cutBlocks']}"
                             f" blocks ({entry['excludedNear']} excluded), not Minecraft's"
                             f" {REAL_LOAD_CUT_BLOCKS}-block render distance")
        # round-22 R22-NEAR-CUT-REUSE: the cut holds at draw time, not only when the scene was built
        nearest = entry.get("nearestSection")
        if not finite_number(nearest) or nearest < entry["cutBlocks"]:
            raise ValueError(f"real-LOAD at draw {at} drew a section {nearest!r} blocks from the camera,"
                             f" inside the {entry['cutBlocks']}-block cut")
        if log_text is not None:
            # round-21 R20-REAL-METADATA: the scene facts are its build's log line
            scene = {int(m.group(1)): m for m in REAL_SCENE_LOG.finditer(head)}.get(entry["sceneBuild"])
            want = None if scene is None else (int(scene.group(2)), int(scene.group(3)), int(scene.group(4)),
                                               int(scene.group(5)), [int(scene.group(6)), int(scene.group(7)),
                                                                     int(scene.group(8))],
                                               int(scene.group(10)), int(scene.group(11)))
            got = (entry["sceneSections"], entry["sceneQuads"], entry["sceneDraws"], entry["sceneLevel"],
                   entry["sceneCentre"], entry.get("excludedNear"), entry.get("cutBlocks"))
            if want != got:
                raise ValueError(f"real-LOAD at draw {at} names scene #{entry['sceneBuild']} as {got} but"
                                 f" the log's build line says {want}")
        previous_judged = entry
        counts, names = judge_load_sample(output, recount, entry, at, coexist_enabled, logged,
                                          log_text, REAL_LOAD_SPEC)
        referenced.update(names)
        if counts["expectVisible"]:
            visible_samples += 1
        if counts["expectHidden"]:
            hidden_samples += 1
        for k in total:
            total[k] += counts[k]
        out.append({"at": at, "status": status, "stage": entry.get("stage"), **counts})
    stray = sorted(set(retained) - referenced)
    if stray:
        raise ValueError(f"{len(stray)} retained real-LOAD file(s) belong to no judged result: {stray[:6]}")
    # round-20 R20-REAL-ENGINE-IDENTITY: a scene meshed before the disconnect must not be drawn after
    # the reconnect (a new instance, a new engine)
    stages = list(LIFECYCLE_STAGES)
    if "reconnect" in stages:
        cut = stages.index("reconnect")
        before_builds = [e["sceneBuild"] for e in judged if e.get("stage") in stages[:cut]]
        after_builds = [e["sceneBuild"] for e in judged if e.get("stage") == "reconnect"]
        if before_builds and after_builds and min(after_builds) <= max(before_builds):
            raise ValueError(f"real-LOAD after reconnect drew scene build {min(after_builds)}, which"
                             f" was meshed before the disconnect")
    if not visible_samples:
        raise ValueError("no judged real-LOAD sample holds a pixel where Voxy's real terrain must"
                         " appear, so the experiment decided nothing")
    # Occlusion: the `horizon` look puts a wall Minecraft draws in front of Voxy-only terrain;
    # Voxy's pixels behind it must be hidden. Without such pixels occlusion is untested.
    if not hidden_samples:
        raise ValueError("no judged real-LOAD sample holds a pixel where Voxy's real terrain must"
                         " be hidden behind nearer Minecraft geometry, so occlusion is untested")
    return {"enabled": True, "samples": out, "judged": len(judged),
            "visibleSamples": visible_samples, "hiddenSamples": hidden_samples,
            "expectVisible": total["expectVisible"],
            "expectHidden": total["expectHidden"], "undetermined": total["undetermined"],
            "geometry": total["geometry"]}


def recount_ladder_sample(output, sample):
    """Recount one sample's band from its retained crops, in both orientations.

    The selected crop must be exactly the band in the selected orientation and every pixel
    must classify as the report says; the rejected crop must be the band in the other
    orientation and mostly outside the palette, so the orientation is identified from
    pixels rather than asserted.
    """
    rect, rejected_rect = sample["rect"], sample["rejectedRect"]
    path = output / sample["file"]
    if not path.is_file():
        raise ValueError(f"the retained ladder crop {sample['file']} is missing")
    rows, (width, height) = read_ppm_gz(path)
    if width != rect[2] - rect[0] or height != rect[3] - rect[1]:
        raise ValueError(f"the ladder crop {sample['file']} is {width}x{height} but its rect"
                         f" says {rect[2] - rect[0]}x{rect[3] - rect[1]}")
    counts = [0] * (LADDER_OTHER + 1)
    for row in rows:
        for px in row:
            counts[ladder_classify(px)] += 1
    published = sample["counts"]
    ours = {"anomaly": counts[LADDER_BASE], "low": counts[LADDER_LOW], "clear": counts[LADDER_CLEAR],
            "rungs": counts[LADDER_RUNG0:LADDER_RUNG0 + LADDER_RUNGS],
            "other": counts[LADDER_OTHER]}
    for field in ("anomaly", "low", "clear", "rungs", "other"):
        if published[field] != ours[field]:
            raise ValueError(f"recounting {sample['file']} finds {field}={ours[field]}, not the"
                             f" published {published[field]}; the aggregate does not match the"
                             f" pixels")
    rejected = output / sample["rejectedFile"]
    if not rejected.is_file():
        raise ValueError(f"the retained rejected-orientation ladder crop"
                         f" {sample['rejectedFile']} is missing")
    rrows, (rw, rh) = read_ppm_gz(rejected)
    if rw != rejected_rect[2] - rejected_rect[0] or rh != rejected_rect[3] - rejected_rect[1]:
        raise ValueError(f"the rejected ladder crop {sample['rejectedFile']} is {rw}x{rh} but"
                         f" its rect says {rejected_rect[2] - rejected_rect[0]}x"
                         f"{rejected_rect[3] - rejected_rect[1]}")
    rejected_other = sum(1 for row in rrows for px in row if ladder_classify(px) == LADDER_OTHER)
    if rejected_other != sample["rejectedOther"]:
        raise ValueError(f"recounting {sample['rejectedFile']} finds {rejected_other} pixels"
                         f" outside the palette, not the published {sample['rejectedOther']}")
    # ⚠ Round-9 review R8-LADDER-GATE: with both booleans flipped and both rectangles
    # recomputed, the same crop bytes passed — the recount knew the crop, not where it came
    # from. The retained quarter-scale thumbnail of the WHOLE readback anchors both crops:
    # every fully covered block of each crop must average to the thumbnail's pixel at the
    # stated position. A crop moved to another position lands on scene, not on itself.
    frame = output / sample["frameFile"]
    if not frame.is_file():
        raise ValueError(f"the retained ladder frame thumbnail {sample['frameFile']} is missing")
    trows, (tw, th) = read_ppm_gz(frame)
    full_w, full_h = sample["targetWidth"], sample["targetHeight"]
    if (tw, th) != (full_w // LADDER_FRAME_SCALE, full_h // LADDER_FRAME_SCALE):
        raise ValueError(f"the ladder frame thumbnail is {tw}x{th}, not the"
                         f" {full_w // LADDER_FRAME_SCALE}x{full_h // LADDER_FRAME_SCALE} a"
                         f" {full_w}x{full_h} frame scales to")
    for label, crop_rows, crop_rect in (("selected", rows, rect),
                                        ("rejected", rrows, rejected_rect)):
        anchored = ladder_anchor_blocks(crop_rows, crop_rect, trows)
        if anchored == 0:
            raise ValueError(f"the {label} ladder crop covers no whole thumbnail block, so its"
                             f" position cannot be checked")
    out = dict(ours)
    out.update(sample=sample["file"], at=sample["at"], flipped=sample["flipped"],
               size=[width, height], rect=list(rect), rejectedOther=rejected_other,
               brackets=ladder_brackets(ours), frame=sample["frameFile"])
    return out


def ladder_anchor_blocks(crop_rows, crop_rect, thumb_rows):
    """Check a crop against the frame thumbnail at its stated rect; return blocks checked."""
    scale = LADDER_FRAME_SCALE
    x0, y0, x1, y1 = crop_rect
    checked = 0
    bx0, bx1 = -(-x0 // scale), x1 // scale
    by0, by1 = -(-y0 // scale), y1 // scale
    for by in range(by0, by1):
        for bx in range(bx0, bx1):
            sums = [0, 0, 0]
            for y in range(by * scale, (by + 1) * scale):
                row = crop_rows[y - y0]
                for x in range(bx * scale, (bx + 1) * scale):
                    px = row[x - x0]
                    for k in range(3):
                        sums[k] += px[k]
            want = tuple(v // (scale * scale) for v in sums)
            got = tuple(thumb_rows[by][bx][:3])
            if got != want:
                raise ValueError(f"the crop at {crop_rect} does not match the retained frame"
                                 f" thumbnail at block ({bx},{by}): crop averages {want},"
                                 f" frame holds {got}; the crop did not come from there")
            checked += 1
    return checked


def ladder_brackets(counts):
    """The per-pixel depth brackets a sample's counts describe, as text; no bound, no direction."""
    z = EXPECTED_LADDER_DEPTHS
    parts = [f"d == 0 (clear): {counts.get('clear', 0)} px", f"0 < d < {z[0]:.3g}: {counts['low']} px"]
    for i, n in enumerate(counts["rungs"]):
        upper = f"{z[i + 1]:.3g}" if i + 1 < LADDER_RUNGS else None
        parts.append((f"{z[i]:.3g} < d <= {upper}" if upper else f"d > {z[i]:.3g}")
                     + f": {n} px")
    return parts


def ladder_z_direction(samples, checkpoints):
    """Read the Z direction from the two straight-down looks, or raise.

    The harness teleports the player to DESCEND_ABOVE_GROUND and then ASCEND_ABOVE_GROUND
    blocks above the highest block under (0, 0), pitch 90, and records the ground height it
    used in those two checkpoints. The ladder samples both. Each sample's camera must be where
    that stage put it; the nearer look's bracket indices must all exceed the farther look's
    (or all fall below). Which look is nearer is known from the teleports, so the comparison
    reads which direction of the depth value is nearer — the one thing a single band could
    never say (rounds 7-11). `samples` are the recounted samples (counts from pixels).
    """
    by_stage = {c.get("stage"): c for c in checkpoints if isinstance(c, dict)}
    picked = {}
    # ⚠ Round-13 review R13-Z-BINDING: the two looks share ONE ground (the harness measures
    # it once), the checkpoints record where the player and camera actually were, and the
    # nearer look is the lower camera. All of that is required, not assumed from labels.
    grounds = {}
    for stage in ("descend", "ascend"):
        checkpoint = by_stage.get(stage)
        if checkpoint is None:
            raise ValueError(f"no {stage} checkpoint, so the Z direction was not measured")
        ground = checkpoint.get("groundY")
        if not finite_number(ground):
            raise ValueError(f"the {stage} checkpoint states no ground height")
        grounds[stage] = ground
    if grounds["descend"] != grounds["ascend"]:
        raise ValueError(f"the two looks state different grounds ({grounds}); the harness"
                         f" measures one ground for both, so these checkpoints are not its")
    for stage, offset in (("descend", DESCEND_ABOVE_GROUND), ("ascend", ASCEND_ABOVE_GROUND)):
        checkpoint = by_stage[stage]
        ground = grounds[stage]
        want_y = ground + offset + PLAYER_EYE_HEIGHT
        for field, want, tolerance in (("cameraY", want_y, 1.0),
                                       ("playerY", ground + offset, 1.0),
                                       ("playerPitch", 90.0, 1.0)):
            got = checkpoint.get(field)
            if not finite_number(got) or abs(got - want) > tolerance:
                raise ValueError(f"the {stage} checkpoint states {field}={got!r}, not the"
                                 f" {want:.2f} its ground and teleport imply")
        candidates = []
        for sample in samples:
            if sample.get("stage") != stage:
                continue
            cam = sample.get("camera") or []
            if len(cam) != 5 or any(not finite_number(v) for v in cam):
                continue
            if (abs(cam[0] - 0.5) <= 2 and abs(cam[2] - 0.5) <= 2 and abs(cam[1] - want_y) <= 1.0
                    and abs(cam[1] - checkpoint["cameraY"]) <= 1.0 and abs(cam[3] - 90) <= 1.0):
                candidates.append(sample)
        if not candidates:
            raise ValueError(f"no ladder sample was taken during {stage} with the camera at"
                             f" y={want_y:.2f} looking straight down, so the Z direction was"
                             f" not measured")
        chosen = max(candidates, key=lambda smp: smp["at"])
        if chosen["low"] or chosen.get("clear"):
            raise ValueError(f"the {stage} sample at draw {chosen['at']} has {chosen['low']}"
                             f" pixel(s) below the smallest rung and {chosen.get('clear')} clear"
                             f" looking straight down at the ground, so that look is not of the"
                             f" ground")
        indices = [i for i, n in enumerate(chosen["rungs"]) if n]
        picked[stage] = {"at": chosen["at"], "cameraY": chosen["camera"][1],
                         "groundY": ground, "aboveGround": chosen["camera"][1] - ground,
                         "brackets": indices, "rungs": chosen["rungs"]}
    near, far = picked["descend"], picked["ascend"]
    if not near["cameraY"] < far["cameraY"]:
        raise ValueError(f"the descend camera ({near['cameraY']:.2f}) is not below the ascend"
                         f" camera ({far['cameraY']:.2f}); the nearer look is not identified")
    # The harness runs descend before ascend; the samples must be in that order.
    if not near["at"] < far["at"]:
        raise ValueError(f"the descend sample (draw {near['at']}) does not precede the ascend"
                         f" sample (draw {far['at']}), contradicting the lifecycle order")
    if max(far["brackets"]) < min(near["brackets"]):
        direction = "larger depth value is nearer (reverse-Z)"
    elif max(near["brackets"]) < min(far["brackets"]):
        direction = "smaller depth value is nearer (conventional Z)"
    else:
        raise ValueError(f"the two straight-down looks do not separate: nearer look brackets"
                         f" {near['brackets']}, farther look brackets {far['brackets']}; the"
                         f" Z direction was not measured")
    return {"direction": direction, "near": near, "far": far,
            "basis": "two looks straight down at the same ground from"
                     f" {DESCEND_ABOVE_GROUND} and {ASCEND_ABOVE_GROUND} blocks above it"
                     " (harness teleports; camera positions published by the ladder; ground"
                     " height from the checkpoints); every pixel bracket of the nearer look"
                     " lies strictly on one side of every bracket of the farther look"}


def native_ladder_result(output, expected_device=None, expected_extents=None,
                         log_text=None, checkpoints=None, require_coexist=False,
                         require_terrain_load=False, require_real_load=False,
                         require_hier_load=False, require_hier_frames=False):
    """Gate the depth ladder's own launch: Minecraft's loaded depth, tested, never written.

    This run is SEPARATE from the main native launch because the terrain probe clears the
    very attachment the ladder measures. The report has to say both were off and recorded
    nothing; a contaminated run is not a measurement of Minecraft's depth.
    """
    result = {"success": False, "failures": [],
              "scope": "per-pixel brackets of Minecraft's depth values in one screen band at"
                       " the LevelRenderer.render tail, from its own depth test; NOT the Z"
                       " convention, NOT a band-wide bound"}
    try:
        report = json.loads((output / "native-depth-ladder.json").read_text())
        result["report"] = report
        retained_crops = [p.name for p in output.glob("native-depth-ladder-*.ppm.gz")]
        result.update(ladder_report_checks(output, report, expected_device, expected_extents,
                                           retained_crops, log_text, require_coexist))
        handed = {s.get("at"): s.get("experiment") for s in report["samples"] if isinstance(s, dict)}
        result["terrain_load"] = terrain_load_checks(
            output, report, [r for r in result["samples"] if handed.get(r["at"]) == LADDER_TERRAIN_LOAD],
            bool(result["coexist"].get("enabled")), log_text, require_terrain_load)
        result["real_load"] = real_load_checks(
            output, report, [r for r in result["samples"] if handed.get(r["at"]) == LADDER_REAL_LOAD],
            bool(result["coexist"].get("enabled")), log_text, require_real_load)
        result["hier_load"] = hier_load_checks(
            output, report, [r for r in result["samples"] if handed.get(r["at"]) == LADDER_HIER_LOAD],
            bool(result["coexist"].get("enabled")), log_text, require_hier_load,
            require_hier_frames)
        latest = result["samples"][-1]
        result["answer"] = (f"{len(result['samples'])} sample(s); the latest (draw"
                            f" {latest['at']}) brackets: " + "; ".join(latest["brackets"]))
        # The probe never infers the direction (zConventionMeasuredHere stays false); this
        # gate derives it from the two straight-down looks when the checkpoints are given.
        result["z_convention_measured"] = False
        if checkpoints is not None:
            for recount, sample in zip(result["samples"], report["samples"]):
                recount["stage"], recount["camera"] = sample.get("stage"), sample.get("camera")
            result["z_direction"] = ladder_z_direction(result["samples"], checkpoints)
            result["answer"] += "; " + result["z_direction"]["direction"]
        if result["coexist"].get("enabled"):
            result["answer"] += (f"; coexist quad at z*={result['coexist']['z']:.3g} composed per"
                                 f" pixel with zero violations in {len(result['coexist']['samples'])}"
                                 f" samples ({result['coexist']['mixedSamples']} mixed)")
        else:
            result["answer"] += "; which direction is nearer is not judged here"
        if result["hier_load"].get("enabled"):
            hl = result["hier_load"]
            result["answer"] += (f"; Voxy's hierarchical pipeline composited natively into"
                                 f" Minecraft's LOADed frame: {hl['judged']} judged sample(s), zero"
                                 f" violations, {hl['expectVisible']} pixel(s) expected visible,"
                                 f" {hl['expectHidden']} expected hidden")
        if result["real_load"].get("enabled"):
            rl = result["real_load"]
            result["answer"] += (f"; real sections drawn with Minecraft's matrix in a LOADed pass:"
                                 f" {rl['judged']} judged sample(s), zero violations,"
                                 f" {rl['expectVisible']} pixel(s) expected visible,"
                                 f" {rl['expectHidden']} expected hidden")
        if result["terrain_load"].get("enabled"):
            tl = result["terrain_load"]
            result["answer"] += (f"; Voxy's terrain pipeline in a LOADed pass composed per pixel"
                                 f" with zero violations in {len(tl['samples'])} samples"
                                 f" ({tl['mixedSamples']} with both determinate kinds)")
        result.update(success=True)
    except (OSError, ValueError, KeyError, TypeError, EOFError, zlib.error) as exc:
        result["failures"].append(f"{type(exc).__name__}: {exc}")
    return result


def single_checkpoint_device(checkpoints):
    """The one device the lifecycle checkpoints saw, or a ValueError."""
    if not checkpoints:
        raise ValueError("there are no lifecycle checkpoints to tie the proofs to")
    devices = set()
    for case in checkpoints:
        renderer = case.get("renderer")
        if not isinstance(renderer, dict) or "vkDevice" not in renderer:
            raise ValueError(f"checkpoint {case.get('stage')} does not name its device")
        devices.add(device_handle_of(renderer["vkDevice"], "checkpoint", "vkDevice"))
    if len(devices) != 1:
        raise ValueError(f"the checkpoints saw more than one device: {devices}")
    return next(iter(devices))


def checkpoint_extents(checkpoints):
    """Every colour-image extent the lifecycle checkpoints observed (resize changes it)."""
    extents = set()
    for case in checkpoints:
        colour = (case.get("renderer") or {}).get("colour") or {}
        w, h = colour.get("width"), colour.get("height")
        if finite_int(w) and finite_int(h) and w > 0 and h > 0:
            extents.add((w, h))
    if not extents:
        raise ValueError("no lifecycle checkpoint states a colour-image extent")
    return extents


def native_depth_result(output, expected_device=None):
    """Report what the depth copy actually produced, and recount it from retained pixels.

    ⚠ Round-7 review R7-DEPTH-GATE rejected the previous version on two counts, both fair.

    First, it never remeasured anything, so mutually contradictory summaries passed: with the
    real all-zero histogram left intact, min=0 / max=1 / topMean=0 / bottomMean=0.8 /
    reversedZ=true was accepted as "readable; reverse-Z (larger is closer)". Likewise every
    histogram entry in the last bin with min=max=0, a colour format where depth belongs, a null
    device, and negative dimensions whose product happened to be right. The probe now retains
    the raw depth and this recounts it, requiring the histogram, extrema and band means to be
    what the pixels actually say.

    Second — the deeper error — the probe inferred the Z convention from "the bottom band is
    nearby ground, the top band is sky", and this gate REJECTED "unknown" whenever the bands
    separated, forcing certainty out of an unproven premise. Band separation does not identify
    which screen region is nearer: a wall, a cave, a tilted camera or a flipped transfer each
    reverses it. The probe no longer asserts a convention at all, and this gate no longer has
    an opinion about one. The convention has to be measured by drawing at known depths against
    a loaded attachment; until that experiment exists, it is unmeasured and said to be.
    """
    result = {"success": False, "failures": [],
              "scope": "what Minecraft's depth attachment yields to a buffer copy;"
                       " NOT the Z convention, which this cannot measure"}
    try:
        report = json.loads((output / "native-depth-probe.json").read_text())
        result["report"] = report
        for field, kind in (("enabled", bool), ("attempted", bool), ("completed", bool),
                            ("uniform", bool), ("zConventionMeasuredHere", bool),
                            ("depthVkFormat", int), ("width", int), ("height", int),
                            ("bins", int), ("histogram", list), ("basis", str),
                            ("closeFailures", int), ("notes", list)):
            if field not in report:
                raise ValueError(f"the depth probe does not state {field}")
            value = report[field]
            if not isinstance(value, kind) or isinstance(value, bool) != (kind is bool):
                raise ValueError(f"depth.{field} is {value!r}, not a {kind.__name__}")
        if not report["enabled"]:
            raise ValueError("the depth probe was not enabled")
        if not report["attempted"]:
            raise ValueError(f"the depth probe never tried to read Minecraft's depth:"
                             f" {report['notes']}")
        if report["closeFailures"]:
            raise ValueError(f"{report['closeFailures']} depth readback buffer(s) could not be"
                             f" closed")
        # ⚠ The probe must never claim to have measured the convention here. If this field is
        # ever true, something has reintroduced the band heuristic.
        if report["zConventionMeasuredHere"]:
            raise ValueError("the depth probe claims to have measured the Z convention from a"
                             " buffer copy, which cannot establish it")
        if "reversedZ" in report:
            raise ValueError("the depth probe still publishes reversedZ; the band heuristic it"
                             " came from cannot identify which screen region is nearer")
        result["readable"] = report["completed"]
        result["z_convention_measured"] = False
        if not report["completed"]:
            result["answer"] = "Minecraft's scene depth could not be read back"
            result["basis"] = report["basis"]
            result.update(success=True)
            return result
        # ⚠ Round-7: a null device, a colour format and negative dimensions all passed.
        if not isinstance(report.get("device"), str):
            raise ValueError("the depth probe does not name its device")
        handle = report["device"]
        try:
            parsed = int(handle, 16) if handle.lower().startswith("0x") else int(handle)
        except ValueError:
            raise ValueError(f"depth.device is {handle!r}, which is not a device handle")
        if parsed == 0:
            raise ValueError("depth.device is a null device handle")
        # Round-8 review: a foreign non-zero depth device passed; it must be the run's.
        if expected_device is not None and parsed != expected_device:
            raise ValueError(f"the depth probe names device {hex(parsed)} but the lifecycle"
                             f" checkpoints saw {hex(expected_device)}")
        if report["depthVkFormat"] != VK_FORMAT_D32_SFLOAT:
            raise ValueError(f"the depth probe read VkFormat {report['depthVkFormat']}, not the"
                             f" D32_SFLOAT ({VK_FORMAT_D32_SFLOAT}) it knows how to interpret")
        if report["width"] < 1 or report["height"] < 1:
            raise ValueError(f"the depth image is {report['width']}x{report['height']}")
        if len(report["histogram"]) != report["bins"]:
            raise ValueError(f"the histogram has {len(report['histogram'])} bins but the probe"
                             f" says {report['bins']}")
        # Round-9 review R9-DEPTH-FINITE: NaN passed the type check and every tolerance
        # comparison. A number here has to be finite.
        for field in ("min", "max", "topMean", "bottomMean", "clearedValue", "clearedShare"):
            value = report.get(field)
            if not finite_number(value):
                raise ValueError(f"depth.{field} is {value!r}, not a finite number")
        if any(not finite_int(v) or v < 0 for v in report["histogram"]):
            raise ValueError(f"the depth histogram holds a non-count: {report['histogram']}")
        if not (0.0 <= report["min"] <= 1.0) or not (0.0 <= report["max"] <= 1.0):
            raise ValueError(f"the depth values are outside [0,1]: min={report['min']}"
                             f" max={report['max']}")
        if report["min"] > report["max"]:
            raise ValueError(f"min {report['min']} exceeds max {report['max']}")
        if report["uniform"] != (report["min"] == report["max"]):
            raise ValueError(f"the probe says uniform={report['uniform']} but min="
                             f"{report['min']} and max={report['max']}")
        # The authoritative part: recount the retained pixels and require agreement.
        result["recount"] = recount_depth_sample(output, report)
        result["answer"] = ("the copy completed but every pixel was identical"
                            f" ({report['min']}), so this path does not observe Minecraft's"
                            " scene depth"
                            if report["uniform"] else
                            "the copy completed and the image varies; what the values MEAN"
                            " (which direction is nearer) is not measured by this path")
        result["basis"] = report["basis"]
        result.update(success=True)
    except (OSError, ValueError, KeyError, TypeError, EOFError, zlib.error) as exc:
        result["failures"].append(f"{type(exc).__name__}: {exc}")
    return result


def recount_depth_sample(output, report):
    """Recount the retained raw depth and require the published summary to match it.

    The probe retains the depth quantised to 16-bit big-endian grey (a gzipped P5 PGM). That is
    lossy, so the comparison is to within one quantisation step — enough to catch a histogram
    that contradicts its extrema, a uniform image reported as varying, or band means invented
    wholesale, which is what round 7 demonstrated.
    """
    name = report.get("sampleFile")
    if not name:
        raise ValueError("the depth probe retained no raw sample, so its summary cannot be"
                         " checked against anything")
    path = output / name
    if not path.is_file():
        raise ValueError(f"the retained depth sample {name} is missing")
    values, (width, height) = read_pgm16_gz(path)
    if (width, height) != (report["width"], report["height"]):
        raise ValueError(f"the retained depth sample is {width}x{height} but the probe read"
                         f" {report['width']}x{report['height']}")
    step = 1.0 / 65535.0
    tolerance = 2 * step
    bins = report["bins"]
    histogram = [0] * bins
    low, high = 1.0, 0.0
    band = max(1, height // 50)
    top_sum = bottom_sum = 0.0
    top_count = bottom_count = 0
    for y in range(height):
        row = values[y]
        for q in row:
            z = q * step
            low = min(low, z)
            high = max(high, z)
            histogram[min(bins - 1, int(z * bins))] += 1
        if y < band:
            top_sum += sum(row) * step
            top_count += width
        if y >= height - band:
            bottom_sum += sum(row) * step
            bottom_count += width
    out = {"sample": name, "size": [width, height], "min": low, "max": high,
           "histogram": histogram,
           "topMean": top_sum / top_count if top_count else None,
           "bottomMean": bottom_sum / bottom_count if bottom_count else None}
    if histogram != report["histogram"]:
        raise ValueError(f"recounting the retained depth finds the histogram {histogram}, not"
                         f" the published {report['histogram']}")
    # Round-8 review: clearedValue/clearedShare were type-checked but never recounted.
    fullest = max(range(bins), key=lambda i: histogram[i])
    total = sum(histogram)
    out["clearedValue"] = (fullest + 0.5) / bins
    out["clearedShare"] = histogram[fullest] / total if total else None
    for field in ("clearedValue", "clearedShare"):
        if out[field] is not None and abs(report[field] - out[field]) > 1e-6:
            raise ValueError(f"the probe reports {field}={report[field]} but the retained"
                             f" pixels say {out[field]}")
    for field, ours in (("min", low), ("max", high),
                        ("topMean", out["topMean"]), ("bottomMean", out["bottomMean"])):
        theirs = report[field]
        if ours is None:
            continue
        if abs(theirs - ours) > tolerance:
            raise ValueError(f"the probe reports {field}={theirs} but the retained pixels say"
                             f" {ours}")
    if (low == high) != report["uniform"]:
        raise ValueError(f"the retained pixels are{'' if low == high else ' not'} uniform but"
                         f" the probe says uniform={report['uniform']}")
    return out


def terrain_report_checks(output, report, expected_device=None):
    """Every terrain acceptance check that needs only the report and its retained samples.

    ⚠ Round-6 review R6-TERRAIN-GATE: `--replay-evidence` repeated only the pixel comparison,
    so a terrain report with enabled/attempted/built false, draws=0, clean=0, problems=3,
    closeFailures=99, leakedProbes=3 and failure notes still replayed as 0 — the same hole
    round 5 found in the marker replay, reintroduced in new code. These checks now live in one
    place that both the stage gate and the replay call.
    """
    checks = {}
    for field, kind in (("enabled", bool), ("attempted", bool), ("built", bool),
                        ("drawsRecorded", int), ("targetWidth", int), ("targetHeight", int),
                        ("colourVkFormat", int), ("clearRgb", int), ("timesClean", int),
                        ("timesWithAProblem", int), ("closeFailures", int),
                        ("failureBudget", int), ("leakedProbes", int), ("leakBudget", int),
                        ("deviceDiverged", bool), ("notes", list)):
        if field not in report:
            raise ValueError(f"the terrain probe does not state {field}")
        value = report[field]
        if not isinstance(value, kind) or isinstance(value, bool) != (kind is bool):
            raise ValueError(f"terrain.{field} is {value!r}, not a {kind.__name__}")
    if not report["enabled"]:
        raise ValueError("the terrain probe was not enabled")
    if not report["attempted"] or not report["built"]:
        raise ValueError(f"the terrain probe never built its pipeline: {report['notes']}")
    if report["drawsRecorded"] < 1:
        raise ValueError("no terrain draw was recorded into Minecraft's command buffer")
    if report["notes"]:
        raise ValueError(f"the terrain probe reported notes: {report['notes']}")
    for field in ("targetWidth", "targetHeight"):
        if report[field] < 1:
            raise ValueError(f"the terrain probe states {field}={report[field]}")
    if report["timesWithAProblem"]:
        raise ValueError(f"{report['timesWithAProblem']} terrain comparison(s) found a"
                         f" problem, first: {report.get('firstProblem')}")
    if report["timesClean"] < 1:
        raise ValueError("Minecraft's frame was never compared against Voxy's own target")
    if report["closeFailures"]:
        raise ValueError(f"{report['closeFailures']} terrain readback buffer(s) could not be"
                         f" closed, so memory was held for the rest of the run")
    if report["leakedProbes"]:
        raise ValueError(f"{report['leakedProbes']} terrain probe(s) were leaked because"
                         f" their retirement could not be handed to Minecraft")
    # ⚠ Round-6 review R6-TERRAIN-DEVICE: if Minecraft's device moved away from the one Voxy
    # adopted, the probe stops for the session — and the run must not then be reported as a
    # clean terrain measurement, because part of it was spent refusing to record.
    if report["deviceDiverged"]:
        raise ValueError("Minecraft's device diverged from Voxy's adopted one during the run,"
                         " so the terrain probe stopped recording partway through")
    # ⚠ Round-6 review R6-TERRAIN-GATE: this required only that `device` be a string, so
    # "0xdead" and "0x0" both passed. It must be a real handle, and it must be the same
    # device every other proof names.
    handle = report.get("device")
    if not isinstance(handle, str):
        raise ValueError("the terrain probe does not name its device")
    try:
        parsed = int(handle, 16) if handle.lower().startswith("0x") else int(handle)
    except ValueError:
        raise ValueError(f"terrain.device is {handle!r}, which is not a device handle")
    if parsed == 0:
        raise ValueError("terrain.device is a null device handle")
    if expected_device is not None and parsed != expected_device:
        raise ValueError(f"the terrain probe names device {hex(parsed)} but the rest of the"
                         f" run names {hex(expected_device)}")
    checks["device"] = parsed

    c = report.get("comparison")
    if not isinstance(c, dict):
        raise ValueError("the terrain probe published no comparison, so the draws are"
                         " unmeasured")
    for field, kind in (("attempted", bool), ("completed", bool), ("referenceSet", int),
                        ("nativeSet", int), ("mismatches", int), ("sampleAtDraw", int),
                        ("flipped", bool)):
        if field not in c:
            raise ValueError(f"the terrain comparison does not state {field}")
        value = c[field]
        if not isinstance(value, kind) or isinstance(value, bool) != (kind is bool):
            raise ValueError(f"comparison.{field} is {value!r}, not a {kind.__name__}")
    if not c["attempted"] or not c["completed"]:
        raise ValueError(f"the terrain comparison did not complete: {c.get('note')}")
    if c.get("note"):
        raise ValueError(f"the terrain comparison rejects the frame: {c['note']}")
    # ⚠ Round-6 review R6-TERRAIN-GATE: the capture number was checked against the file name
    # but never for positivity or against the total, so a report with one draw and a
    # comparison at draw 3598 passed.
    if c["sampleAtDraw"] < 1:
        raise ValueError(f"comparison.sampleAtDraw is {c['sampleAtDraw']}, so the sample is"
                         f" not bound to any capture")
    if c["sampleAtDraw"] > report["drawsRecorded"]:
        raise ValueError(f"the comparison claims draw {c['sampleAtDraw']} but only"
                         f" {report['drawsRecorded']} terrain draws were recorded")
    if c["referenceSet"] < 1000:
        raise ValueError(f"Voxy's own target holds only {c['referenceSet']} non-background"
                         f" pixels, so there is nothing to compare against")
    if c["nativeSet"] < 1000:
        raise ValueError(f"Minecraft's frame holds only {c['nativeSet']} non-background"
                         f" pixels, so the terrain draws did not reach it")
    if c["mismatches"]:
        raise ValueError(f"{c['mismatches']} pixels differ between Minecraft's frame and"
                         f" Voxy's own target")
    checks["comparison"] = c
    checks["recompare"] = recompare_terrain_samples(output, report, c)
    return checks


def native_terrain_result(output, expected_device=None):
    """Gate Voxy's REAL terrain pipeline recorded into Minecraft's own Vulkan frame.

    ⚠ This is an EXPERIMENT, not a promotion. Round 4 ruled the diagnostic layer "not yet
    sound enough to use as the accepted foundation for terrain work" while permitting terrain
    investigation as experimental. A green result here says the pipeline ran and agreed with
    Voxy's own target on the same device; it says nothing about real-world terrain, about
    coexisting with Minecraft's scene, or about the diagnostic layer being accepted.

    The claim it does check is strong and falsifiable: the same synthetic scene drawn by the
    same renderer into Minecraft's colour image and into Voxy's own render target must be
    pixel-identical in RGB. The implementation reports that comparison, and this gate repeats
    it from the two retained raw samples — so a wrong aggregate cannot pass.
    """
    result = {"success": False, "failures": [],
              "scope": "Voxy's real terrain pipeline recorded into Minecraft's Vulkan frame;"
                       " EXPERIMENTAL, not an accepted foundation"}
    try:
        report = json.loads((output / "native-terrain-probe.json").read_text())
        result["report"] = report
        result.update(terrain_report_checks(output, report, expected_device))
        result.update(success=True)
    except (OSError, ValueError, KeyError, TypeError, EOFError, zlib.error) as exc:
        result["failures"].append(f"{type(exc).__name__}: {exc}")
    return result


def recompare_terrain_samples(output, report, comparison):
    """Recompare Minecraft's retained frame against Voxy's retained target, independently."""
    name = comparison.get("sampleFile")
    if not name:
        raise ValueError("the terrain comparison retained no raw sample")
    sample = output / name
    reference = output / name.replace("native-terrain-sample-", "native-terrain-reference-")
    for path in (sample, reference):
        if not path.is_file():
            raise ValueError(f"the retained terrain sample {path.name} is missing")
    # ⚠ Round-5 review B4: a sample must be bound to the capture that produced it. The name
    # carries the draw count the copy was REGISTERED at; the report must agree. Measured: a
    # sample from before a resize stayed referenced after the rebuild, and the reported draw
    # count (3363) disagreed with the file name (-3).
    stem = name[len("native-terrain-sample-"):-len(".ppm.gz")]
    if not stem.isdigit() or int(stem) != comparison.get("sampleAtDraw"):
        raise ValueError(f"the retained sample is named for draw {stem!r} but the comparison"
                         f" says it was taken at {comparison.get('sampleAtDraw')!r}")
    got, (gw, gh) = read_ppm_gz(sample)
    want, (rw, rh) = read_ppm_gz(reference)
    if (gw, gh) != (rw, rh):
        raise ValueError(f"the retained samples are {gw}x{gh} and {rw}x{rh}")
    if (gw, gh) != (report["targetWidth"], report["targetHeight"]):
        raise ValueError(f"the retained samples are {gw}x{gh} but the probe drew to"
                         f" {report['targetWidth']}x{report['targetHeight']}")
    clear = report["clearRgb"]
    clear_rgb = (clear & 0xFF, (clear >> 8) & 0xFF, (clear >> 16) & 0xFF)
    differ = native_set = reference_set = 0
    for y in range(gh):
        a, b = got[y], want[y]
        for x in range(gw):
            if a[x] != b[x]:
                differ += 1
            if a[x] != clear_rgb:
                native_set += 1
            if b[x] != clear_rgb:
                reference_set += 1
    out = {"sample": name, "size": [gw, gh], "mismatches": differ,
           "nativeSet": native_set, "referenceSet": reference_set,
           "sampleAtDraw": comparison.get("sampleAtDraw")}
    if differ:
        raise ValueError(f"recomparing the retained samples finds {differ} differing pixels"
                         f" between Minecraft's frame and Voxy's own target: {out}")
    if native_set < 1000 or reference_set < 1000:
        raise ValueError(f"the retained samples are almost entirely background, so an identical"
                         f" pair proves nothing: {out}")
    for label, theirs, ours in (("mismatches", comparison["mismatches"], differ),
                                ("nativeSet", comparison["nativeSet"], native_set),
                                ("referenceSet", comparison["referenceSet"], reference_set)):
        if theirs != ours:
            raise ValueError(f"the comparison reported {label}={theirs} but the retained samples"
                             f" hold {ours}; the aggregate does not match the pixels")
    return out


def native_proof_files_result(output, checkpoints):
    """Gate the proof files, their identities, and their agreement with each other.

    Round-2 review B3: requiring the files was not enough — a fixture with a null adopted
    device and a device mismatch returned true, because a missing identity skipped the check
    and the marker/compute/shader files' own identities were never looked at. Every field this
    gate relies on must now be PRESENT and of the right type, and every file that names a
    device must name the same one.
    """
    result = {"success": False, "failures": [], "files": {}}
    wanted = ("native-device-features.json", "native-compute-probe.json",
              "native-adopted-context.json", "native-real-shader.json",
              "native-marker-draw.json", "native-vulkan-probe.json")

    def need(body, name, field, kind):
        if field not in body:
            raise ValueError(f"{name} does not state {field}, so its claim cannot be checked")
        value = body[field]
        if not isinstance(value, kind) or isinstance(value, bool) != (kind is bool):
            raise ValueError(f"{name}.{field} is {value!r}, which is not a {kind.__name__}")
        return value

    def handle_of(value, name, field):
        """Device handles are hex in the probe files and integers in the checkpoints."""
        if isinstance(value, int) and not isinstance(value, bool):
            return value
        if isinstance(value, str):
            try:
                return int(value, 16) if value.lower().startswith("0x") else int(value)
            except ValueError:
                pass
        raise ValueError(f"{name}.{field} is {value!r}, which is not a device handle")

    def device(value, name, field):
        """A device handle that is actually a handle. Zero is not one.

        ⚠ Round-5 review B3: all-zero handles agreed with each other and passed this helper,
        which the environment gate would have rejected — and replay does not run that gate.
        """
        handle = handle_of(value, name, field)
        if handle == 0:
            raise ValueError(f"{name}.{field} is a null device handle")
        return handle

    try:
        loaded = {}
        for name in wanted:
            path = output / name
            if not path.is_file():
                raise ValueError(f"{name} was never written, so its claim is unproven")
            loaded[name] = json.loads(path.read_text())
            result["files"][name] = loaded[name]

        features = loaded["native-device-features.json"]
        expected_features = {"drawIndirectFirstInstance", "shaderInt64",
                             "fragmentStoresAndAtomics", "vertexPipelineStoresAndAtomics"}
        if not need(features, "native-device-features.json", "enabled", bool):
            raise ValueError("the device-feature injection was not enabled")
        if not need(features, "native-device-features.json", "attempted", bool):
            raise ValueError("the device-feature injection was never attempted")
        added_list = need(features, "native-device-features.json", "added", list)
        # ⚠ Round-6 review B3: `set(added) != expected` accepted DUPLICATE entries, so a list
        # naming one feature four times and another once could satisfy the set comparison.
        if len(added_list) != len(set(added_list)):
            raise ValueError(f"the device-feature list repeats entries: {added_list}")
        added = set(added_list)
        if added != expected_features:
            raise ValueError(f"the device features Voxy needs were not all requested: {features}")
        # ⚠ Round-5 review B3: this was a substring test with no coverage requirement, so an
        # EMPTY notes list passed vacuously and "FAILED: not verified by read-back" passed
        # because it contains the phrase. Each requested feature must have exactly one note
        # saying which offset was verified for it, and nothing else may appear.
        notes = need(features, "native-device-features.json", "notes", list)
        verified = {}
        for entry in notes:
            match = re.fullmatch(r"(\w+): offset (\d+) verified by read-back", str(entry))
            if not match:
                raise ValueError(f"the feature injection note {entry!r} is not a read-back"
                                 f" verification, so it cannot stand for one")
            if match.group(1) in verified:
                raise ValueError(f"{match.group(1)} is verified twice: {notes}")
            verified[match.group(1)] = int(match.group(2))
        if set(verified) != added:
            raise ValueError(f"the features verified by read-back {sorted(verified)} are not the"
                             f" ones requested {sorted(added)}")
        # ⚠ Round-6 review B3: four distinct features could all claim offset 0 and pass. The
        # read-back experiment resolves one offset PER feature; two features sharing an offset
        # means one of them was never actually located.
        offsets = sorted(verified.values())
        if len(set(offsets)) != len(offsets):
            raise ValueError(f"two features claim the same read-back offset, so at least one"
                             f" was not actually located: {verified}")
        # ⚠ Round-7 review B3: distinct but IMPOSSIBLE offsets passed, including offsets past
        # the end of the struct entirely. VkPhysicalDeviceFeatures is 55 VkBool32 fields, so
        # every member offset is a multiple of 4 below 220; an offset outside that cannot be
        # the field it names, whatever the note says.
        for feature, offset in sorted(verified.items()):
            want = VK_FEATURE_OFFSETS.get(feature)
            if want is None:
                raise ValueError(f"{feature} is not a feature this gate knows the offset of")
            if offset != want:
                raise ValueError(f"{feature} claims read-back offset {offset}, but its member"
                                 f" offset in VkPhysicalDeviceFeatures is {want}; a read-back"
                                 f" that landed elsewhere did not verify that field")

        compute = loaded["native-compute-probe.json"]
        if not need(compute, "native-compute-probe.json", "attempted", bool):
            raise ValueError("the int64 compute proof was never attempted")
        if not need(compute, "native-compute-probe.json", "succeeded", bool):
            raise ValueError(f"the int64 compute proof did not succeed: {compute}")
        # ⚠ Round-5 review B3: these compared two arbitrary strings, so expected="" and
        # readBack="" passed. The source writes a fixed 64-bit sentinel; require it, so a
        # jointly-empty or jointly-zero pair cannot stand in for a measurement.
        if need(compute, "native-compute-probe.json", "expected", str).lower() != INT64_SENTINEL:
            raise ValueError(f"the int64 compute proof expected {compute['expected']!r}, not the"
                             f" sentinel {INT64_SENTINEL} the source writes")
        if need(compute, "native-compute-probe.json", "readBack", str).lower() != INT64_SENTINEL:
            raise ValueError(f"the int64 compute read back {compute['readBack']!r}, not the"
                             f" sentinel {INT64_SENTINEL}")
        if need(compute, "native-compute-probe.json", "notes", list):
            raise ValueError(f"the int64 compute proof reported notes: {compute['notes']}")

        adopted = loaded["native-adopted-context.json"]
        if not need(adopted, "native-adopted-context.json", "adopted", bool):
            raise ValueError(f"Minecraft's device was not adopted: {adopted}")
        if not need(adopted, "native-adopted-context.json", "provenByVoxyBufferAndShader", bool):
            raise ValueError(f"the adopted context was not proven with Voxy's own layers: {adopted}")
        if need(adopted, "native-adopted-context.json", "notes", list):
            raise ValueError(f"the adoption proof reported notes: {adopted['notes']}")
        if not need(adopted, "native-adopted-context.json", "enabled", bool):
            raise ValueError("adoption was not enabled, so its proof means nothing")
        # ⚠ Round-5 review B3: `attempted` was never read here, so a proof asserting
        # adopted/proven while stating it never tried still passed.
        if not need(adopted, "native-adopted-context.json", "attempted", bool):
            raise ValueError("the adoption proof was never attempted, so its other fields"
                             " describe nothing that ran")
        if need(adopted, "native-adopted-context.json", "readBack", str) != \
                compute["expected"]:
            raise ValueError(f"the adopted-context proof read back {adopted['readBack']},"
                             f" not the expected {compute['expected']}")
        adopted_device = device(need(adopted, "native-adopted-context.json", "device", str),
                                "native-adopted-context.json", "device")

        shader = loaded["native-real-shader.json"]
        if not need(shader, "native-real-shader.json", "attempted", bool):
            raise ValueError("the real-shader proof was never attempted")
        # ⚠ Round-5 review B3: this was read with .get(), so deleting the key escaped the
        # required-field discipline entirely.
        if "firstMismatch" not in shader:
            raise ValueError("native-real-shader.json does not state firstMismatch")
        if shader.get("firstMismatch") is not None:
            raise ValueError(f"the real-shader proof names a mismatch: {shader['firstMismatch']}")
        if not need(shader, "native-real-shader.json", "succeeded", bool):
            raise ValueError(f"Voxy's real shader stack did not succeed: {shader}")
        if need(shader, "native-real-shader.json", "mismatches", int) != 0:
            raise ValueError(f"Voxy's real shader stack disagreed with the CPU reference: {shader}")
        if need(shader, "native-real-shader.json", "quadOrdinalsChecked", int) < 100:
            raise ValueError(f"only {shader['quadOrdinalsChecked']} ordinals were checked")
        if need(shader, "native-real-shader.json", "notes", list):
            raise ValueError(f"the real-shader proof reported notes: {shader['notes']}")

        # ⚠ Round-5 review B3: this helper loads the marker and probe reports for their
        # device handles and never looked at their failure flags, which matters because the
        # replay path calls it. Check them here as well.
        marker = loaded["native-marker-draw.json"]
        if not need(marker, "native-marker-draw.json", "enabled", bool):
            raise ValueError("the marker draw was not enabled")
        if not need(marker, "native-marker-draw.json", "pipelineLive", bool):
            raise ValueError("no marker pipeline was live on Minecraft's device")
        if need(marker, "native-marker-draw.json", "notes", list):
            raise ValueError(f"the marker draw reported notes: {marker['notes']}")
        probe = loaded["native-vulkan-probe.json"]
        if not need(probe, "native-vulkan-probe.json", "mcUsesVulkan", bool):
            raise ValueError("the probe says Minecraft was not using its Vulkan backend")
        if need(probe, "native-vulkan-probe.json", "notes", list):
            raise ValueError(f"the Vulkan probe reported notes: {probe['notes']}")

        # Every file that names a device must name the SAME device, and so must the
        # lifecycle checkpoints: that is what ties these proofs to one measured run.
        marker_device = device(need(marker, "native-marker-draw.json", "device", str),
                               "native-marker-draw.json", "device")
        probe_device = device(need(probe, "native-vulkan-probe.json", "vkDevice", str),
                              "native-vulkan-probe.json", "vkDevice")
        if not checkpoints:
            raise ValueError("there are no lifecycle checkpoints to tie the proofs to")
        checkpoint_devices = set()
        for case in checkpoints:
            renderer = case.get("renderer")
            if not isinstance(renderer, dict) or "vkDevice" not in renderer:
                raise ValueError(f"checkpoint {case.get('stage')} does not name its device")
            checkpoint_devices.add(device(renderer["vkDevice"], "checkpoint", "vkDevice"))
        # ⚠ Round-3 review B3: the compute and real-shader proofs published no identity at
        # all, so a contradictory one was ignored. They name their device now and must agree.
        compute_device = device(need(compute, "native-compute-probe.json", "device", str),
                                "native-compute-probe.json", "device")
        shader_device = device(need(shader, "native-real-shader.json", "device", str),
                               "native-real-shader.json", "device")
        identities = {"adopted": adopted_device, "marker": marker_device, "probe": probe_device,
                      "compute": compute_device, "realShader": shader_device}
        result["device_identities"] = {k: hex(v) for k, v in identities.items()}
        result["checkpoint_devices"] = [hex(v) for v in sorted(checkpoint_devices)]
        if len(checkpoint_devices) != 1:
            raise ValueError(f"the checkpoints saw more than one device: {checkpoint_devices}")
        expected_device = next(iter(checkpoint_devices))
        for who, value in identities.items():
            if value != expected_device:
                raise ValueError(f"the {who} proof names device {hex(value)} but the lifecycle"
                                 f" checkpoints saw {hex(expected_device)}")
        result.update(success=True)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        result["failures"].append(str(exc))
    return result


NATIVE_EXPECTED_ERRORS = ("Minecraft is not using the OpenGL backend; Voxy's Vulkan path still ",
                          "Voxy is unsupported on your system.")


def native_log_problems(text):
    """What native_log_checks refuses in a launch log, as text (empty: none)."""
    problems = []
    diagnostics = [line.strip() for line in text.splitlines()
                   if re.search(r"\[vk-validation\]|Validation (Error|Warning)|SYNC-HAZARD-|VUID-", line)]
    if diagnostics:
        problems.append(f"validation output: {diagnostics[:3]}")
    if not any("VK_LAYER_KHRONOS_validation" in line and "Insert" in line for line in text.splitlines()):
        problems.append("no validation-layer loader evidence")
    unexpected = [line.strip() for line in text.splitlines()
                  if "/ERROR]" in line and "(Voxy)" in line
                  and not any(message in line for message in NATIVE_EXPECTED_ERRORS)]
    if unexpected:
        problems.append(f"unexpected Voxy errors: {unexpected[:3]}")
    return problems


def native_log_checks(result, logfile):
    """Judge a native launch's log: validation output, loader evidence, application errors."""
    text = logfile.read_text(errors="replace")
    result["diagnostics"] = [line.strip() for line in text.splitlines()
        if re.search(r"\[vk-validation\]|Validation (Error|Warning)|SYNC-HAZARD-|VUID-", line)]
    result["success"] &= not result["diagnostics"]
    result["validation_layer_loader_evidence"] = [line.strip() for line in text.splitlines()
        if "VK_LAYER_KHRONOS_validation" in line and "Insert" in line]
    result["success"] &= bool(result["validation_layer_loader_evidence"])
    result["application_errors"] = [line.strip() for line in text.splitlines()
        if "/ERROR]" in line and "(Voxy)" in line]
    expected_errors = ("Minecraft is not using the OpenGL backend; Voxy's Vulkan path still ",
                       "Voxy is unsupported on your system.")
    result["unexpected_application_errors"] = [line for line in result["application_errors"]
        if not any(message in line for message in expected_errors)]
    result["success"] &= not result["unexpected_application_errors"]


def retain_native_evidence(output, native_output, timestamp, summary):
    """Retain enough in the repository to CHECK the native claims, not merely to cite them.

    Round-2 review B1: the first version kept the JSON assertions and screenshot hashes, which
    is not enough to replay either gate — no log, no gate results, no binding to the candidate,
    and no pixels at all. This keeps the stage log, the finished summary, the source
    fingerprint, the revision and worktree state, and a small crop of the marker region from a
    captured frame, alongside every proof file and a manifest of hashes.
    """
    kept = {"run": timestamp, "files": {}, "screenshots": {}}
    target = ROOT / "docs" / "ai" / "runs" / "native-evidence" / timestamp
    try:
        target.mkdir(parents=True, exist_ok=True)
        for path in sorted(native_output.glob("*.json")):
            shutil.copyfile(path, target / path.name)
            kept["files"][path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        for name in ("native.log", "summary.json", "source-sha256.json"):
            path = output / name
            if path.is_file():
                shutil.copyfile(path, target / name)
                kept["files"][name] = hashlib.sha256(path.read_bytes()).hexdigest()
        kept["candidate"] = {"revision": summary.get("revision"),
                             "worktree_status": summary.get("worktree_status"),
                             "selection": summary.get("selection"),
                             "graphics_backend_preference": summary.get("graphics_backend_preference"),
                             "host": summary.get("host")}
        for path in sorted(native_output.glob("*.png")):
            kept["screenshots"][path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        # ⚠ Round-3 review B1: one unbound crop cannot replay the pixel gate. Keep a crop of
        # the marker region for EVERY captured frame — a few hundred kilobytes in total, where
        # the screenshots themselves would be tens of megabytes — so the per-frame judgement can
        # be re-made from the repository.
        crops, crop_origins = retain_marker_crops(native_output, target)
        kept["files"].update(crops)
        kept["crops"] = sorted(crops)
        # ⚠ Round-4 review B1: the reviewer had to reconstruct each crop's origin from the
        # recording constants, which makes placement a source-based assumption rather than a
        # retained fact. State it.
        kept["crop_origins"] = crop_origins
        # ⚠ Round-4 review B1: the crops are post-composition screenshots. The AUTHORITATIVE
        # proof is the readback of Minecraft's own colour image, and none of its pixels were
        # retained, so no reader could reclassify it. Keep the raw samples; they are the one
        # artifact the marker verdict actually rests on.
        samples = {}
        for pattern in ("native-marker-sample-*.ppm", "native-marker-rejected-*.ppm.gz",
                        "native-depth-sample.pgm.gz",
                        "native-terrain-sample-*.ppm.gz",
                        "native-terrain-reference-*.ppm.gz"):
            for path in sorted(native_output.glob(pattern)):
                shutil.copyfile(path, target / path.name)
                samples[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        kept["files"].update(samples)
        kept["raw_colour_samples"] = sorted(samples)
        if not samples:
            raise ValueError("no raw colour-image sample was produced, so the authoritative"
                             " marker proof cannot be retained")
        # ⚠ Round-5 review B1: "at least one sample exists" was not the requirement. The
        # reviewer duplicated a sample, deleted the one the report REFERENCES, and retention
        # reported no error while replay then failed on the missing file. The sample each
        # report points at is the one that has to be here.
        for name, referenced in (("native-marker-draw.json", lambda r: (r.get("readback") or {}).get("rejectedOrientationSample")),
                                 ("native-marker-draw.json", lambda r: (r.get("readback") or {}).get("sampleFile")),
                                 ("native-depth-probe.json", lambda r: r.get("sampleFile")),
                                 ("native-terrain-probe.json", lambda r: (r.get("comparison") or {}).get("sampleFile"))):
            path = native_output / name
            if not path.is_file():
                continue
            wanted = referenced(json.loads(path.read_text()))
            if wanted and wanted not in samples:
                raise ValueError(f"{name} references the sample {wanted}, which was not"
                                 f" retained; the evidence would point at nothing")
            if wanted and name == "native-terrain-probe.json":
                pair = wanted.replace("native-terrain-sample-", "native-terrain-reference-")
                if pair not in samples:
                    raise ValueError(f"{name} references {wanted} but its reference image"
                                     f" {pair} was not retained, so it cannot be recompared")
        # ⚠ The ladder's own launch is evidence too, and round 7 recorded
        # `isolated_run_independently_confirmed: false` against an isolation claim whose run
        # was never retained. Keep the ladder launch under ladder/: its proof files, its raw
        # crop and its log, all manifest members, and require the referenced crop.
        ladder_output = native_output.parent / "native-ladder"
        kept["ladder"] = None
        if ladder_output.is_dir():
            ladder_target = target / "ladder"
            ladder_target.mkdir(exist_ok=True)
            ladder_kept = {}
            for pattern in ("*.json", "native-depth-ladder-*.ppm.gz",
                            "native-terrain-load-*.ppm.gz", "native-terrain-load-*.f32.gz",
                            "native-real-load-*.ppm.gz", "native-real-load-*.f32.gz",
                            "native-hier-load-*.ppm.gz", "native-hier-load-*.f32.gz"):
                for path in sorted(ladder_output.glob(pattern)):
                    shutil.copyfile(path, ladder_target / path.name)
                    ladder_kept["ladder/" + path.name] = hashlib.sha256(
                        path.read_bytes()).hexdigest()
            log = output / "native-ladder.log"
            if log.is_file():
                shutil.copyfile(log, ladder_target / log.name)
                ladder_kept["ladder/" + log.name] = hashlib.sha256(log.read_bytes()).hexdigest()
            report_path = ladder_output / "native-depth-ladder.json"
            if not report_path.is_file():
                raise ValueError("the ladder launch wrote no native-depth-ladder.json, so its"
                                 " measurement cannot be retained")
            wanted = []
            ladder_report = json.loads(report_path.read_text())
            for entry in ladder_report.get("coexist") or []:
                for field in ("file", "frameFile"):
                    name = (entry or {}).get(field)
                    if not name or "ladder/" + name not in ladder_kept:
                        raise ValueError(f"the ladder's coexist result references {name!r},"
                                         f" which was not retained")
                    wanted.append("ladder/" + name)
            for name_of in ("native-hier-load.json",):
                path_of = ladder_output / name_of
                if not path_of.is_file():
                    continue
                for entry in json.loads(path_of.read_text()).get("results") or []:
                    for field in ("file", "frameFile", "referenceFile", "referenceDepthFile"):
                        name = (entry or {}).get(field)
                        if name is None:
                            continue
                        if "ladder/" + name not in ladder_kept:
                            raise ValueError(f"the hierarchical-LOAD result references {name!r},"
                                             f" which was not retained")
                        wanted.append("ladder/" + name)
            real_load_path = ladder_output / "native-real-load.json"
            if real_load_path.is_file():
                for entry in json.loads(real_load_path.read_text()).get("results") or []:
                    for field in ("file", "frameFile", "referenceFile", "referenceDepthFile"):
                        name = (entry or {}).get(field)
                        if name is None:
                            continue   # a sample the probe did not judge names no files
                        if "ladder/" + name not in ladder_kept:
                            raise ValueError(f"the real-LOAD result references {name!r}, which"
                                             f" was not retained")
                        wanted.append("ladder/" + name)
            terrain_load_path = ladder_output / "native-terrain-load.json"
            if terrain_load_path.is_file():
                for entry in json.loads(terrain_load_path.read_text()).get("results") or []:
                    for field in ("file", "frameFile", "referenceFile", "referenceDepthFile"):
                        name = (entry or {}).get(field)
                        if not name or "ladder/" + name not in ladder_kept:
                            raise ValueError(f"the terrain-LOAD result references {name!r},"
                                             f" which was not retained")
                        wanted.append("ladder/" + name)
            for sample in ladder_report.get("samples") or []:
                for field in ("file", "rejectedFile", "frameFile"):
                    name = (sample or {}).get(field)
                    if not name:
                        raise ValueError(f"a ladder sample references no {field}, so it"
                                         f" cannot be recounted from the repository")
                    if "ladder/" + name not in ladder_kept:
                        raise ValueError(f"the ladder report references {name}, which was"
                                         f" not retained; the evidence would point at nothing")
                    wanted.append("ladder/" + name)
            if not wanted:
                raise ValueError("the ladder report lists no samples, so there is nothing to"
                                 " recount from the repository")
            # ⚠ What the band was looking at is part of the claim ("over terrain", not
            # "over sky"); keep a crop of the band from every captured frame, both ends
            # because the frame's row order is y-ambiguous, with origins stated.
            crops, crop_origins = retain_ladder_band_crops(ladder_output, ladder_target)
            ladder_kept.update(crops)
            kept["files"].update(ladder_kept)
            kept["ladder"] = {"path": "ladder", "files": sorted(ladder_kept),
                              "samples": wanted,
                              "band_crops": sorted(crops), "band_crop_origins": crop_origins}
        # The product launch: its probe report, its own checkpoints, its log, and a quarter-scale
        # thumbnail of every checkpoint frame — what normal play looked like in each stage.
        render_output = native_output.parent / "native-render"
        kept["render"] = None
        if render_output.is_dir():
            render_target = target / "render"
            render_target.mkdir(exist_ok=True)
            render_kept = {}
            for path in sorted(render_output.glob("*.json")):
                shutil.copyfile(path, render_target / path.name)
                render_kept["render/" + path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
            log = output / "native-render.log"
            if log.is_file():
                shutil.copyfile(log, render_target / log.name)
                render_kept["render/" + log.name] = hashlib.sha256(log.read_bytes()).hexdigest()
            if "render/native-hier-load.json" not in render_kept:
                raise ValueError("the product launch wrote no native-hier-load.json, so it cannot"
                                 " be retained")
            thumbs = retain_render_thumbnails(render_output, render_target)
            missing = [st for st in LIFECYCLE_STAGES if f"render/frame-{st}.png" not in thumbs]
            if missing:
                raise ValueError(f"the product launch's checkpoint thumbnails for {missing} could"
                                 f" not be retained")
            render_kept.update(thumbs)
            kept["files"].update(render_kept)
            kept["render"] = {"path": "render", "files": sorted(render_kept),
                              "thumbnails": sorted(thumbs), "thumbnailScale": RENDER_THUMB_SCALE}
        (target / "MANIFEST.json").write_text(json.dumps(kept, indent=2) + "\n")
        kept["path"] = str(target.relative_to(ROOT))
    except (OSError, ValueError) as exc:
        kept["error"] = str(exc)
    return kept


def retain_ladder_band_crops(ladder_output, target):
    """Save a PNG of the ladder band (inline control top to control-band bottom) from every
    captured frame of the ladder launch. Visual record only: the gate does not read these."""
    band = EXPECTED_LADDER_BAND
    top, bottom = max(band[1], band[3]), min(band[1], band[3])
    kept, origins = {}, {}
    for png in sorted(ladder_output.glob("*.png")):
        try:
            width, height = png_size(png)
            x0 = max(0, int((band[0] + 1.0) * 0.5 * width) - 2)
            x1 = min(width, int((band[2] + 1.0) * 0.5 * width) + 2)
            ys = [int((1.0 - v) * 0.5 * height) for v in (top, bottom)]
            for label, rows_range in (("top", ys), ("bottom", [height - y for y in ys])):
                # a little context above and below, so the eye can see what the band covers
                y0, y1 = max(0, min(rows_range) - 24), min(height, max(rows_range) + 24)
                if y1 <= y0:
                    continue
                rows, _ = top_rows_rgb(png, y1 + 1)
                crop = [row[x0:x1] for row in rows[y0:y1]]
                if not crop or not crop[0]:
                    continue
                name = f"crop-{png.stem}-band-{label}.png"
                write_rgb_png(target / name, crop)
                kept["ladder/" + name] = hashlib.sha256((target / name).read_bytes()).hexdigest()
                origins["ladder/" + name] = {"parent": [width, height], "origin": [x0, y0],
                                             "size": [len(crop[0]), len(crop)], "end": label}
        except (OSError, ValueError):
            continue
    return kept, origins


RENDER_THUMB_SCALE = 4


def retain_render_thumbnails(render_output, target):
    """A RENDER_THUMB_SCALE-times smaller copy (block means) of every checkpoint frame of the
    product launch. Visual record only: the gate does not read these."""
    kept = {}
    for png in sorted(render_output.glob("*.png")):
        try:
            width, height = png_size(png)
            rows, _ = top_rows_rgb(png, height)
        except (OSError, ValueError):
            continue
        k = RENDER_THUMB_SCALE
        thumb = []
        for ty in range(height // k):
            row = []
            for tx in range(width // k):
                acc = [0, 0, 0]
                for y in range(ty * k, ty * k + k):
                    src = rows[y]
                    for x in range(tx * k, tx * k + k):
                        px = src[x]
                        acc[0] += px[0]; acc[1] += px[1]; acc[2] += px[2]
                row.append(tuple(v // (k * k) for v in acc))
            thumb.append(row)
        if not thumb or not thumb[0]:
            continue
        name = f"frame-{png.stem}.png"
        write_rgb_png(target / name, thumb)
        kept["render/" + name] = hashlib.sha256((target / name).read_bytes()).hexdigest()
    return kept


def retain_marker_crops(native_output, target):
    """Save a small PNG of the marker region from every captured frame."""
    report_path = native_output / "native-marker-draw.json"
    if not report_path.is_file():
        return {}
    report = json.loads(report_path.read_text())
    geometry = report.get("geometry") or {}
    box, strip = geometry.get("box"), geometry.get("controlStrip")
    cell = geometry.get("depthTestedPassCell")
    if not (isinstance(box, list) and isinstance(strip, list) and isinstance(cell, list)):
        return {}
    kept, origins = {}, {}
    for png in sorted(native_output.glob("*.png")):
        try:
            width, height = png_size(png)
            xs = [int((v + 1.0) * 0.5 * width) for v in (box[0], box[2])]
            ys = [int((1.0 - v) * 0.5 * height)
                  for v in (box[1], box[3], strip[1], strip[3], cell[1], cell[3])]
            # Both orientations can hold the marker, so keep a band from each end.
            mirrored = [height - y for y in ys]
            x0, x1 = max(0, min(xs) - 2), min(width, max(xs) + 2)
            for label, rows_range in (("top", ys), ("bottom", mirrored)):
                y0, y1 = max(0, min(rows_range) - 2), min(height, max(rows_range) + 2)
                if y1 <= y0:
                    continue
                rows, _ = top_rows_rgb(png, y1 + 1)
                crop = [row[x0:x1] for row in rows[y0:y1]]
                if not crop or not crop[0]:
                    continue
                name = f"crop-{png.stem}-{label}.png"
                write_rgb_png(target / name, crop)
                kept[name] = hashlib.sha256((target / name).read_bytes()).hexdigest()
                origins[name] = {"parent": [width, height], "origin": [x0, y0],
                                 "size": [len(crop[0]), len(crop)], "end": label}
        except (OSError, ValueError):
            continue
    return kept, origins


REPO = Path(__file__).resolve().parent.parent
SOURCE_BINDING_MINIMUM = 100
SOURCE_BINDING_REQUIRED = ("scripts/verify.py", "build.gradle",
                           "src/main/java/me/cortex/voxy/client/core/vk/mcnative/"
                           "McNativeDepthLadder.java",
                           "src/main/java/me/cortex/voxy/client/core/vk/mcnative/"
                           "McNativeMarkerDraw.java")


def source_binding(fingerprints):
    """Check a retained source fingerprint against the repository this replay runs in.

    ⚠ Round-11 review B1: comparing the LISTED files bound only what the producer chose to
    list — four required names plus a hundred unrelated files, or the real list minus one
    source, or a hundred aliases of one file, all passed. The fingerprint's key set must be
    exactly the checkout's own source inventory (the same selection `source_fingerprints`
    makes at launch), with normalized repository-relative names, and every byte must match.
    """
    if not isinstance(fingerprints, dict):
        raise ValueError("the fingerprint is not a map of file to sha256")
    if len(fingerprints) < SOURCE_BINDING_MINIMUM:
        raise ValueError(f"the fingerprint names only {len(fingerprints)} file(s); a run of"
                         f" this tree fingerprints hundreds")
    for name in SOURCE_BINDING_REQUIRED:
        if name not in fingerprints:
            raise ValueError(f"the fingerprint does not name {name}")
    for name, digest in fingerprints.items():
        if not isinstance(name, str) or not isinstance(digest, str) \
                or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError(f"the fingerprint entry {name!r}: {digest!r} is not a sha256")
        if name.startswith(("/", "./", "../")) or "/./" in name or "/../" in name \
                or "//" in name or name != name.strip():
            raise ValueError(f"the fingerprint names {name!r}, which is not a normalized"
                             f" repository-relative path")
    inventory = source_fingerprints(REPO)
    extra = sorted(set(fingerprints) - set(inventory))
    omitted = sorted(set(inventory) - set(fingerprints))
    if extra or omitted:
        raise ValueError(f"the fingerprint's file set is not this checkout's source inventory:"
                         f" {len(omitted)} source file(s) omitted, {len(extra)} unknown name(s)"
                         f" listed: {(omitted + extra)[:6]}")
    mismatched = sorted(name for name, digest in fingerprints.items()
                        if inventory[name] != digest)
    return {"entries": len(fingerprints), "inventory": len(inventory),
            "mismatched": mismatched, "absent": [], "checkout": str(REPO)}


def replay_evidence(directory):
    """Re-check a retained evidence directory without launching anything.

    ⚠ Round-4 review B1: the reviewer called the committed gates directly and they failed for
    want of the full screenshots, which are far too large to commit. The screenshots were only
    ever supporting evidence; the authoritative proof is the readback of Minecraft's own colour
    image, whose raw pixels and proof files ARE retained. This replays exactly that part, and
    says plainly which part it is not replaying.
    """
    report_path = directory / "native-marker-draw.json"
    if not report_path.is_file():
        print(f"{directory}: no native-marker-draw.json", file=sys.stderr)
        return 2
    report = json.loads(report_path.read_text())
    outcome = {"directory": str(directory), "replayed": [], "not_replayed": [
        "per-checkpoint screenshot measurement (the full frames are not committed; the "
        "retained crops carry it, with their origins in MANIFEST.json)",
        "the environment gate, the validation-layer loader evidence and the stage log's "
        "diagnostic scan — these read the run's log and checkpoints, not this directory's "
        "proof files",
        "the Minecraft-side behaviour itself: this replays the recorded evidence, it does "
        "not re-run anything"]}
    # ⚠ Round-5 review B1: a directory with MANIFEST.json simply deleted replayed as 0.
    # No manifest means nothing binds these files to the run, so there is nothing to replay.
    manifest = directory / "MANIFEST.json"
    if not manifest.is_file():
        outcome["error"] = "no MANIFEST.json, so nothing binds these files to a run"
        print(json.dumps(outcome, indent=2))
        return 1
    kept = json.loads(manifest.read_text())
    # ⚠ Round-6 review B1: an EMPTY files map produced no mismatches and passed. A manifest
    # that binds nothing binds nothing.
    files = kept.get("files") or {}
    if len(files) < 10:
        outcome["error"] = (f"the manifest lists only {len(files)} file(s), which cannot bind"
                            f" this directory to a run")
        print(json.dumps(outcome, indent=2))
        return 1
    bad = sorted(name for name, digest in files.items()
                 if not (directory / name).is_file()
                 or hashlib.sha256((directory / name).read_bytes()).hexdigest() != digest)
    outcome["manifest_mismatches"] = bad
    # Round-8 review B1: hashes were checked for whichever files were LISTED, so dropping the
    # reports, the summary, the source fingerprint or the log from the manifest (files intact)
    # replayed as 0. Every file in the directory must be a member; MANIFEST.json binds itself.
    # ⚠ Round-9 review B1: the exemption matched by BASENAME, so a nested ladder/MANIFEST.json
    # holding anything passed. Only the root manifest binds itself.
    unlisted = sorted(str(path.relative_to(directory)) for path in directory.rglob("*")
                      if path.is_file() and str(path.relative_to(directory)) != "MANIFEST.json"
                      and str(path.relative_to(directory)) not in files)
    outcome["manifest_unlisted"] = unlisted
    outcome["replayed"].append("manifest hashes and completeness")
    if unlisted:
        outcome["error"] = (f"{len(unlisted)} retained file(s) are not manifest members, so"
                            f" nothing binds them to the run: {unlisted[:8]}")
        print(json.dumps(outcome, indent=2))
        return 1
    # ⚠ Round-9 review B1: membership of whatever survived is not an inventory. Deleting the
    # source fingerprint or a log together with its entry replayed as 0. These must exist.
    required = ["summary.json", "source-sha256.json", "native.log", "native-marker-draw.json",
                "native-adopted-context.json", "native-vulkan-probe.json",
                "native-device-features.json", "native-compute-probe.json",
                "native-real-shader.json"]
    stage_for_inventory = ((json.loads((directory / "summary.json").read_text())
                            .get("stages") or {}).get("native_environment")
                           if (directory / "summary.json").is_file() else None) or {}
    if stage_for_inventory.get("ladder_run"):
        required += ["ladder/native-depth-ladder.json", "ladder/native-result.json",
                     "ladder/native-ladder.log"]
    if stage_for_inventory.get("terrain"):
        required.append("native-terrain-probe.json")
    if stage_for_inventory.get("depth"):
        required.append("native-depth-probe.json")
    if (stage_for_inventory.get("instance") or {}).get("enabled"):
        required.append("native-instance.json")
    missing = [name for name in required if name not in files or not (directory / name).is_file()]
    outcome["required_missing"] = missing
    if missing:
        outcome["error"] = (f"required retained file(s) are absent or unlisted, so the run is"
                            f" not fully bound: {missing}")
        print(json.dumps(outcome, indent=2))
        return 1
    # ⚠ Round-10 review B1: the fingerprint only had to exist. Replaced by `{}` it still
    # replayed as 0. It must name the sources the run was built from, and they must be the
    # sources of THIS checkout — that is what binds the retained run to the candidate.
    try:
        binding = source_binding(json.loads((directory / "source-sha256.json").read_text()))
    except (ValueError, TypeError, OSError) as exc:
        outcome["error"] = f"source-sha256.json does not bind the run: {exc}"
        print(json.dumps(outcome, indent=2))
        return 1
    outcome["source_binding"] = binding
    outcome["replayed"].append("source fingerprint against this checkout")
    if binding["mismatched"] or binding["absent"]:
        outcome["error"] = (f"the retained source fingerprint disagrees with this checkout:"
                            f" {len(binding['mismatched'])} file(s) differ,"
                            f" {len(binding['absent'])} absent; the run was not built from"
                            f" these sources: {(binding['mismatched'] + binding['absent'])[:6]}")
        print(json.dumps(outcome, indent=2))
        return 1
    if bad:
        print(json.dumps(outcome, indent=2))
        return 1
    try:
        # ⚠ Round-5 review B1: replay used to call only the recount, so a report saying
        # attempted=false, timesWithAProblem=3, enabled=false, depthAttached=false and
        # closeFailures=99 replayed as 0. It now runs every marker check that does not need
        # the full screenshots — the same function the stage gate uses.
        checks = marker_report_checks(directory, report)
        outcome["recount"] = checks.get("recount")
        outcome["replayed"].append("marker report acceptance checks")
        outcome["replayed"].append("authoritative raw colour-image recount")
        # The proofs must be tied to the lifecycle the retained summary records, not to an
        # empty list that would make the identity check vacuous.
        retained = directory / "summary.json"
        if not retained.is_file():
            raise ValueError("no retained summary.json, so the proofs cannot be tied to a run")
        stage = (json.loads(retained.read_text()).get("stages") or {}).get("native_environment")
        checkpoints = ((stage or {}).get("gate") or {}).get("checkpoints") or []
        outcome["checkpoints"] = len(checkpoints)
        outcome["proofs"] = native_proof_files_result(directory, checkpoints)["failures"]
        outcome["replayed"].append("proof-file consistency")
        # ⚠ Round-7 review R7-DEPTH-GATE: replay did not call the depth gate AT ALL and did
        # not list it among its omissions, so all three contradictory depth mutations replayed
        # as 0. This is the third time a gate and the replay diverged; every gate belongs here.
        depth_path = directory / "native-depth-probe.json"
        # Round-8 review: a summary that recorded a depth gate while the report is absent
        # replayed as 0 by silently skipping. The stage result says what ran; require it.
        if (stage or {}).get("depth") and not depth_path.is_file():
            raise ValueError("the retained summary records a depth gate but"
                             " native-depth-probe.json is not retained")
        if depth_path.is_file():
            depth = native_depth_result(directory, single_checkpoint_device(checkpoints))
            if not depth["success"]:
                raise ValueError(f"depth gate: {depth['failures']}")
            outcome["depth"] = {"answer": depth.get("answer"),
                                "recount": depth.get("recount", {}).get("size")}
            outcome["replayed"].append("depth probe acceptance checks and pixel recount")
        # Native instance mode (a world engine without a render path): required when the
        # retained launch command enabled it; the retained native.log is reconciled.
        instance_launched = launch_enables((stage or {}).get("command"), "harnessNativeInstance")
        instance_log = directory / "native.log"
        instance = native_instance_result(
            directory, instance_launched,
            instance_log.read_text(errors="replace") if instance_log.is_file() else None)
        if not instance["success"]:
            raise ValueError(f"instance gate: {instance['failures']}")
        if instance.get("enabled"):
            outcome["instance"] = {"answer": instance.get("answer"),
                                   "samples": instance.get("samples")}
            outcome["replayed"].append("native instance mode acceptance checks (world engine,"
                                       " ingested sections, no render path), log reconciled")
        terrain_path = directory / "native-terrain-probe.json"
        if terrain_path.is_file():
            terrain = json.loads(terrain_path.read_text())
            # ⚠ Round-7 review R6-TERRAIN-GATE residual: replay omitted the adopted-device
            # argument, so the terrain probe's identity was never compared on this path.
            adopted = json.loads((directory / "native-adopted-context.json").read_text())
            # Round-8 review R6-TERRAIN-GATE: a decimal adopted handle made this None and
            # the terrain identity comparison vacuous. Parse it the one way, or fail.
            expected = device_handle_of(adopted.get("device"), "native-adopted-context.json",
                                        "device")
            terrain_checks = terrain_report_checks(directory, terrain, expected)
            outcome["terrain_recompare"] = terrain_checks["recompare"]
            outcome["replayed"].append("terrain report acceptance checks")
            outcome["replayed"].append("terrain frame-vs-reference recomparison")
        # ⚠ The depth ladder has its own launch and its own directory. Its gate is the same
        # function the stage calls, with the device tied to THAT launch's checkpoints — a
        # second process has a second VkDevice, so the main launch's identity is the wrong
        # reference. Say so when none was retained, rather than silently skipping.
        ladder_dir = directory / "ladder"
        ladder_path = ladder_dir / "native-depth-ladder.json"
        ladder_refs = []
        if (stage or {}).get("ladder_run") and not ladder_path.is_file():
            raise ValueError("the retained summary records a ladder launch but"
                             " ladder/native-depth-ladder.json is not retained")
        if ladder_path.is_file():
            ladder_stage = (stage or {}).get("ladder_run") or {}
            ladder_checkpoints = ((ladder_stage.get("environment") or {}).get("checkpoints")
                                  or [])
            # Round-8 review: replay consumed only the summary's copy of the ladder launch's
            # checkpoints. The launch's own native-result.json must agree with it.
            own_path = ladder_dir / "native-result.json"
            if not own_path.is_file():
                raise ValueError("the ladder launch's own native-result.json is not retained")
            own = json.loads(own_path.read_text()).get("checkpoints") or []
            if own != ladder_checkpoints:
                raise ValueError("the ladder launch's own checkpoints differ from the summary's"
                                 " copy, so its identity is not established")
            ladder_device = single_checkpoint_device(ladder_checkpoints)
            ladder_extents = checkpoint_extents(ladder_checkpoints)
            ladder = json.loads(ladder_path.read_text())
            ladder_log = ladder_dir / "native-ladder.log"
            if not ladder_log.is_file():
                raise ValueError("the ladder launch's log is not retained, so its sample"
                                 " inventory cannot be reconciled")
            # ⚠ Round-15 review R15-COEXIST-PRESENCE: the retained launch command says whether
            # the experiment was on; a report that says otherwise is not that launch's.
            # ⚠ Round-17 review: the stage's literal tokens are the authority; a command that
            # lacks them is not the stage's launch (ladder_launch_requirements refuses it).
            launched = ladder_launch_requirements(ladder_stage.get("command"))
            ladder_result = native_ladder_result(ladder_dir, ladder_device, ladder_extents,
                                                 ladder_log.read_text(errors="replace"),
                                                 ladder_checkpoints,
                                                 require_coexist=launched["coexist"],
                                                 require_terrain_load=launched["terrainLoad"],
                                                 require_real_load=launched["realLoad"],
                                                 require_hier_load=launched["hierLoad"],
                                                 require_hier_frames=launched["hierFrames"])
            if not ladder_result["success"]:
                raise ValueError(f"ladder gate: {ladder_result['failures']}")
            outcome["ladder"] = {"samples": ladder_result["samples"],
                                 "z_direction": ladder_result.get("z_direction"),
                                 "coexist": ladder_result.get("coexist"),
                                 "checkpoints": len(ladder_checkpoints)}
            # ⚠ Round-13 review R13-Z-BINDING: the summary's saved direction was never
            # compared with the recomputed one, so a relabelled report could contradict it.
            saved = ((ladder_stage.get("ladder") or {}).get("z_direction") or {}).get("direction")
            if saved != ladder_result["z_direction"]["direction"]:
                raise ValueError(f"the retained summary saved the direction {saved!r} but the"
                                 f" retained evidence now reads"
                                 f" {ladder_result['z_direction']['direction']!r}")
            outcome["replayed"].append("depth-ladder acceptance checks (its own launch,"
                                       " terrain and marker off, identity from its own"
                                       " checkpoints)")
            outcome["replayed"].append("depth-ladder per-pixel recount of every retained crop,"
                                       " both orientations")
            for sample in ladder.get("samples") or []:
                ladder_refs += ["ladder/" + sample.get("file", ""),
                                "ladder/" + sample.get("rejectedFile", ""),
                                "ladder/" + sample.get("frameFile", "")]
            for entry in ladder.get("coexist") or []:
                ladder_refs.append("ladder/" + str(entry.get("file", "")))
                ladder_refs.append("ladder/" + str(entry.get("frameFile", "")))
            for entry in (ladder_result.get("terrain_load") or {}).get("samples") or []:
                for field in ("file", "frameFile", "referenceFile", "referenceDepthFile"):
                    ladder_refs.append("ladder/" + str(entry.get(field, "")))
            outcome["ladder"]["terrain_load"] = ladder_result.get("terrain_load")
            for entry in (ladder_result.get("real_load") or {}).get("samples") or []:
                if entry.get("status") == "judged":
                    for field in ("file", "frameFile", "referenceFile", "referenceDepthFile"):
                        ladder_refs.append("ladder/" + f"native-real-load-"
                                           + {"file": "", "frameFile": "frame-",
                                              "referenceFile": "reference-",
                                              "referenceDepthFile": "depth-"}[field]
                                           + f"{entry['at']}"
                                           + (".f32.gz" if field == "referenceDepthFile" else ".ppm.gz"))
            outcome["ladder"]["real_load"] = ladder_result.get("real_load")
            for entry in (ladder_result.get("hier_load") or {}).get("samples") or []:
                if entry.get("status") == "judged":
                    at_ = entry["at"]
                    ladder_refs += [f"ladder/native-hier-load-{at_}.ppm.gz",
                                    f"ladder/native-hier-load-frame-{at_}.ppm.gz",
                                    f"ladder/native-hier-load-reference-{at_}.ppm.gz",
                                    f"ladder/native-hier-load-depth-{at_}.f32.gz",
                                    f"ladder/native-hier-load-voxydepth-{at_}.f32.gz"]
            outcome["ladder"]["hier_load"] = ladder_result.get("hier_load")
            if (ladder_result.get("hier_load") or {}).get("enabled"):
                outcome["replayed"].append("hierarchical-LOAD per-pixel recount of every judged"
                                           " sample by Voxy's GL rule against the ladder's CLEAR"
                                           " class of the same frame, and the reprojection of"
                                           " every reference depth")
            if (ladder_result.get("real_load") or {}).get("enabled"):
                outcome["replayed"].append("real-LOAD per-pixel recount of every judged sample"
                                           " against the ladder's brackets and Voxy's reference"
                                           " depth of the same frame")
            # instance mode in the ladder launch (real-LOAD needs it)
            ladder_instance = native_instance_result(
                ladder_dir, launched["instance"], ladder_log.read_text(errors="replace"))
            if not ladder_instance["success"]:
                raise ValueError(f"ladder instance gate: {ladder_instance['failures']}")
            if (ladder_result.get("terrain_load") or {}).get("enabled"):
                outcome["replayed"].append("terrain-LOAD per-pixel recount of every retained"
                                           " crop against the ladder's brackets and Voxy's"
                                           " reference depth")
        else:
            outcome["not_replayed"].append("the depth ladder: this run retained no ladder"
                                           " launch, so no bound on Minecraft's depth is"
                                           " replayed from it")
        # The product launch (voxy.native.render alone): same gate, its own checkpoints.
        render_dir = directory / "render"
        render_report = render_dir / "native-hier-load.json"
        if (stage or {}).get("render_run") and not render_report.is_file():
            raise ValueError("the retained summary records a product launch but"
                             " render/native-hier-load.json is not retained")
        if render_report.is_file():
            render_stage = (stage or {}).get("render_run") or {}
            render_checkpoints = ((render_stage.get("environment") or {}).get("checkpoints") or [])
            own_path = render_dir / "native-result.json"
            if not own_path.is_file():
                raise ValueError("the product launch's own native-result.json is not retained")
            if (json.loads(own_path.read_text()).get("checkpoints") or []) != render_checkpoints:
                raise ValueError("the product launch's own checkpoints differ from the summary's"
                                 " copy, so its identity is not established")
            command = render_stage.get("command")
            if not isinstance(command, list) or any(f not in command for f in RENDER_LAUNCH_FLAGS):
                raise ValueError("the retained product launch command lacks the product switch")
            render_log = render_dir / "native-render.log"
            if not render_log.is_file():
                raise ValueError("the product launch's log is not retained")
            render_result = native_render_result(render_dir, render_log.read_text(errors="replace"),
                                                 single_checkpoint_device(render_checkpoints),
                                                 command)
            if not render_result["success"]:
                raise ValueError(f"product render gate: {render_result['failures']}")
            outcome["render"] = {"framesComposited": render_result["framesComposited"],
                                 "perStage": render_result["perStage"]}
            # round-27: every lifecycle checkpoint's thumbnail is part of the claim
            ladder_refs += ["render/native-hier-load.json", "render/native-result.json",
                            "render/native-render.log"] + [f"render/frame-{stage_}.png"
                                                           for stage_ in LIFECYCLE_STAGES]
            outcome["replayed"].append("product launch acceptance checks (voxy.native.render"
                                       " alone; its own checkpoints)")
        else:
            outcome["not_replayed"].append("the product launch: this run retained none")
        # ⚠ Round-6 review R6-TERRAIN-GATE: manifest success is not provenance. Require the
        # samples the reports reference to be manifest MEMBERS, so a file dropped in beside
        # the evidence cannot stand in for one the run produced.
        rb_ref = report.get("readback") or {}
        terrain_ref = (terrain if terrain_path.is_file() else {}).get("comparison") or {}
        referenced = [rb_ref.get("sampleFile"), rb_ref.get("rejectedOrientationSample"),
                      (report.get("sampleFile") if depth_path.is_file() else None),
                      terrain_ref.get("sampleFile"), *ladder_refs]
        if terrain_ref.get("sampleFile"):
            referenced.append(terrain_ref["sampleFile"].replace(
                "native-terrain-sample-", "native-terrain-reference-"))
        if depth_path.is_file():
            referenced.append(depth.get("report", {}).get("sampleFile")
                              if isinstance(depth.get("report"), dict) else None)
        for name in [n for n in referenced if n]:
            if name not in (kept.get("files") or {}):
                raise ValueError(f"{name} is referenced but is not a manifest member, so it is"
                                 f" not evidence this run produced")
    except (OSError, ValueError, KeyError, TypeError, EOFError, zlib.error) as exc:
        # ⚠ Round-6 review (non-blocking): a truncated gzip trailer threw an uncaught
        # EOFError, which is not a false pass but is not the promised structured failure.
        outcome["error"] = f"{type(exc).__name__}: {exc}"
        print(json.dumps(outcome, indent=2))
        return 1
    print(json.dumps(outcome, indent=2))
    return 0 if not outcome["proofs"] else 1


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
    parser.add_argument("--replay-evidence", metavar="DIR",
        help="re-run the marker gate's authoritative recount against a retained evidence "
             "directory (docs/ai/runs/native-evidence/<run>) and exit; launches nothing")
    args = parser.parse_args()
    if args.replay_evidence:
        return replay_evidence(Path(args.replay_evidence))
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
            result["success"] &= len(checkpoints) == len(LIFECYCLE_STAGES) and not result["missing_images"]
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
                "-PharnessNativeFeatures=true", "-PharnessNativeAdopt=true",
                "-PharnessNativeProbe=true", "-PharnessNativeTerrain=true",
                "-PharnessNativeDepth=true", "-PharnessNativeInstance=true"], output,
                args.timeout)
            result["gate"] = native_environment_result(native_output)
            result["success"] &= result["gate"]["success"]
            checkpoints = result["gate"].get("checkpoints") or []
            result["marker"] = native_marker_result(native_output, checkpoints)
            result["success"] &= result["marker"]["success"]
            result["proofs"] = native_proof_files_result(native_output, checkpoints)
            result["success"] &= result["proofs"]["success"]
            # ⚠ EXPERIMENTAL (round 4 permits terrain investigation, not promotion). It is
            # gated like everything else: if Voxy's real terrain pipeline is asked to record
            # into Minecraft's frame and the result disagrees with Voxy's own target, the stage
            # fails. Being experimental is about what may be CLAIMED, not about being ungated.
            # ⚠ Round-6 review R6-TERRAIN-GATE: the terrain probe's device was never
            # compared with the one every other proof names, so "0xdead" passed.
            identities = (result["proofs"].get("device_identities") or {})
            expected_device = None
            if identities.get("adopted"):
                expected_device = int(identities["adopted"], 16)
            result["terrain"] = native_terrain_result(native_output, expected_device)
            result["success"] &= result["terrain"]["success"]
            # ⚠ Answers a question (is Minecraft's depth readable, and which way does it run?)
            # rather than proving a claim. A "cannot be read" answer passes; a probe that
            # contradicts itself or asserts an unmeasured convention does not.
            result["depth"] = native_depth_result(native_output, expected_device)
            result["success"] &= result["depth"]["success"]
            # Native instance mode: a world engine with ingested sections and no render path.
            # Required whenever the launch enabled it (Gradle's semantics, see launch_enables).
            result["instance"] = native_instance_result(
                native_output, launch_enables(result["command"], "harnessNativeInstance"),
                (output / "native.log").read_text(errors="replace"))
            result["success"] &= result["instance"]["success"]
            native_log_checks(result, output / "native.log")
            result["scope"] = "Minecraft-native Vulkan environment only; no Voxy LoD acceptance"
            result["voxy_integration_status"] = "BLOCKED_UNIMPLEMENTED"
            # ⚠ The depth LADDER measures Minecraft's own loaded depth, and the terrain probe
            # above CLEARS that attachment every frame. They cannot share a launch, so the
            # ladder gets its own, with terrain and marker off and the ladder's report
            # required to say so. Its device is tied to its own run's checkpoints, not to the
            # first launch's: a second process has a second VkDevice.
            ladder_output = output / "native-ladder"
            ladder_output.mkdir()
            ladder_game = ladder_output / "game"
            ladder_game.mkdir()
            (ladder_game / ".voxy-harness").write_text(timestamp)
            (ladder_game / "options.txt").write_text((game / "options.txt").read_text())
            ladder_run = run_stage("native-ladder", ["runHarnessClient", *native_common,
                f"-PharnessOutput={ladder_output}", f"-PharnessRunDir={ladder_game}",
                f"-PharnessSeconds={args.seconds}", "-PharnessNative=true",
                "-PharnessGraphicsBackend=vulkan", "-PharnessNativeFeatures=true",
                "-PharnessNativeAdopt=true", "-PharnessNativeProbe=true",
                *LADDER_LAUNCH_FLAGS], output, args.timeout)
            ladder_run["environment"] = native_environment_result(ladder_output)
            ladder_run["success"] &= ladder_run["environment"]["success"]
            ladder_device, ladder_extents = None, None
            try:
                ladder_checkpoints = ladder_run["environment"].get("checkpoints") or []
                ladder_device = single_checkpoint_device(ladder_checkpoints)
                ladder_extents = checkpoint_extents(ladder_checkpoints)
            except ValueError as exc:
                ladder_run["device_error"] = str(exc)
                ladder_run["success"] = False
            ladder_run["ladder"] = native_ladder_result(
                ladder_output, ladder_device, ladder_extents,
                (output / "native-ladder.log").read_text(errors="replace"),
                ladder_run["environment"].get("checkpoints") or [],
                require_coexist=launch_enables(ladder_run["command"], "harnessNativeCoexist"),
                require_terrain_load=launch_enables(ladder_run["command"],
                                                    "harnessNativeTerrainLoad"),
                require_real_load=launch_enables(ladder_run["command"], "harnessNativeRealLoad"),
                require_hier_load=launch_enables(ladder_run["command"], "harnessNativeHierLoad"),
                require_hier_frames=launch_enables(ladder_run["command"], "harnessNativeHierFrames"))
            ladder_run["success"] &= ladder_run["ladder"]["success"]
            ladder_run["instance"] = native_instance_result(
                ladder_output, launch_enables(ladder_run["command"], "harnessNativeInstance"),
                (output / "native-ladder.log").read_text(errors="replace"))
            ladder_run["success"] &= ladder_run["instance"]["success"]
            native_log_checks(ladder_run, output / "native-ladder.log")
            ladder_run["scope"] = ("a second Minecraft launch, terrain and marker OFF, so the"
                                   " ladder tests Minecraft's own depth; bounds only, no"
                                   " convention")
            result["ladder_run"] = ladder_run
            result["success"] &= ladder_run["success"]
            # The product launch: the native path under the product switch alone, as normal play
            # would run it — no ladder, no other diagnostic. Its own process and device.
            render_output = output / "native-render"
            render_output.mkdir()
            render_game = render_output / "game"
            render_game.mkdir()
            (render_game / ".voxy-harness").write_text(timestamp)
            (render_game / "options.txt").write_text((game / "options.txt").read_text())
            render_run = run_stage("native-render", ["runHarnessClient", *native_common,
                f"-PharnessOutput={render_output}", f"-PharnessRunDir={render_game}",
                f"-PharnessSeconds={args.seconds}", "-PharnessNative=true",
                "-PharnessGraphicsBackend=vulkan", *RENDER_LAUNCH_FLAGS], output, args.timeout)
            render_run["environment"] = native_environment_result(render_output)
            render_run["success"] &= render_run["environment"]["success"]
            render_device = None
            try:
                render_device = single_checkpoint_device(
                    render_run["environment"].get("checkpoints") or [])
            except ValueError as exc:
                render_run["device_error"] = str(exc)
                render_run["success"] = False
            render_log = output / "native-render.log"
            render_run["render"] = native_render_result(
                render_output, render_log.read_text(errors="replace") if render_log.is_file()
                else None, render_device, render_run["command"])
            render_run["success"] &= render_run["render"]["success"]
            native_log_checks(render_run, render_log)
            render_run["scope"] = ("a third Minecraft launch with only the product switch"
                                   " (voxy.native.render): the native path as normal play runs it")
            result["render_run"] = render_run
            result["success"] &= render_run["success"]
            summary["stages"]["native_environment"] = result
            save()
            # Retained after the summary is written, so the evidence includes the finished
            # gate results and the log rather than a half-written snapshot.
            summary["native_evidence_pending"] = {"output": str(native_output)}
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
        # ⚠ Round-3 review B1: the retained summary was the pre-finalization copy, whose
        # success is false and whose source-change check had not run. Retain after the last
        # save, then save once more so the manifest is part of the summary too.
        pending = summary.pop("native_evidence_pending", None)
        if pending:
            stage = summary.get("stages", {}).get("native_environment")
            if isinstance(stage, dict):
                kept = retain_native_evidence(output, Path(pending["output"]), timestamp, summary)
                stage["retained_evidence"] = kept
                # ⚠ Round-4 review B1: a retention failure was recorded and then ignored,
                # so a run could pass while keeping nothing to check it with. The evidence is
                # part of the claim, so losing it fails the stage.
                if kept.get("error") or not kept.get("raw_colour_samples"):
                    stage["success"] = False
                    summary["success"] = False
                    summary.setdefault("retention_failure",
                        kept.get("error") or "no raw colour sample was retained")
            save()
    print(f"{'PASS' if summary['success'] else 'FAIL'}: {summary_file}", flush=True)
    return 0 if summary["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
