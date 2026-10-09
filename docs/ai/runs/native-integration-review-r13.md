VERDICT: REDESIGN

Independent round-13 review of **e1bf895dc02527c11fc716ce62a836b7a1ec5eeb**, in `/Users/xyz/orca/workspaces/voxy/native-review-r13`, 2026-10-09. Initial HEAD matched the request and the working tree was clean. Implementation, fixtures, committed evidence and living specifications were not changed. Only this report and review material under `.agent-run/` were written. No Minecraft/native/live launch was performed.

The original two looks support **reverse-Z at this hook**, subject to the inspected source and the retained scene geometry. This is a useful new bounded observation. It is not a measurement of scale or native Voxy coexistence. **The gate is not yet a sufficient consistency check for its new direction evidence:** changing just the ladder report's stage/camera metadata and refreshing the manifest reverses its answer while leaving contradictory stage logs, summary and all images intact. That is narrower than the already disclosed escape involving a jointly rewritten evidence package.

| Candidate claim | Judgment |
| --- | --- |
| (a) The retained original experiment measures larger depth values as nearer | **CONFIRMED as a bounded empirical observation** for these two looks and this unmodified source path. Camera movement and nearer-look identification do not come from depth values. **REFUTED** as a general guarantee for arbitrary terrain from a one-column heightmap alone. |
| (b) The reading uses retained, replayable inputs | **CONFIRMED for the original package:** replay 0 and independent recounts match. **REFUTED if this means replay establishes that the retained geometry labels agree with the other retained facts.** R13-Z-BINDING below gives opposite answers with unchanged pixels. |
| (c) The survey claims no more than the evidence | **CONFIRMED** for the new section's local observation, unmeasured scale and absent coexistence. **REFUTED** for its blanket consistency wording and remaining contradictory wording about what the measurement checks; see item 4. |

## 1. Direction measurement and geometry

**CONFIRMED — geometry was established independently of depth for this observation.** `src/harness/java/me/cortex/voxy/harness/LiveWorldHarness.java:165-170` creates a normal fixed-seed world (`20261004`); `:200-206` reads `MOTION_BLOCKING` height at `(0,0)` once and reuses that **same** `groundY` for both teleports. `:127-129` maintains pitch 90 and yaw 0. Readiness checks at `:227-228,332-333` wait for the player near the teleport destination. A heightmap result is the column's surface boundary/first free height, not an independently surveyed plane over the entire band.

The retained ladder launch's checkpoints (`ladder/native-result.json:552-555,603-606`) say:

| Look | Ground | Player y | Camera y | Pitch |
| --- | ---: | ---: | ---: | ---: |
| descend | 67 | 79 | 80.61999988555908 | 90 |
| ascend | 67 | 175 | 176.61999988555908 | 90 |

Thus the observed eye offset is 1.62 and the camera moves vertically **96 blocks**, without using any depth value. Both samples have x=z=0.5 and yaw 0. The ladder captures the property and main-camera coordinates synchronously **at readback request**, then passes those captured values to the callback (`McNativeDepthLadder.java:365-370,393-425`); it does not label a delayed callback from whatever camera happens to exist later. The original sample labels agree with all 24 samples' surrounding harness-stage log entries, independently parsed in `.agent-run/r13-retained-crosschecks.json`. The relevant stage/sample log sequence is `ladder/native-ladder.log:550-559`.

**Visual inspection:** decoded and inspected both original quarter-scale frame thumbnails, both selected bands and both rejected-orientation crops for draws **5042 and 5522**. Inspectable PNG conversions are `.agent-run/r13-{descend,ascend}-{frame,band,rejected}.png`; they preserve the committed PPM data. The near frame shows a close grassy stepped slope and trees, with water toward the top; the high frame shows the wider hills, forest and river. The near selected band has red/green terrain structure; the far selected band is yellow. The opposite crops show ordinary scene pixels. Neither selected strip is a sky-facing horizon look.

**CONFIRMED — the observed nearer look is identified independently of depth.** The close hillside context, downward camera and 96-block displacement support this ordering without using the illustrative `near/d` calculation. Trees in these scenes change surface height by much less than the displacement; the river/water visible in the far context is below the local hillside, not a surface near the high camera. No retained scene indicates a shaft under the entire near strip or a high overhang under the entire far strip that would reverse the ordering. I found no such explanation for the original bracket shift.

**REFUTED — the two bands are guaranteed to hit identical world surfaces, or every near/far distance is bounded by `groundY(0,0)` alone.** The band is off-center (`[-0.6,0.36,0.6,0.2]`, selected rectangle `[384,648,1536,734]`). A higher perspective camera gives it a larger and displaced world footprint. Trees, an adjacent cave opening and water can change which surface a ray hits; a single heightmap column does not survey those surfaces. A low camera over a deep opening in its strip and a high camera whose larger strip meets raised terrain is a counterexample to the general geometry premise. The original thumbnails support a local empirical inference, not a retained per-ray distance reference. The drawn palette also obscures the original scene inside the selected band. These limits do not turn the actual close hillside into a demonstrated deep shaft, nor justify claiming a scale measurement.

**CONFIRMED — the numerical separation rule is sound conditional on the look ordering and actual pipeline state.** `verify.py:1288-1305` chooses the last **eligible** sample of each stage, refuses any low pixel in that selected sample, and compares the whole sets of occupied rung indices. For an accepted rung i the underlying finite depth is `(z_i,z_{i+1}]`, with the top rung unbounded above by this test. Strictly separated indices imply strictly separated depth values, including at shared interval boundaries. Overlap yields refusal, not a direction guess. No means, majority threshold or assumed projection scale enters the comparison. `low == 0` is a conservative value-domain condition; it is not a general detector of sky, and under conventional Z sky need not be low.

Independent exact-RGB recounts (not the gate classifier) confirm:

| Selected sample | Camera y | Occupied rungs | Pixel counts |
| --- | ---: | --- | --- |
| descend 5042 | 80.62 | 3, 4 | 45,734; 53,338 |
| ascend 5522 | 176.62 | 2 | 99,072 |

Both have zero low/anomaly/other pixels. Therefore every original near-strip depth exceeds every original far-strip depth. **CONFIRMED reverse-Z as the bounded reading of this original experiment; REFUTED scale measured.** Earlier eligible looks 4802 and 5282 independently have the same respective histograms.

### R13-Z-BINDING — blocking gate finding

**REFUTED — replay enforces the geometry facts it uses to name a look nearer.** `verify.py:1253-1311` trusts each sample's `stage` and `camera`, reads each checkpoint's `groundY` independently, and calls `descend` near without checking a shared ground, actual height ordering, checkpoint player/camera consistency, or the retained stage chronology. `:1025-1048` reconciles log draw numbers, orientation and counts but not stages/cameras. `:2393-2409` requires equality of checkpoint copies and then calls the same direction gate; equal copies can share the same contradiction. The saved summary's derived direction is not reconciled either.

Reproduction with **only two byte-changed files**, `ladder/native-depth-ladder.json` and `MANIFEST.json`:

1. For all four samples 4802/5042/5282/5522, exchange `descend` and `ascend` labels and exchange their camera y values (80.62 ↔ 176.62). Keep draw numbers, counts, crops, thumbnails, logs and checkpoints unchanged.
2. Refresh manifest hashes. The accepted reproduction retains the original summary and `native-result.json` byte-for-byte.
3. Run `python3 scripts/verify.py --replay-evidence .agent-run/r13-accepted-all_labels_and_camera_y_swapped`.

**Observed exit 0, conventional Z**, near draw **5522** with rung 2, far draw **5042** with rungs 3/4. Output is `.agent-run/r13-report-only-opposite-replay.json`. The unchanged log explicitly places 5042 in descend and 5522 in ascend. The unchanged summary still records the original reverse-Z answer. The unchanged frame contexts still show the original close/wide views. No crop, thumbnail, log, source fingerprint or executed GPU state had to be forged.

A second accepted attack keeps **all sample camera numbers truthful**: exchange the four stage labels and set checkpoint ground to **163 for descend and -29 for ascend**, in both checkpoint copies. The gate calls camera **176.62 near** and **80.62 far**, again conventional Z. It neither requires the same ground nor notices that the checkpoint's retained `playerY`/`cameraY` contradict the new ground-plus-offset teleport. Saved reproduction: `.agent-run/r13-accepted-all_labels_swapped_ground_split`; table case `all_labels_swapped_ground_split` and repeated case `opposite_with_truthful_sample_cameras`.

These are **internally contradictory packages accepted by the gate**, not proof the original evidence is forged and not a new demand for cryptographic GPU authentication. They refute the narrower claim that replay checks the agreement of already retained direction evidence. Ground/teleport consistency and binding stage labels to the retained lifecycle remain required before this gate can certify that premise. Implementation repairs are outside this review.

A broader numerical-consistency forgery also passes: swap all three image files of 5042/5522, swap their histograms and rejected counts, update the log counts, derived summary and manifest. It returns conventional Z while keeping original camera/stage metadata. Case `images_histograms_rejected_counts_log_summary_swapped`, reproduced by `.agent-run/r13-extra-attacks.py`. This is the **already disclosed coordinated-forgery boundary**, not a separate blocking authenticity requirement. A fully physically plausible fabricated package would additionally need coherent scene backgrounds/labels; neither accepted manipulation is a hardware observation of conventional Z.

## 2. Gate, replay and mutation table

Commands: `python3 .agent-run/r13-mutations.py` (24 cases including baseline), `python3 .agent-run/r13-extra-attacks.py` (two additional cases). Results are `.agent-run/r13-mutations.json` and `r13-extra-attacks.json`. Mutations use temporary copies and refreshed manifest hashes; the committed evidence is intact. Every case returning 0 is named below.

| Mutation/case | Replay | Interpretation |
| --- | --- | --- |
| `baseline` | **0**, reverse-Z | Original package. |
| `latest_descend_y_plus3`, `latest_descend_pitch30`, `latest_descend_camera_null` | **0**, reverse-Z | Chooses earlier valid descend 4802. This is the specified last-eligible rule. |
| `all_descend_y_plus3`, `all_descend_pitch30` | **1** each | No eligible descend camera. |
| `all_descend_x_plus2` | **0**, reverse-Z | Accepted boundary: x/z tolerance is **two blocks**, not exact teleport equality. |
| `all_descend_x_plus2point01` | **1** | Outside that boundary. |
| `all_descend_y_plus0point9` | **0**, reverse-Z | Within the one-block vertical tolerance. |
| `all_descend_yaw180` | **0**, reverse-Z | Yaw is finite but not compared; at exactly pitch 90 nadir stays downward, but the off-center strip rotates. |
| `latest_stage_labels_swapped` | **0**, reverse-Z | Falls back to 4802/5282; both relabeled latest samples are camera-ineligible. |
| `all_stage_labels_swapped` | **1** | Stage labels alone cannot pass the y check. |
| `all_labels_and_camera_y_swapped` | **0**, **conventional Z** | Report-only semantic forgery; contradicts unchanged log/summary/images. R13-Z-BINDING. |
| `all_labels_swapped_ground_split` | **0**, **conventional Z** | True camera numbers, unequal grounds, inverted physical height ordering. R13-Z-BINDING. |
| `ground_own_only_plus10` | **1** | Checkpoint copies disagree. |
| `ground_both_plus10`, `ground_missing_both` | **1** each | No matching camera, or missing ground. |
| `ground_both_plus0point5` | **0**, reverse-Z | Inside vertical tolerance; ground is not required to be an integer. |
| `ground_ascend_only_plus0point5` | **0**, reverse-Z | Different ground heights across stages are not rejected. |
| `selected_pixels_swapped_with_counts`, `all_three_images_swapped_counts_no_log` | **1** each | Log counts disagree with report. |
| `all_three_images_swapped_counts_and_log` | **1** | Remaining rejected-crop count does not agree; updating that too is the accepted extra case below. |
| `latest_near_sky_pixel_anchored_recounted_logged` | **1** | One grey low pixel in 5042, with crop, thumbnail block, counts and log consistent: selected near look refused. |
| `earlier_near_sky_pixel_anchored_recounted_logged` | **0**, reverse-Z | One low pixel in 4802 is allowed because valid 5042 is selected. No claim that every earlier look is ground-only. |
| `images_histograms_rejected_counts_log_summary_swapped` | **0**, **conventional Z** | Coordinated image/report/log/summary forgery; disclosed limit. |
| `opposite_with_truthful_sample_cameras` | **0**, **conventional Z** | Repeats split-ground attack with complete replay output retained. |

**CONFIRMED stage/camera labeling as source evidence, not authenticated geometry.** The actual harness sets the stage property on the render thread (`LiveWorldHarness.java:155-159`), and the actual probe reads the camera (`McNativeDepthLadder.java:365-370,393-400`). These are two code paths with distinct duties, not independent observers of GPU state. There is no test of the actual private camera readback path. Original labels agree with the retained log; replay does not enforce that agreement. R13-Z-BINDING is a missing consistency check over existing records, separable from arbitrary consistent forgery, dependencies/runtime identity and the standing restore-after-call limit.

**CONFIRMED retained baseline facts independently:** all **24** selected histograms match exact literal palette RGB values; rejected counts match an independently coded three-level classifier; both orientations anchor to **259,026** complete 4×4 thumbnail blocks; all **245** manifest members hash correctly; all **408** selected-source keys and bytes equal this checkout. Reproduce with `python3 .agent-run/r13-independent.py`; results in `.agent-run/r13-independent.json`. Boundary pixels and within-block permutations remain outside full positional anchoring.

The run's initial/final revision is **672e85f4c69e9e7720b3d34c8abb57095907fa2b**, with dirty sources at launch and `changed_sources_during_run: []`, not reviewed HEAD. The independent selected-source comparison is what binds those source bytes to HEAD. It does not authenticate compiled classes, dependencies, driver, JVM or execution.

## 3. Tests and tests passing broken code

| Required command | Observed result |
| --- | --- |
| `./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true` | **BUILD SUCCESSFUL**; `:test` executed, not FROM-CACHE. XML: **333 tests, 1 skip, 0 failures/errors**. |
| `python3 -m unittest discover -s scripts/tests` | **170 tests, OK**, 376.794 seconds. |
| `python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261009T033539-494735Z` | **Exit 0**, ten replay checks, 24 ladder samples, reverse-Z. |

Logs are `.agent-run/r13-{gradle,python,replay}.log`; XML totals and skip identity are retained in `r13-retained-crosschecks.json`. The skip is `VkBarriersTest.missingBarrierIsDetected()`, the documented descriptor-bound SSBO synchronization blind spot. The deliberate fill-buffer WAW control executes; the Gradle log also retains the existing **VALIDATION-SETTINGS** deprecation warning. Plain Gradle success is not native runtime acceptance.

**CONFIRMED fixtures were not shrunk or edited:** ladder/marker frames remain **960×540**, ladder band **576×43**, depth fixture **48×100**. The Python direction tests still render those full fixtures. No selected source was edited, including for mutation testing.

**REFUTED — green tests exclude all meaningful broken implementations.** `python3 .agent-run/r13-test-escapes.py` compiles modified copies of the direction function only in memory and runs the unchanged nine-test `LadderDirectionTest` class:

| Broken code | Passed / failed |
| --- | --- |
| Remove both x/z location predicates from the eligibility condition | **9 / 0** |
| Select first eligible sample instead of last | **8 / 1** |

In particular, **`test_a_camera_not_where_the_stage_put_it_fails` passes with the entire x/z check removed**: `test_ladder_gate.py:623-632` tests only y and pitch. The first-sample mutant is caught specifically by `test_the_last_look_of_each_stage_is_the_one_judged` (`:649-655`); the other eight tests pass it. Results and exact test names are in `.agent-run/r13-test-escapes.json`.

The direction tests do not cover shared-ground equality, physical camera-height ordering, checkpoint player/camera consistency or stage/log reconciliation. Their conventional-Z fixture intentionally exchanges synthetic near/far depth fields (`:607-610`); that tests the sign decision, not scene geometry. The retained replay tests therefore remain green despite the R13-Z-BINDING attacks.

The seven ladder JUnit tests inspect inertness, constants, classification and pipeline-building state; none invokes the private camera capture/readback callback. The already documented production-creator restore escape remains: the JUnit builder tests inject a different creator, and post-return `pipelineStates` cannot prove state at the Vulkan invocation (`McNativeDepthLadderTest.java:78-159`, `McNativeDepthLadder.java:725-745,760-769`). This review inspected the unchanged callback; it did **not** rerun round 12's bytecode instrumentation or claim a newly mutated GPU run.

## 4. Survey, replay table, terrain and depth

**CONFIRMED — the Z section's original numbers and local scope.** Survey `:1253-1292` correctly distinguishes the probe's false convention flag from the gate's derived direction, reports the observed two histograms, and excludes scale/coexistence. Its approximate `near=0.05` distances are expressly illustrative and were not used here to decide nearer. “Same ground” must mean this local terrain region, not identical world-space rays/surfaces. The wording “x,z at the teleport” at `:1261` omits the actual ±2-block tolerance and yaw is unchecked; those are non-blocking precision limits for the original exact-position looks.

**REFUTED — blanket evidence consistency.** Survey `:1199-1200` says the records agree with each other. The report-only opposite replay disagrees with the unchanged lifecycle log and saved direction; the split-ground replay violates the harness's own reuse of one ground height. The survey needs the narrower scope of what replay actually checks until R13-Z-BINDING is closed. This is not an allegation about the original run's honesty.

**CONFIRMED — round-12 dispositions as historical results.** Survey `:1202-1208` agrees with the retained round-12 report: both round-11 blockers closed, 19 samples, 198,546 blocks and 408 selected-source entries; the standing delivery boundary remained. No old inventory or persistent-creator finding is reopened here. **REFUTED as unconditional wording:** `:1209-1210` still says “what checks it is the measurement,” even though `:1235-1237` correctly says restore-at-call is not claimed to be checked. Round 12's accepted all-palette consistent forgeries show why measurement is not a universal native-call-state check. Keep this as a non-blocking documentation limit, not a new runtime defect found in the current three-line creator.

**CONFIRMED — fourteen-run replay table.** Fresh commands returned **1 for each of thirteen older runs**, **0 only for 20261009T033539-494735Z**; `.agent-run/r13-replay-table.json` records all fourteen. The first seven fail the required ladder-source fingerprint entry; the next six fail source-byte binding before later schema checks are reached. Survey `:433-463` correctly treats old “replay 0” paragraphs as historical and preserves the records. The table's header and new measurement section still use 2026-10-07 although the newest run is dated 2026-10-09; this is date drift, not a missing run.

**REFUTED — handoff describes this HEAD.** `docs/ai/handoff.md:3-4` still names `9b6a1816`; `:30-31` says eleven reviews; its status table calls round-12 repairs unreviewed; it still says Z is unmeasured, Python 161/158 and thirteen retained runs. The current-state document likewise retains an earlier unqualified “Z convention ... unmeasured” sentence before the new measured-direction paragraph. These are non-blocking task/documentation drift and were not used to overrule the source or retained evidence. Nothing was repaired in this review.

**CONFIRMED bounded synthetic terrain measurement; REFUTED native LoD/coexistence acceptance.** Independently decompressed **25** native/reference terrain pairs and found every pair byte-identical. The gate's selected draw **5294**, 1920×1080, has **26,116** non-background pixels and zero mismatch (`native-terrain-probe.json:19`). Input remains synthetic and Minecraft's frame is cleared. Main and ladder launches are distinct (devices **0x75ef6dc018** and **0x783a580018**); ladder flags and draw counts for terrain/marker are all off/zero. Original logs have no diagnostics matched by the runner and retain Khronos layer insertion. Their loader environment is not independent proof of native synchronization-validation activation; the adopted-context status still reports `syncValidation:false`.

**CONFIRMED depth-copy observation; REFUTED scene-depth/convention/scale conclusion from that copy.** The 1708×960 retained PGM has **1,639,680** quantized zero samples, independently checked, and the report records raw min/max/topMean/bottomMean as 0.0 with `zConventionMeasuredHere:false`. Zero quantized samples alone do not establish exact raw floating-point zero; that exactness comes from the producer's report. `clearedValue=0.03125` is the histogram bin midpoint, not proof of Minecraft's clear value. The behavioral ladder sees structured non-zero brackets at its hook, in another launch; why the buffer-copy route differs remains unexplained. No coexistence impossibility follows.

**Standing blocking boundary — R13-DELIVERY:** `project-goal.md:9-17,40-51` requires real native Voxy LoD, correct composition and normal-play lifecycle behavior. The diagnostic layer remains unaccepted, `BLOCKED_UNIMPLEMENTED` remains accurate, and none of this evidence delivers the target. The candidate accepts this boundary. A confirmed Z observation is not an accepted foundation or a production-native PASS.

## 5. Safety for normal play

**CONFIRMED — I would ship this change on the established GL path with every listed native flag unset**, within the existing renderer's supported configuration. The production change is inside the flagged ladder; the harness teleport/property changes live only in `src/harness`. This is a scoped dormant-change safety judgment, not a fresh live GL certification or an endorsement of the old macOS GL-hosted diagnostic renderer as the delivery target.

**CONFIRMED — safe dormant behavior on Minecraft Vulkan; REFUTED shipping it as functioning native Voxy.** `VoxyClient.java:96-112` disables Voxy when Minecraft lacks GL. Thus ordinary Minecraft Vulkan play can keep running with Voxy inactive; there is no native LoD product to ship. Flags absent means no device adoption, feature augmentation, probe output or diagnostic draw. The native entry gates are `McNativeVulkanProbe.java:296`, `McNativeDeviceFeatures.java:78`, `McNativeVkContext.java:93-101`, `McNativeDepthProbe.java:99`, `McNativeTerrainProbe.java:185`, `McNativeMarkerDraw.java:273`, and **`McNativeDepthLadder.java:219-221`**. Ladder shutdown returns before touching Minecraft if no instance exists (`:853-856,870-873`); merely setting the harness stage property does not enable it.

**CONFIRMED — the current ladder cannot render unflagged and does not write Minecraft depth when flagged.** Both attachment clear options are empty (`McNativeDepthLadder.java:292-295`), all three pipelines have depth test enabled and **depth writes disabled** (`:693-697`), and the base/control/rung draws only alter colour (`:315-346`). The reused marker vertex shader supplies the pushed depth; its fragment shader only outputs colour, not fragment depth. Readback copies the colour texture, not depth (`:369`). Pipeline-state refusal and JUnit cover the inspected state construction; the disclosed malicious restore-after-call limitation is not claimed solved. A simultaneously flagged terrain/marker probe can write depth on its own; the isolated ladder run explicitly excludes them.

**CONFIRMED — harness changes are development-only.** `build.gradle:519-536` creates a separate harness source set and includes its mod only in the custom harness run; ordinary jar inputs are the main source set. `LiveWorldHarness.java:63-74` also requires a development environment, the harness flag and a marked fresh game directory. No teleport or harness property setter was added to production source. This source-set conclusion was inspected; no new packaged-jar/runtime matrix was run.

## What could not be checked

- No Minecraft, native or live scenario was launched, as requested. No new GPU observation of these two scenes, eye pose, driver behavior, image ownership, coexistence, long-session lifetime or player release behavior was obtained.
- Full original readback frames and the generated world save are not retained. The thumbnails and both crops were inspected; exact original terrain surfaces under every palette-covered pixel, hidden caves, full-frame screenshot hashes and a per-ray world-distance bound could not be independently reconstructed. A one-column heightmap cannot fill that gap.
- Replay explicitly omits the full environment gate, retained-log diagnostic scan and per-checkpoint full-frame measurements. Original stage chronology and diagnostic text were separately inspected here, but executable/runtime origin is not authenticated by editable hashes.
- No native depth scale, near/far calibration, projection-matrix mapping, another hook/seed/device, or native terrain coexistence was tested. The depth-copy discrepancy remains unexplained.
- Round-12 creator instrumentation was not repeated; source still forwards the real create-info unchanged, and the previously disclosed arbitrary-native-call/restore escape remains outside post-return inspection.

Review artifacts and reproduction commands above preserve the distinction between original observations, accepted malformed evidence, coordinated forgery, and unperformed hardware experiments.
