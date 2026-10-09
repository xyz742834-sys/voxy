VERDICT: REDESIGN

Independent review, round 18, 2026-10-09. Candidate `a6e1c3a39fab2a4d00848a133387e7c009c3b06e`; initial tracked tree clean. Implementation, tests, evidence and living documentation were not repaired. Only review artifacts were written. No Minecraft/native/live launch was performed.

**Blocking — DELIVERY-BOUNDARY, CONFIRMED.** The standing rounds 4–17 boundary still governs: diagnostic measurements are not an accepted foundation or delivered Minecraft-native Voxy LoD. The candidate accepts this. `docs/ai/project-goal.md:9`, `docs/ai/handoff.md:136`, `src/main/java/me/cortex/voxy/client/VoxyClient.java:96`. No new failure of the original retained per-pixel measurement was found. The additional findings below are non-blocking limitations, test coverage and documentation defects.

## 1. R16-COEXIST-LAUNCH-SEMANTICS — CONFIRMED repaired

Actual `./gradlew help --offline --console=plain -I <review init>` was run for **17 forms × 10 harness properties**, including the new terrain-LOAD switch. The init prints only `project.hasProperty` for the named switches. All modeled values match actual Gradle. Attached `-P` true/false/empty/bare; separated `-P` false/bare; separated and attached `--project-prop`; attached/bare/separated `-Dorg.gradle.project.*`; separated/attached `--system-prop` enable the property. The neighboring property, unrelated system property and absence do not. `build.gradle:539`, `:564`, `:602`, `:611`; `scripts/verify.py:1224`.

The stronger authority is separately confirmed: `scripts/verify.py:1278` / `:1284` demand all three literal stage tokens and reject terrain/marker/depth properties under every measured enabling CLI spelling. Replay calls it at `scripts/verify.py:3087` before accepting ladder evidence.

Against the **new committed run**, with copied evidence and refreshed manifest hashes/membership:

- The original package replays **0** (11 named checks; 413 source entries exactly match the checkout; 300 manifest members).
- Off/empty coexist report, removed coexist files and silent coexist log, with the original command: **1**.
- The same downgrade with every one of the 17 matrix forms substituted for the coexist literal: **1** in every case. Missing and empty commands: **1**. No silent-log downgrade replays 0.
- Valid evidence with any required literal replaced by false/bare/separated/long/system-property forms (27 cases across all three required switches): **1**. Semantically enabling alternatives are refused because they are not the stage's literal launch.
- A command that retains the literals and additionally enables terrain, marker or depth in any of the 14 enabling forms (42 cases): **1** in every case.

Artifacts/reproduction: `python3 .agent-run/r18-launch-matrix.py`; `python3 .agent-run/r18-attacks.py`; `.agent-run/r18-launch-matrix.json`, `r18-attacks.json` and each `r18-attack-*.json`. These attacks refresh manifests, so refusals are not merely stale-hash failures. The unretained `ORG_GRADLE_PROJECT_*` environment is a disclosed limit. `gradle.properties` is in the checked source inventory; this is command/evidence consistency, not runtime provenance authentication.

## 2. Terrain-LOAD measurement — CONFIRMED, bounded

I decoded gzip P6 crops and VXF32 little-endian float32 reference depth independently, without importing `verify.py` in the recount. For **every one of 20 samples**, I checked the selected ladder crop, coexist crop, terrain-LOAD crop, reference colour and reference depth; exact ladder counts, eight coexist counts and all twelve terrain counts agree with the reports and exactly one matching log line each. An additional independent anchor calculation checked selected/rejected ladder crops and both later crops against their quarter-scale thumbnails: **106,524 complete 4×4 blocks for each of the four crop kinds**. Rejected-orientation classifications agree too. Artifacts: `r18-recount.py/.json`, `r18-anchors.py/.json`.

Rule applied: a rung represents MC depth **lo < d_MC ≤ hi**. For geometry (`d_V > 0`), **d_V ≥ hi** requires exact reference RGB; **d_V ≤ lo** requires byte-identical previous/coexist RGB; inside the bracket either colour is allowed and counted undetermined. LOW (`d_MC < 2^-16`) and BASE (`d_MC = 2^-16`) use their separate endpoint rules. Without reference geometry, the output must be unchanged. A third colour, missing required-visible colour, visible required-hidden colour, or changed no-geometry pixel is a violation. Equality at a rung top passes `GREATER_OR_EQUAL`; equality at a rung bottom fails because MC's lower bound is strict.

| Count | Independent total |
| --- | ---: |
| geometry | 495376 |
| noGeometry | 1243376 |
| expectVisible | 439452 |
| expectHidden | 22973 |
| undetermined | 32951 |
| visible | 454561 |
| hidden | 40815 |
| ambiguous | 0 |
| other | 0 |
| visibleWhereHidden | 0 |
| hiddenWhereVisible | 0 |
| changedWhereNoGeometry | 0 |

All samples below agree with both the report and the log; the full twelve counts per sample are in `.agent-run/r18-recount.json`.

| Draw | Stage | Geometry | Must show | Must hide | Undetermined | Shown | Hidden | Violations |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2 | warmup | 21972 | 21929 | 0 | 43 | 21972 | 0 | 0 |
| 242 | warmup | 21972 | 20919 | 21 | 1032 | 21796 | 176 | 0 |
| 482 | turn | 21972 | 21302 | 44 | 626 | 21811 | 161 | 0 |
| 722 | turn | 21972 | 21624 | 0 | 348 | 21940 | 32 | 0 |
| 962 | turn | 21972 | 20754 | 58 | 1160 | 21682 | 290 | 0 |
| 1202 | travel | 21972 | 21972 | 0 | 0 | 21972 | 0 | 0 |
| 1442 | travel | 21972 | 21972 | 0 | 0 | 21972 | 0 | 0 |
| 1682 | return | 21972 | 20754 | 58 | 1160 | 21682 | 290 | 0 |
| 1922 | return | 21972 | 20754 | 58 | 1160 | 21682 | 290 | 0 |
| 2162 | edit | 21972 | 20754 | 58 | 1160 | 21682 | 290 | 0 |
| 2402 | remove | 21972 | 20754 | 58 | 1160 | 21682 | 290 | 0 |
| 2642 | remove | 21972 | 20754 | 58 | 1160 | 21682 | 290 | 0 |
| 2882 | resize | 28964 | 26951 | 64 | 1949 | 28510 | 454 | 0 |
| 3122 | resize | 28964 | 26951 | 64 | 1949 | 28510 | 454 | 0 |
| 3362 | reload | 28964 | 26951 | 64 | 1949 | 28510 | 454 | 0 |
| 3602 | nether | 28964 | 21492 | 7458 | 14 | 21492 | 7472 | 0 |
| 3842 | overworld | 28964 | 26951 | 64 | 1949 | 28510 | 454 | 0 |
| 4082 | descend | 28964 | 0 | 14782 | 14182 | 0 | 28964 | 0 |
| 4322 | ascend | 28964 | 26950 | 64 | 1950 | 28510 | 454 | 0 |
| 4562 | reconnect | 28964 | 28964 | 0 | 0 | 28964 | 0 | 0 |

**Zero violations; 14 samples have both determinate kinds.** No violated pixel was found in the original files. The two reference depth ranges are approximately `[0.00014531454, 0.0030659903]` and `[0.00014531380, 0.0030660268]`: both span below 2^-12 and above 2^-10. The fit leaves clip z and w unchanged (`McNativeTerrainLoad.java:350`); the depth sweep places 192 cells above, 96 below and 192 inside that terrain bracket. Fresh JUnit verifies the fit at three extents; the new GPU test actually ran and verifies every geometry pixel lies in the band and both depth sides exist.

**Probe source — CONFIRMED successful capture sequence.** Native probe Java names below are under `src/main/java/me/cortex/voxy/client/core/vk/mcnative/`; renderer names under `src/main/java/me/cortex/voxy/client/core/vk/`; the hook is `src/main/java/me/cortex/voxy/client/mixin/minecraft/MixinLevelRenderer.java`. `MixinLevelRenderer.java:61` / `:65` calls the ladder first, then terrain-LOAD. `McNativeDepthLadder.java:393` requests the first copy, records the no-write coexist pass and requests its copy outside that pass (`:413`); terrain-LOAD consumes the one-shot sample token at `McNativeTerrainLoad.java:170`. Its pass (`:279`) attaches colour and depth with empty clear optionals; inspection of the actual Minecraft 26.2 `VulkanCommandEncoder` bytecode confirms empty optionals select `loadOp(0)` (LOAD) for both attachments. `:288` calls the same scene renderer's `recordDrawsInRenderPass`; `:293` requests the third copy after the pass closes. `VkTerrainRenderer.java:228` declares test/write on and `:230` selects Voxy's `VkDepth.COMPARE_OP` (6). `McNativeTerrainScene.java:108` constructs the renderer at the same extent, `:112` writes the fixed matrix once, and `:117` renders/readbacks the reference with that same renderer and resources, waiting for its fence before reuse. Reference is cached per built scene extent, not freshly rendered for every sample. Pipeline state is **declared**, not read back/authenticated. The ladder and coexist pipelines still have writes off (`McNativeDepthLadder.java:984`; all four retained states have third entry 0).

**REFUTED an unconditional two-readback prerequisite on error paths; CONFIRMED the successful-path order.** The source does not atomically guarantee both earlier requests succeed: the token is published after the first request, before coexist's request. A failed coexist request/recording would be refused by the required coexist evidence/notes, but the LOAD probe can consume that token on such an error path. No such failure appears in this run. The fixed launch keeps both experiment flags enabled; dynamic in-process flag changes were not tested.

**Coexistence — CONFIRMED again in this run.** All 20 exact-RGB pairs, **1,738,752 pixels**: 1,669,110 must-show/present, 69,642 must-hide/unchanged, zero other, zero absent-where-pass, zero present-where-fail. Mixed draws are 3602 and 4082. The quad's Voxy compare and no-write state are `[6,1,0]`. The launch repair makes this evidence required.

**Z direction — CONFIRMED bounded reverse-Z.** Last descend draw 4082: camera y 80.62, ground y 67, brackets 3–4 (45,734 / 53,338 pixels). Last ascend draw 4322: camera y 176.62, same ground, bracket 2 (99,072 pixels). Every nearer-look bracket lies strictly above every farther-look bracket. Source-bound replay reconciles the request-time stage/camera, later sample line and checkpoints. This does not establish per-ray distance bounds, real-world depth scale or general geometry correctness.

**Gate attacks — CONFIRMED principal refusal checks; limited metadata binding.** With manifests refreshed, third-crop pixel corruption, a reference colour corruption on geometry, each of the twelve count changes, missing/duplicate/wrong-count log lines, reference depth changed to zero/far/outside [0,1], changed compare/test/write declaration, claimed state readback, scene-label change and wrong probe/ladder/both pass counts all replay **1**. The probe/ladder device, formats 37/126, sample inventories, counts and per-draw logs are reconciled (`scripts/verify.py:1542`–`:1764`).

The following mutations replay **0**, explicitly retained and named in `r18-attacks.json`:

- `depth_ulp`: one interior positive reference depth increases by one float32 ULP, without changing extrema or any bracket decision/count. This illustrates the disclosed bracket-grain limit.
- `pixel_referenceFile`: a reference colour byte outside reference geometry changes. Reference background colour does not participate in the rule there; the actual output is still required unchanged. Changing a reference byte on geometry (`pixel_reference_geometry`) is refused.
- `field_mvp`, `field_eye`, `field_width`, `field_height`, `field_drawCount`, `field_referenceSet`, `field_near`: respectively a zero matrix, fictitious eye, final width/height 1, draw count/reference set 1, near 999. The gate pins the scene **label**, checks a finite 16-element matrix and positive counters, but does not reconcile these published facts with the source/reference. **Non-blocking R18-TERRAIN-METADATA**: a passing recount must not be promoted to complete report-metadata binding. The original files and source-level same-matrix/render contract still support the bounded measurement.

Direct CLI reproductions: `python3 scripts/verify.py --replay-evidence .agent-run/r18-passing-field_mvp` and `... .agent-run/r18-passing-depth_ulp` both exit 0, recorded in `r18-cli-*-replay.json`. Every passing mutation package is retained under `.agent-run/r18-passing-*`.

## 3. Tests — CONFIRMED fresh passes; REFUTED full terrain-LOAD guard sensitivity

Required command run: `./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true`. **341 tests: 340 passed, one documented skip, zero failures/errors.** Strict `junit_result(build/test-results/test)` succeeds: validation and sync validation active, fill-buffer negative control executed, no unexpected diagnostic or skip. The sole known gap is `VkBarriersTest.missingBarrierIsDetected`. All eight new terrain-LOAD tests passed, including the real GPU reference render. The command log also contains an unprefixed VALIDATION-SETTINGS warning about deprecated layer configuration; the scoped XML diagnostic parser does not count that loader/configuration warning. I did not treat a passing parser as proof that the complete command log contains no warning.

Required `python3 -m unittest discover -s scripts/tests`: **220**, not 219, tests passed in 712.399 s, no skips/errors/failures. Required original evidence replay: exit **0**, eleven listed checks. Logs: `.agent-run/r18-gradle.log`, `r18-python.log`, `r18-original-replay.json`, `r18-junit.json`.

I enumerated every `if` with a direct refusal `raise` in the three named functions and compiled one guard at a time to `False` in an isolated Python process, leaving repository source and every fixture unchanged. All **23 coexist guards** are killed by the full unchanged 20-case `LadderCoexistTest` class. All **3 ladder-launch guards** are killed by the unchanged `LadderRetentionTest` + `LadderCoexistTest` suites. Of **48 terrain-LOAD guards**, 44 are killed; these four survive the complete unchanged 20-case `TerrainLoadGateTest` class:

| Guard | Condition removed | Focused result |
| --- | --- | --- |
| scripts/verify.py:1576 | `retained` | 20 pass |
| scripts/verify.py:1703 | `(bw, bh) != (aw, ah)` | 20 pass |
| scripts/verify.py:1732 | `got is not None` | 20 pass |
| scripts/verify.py:1738 | `counts['geometry'] == 0` | 20 pass |

**Non-blocking R18-TEST-TERRAINLOAD.** A further full discovery run with **all four surviving guards disabled together** passes all **220 tests** (artifact `r18-full-survivor-suite.json`, 774.280 s). This records a combined full-suite run, not 74 separate full-suite runs. Individual survivors were separately confirmed with the complete relevant class. Some checks are defensive/redundant under the required stage: coexist or the ladder already validates the previous crop dimensions, and an enabled stage cannot take the off-files branch. The geometry-zero and no-geometry-extrema refusals are masked by earlier count/refusal failures in existing negative fixtures. They still lack a test that fails upon their removal. No claim of complete new guard sensitivity is justified. Mutation audit/reproduction: `r18-guard-mutations.py/.json`; `r18-full-survivor-suite.py/.json`. Fixtures were not reduced, and no test/source file was edited.

## 4. Survey and handoff — CONFIRMED bounded measurement; REFUTED wholly current prose

**CONFIRMED:** the new section (`docs/ai/vulkan-native-integration-survey.md:1420`–`:1489`) reports the counts I independently obtained and expressly limits the result to synthetic geometry, bracket grain and declared state, with no GPU-authentication claim. The reference's once-at-build-time reuse is disclosed (`:1447`); the preceding phrase “two independent measurements of the same frame” is imprecise and should not be read as a freshly measured reference each frame. Nothing here establishes an accepted foundation, a real-world section draw or native LoD delivery. The round-17 paragraph (`:1402`) accurately records the old failure and the new CLI/literal-token repair.

**CONFIRMED:** nineteen retained run directories; exactly the newest replays 0. I ran replay against all nineteen (`r18-run-inventory.json`). The table (`:448`–`:468`) matches those statuses. Earlier runs currently fail source binding before some of their named historical schema defects; the table explains why they are unsuitable, not necessarily the first refusal reached today. The newest retained revision is `b52233434eaf8a5b4b27ed4403e8fcb6132defb1`, but its **413 source hashes exactly match this HEAD**; documentation-only commits do not invalidate that match. `changed_sources_during_run` is empty. This binds source inventory/bytes, not the compiled runtime.

**CONFIRMED R14-DOC-DRIFT closure in its round-17 scope:** the manual command now carries coexist and terrain-LOAD (`handoff.md:231`); the old stale retained-run count is removed and the inventory/table correctly contains 19. Both entries in `round17_findings_closed` are true. Additional current documentation defects are recorded separately:

- **R18-DOC-SEAM, REFUTED** the assertion in handoff next-step item 3 (`:199`–`:204`) that the native path already has WorldEngine/meshing/NodeManager producing sections. `VoxyClient.java:107`–`:118` gives MC Vulkan `BACKEND=null`, and `:134`–`:150` installs the instance factory only when supported; `VoxyCommon.java:74` returns without a factory, and `VulkanWorldUpdates.java:35` clears work outside the selected Voxy backend. The new scene builds synthetic geometry/metadata/positions and static draw commands (`McNativeTerrainScene.java:99`). Reusing these components and connecting real section uploads/camera is a sensible experimental next direction, but native producer startup and its resource/queue lifetime connection are also absent. This is not an already functioning native producer with only a missing upload.
- **R18-DOC-DRIFT:** the handoff command comment (`:217`) still says Python 199 while its prose (`:171`) correctly says 220; the survey's final unanswered item (`:1514`) still says the real terrain LOAD pass is next, after measuring it above. The handoff (`:207`) retains present-tense all-zero-copy shorthand that does not describe this newest run.

The old CLEAR-pass terrain experiment also remains separately scoped: I independently compared **all 20 retained full-resolution pairs**, exact RGB equal (12 at 1708×960 / 20,663 non-background pixels; eight at 1920×1080 / 26,116). It clears MC's attachments and does not establish coexistence or real-world LoD. `.agent-run/r18-other-recount.json`.

**Newest depth-copy result — CONFIRMED varying near-zero quantized values, REFUTED all-zero characterization of this run.** Its retained 16-bit PGM has **1,583,839 code-0 pixels and 55,841 code-1 pixels** (1,639,680 total), not a uniform zero image. The probe reports min 0 and max 1.4127476e-5; exact raw float32 MC depths are not retained. All pixels still fall in histogram bin 0, which must not be mistaken for all pixels equaling 0. The dated old all-zero measurement may remain historical, but neither it nor this tiny variation proves cause, scene-depth accessibility, direction or scale. The ladder provides the separate bounded direction evidence.

## 5. Normal-play safety — CONFIRMED dormant scope

With all nine native flags unset, **I would ship this dormant change on a supported established GL configuration**, subject to the existing backend's baseline limits. Terrain-LOAD immediately returns at `McNativeTerrainLoad.java:158`; it allocates, touches the device and records only after its own flag and a ladder token (`:170`). The other probe/adoption/feature entry points retain their flag guards; `McNativeDeviceFeatures.java:78` returns the exact requested feature set unflagged. The shared synthetic scene builder is reached only from flagged probes. No new GL-dependent native prerequisite is introduced.

On **Minecraft Vulkan**, I would ship it only as an inert/disabled Voxy configuration, **not as functioning native Voxy or delivered LoD**: backend selection disables Voxy there (`VoxyClient.java:96`–`:118`). The native mixins/probes remain dormant, and ordinary MC rendering is unchanged by these experiments. There is no fresh player/package test in this review.

**CONFIRMED** terrain-LOAD cannot act unflagged and, with the fixed launch's flags, acts only on frames for which the ladder supplied its one-shot sample token. It alone has terrain depth writes; ladder/base/control/rungs/coexist never write MC depth. Source and fresh inertness tests support this (`McNativeTerrainLoadTest.java:26`, `McNativeDepthLadder.java:243`, `:984`). This safety judgment does not certify the separate macOS GL-hosted diagnostic product or native resource pressure/lifecycle behavior with experiment flags on.

## Could not check, and why

- Fresh Minecraft/native/live execution: explicitly prohibited; I did not run `--only native` or `--only live`.
- Player/package GL or Vulkan regression, actual real sections and MC camera/LoD, normal travel/updates/reload/reconnect/pressure/lifetime acceptance: no permitted runtime evidence establishes them; native delivery remains unimplemented.
- GPU-origin authentication, compiled class/dependency/driver provenance or what the native creator/binder consumed: source hashes, declared terrain state, post-return ladder state and editable records do not provide that proof.
- Every original full-resolution MC readback or exact float32 MC depth: only crops/quarter-scale thumbnails and a lossy MC-depth PGM are retained. Float32 **Voxy reference** depth is retained and was independently decoded.
- Capture-time environment project properties, persistent environment provenance or dynamic runtime flag toggling: environment values are not retained and the experiment uses fixed launch flags.
- Full 220-case suite independently for each of 74 guard mutants: I ran complete unchanged relevant class suites for each mutant and an additional full suite with all four survivors together. The audit reports the scope exactly; no fixture was shrunk.
