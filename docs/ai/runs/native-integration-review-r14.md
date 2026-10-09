VERDICT: REDESIGN

Independent round-14 review of **059df4ca59ad2e9ca8d4d50646d21fc0a06335cc**, in `/Users/xyz/orca/workspaces/voxy/native-review-r14`, 2026-10-09. Initial HEAD matched the request and the working tree was clean. Implementation, fixtures, committed evidence and existing specifications were not changed. Only this report and review material under `.agent-run/` were written. No Minecraft/native/live launch was performed.

**R13-Z-BINDING is closed.** The original two looks still support reverse-Z as a bounded empirical observation, and the listed new reconciliations work. I found no new blocking direction/evidence defect. REDESIGN remains necessary under the accepted rounds 4–13 delivery boundary: this is neither an accepted diagnostic foundation nor functioning native Voxy LoD. Documentation drift and two additional test-coverage gaps remain non-blocking.

| Candidate claim | Judgment |
| --- | --- |
| (a) Report relabelling, split ground, contradictory checkpoints and reordered looks fail replay | **CONFIRMED**, including the two formerly accepted round-13 packages adapted to the new run. |
| (b) The direction reading uses retained, mutually reconciled inputs | **CONFIRMED for the listed checks**, with stated tolerances and editable-record limits; no authentication of GPU origin or all summary fields. |
| (c) The survey claims no more than its evidence | **CONFIRMED for the bounded Z section and narrowed consistency claim; REFUTED as a blanket judgment of current documentation.** Contradictory present-tense direction prose and stale handoff remain. |

## 1. R13-Z-BINDING and adversarial replay

**CONFIRMED — repaired.** `scripts/verify.py:1043–1081` reconciles each report stage with the sample log's stage and the harness stage current at that logged sample. It compares all five camera components with the log (tolerance 0.001), in addition to orientation and counts. `:1302–1355` requires finite equal grounds; checkpoint cameraY/playerY/playerPitch consistent with pinned offsets and eye height (one block/degree tolerances); eligible sample x/z within two blocks of `(0.5,0.5)`, y within one block of both expected and checkpoint cameraY, and pitch within one degree of 90; the last eligible descend sample must be below and precede the last eligible ascend sample. `:2452–2458` compares the two checkpoint copies; `:2475–2479` compares saved and recomputed direction.

Reproduction: `python3 .agent-run/r14-attacks.py`. It copies the full new package, mutates records and refreshes the manifest; it leaves committed evidence and fixtures intact. Replay uses the same `replay_evidence` entry point as the requested command. Per-case outputs and exact changed-file lists are retained in `.agent-run/r14-attacks.json` and `.agent-run/r14-attack-*.json`.

| Attack | Exit | Result |
| --- | ---: | --- |
| `labels_camera_swapped` | 1 | Former report-only reversal: ladder report plus manifest; rejected at draw 4082 because report and sample-log stages disagree. |
| `labels_split_ground` | 1 | Former labels/split-ground reversal: report, both checkpoint copies and manifest; rejected by log binding. |
| `images_histograms_log_unsaved` | 1 | Swap all three images for 4082/4322, report counts/rejected counts and logged counts: recomputes conventional Z, but saved reverse-Z direction contradicts it. |
| `images_histograms_log_saved` | **0** | Same swap plus changed saved summary direction: conventional Z. This is the disclosed coordinated forgery, not a new metadata-only escape. |
| `checkpoint_split_ground` | 1 | Changing both copies of ascend ground to -29 reaches and fails the shared-ground check. |
| `checkpoint_camera_contradiction` | 1 | CameraY 136.62 contradicts expected 176.62. |
| `checkpoint_player_contradiction` | 1 | PlayerY 135 contradicts expected 175. |
| `checkpoint_pitch_contradiction` | 1 | Player pitch 30 contradicts 90. |
| `log_stage_contradiction` | 1 | Harness stage overworld at descend sample contradicts report. |
| `log_camera_contradiction` | 1 | Logged descend camera changed to the higher camera contradicts report. |
| `reordered_reconciled_pair` | 1 | Swapped report labels/cameras and reconciled log metadata/chronology reach the independent order check: descend 4322 does not precede ascend 4082. |

**CONFIRMED — no report-only, checkpoint-only or log-only passing reversal found.** With this package's images unchanged, their recounted histograms remain fixed. The geometry and chronology checks prevent renaming the higher/later look as the lower/earlier one. Independently, a changed answer cannot pass while `summary.json` still saves reverse-Z. These conclusions concern the implemented direction checks, not an exhaustive malformed-input or GPU-authentication proof.

**What a passing reversal now required in the reproduced image-swap route:** rewrite `ladder/native-depth-ladder.json`; six image files (selected crop, rejected crop and frame thumbnail for each of 4082/4322); `ladder/native-ladder.log`; `summary.json`'s saved direction; and `MANIFEST.json`. That is ten files. The own checkpoints and source fingerprint remained unchanged. Refreshed hashes alone do not authenticate the rewritten images. If choosing to fabricate geometry instead, the report, log sample metadata, harness chronology and both checkpoint copies must satisfy the ground/teleport and order constraints together, as must the saved direction. The ten-file result is a concrete accepted route, not a proven minimum for every possible fabrication. Changed screenshot band crops and full physical scene plausibility are not requirements of these listed replay checks.

## 2. Direction measurement and geometry scope

**CONFIRMED — reverse-Z for these two retained straight-down looks.** Source establishes the independent geometry: `LiveWorldHarness.java:165–170` creates the fixed-seed world; `:203–206` reads one MOTION_BLOCKING height at `(0,0)` and uses it for both 12/108-block teleports; `:127–129` holds pitch 90 and yaw 0; `:294–297` records player/camera/ground. `McNativeDepthLadder.java:365–370,403–425,448–449` captures camera/stage at readback request and retains/logs those values, rather than querying the camera afresh in the callback.

The new package's selected samples are **4082 and 4322**, not the older run's 5042/5522 shown in the survey table. Both runs are explicitly named at `vulkan-native-integration-survey.md:1273–1279`; the numerical result is identical:

| Look | Ground | Player y | Camera y | Exact-RGB occupied brackets/counts |
| --- | ---: | ---: | ---: | --- |
| descend 4082 | 67 | 79 | 80.61999988555908 | 3: 45,734; 4: 53,338 |
| ascend 4322 | 67 | 175 | 176.61999988555908 | 2: 99,072 |

Independent decoding/recount and complete-block anchoring are in `.agent-run/r14-independent.py` / `.json`: **20 samples, 210,642 matching 4×4 blocks**, zero selected anomaly/other pixels. Selected near/far samples have zero low pixels. Original frame thumbnails and bands were visually inspected through lossless PNG decodes (`r14-descend-*`, `r14-ascend-*`): close stepped grass/trees below the low camera; much wider hills/river/forest below the high camera; green/red near band and yellow far band. The 96-block camera displacement and close/wide scene context identify the nearer look independently of the measured depth values. Strictly separated occupied indices imply every near-band depth is greater than every far-band depth, conditional on the inspected draw state. No majority, mean depth or projection scale is used.

**CONFIRMED — the revised geometry scope is accurate** (`survey:1289–1301`). The off-centre band `[384,648,1536,734]` at 1920×1080 has a larger and displaced world footprint from the higher perspective camera. One heightmap column is not a per-ray distance reference, and the two looks need not hit identical surfaces. Scene context supports the empirical ordering here; exact per-ray distances are not retained or measured. The illustrative near=0.05 conversion is explicitly outside the measurement (`:1285–1287`).

**CONFIRMED — no stronger scale, arbitrary-world, other-hook or coexistence result in this section.** Read its “every pixel … nearer” language as the bounded empirical ordering accepted in round 13, not an independently surveyed distance bound for every ray. The “nothing is sky” parenthesis (`:1266`) is the scene premise; absence of low pixels alone is not a general sky detector. The new reconciliations bind editable records and cannot turn that scene premise into authenticated per-ray geometry.

## 3. Tests and mutation coverage

Commands rerun exactly as requested:

```sh
./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true
python3 -m unittest discover -s scripts/tests
python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261009T041151-205062Z
```

**CONFIRMED — JUnit 333 tests, one documented skip, zero failures/errors.** Test task executed. The skip is `VkBarriersTest.missingBarrierIsDetected()` (descriptor-bound SSBO synchronization blind spot); the fill-buffer WAW negative control executed and detected its hazard. Per-test validation messages occur only in that deliberate control. Artifacts: `r14-gradle.log`, `r14-junit.json`, `r14-junit-diagnostics.json`. **CONFIRMED — original replay exit 0**, ten listed checks, 217 manifest members and 408 source entries equal to the selected checkout inventory and bytes. The retained run names pre-commit revision `bebcbd649b5b4f1459a5ca4a78951695aca4c523` with dirty repair files and `changed_sources_during_run: []`; all 408 selected source bytes match this reviewed HEAD. That is source-byte binding, not a claim the recorded revision already was `059df4ca`. Replay explicitly excludes the environment gate, validation-loader/diagnostic scan, full screenshot measurement and Minecraft execution; a replay pass is not a fresh runtime pass.

**CONFIRMED — Python 175 cases, zero failures/errors/skips**, in 390.005 seconds (`r14-python.log`).

In-memory function mutations use `python3 .agent-run/r14-mutants.py <name>` (unchanged 13-test `LadderDirectionTest`); disk source and every fixture remain unchanged, including 960×540 ladder fixtures and full-size retained evidence.

| Mutation | Direction tests | Detector |
| --- | --- | --- |
| Remove x/z eligibility | 12 pass, 1 fail | Camera-location test now covers x and z; **R13-TEST-XZ closed** (`test_ladder_gate.py:633–640`). |
| First eligible instead of last | 12 pass, 1 fail | Last-look test (`:711–718`). |
| Remove shared-ground check | 12 pass, 1 fail | Split-ground test (`:667–681`), specifically its required error fragment. With this mutant, checkpoint geometry still refuses that fixture; rejection alone does not isolate shared-ground coverage. |
| Remove checkpoint geometry loop | 12 pass, 1 fail | Same combined test (`:684–688`); sample/checkpoint y matching still refuses its bad-camera fixture, and the expected error wording differs. |
| Remove harness-stage reconciliation | 12 pass, 1 fail | Contradicted harness-stage test (`:656–665`). |
| Remove sample-log-stage/report-stage reconciliation only | 13 pass | Additional uncovered guard at `verify.py:1063`. |
| Remove sample/checkpoint cameraY matching only | 13 pass | Additional uncovered guard at `verify.py:1333`. |

**CONFIRMED — both additional mutants also pass the entire unchanged 175-case Python suite**, zero failures/errors/skips: `python3 .agent-run/r14-mutants.py log_sample_stage --full` and `python3 .agent-run/r14-mutants.py sample_checkpoint_camera --full`. Their `r14-mutant-*-full.{json,log}` results name the exact run and every test. JUnit source is unaffected by these Python-only in-memory changes. **REFUTED — these two guards are protected by the full test suite.**

**REFUTED — the tests independently cover every new reconciliation.** The last two mutations are meaningful: `r14-test-escapes.py` retains contradictory packages that baseline replay rejects and each mutant accepts, still reading reverse-Z. One changes only the sample log line's stage (harness and report remain truthful). The other places sample camera 0.75 above expected and checkpoint camera 0.75 below expected, retaining/logging both and keeping each individually within the one-block expected-height tolerance; their 1.5-block disagreement is caught only by the removed cross-check. These are non-blocking **R14-TEST-BINDINGS** coverage findings; the candidate's actual guards work. They are not a passing reversal against unmodified HEAD.

## 4. Survey and documentation

**CONFIRMED — fifteen retained runs, only newest replays.** Every directory was actually replayed (`r14-replay-table.json`): fourteen exit 1, newest exit 0. Survey `:441–464` explicitly describes historical rows and current source binding. Today the older runs generally fail first on missing/mismatching fingerprint sources; a row's older-schema explanation need not be the first current failure. There is no accepted layer hidden behind these older passes.

**CONFIRMED — narrowed consistency statement and round-13 paragraph** (`:1195–1203,1303–1317`), subject to stated tolerances. The old report-only reversal is refused; the coordinated-record rewrite remains possible and disclosed. The round-12 paragraph (`:1205–1217`) correctly scopes its historical no-open-evidence-finding result and now excludes native-call authentication. The later round-11 paragraph (`:1237–1240`) also explicitly acknowledges restore-after-call. Post-return states still cannot prove what the Vulkan call consumed; its inspected current creator forwards the structs unchanged (`McNativeDepthLadder.java:764–774`). This round did not repeat old bytecode instrumentation.

**REFUTED — R13-DOC-DRIFT fully closed.** Non-blocking **R14-DOC-DRIFT** remains:

- Survey `:1243–1247` still says direction is “unmeasured, and the gate forbids claiming it” immediately before the measured-Z section. That is true of a single-band probe without checkpoints, but the present wording attributes it to the ladder/gate generally.
- `handoff.md:3–4,29–33,52–60,90–104,125–128,132–143,178` still describes `e1bf895d`, twelve reviews, an unreviewed direction, an explicitly unmeasured Z convention, Python 170, the previous newest run, round-13 dispatch and fourteen retained runs. It does not state this candidate's round-13 repairs.
- `current-state.md:59–62` dates the Z measurement 2026-10-07, whereas the retained direction runs and survey are dated 2026-10-09.
- Source comments at `McNativeDepthLadder.java:733–734,763` still claim post-return state equals what Vulkan received / measurement checks beyond the creator, stronger than the corrected survey's exclusion. Implementation does not establish those assertions.

These do not refute the repaired numerical reading. Claim (c) is valid for the explicitly bounded new section and listed checks, but not for all current prose. No documentation was repaired during this review.

## 5. Normal-play safety and standing judgments

**CONFIRMED — dormant safety with all seven native flags unset.** Ladder `renderIfEnabled()` returns before device lookup/allocation (`McNativeDepthLadder.java:219–221`); shutdown returns on a null instance (`:858–864,877–883`). Marker, terrain and depth entry points return on their respective flags (`McNativeMarkerDraw.java:273`, `McNativeTerrainProbe.java:185`, `McNativeDepthProbe.java:99`); probe is gated at `McNativeVulkanProbe.java:296`; adoption at `McNativeVkContext.java:96–99`; disabled device-feature augmentation returns the original set at `McNativeDeviceFeatures.java:78`. Unadopted release returns before device wait (`McNativeVkContext.java:287`). The always-present render-tail calls (`MixinLevelRenderer.java:56–62`) do not bypass those guards.

**CONFIRMED — the ladder never writes Minecraft depth even when enabled, in this inspected source.** Colour and depth are LOADed, with no clear (`McNativeDepthLadder.java:292–295`); all three pipeline states use depth test on, writes off (`:698–703`), through actual production build/create paths (`:653–658,717–774`). Readback copies colour, not a write to depth (`:365–370`). JUnit checks dormant entry points and all three states/create-infos (`McNativeDepthLadderTest.java:21–42,51–109,119–159`). This is source/test evidence, not authentication of native-call arguments against altered compiled code. A simultaneously flagged marker/terrain probe can write/clear depth; ladder isolation rejects those runs and the ladder itself still does not write it.

**CONFIRMED — harness movement/teleports/checkpoints live in the development-only harness source set.** `build.gradle:519–538` defines `sourceSets.harness`, adds its mod only to `runHarnessClient`, and uses its separate run directory. No harness output is added to the production jar. Source-set/build inspection was performed; no new packaged-jar player launch was performed.

**Would ship:** yes for this dormant change on a supported GL-renderer configuration, subject to the existing renderer's standing limits. On Minecraft Vulkan, it is safe to remain installed and inert with those flags unset; **no, I would not ship it as functioning native Voxy**. `VoxyClient.java:93–111` disables Voxy when Minecraft has no GL backend/context. No new player release/runtime matrix was tested.

**CONFIRMED — terrain experiment is bounded synthetic equality only:** all **21** independently decoded native/reference pairs match byte-for-byte; selected draw 4085 reports 26,116 non-background pixels and zero mismatches. It clears Minecraft's frame, uses synthetic input, and does not establish coexistence, real-world LoD or an accepted foundation.

**CONFIRMED — retained quantized depth copy is all zero:** 1,639,680 16-bit PGM samples at 1708×960. Raw exact float zero is producer-reported; the original float buffer is not independently retained. **REFUTED — copy establishes direction, scale or cause of the discrepancy.** Behavioral ladder direction is a separate observation. **CONFIRMED — ladder counts, isolated launch identity, bracket comparison and listed repaired bindings; REFUTED — ladder/native integration accepted.**

## What could not be checked, and why

- No Minecraft/native/live launch, by instruction. Therefore no fresh runtime coexistence, validation-loader/environment acceptance, long-session lifetime, resource reload, player GL regression or Vulkan release testing.
- No full original readbacks, unpainted selected-band reference, retained world save or per-ray world-distance survey. Thumbnail anchoring checks complete 4×4 blocks; boundary pixels and within-block permutations are not fully position-proved. Geometry ordering remains the bounded scene observation.
- No depth scale, other hooks/seeds/devices, functioning native LoD, travel/update correctness or sustained GPU-resource pressure acceptance. Those delivery requirements are unimplemented/unmeasured.
- No authentication of editable records, commit identity, dependency jars, Minecraft/Sodium runtime binaries, JVM, driver or GPU execution. Source inventory binding covers selected checkout source bytes only. No repeat of round-12 production-creator bytecode instrumentation; the disclosed restore-after-call limit remains.

Machine verdict: `.agent-run/native-integration-review.json`. Supporting commands, outputs, mutation scripts/packages, decoded images and independent recounts are retained under `.agent-run/r14-*`.
