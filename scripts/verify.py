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
EXPECTED_LADDER_PALETTE = [[1.0, 1.0, 1.0], [0.5, 0.5, 0.5], [1.0, 0.0, 1.0], [0.0, 1.0, 1.0],
                           [1.0, 1.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0],
                           [1.0, 0.5, 0.0], [0.5, 0.0, 1.0]]
LADDER_BASE, LADDER_LOW, LADDER_RUNG0 = 0, 1, 2
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


def ladder_report_checks(output, report, expected_device=None, expected_extents=None):
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
    samples = report["samples"]
    if not samples:
        raise ValueError("the ladder retained no sample, so there is nothing to recount")
    checks["samples"] = []
    last_at = 0
    for index, sample in enumerate(samples):
        if not isinstance(sample, dict):
            raise ValueError(f"ladder sample {index} is {sample!r}, not an object")
        for field, kind in (("at", int), ("flipped", bool), ("targetWidth", int),
                            ("targetHeight", int), ("rect", list), ("counts", dict),
                            ("rejectedFlipped", bool), ("rejectedRect", list),
                            ("rejectedOther", int), ("file", str), ("rejectedFile", str)):
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
        for field in ("anomaly", "low", "other"):
            if not finite_int(counts.get(field)) or counts[field] < 0:
                raise ValueError(f"ladder sample {index}.counts.{field} is"
                                 f" {counts.get(field)!r}")
        rungs = counts.get("rungs")
        if (not isinstance(rungs, list) or len(rungs) != LADDER_RUNGS
                or any(not finite_int(v) or v < 0 for v in rungs)):
            raise ValueError(f"ladder sample {index}.counts.rungs is {rungs!r}")
        rect = sample["rect"]
        area = (rect[2] - rect[0]) * (rect[3] - rect[1])
        total = counts["anomaly"] + counts["low"] + counts["other"] + sum(rungs)
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
                                     "native-depth-ladder-rejected-")):
            if name != f"{prefix}{at}.ppm.gz":
                raise ValueError(f"ladder sample {index}.{label} is {name!r}, not the crop of"
                                 f" draw {at}")
        checks["samples"].append(recount_ladder_sample(output, sample))
    return checks


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
    ours = {"anomaly": counts[LADDER_BASE], "low": counts[LADDER_LOW],
            "rungs": counts[LADDER_RUNG0:LADDER_RUNG0 + LADDER_RUNGS],
            "other": counts[LADDER_OTHER]}
    for field in ("anomaly", "low", "rungs", "other"):
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
    out = dict(ours)
    out.update(sample=sample["file"], at=sample["at"], flipped=sample["flipped"],
               size=[width, height], rejectedOther=rejected_other,
               brackets=ladder_brackets(ours))
    return out


def ladder_brackets(counts):
    """The per-pixel depth brackets a sample's counts describe, as text; no bound, no direction."""
    z = EXPECTED_LADDER_DEPTHS
    parts = [f"d < {z[0]:.3g}: {counts['low']} px"]
    for i, n in enumerate(counts["rungs"]):
        upper = f"{z[i + 1]:.3g}" if i + 1 < LADDER_RUNGS else None
        parts.append((f"{z[i]:.3g} < d <= {upper}" if upper else f"d > {z[i]:.3g}")
                     + f": {n} px")
    return parts


def native_ladder_result(output, expected_device=None, expected_extents=None):
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
        result.update(ladder_report_checks(output, report, expected_device, expected_extents))
        latest = result["samples"][-1]
        result["answer"] = (f"{len(result['samples'])} sample(s); the latest (draw"
                            f" {latest['at']}) brackets: " + "; ".join(latest["brackets"])
                            + "; which direction is nearer is unmeasured")
        result["z_convention_measured"] = False
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
        for field in ("min", "max", "topMean", "bottomMean", "clearedValue", "clearedShare"):
            value = report.get(field)
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                raise ValueError(f"depth.{field} is {value!r}, not a number")
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
            for pattern in ("*.json", "native-depth-ladder-*.ppm.gz"):
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
            for sample in json.loads(report_path.read_text()).get("samples") or []:
                for field in ("file", "rejectedFile"):
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
    unlisted = sorted(str(path.relative_to(directory)) for path in directory.rglob("*")
                      if path.is_file() and path.name != "MANIFEST.json"
                      and str(path.relative_to(directory)) not in files)
    outcome["manifest_unlisted"] = unlisted
    outcome["replayed"].append("manifest hashes and completeness")
    if unlisted:
        outcome["error"] = (f"{len(unlisted)} retained file(s) are not manifest members, so"
                            f" nothing binds them to the run: {unlisted[:8]}")
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
            ladder_checks = ladder_report_checks(ladder_dir, ladder, ladder_device,
                                                 ladder_extents)
            outcome["ladder"] = {"samples": [
                {k: v for k, v in sample.items() if k != "brackets"} | {"brackets": sample["brackets"]}
                for sample in ladder_checks["samples"]],
                "checkpoints": len(ladder_checkpoints)}
            outcome["replayed"].append("depth-ladder acceptance checks (its own launch,"
                                       " terrain and marker off, identity from its own"
                                       " checkpoints)")
            outcome["replayed"].append("depth-ladder per-pixel recount of every retained crop,"
                                       " both orientations")
            for sample in ladder.get("samples") or []:
                ladder_refs += ["ladder/" + sample.get("file", ""),
                                "ladder/" + sample.get("rejectedFile", "")]
        else:
            outcome["not_replayed"].append("the depth ladder: this run retained no ladder"
                                           " launch, so no bound on Minecraft's depth is"
                                           " replayed from it")
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
                "-PharnessNativeFeatures=true", "-PharnessNativeAdopt=true",
                "-PharnessNativeProbe=true", "-PharnessNativeTerrain=true",
                "-PharnessNativeDepth=true"], output, args.timeout)
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
                "-PharnessNativeDepthLadder=true"], output, args.timeout)
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
            ladder_run["ladder"] = native_ladder_result(ladder_output, ladder_device,
                                                        ladder_extents)
            ladder_run["success"] &= ladder_run["ladder"]["success"]
            native_log_checks(ladder_run, output / "native-ladder.log")
            ladder_run["scope"] = ("a second Minecraft launch, terrain and marker OFF, so the"
                                   " ladder tests Minecraft's own depth; bounds only, no"
                                   " convention")
            result["ladder_run"] = ladder_run
            result["success"] &= ladder_run["success"]
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
