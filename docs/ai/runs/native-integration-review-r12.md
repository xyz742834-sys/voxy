VERDICT: REDESIGN

Independent round-12 review of `9b6a18160b8111cf241787d4ca28c363af8b524b`, branch `xyz742834-sys/native-review-r12`, in `/Users/xyz/orca/workspaces/voxy/native-review-r12`, 2026-10-07. Initial HEAD matched the request and the working tree was clean. Only review artifacts were written. No implementation, fixture, committed evidence or living specification was edited; no Minecraft/native/live stage was launched. Evidence attacks used temporary copies; bytecode changes used separate instrumented JVMs.

The rounds 4–11 boundary still requires **REDESIGN**. These independently confirmed bounded measurements do not authorize calling the diagnostic layer an accepted foundation, and do not deliver streamed native Voxy LoD, scene coexistence, correct lighting/depth or normal-play lifecycle/pressure acceptance.

| Candidate claim | Judgment |
| --- | --- |
| (a) Round-11 blocking residuals repaired | **CONFIRMED for the named attacks.** B1's partial/minus-one/alias fingerprints fail. Persistent rewrites inside the production creator still escape JUnit, but the new post-return refusal and published state gate catch them. This is not universal protection against an arbitrary creator/native call. |
| (b) Per-pixel measurement, isolation, block-grain crop provenance, inventory and source binding retained and replayable here | **CONFIRMED.** The newest package has **19** samples, **198,546** matching complete thumbnail blocks and **408** matching selected-source entries. The round-11 package's **18** counts and **186,450** blocks were separately reproduced; those are earlier-run totals, not newest-run totals. |
| (c) Survey claims are limited to retained evidence | **CONFIRMED for the current conditional observations and explicit limits. REFUTED as an unconditional calibration or native-call-state guarantee.** Its earlier arbitrary-LESS qualification governs “positively controlled”; its creator-call limitation must also govern the sentence saying an alternative struct is checked by the measurement. Dated superseded inference and stale handoff prose remain documented below. |

## 1. Each round-11 finding

### B1 — CONFIRMED repaired

`scripts/verify.py:308–313` selects existing Git-listed cached/untracked non-ignored files under `src/`, `scripts/`, plus `build.gradle` and `gradle.properties`. `source_binding` at **2107–2142** validates names/digests, compares the complete key sets, then compares every digest. Replay refuses mismatches at **2228–2242**. The minimum-entry threshold is no longer the inventory condition.

Reproduce: `python3 .agent-run/r12-source-attacks.py`, `python3 .agent-run/r12-extra.py`, and `python3 .agent-run/r12-new-attacks.py`. Each attack refreshes the evidence manifest, so refusal is semantic rather than a stale manifest hash.

| Fingerprint attack | Replay |
| --- | --- |
| Four required names plus 100 existing unrelated files: 104 entries, 404 actual source entries missing | **1**, key-set mismatch |
| Full real fingerprint minus `McNativeDepthProbe.java` | **1**, one omitted source |
| Four required files inflated with `./` aliases: 103 names, four distinct files | **1**, non-normalized name |
| Empty map; wrong valid-format digest; nonexistent replacement name; extra unrelated name | **1** each |
| Replace one source name with `./`, `../native-review-r12/`, absolute `/tmp/`, or leading-space alias | **1** each |

**CONFIRMED:** all actual 408 keys equal this checkout's independently enumerated selected inventory and all hashes match. All **214** manifest members independently hash correctly. Launch and final revisions are `816c4c5bcd76ae485bfe5a0e28d6b27905d46ae7`, with `changed_sources_during_run: []`; they are not reviewed HEAD, but every selected source byte matches HEAD. See `.agent-run/r12-independent-evidence.json` and `r12-retained-details.json`.

**No differing parsed fingerprint key set or digest was found that replays as 0.** For a stable checkout, the explicit equality and digest checks exclude such a map. JSON formatting/order is immaterial; it is the parsed map that is bound. This is byte equality for the defined selection, not commit identity, Git modes, or evidence that executed classes were compiled from those bytes. It excludes dependency/Minecraft/Sodium jars, compiled artifacts, build caches, `settings.gradle`, wrapper/launcher configuration, JVM, loader/layers/driver binaries and original GPU execution. Git-ignored/unlisted files outside the selection are not silently part of this claim. Instrumentation can change executing bytecode without changing selected source files. The survey discloses the important source-versus-runtime boundary at **1209–1212**.

### R10-CREATE-TEST — CONFIRMED named persistent-state repair; REFUTED universal native-call pinning

The production path calls `buildPipelines(..., vulkanCreator(vk))` at `McNativeDepthLadder.java:633–635`. The builder reads each create-info's depth state **after** the creator returns (**707–718**), publishes it (**720–722**) and returns zero handles on disagreement (**723–728**). `json()` derives `depthWritesEnabled` from those observations (**886–896**). `verify.py:933–935,989–995` pins writes-off and the three states `[[1,1,0],[7,1,0],[4,1,0]]` (LESS, ALWAYS, GREATER; tests on, writes off). Missing states and each of nine op/test/write mutations fail full replay. The new refusal test is `McNativeDepthLadderTest.java:120–159`.

Reproduce bytecode flips: `python3 .agent-run/r12-bytecode.py`. Exactly one ladder transformation is logged per mutant. Every `@Test` method is invoked and assertion results counted, independently of process exit:

| Actual ladder bytecode mutation | Passed / failed of seven |
| --- | --- |
| Baseline | **7 / 0** |
| Helper writes false → true | **3 / 4** |
| Compare table LESS → ALWAYS | **4 / 3** |
| Builder's own `pipelineInfo` call substitutes ALWAYS for LESS | **5 / 2** |
| `pipelineInfo`'s depth-state call substitutes ALWAYS for LESS | **5 / 2** |
| Production `lambda$vulkanCreator$...`: persistent LESS → ALWAYS before Vulkan | **7 / 0** |
| Same production callback: persistent writes on | **7 / 0** |

The last two still pass all seven tests because those tests inject a different creator. **The gate repair, not additional JUnit coverage of the Vulkan call, closes the specified attacks.**

Reproduce the actual callback/build/publication seam: `python3 .agent-run/r12-production-probe.py`. Instrumentation calls the production creator via the real builder, replacing **only the Vulkan invocation** with a capture stub returning successful dummy handles. No VkDevice or GPU pipeline is created. The captured arguments establish the mutation's state at the call site, not actual Vulkan execution.

| Callback experiment | Call-site captured states | Post-return publication / handles | Full replay with its published state fields |
| --- | --- | --- | --- |
| Baseline | intended table | intended table; 1001,1002,1003 | **0** |
| Persistent LESS → ALWAYS | `[[7,1,0],[7,1,0],[4,1,0]]` | same; 0,0,0 | **1** |
| Persistent writes on | `[[1,1,1],[7,1,1],[4,1,1]]` | same; writes true; 0,0,0 | **1** |
| LESS → ALWAYS at call, restore original struct immediately afterward | wrong first compare | intended table; nonzero handles; no note | **0** |
| Writes on at call, restore writes off immediately afterward | writes enabled in all three | intended table; writes false; nonzero handles; no note | **0** |

**Named remaining mutations:** `restoreLessAlways` and `restoreWrites` pass **all seven JUnit tests** and their published state fields pass the replay gate. They require neither a private alternative struct nor rewritten source fingerprints. The probe uses the genuine retained crops for its state-field replay; it does **not** claim a newly mutated GPU run produced those crops. Separately, consistently repainted all-violet records also pass full replay; all-violet is what ALWAYS substituted for LESS would leave. Consistently repainted all-magenta records pass too: with all three pipelines writing depth, the ALWAYS base writes 0.5, GREATER at z₀ fails, the first LESS rung writes z₀ and all deeper LESS rungs fail. These are synthetic consistency experiments, not hardware executions.

**CONFIRMED inspected current production callback:** `vulkanCreator` at **742–751** forwards `info` unchanged, checks success and returns the handle; it contains neither restoration nor an alternative struct. The survey's call exclusion and arbitrary-broken-LESS caveat are real limits. **REFUTED** interpreting post-return state as proof of all state consumed by Vulkan, or its measurement cross-check as detection of every possible creator defect (`verify.py:993`'s “were created with” and survey **1219–1221** are stronger wording than the metadata alone proves). The two old persistent mutations are closed; the named restore escapes are retained as a non-blocking limit on that closure, not evidence of such a defect in current source or of forged committed data.

### Other round-11 dispositions — explicit recheck

- **R9-SURVEY-OVERCLAIM: CONFIRMED stays closed for the named sentence.** Survey **1074–1077** declines claims about terrain elsewhere in the frame/world; **1190** withdraws “none was loaded.” The retained cloud-band observation needs no excluded full-frame loading inference.
- **R10-LOG-DETAILS: CONFIRMED stays closed.** `verify.py:1016–1039` checks draw multiplicity, orientation and all eleven counts. Independently parsed **19** log lines match the current report. Twelve full-replay mutations (orientation and each count) all return **1**: `python3 .agent-run/r12-log-attacks.py`.
- **R10-DEPTH-FIXTURE: CONFIRMED stays closed.** The fixture is **48×100** at `test_marker_gate.py:1080`. Changing the depth recount to `band=1` fails `test_a_varying_image_is_accepted_without_a_convention`; its published top mean is `0.005050736247806519`, defective recount 0.0. Fourteen other depth tests still pass that broken averaging; their class does not.
- **R10-ANCHOR-GRAIN / R10-CONSISTENT-FORGERY: CONFIRMED accurate limits, not repairs.** See §2's accepted mutations and survey **1193–1199**.
- **Round-11 actual counts/block totals: CONFIRMED independently.** `python3 .agent-run/r12-previous-evidence.py` decodes its earlier package directly: all 18 histograms and both orientations match, with 186,450 complete matching blocks. That old package now fails source-bound replay, as expected.
- **Round-11 stale replay-table finding: CONFIRMED repaired in the survey.** All thirteen cells agree with fresh commands; the handoff remains stale (§4).

## 2. Per-pixel ladder and complete mutation outcomes

**CONFIRMED mechanism, conditional on inspected draw state.** `McNativeDepthLadder.java:288–291` LOADs both attachments. At **323–332** it draws a white ALWAYS base, grey GREATER at z₀, then eight ascending LESS rungs over the same band, with writes off (**675–679**). The shared marker shader uses the pushed vertex depth and does not write fragment depth. For finite depth d, grey means d < z₀; rung i means zᵢ < d ≤ zᵢ₊₁ (or d > z₇). Equality d=z₀ leaves white and is refused. NaN/unordered comparisons likewise do not yield an accepted white pixel.

**CONFIRMED complementary control**, not universal LESS calibration. The same-pixel GREATER draw covers the low side; equality/anomaly is refused. An arbitrarily broken LESS path could still paint a valid palette outcome. Survey **1117–1122** acknowledges this, and its final “positively controlled” phrase at **1225–1226** must retain that qualification. It does not establish a Z direction or known world-distance reference.

**CONFIRMED independently:** every newest selected exact-RGB histogram and rejected-crop count matches. Samples are draws **2, 242, …, 4322**: 19, with `anomaly = other = 0` throughout. Both crops of every sample average to their stated thumbnail positions; **198,546** complete blocks match. The 1024×77 crops have **1,328** unanchored boundary pixels each; the 1152×86 crops have **2,304**. `verify.py:1203–1226` uses integer means over complete 4×4 blocks. The approximate “1300–2300” limit is fair. A within-block permutation and small RGB changes hidden by floored means are not individual-pixel positional proof.

Full independent counts are retained in `.agent-run/r12-independent-evidence.json`; representative current counts:

| Draw | Low | Nonzero rung counts |
| --- | ---: | --- |
| 2 | 41,907 | r0 29,856; r1 7,085 |
| 242 | 13,314 | r2 65,534 |
| 962 | 11,496 | r2 67,352 |
| 1202 and 1442 | 78,848 each | none |
| 3122 | 14,452 | r2 84,620 |
| 3842 | 80,030 | r3 2,738; r4 16,304 |
| 4322 | 14,452 | r2 84,620 |

**CONFIRMED isolation retained:** the second-launch command enables probe/features/adopt/ladder, with marker/terrain/depth-copy flags absent; marker/terrain report flags and draw counts are zero. Its eleven checkpoints equal the summary's copied checkpoints. Ladder device is **0x7a496d4018**; main-launch device is **0x7672f93018**. Gate comparison uses the ladder launch's own device/extents (`verify.py:2310–2339`). Both retained logs show Khronos layer insertion and no matched VUID/validation-error/warning/synchronization-hazard lines. The adopted context says `validation=true syncValidation=false`; this does not establish native synchronization-validation activation. Thumbnails of draws 2, 242 and 3842 were visually inspected: cloud/sky, hills and nether context respectively. Those inspectable RGB contexts are distinct from an independent scene-depth reference.

Reproduce the evidence mutation table with `python3 .agent-run/r12-attacks.py`, `r12-extra.py`, `r12-source-attacks.py`, `r12-log-attacks.py`, `r12-new-attacks.py`, and `r12-magenta-attack.py`. These record **116** case outcomes in the corresponding JSON files. All altered evidence is temporary; manifest hashes are refreshed except deliberate membership attacks.

| Mutation group | Replay |
| --- | --- |
| Unlisted nested manifest/ordinary file; required artifact removed with manifest entry | **1** (marker-report absence **2**) |
| Coordinated orientation/rectangles with unchanged thumbnails; repaint thumbnails but not log | **1** |
| Omit all but one sample from report only, or report plus files but not log | **1** |
| Rename draw without log; reorder samples; one-sided counts; all-violet crops with incomplete record updates | **1** |
| Wrong device/extent; tiny crop; checkpoint-copy mismatch; contaminated terrain/marker launch; writes/convention/band-wide bound claims | **1** |
| All 27 depth NaN cases and seven ladder NaN cases | **1** |
| All fingerprint attacks and twelve log-detail attacks | **1** |
| Missing pipelineStates and each of nine changed op/test/write entries | **1** |

**Every evidence mutation that replayed as 0:**

| Case name | Meaning |
| --- | --- |
| `omit_17_report_files_and_log` | In this **19-sample** package the inherited case name actually removes **18** samples, their 54 files and sample-log lines. Consistent inventory forgery; one sample remains. |
| `within_block_pixel_permutation` | Swap different colours inside a complete block, preserving histogram and mean. Disclosed individual-pixel positional loss. |
| `rename_draw_with_log` | Rename draw 2 and its three files to draw 3, update log. Internally matched inventory is not authenticated to an independent schedule. |
| `edge_pixel_recount_report_and_log` | Pixel (342,576), magenta → violet, change histogram/log, keep thumbnail. Disclosed boundary gap. |
| `orientation_repainted_thumbnail_and_log` | Flip both orientations/rectangles and repaint thumbnail blocks/log. Disclosed consistent forgery. |
| `violet_crops_counts_thumbnail_and_log` | All-violet selected crops, consistent histograms/thumbnail/log. Disclosed forgery and arbitrary-LESS limitation. |
| `magenta_crops_counts_thumbnail_and_log` | All-magenta selected crops, consistent histograms/thumbnail/log. Same consistency limit. |

Additional production-call state experiments returning 0 are baseline, `restoreLessAlways` and `restoreWrites` (§1). No other evidence mutation returned 0 in these tables.

**CONFIRMED gate soundness within its stated internal-consistency domain. REFUTED authentication/calibration outside that domain.** Actual retained histograms and block means need none of the excluded individual-pixel anchoring, runtime-binary identity or forgery resistance to be independently reproduced. Inferring physical attachment brackets also depends on the inspected unmodified draw/native path, as explicitly retained here. No measurement of near/far direction is promoted.

## 3. Required tests and tests that pass broken code

| Exact command | Fresh result |
| --- | --- |
| `./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true` | **BUILD SUCCESSFUL**, test task executed. XML: **333 tests, 1 skip, 0 failures/errors**. |
| `python3 -m unittest discover -s scripts/tests` | **161 tests, OK**, 304.721 seconds. |
| `python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261007T031630-708042Z` | **exit 0**, ten replay checks, 19 ladder samples. |

Logs: `.agent-run/r12-gradle.log`, `r12-python.log`, `r12-replay.log`. The single skip is `VkBarriersTest.missingBarrierIsDetected()`, the documented descriptor-bound SSBO hazard not observed by validation. JUnit contains the deliberate `vkCmdFillBuffer` WAW positive control. A startup **VALIDATION-SETTINGS** warning says deprecated layer settings take precedence over newer settings; it is retained, not suppressed. No unexpected VUID/operation hazard was found in this run. The offscreen context's validation/synchronization settings do not prove native-launch settings.

**CONFIRMED fixtures not shrunk:** marker/ladder frames remain **960×540** (`test_marker_gate.py:32`, `test_ladder_gate.py:39`), ladder band **576×43**, depth **48×100**. None was edited.

**Named tests passing broken code:** all seven ladder JUnit tests, including the state-rewriting refusal and creator-input tests, pass production-callback persistent and restoring mutations (§1). Persistent ones are stopped by the gate. The gradient Python test `test_a_gradient_field_is_bracketed_pixel_by_pixel` passes doubled thresholds because it shares constants, but `test_the_pinned_constants_are_the_literal_ones` fails. Fourteen of fifteen `DepthProbeGateTest` cases pass `band=1`, but its varying-image case fails. Reproduce both Python mutations with `python3 .agent-run/r12-test-escapes.py`. These are individual-test limitations; the Python classes detect the named defects. JUnit alone does not inspect the real Vulkan invocation.

## 4. Survey accuracy, terrain and depth

**CONFIRMED corrected replay table:** survey **448–462** lists all thirteen retained directories. Independent subprocess commands return **1** for each of twelve older runs and **0** only for `031630-708042Z`. The first seven fail the required ladder-source entry; the next five fail current source-byte comparison. Older table cells can also describe later checks their packages cannot satisfy, but those are not the first refusal encountered today. Dated “replay 0” statements elsewhere are historical observations, qualified by **441–446**, not current successful replay claims.

**CONFIRMED bounded synthetic terrain experiment:** all **23** retained native/reference PPM pairs independently match byte-for-byte after decompression. The gate-selected draw **4579**, 1920×1080, has **26,116** non-background pixels and zero mismatches. This is synthetic input through the real terrain pipeline on Minecraft's device while the experiment clears Minecraft's frame/depth, not streaming LoD or coexistence. Native status remains `BLOCKED_UNIMPLEMENTED`.

**CONFIRMED bounded depth result:** independent P5 decoding finds **1,639,680 quantized zeros**, 1708×960. Original floating-point bits are not retained. Neither uniformity nor disagreement with a ladder measured in another launch/frame identifies the copy's failure mode or proves that the copied frame had nonzero scene depth. The depth-only isolated run is still not retained. The ladder observes structured nonzero brackets under its inspected mechanism in a separate launch; the Z convention remains unmeasured.

**Remaining non-blocking documentation limits:**

1. The survey's current round-11 source-binding description is supported. Its creator paragraph's “measurement itself … checks” (**1219–1221**) is a cross-check, **not** a guarantee that every alternative native-call state is detected; restore mutations and coherent violet/magenta records refute the stronger reading. Post-return metadata pins post-return observations, not a trusted call trace.
2. Historical eight-column sentences **983–985,1004,1011–1013** still assert an unjustified band-wide bound/control inference, but **1043–1044,1067–1071,1097–1111** explicitly record the round-8 refutation and replacement. They must remain treated as superseded history, not surviving current evidence. The final current per-pixel claim does not need their prefix/cross-column inference.
3. **REFUTED current handoff accuracy:** `handoff.md:4` still names `867cd25d`; **87** repeats older counts, “chunks not yet loaded” and “i.e. the cleared value”; **117–128** says JUnit 332/Python 160 and that `025423` replays here, and describes a pending round-11 dispatch. That file explicitly labels itself task state (**7**). Those assertions are stale/refuted at HEAD and cannot overrule the corrected survey, new package, or this review. The survey's withdrawal stays closed; the handoff's copies are not evidence.

These observations do not convert a consistency check into a forensic authenticity system. They also do not establish an accepted layer. The delivery boundary is the final blocking finding; no new defect was demonstrated in the inspected current ladder's GPU state.

## 5. Safety for normal play

**CONFIRMED: I would ship these diagnostic changes dormant to a player on the established supported GL backend**, subject to ordinary release checks. **CONFIRMED: I would ship them dormant alongside Minecraft's Vulkan backend with Voxy disabled. REFUTED native Voxy delivery to that Vulkan player.** This is a source/test judgment of the dormant changes, not fresh ordinary-play or whole-project certification.

With all seven listed flags unset:

- Ladder returns at `McNativeDepthLadder.java:216` before device, target, GPU or file access; shutdown returns without device work when no instance exists (**838,855**). Its inertness test passes.
- Marker/terrain/depth/environment guards remain (`McNativeMarkerDraw.java:273`, `McNativeTerrainProbe.java:185`, `McNativeDepthProbe.java:99`, `McNativeVulkanProbe.java:296`).
- Features return the requested feature set unchanged (`McNativeDeviceFeatures.java:78`); adoption only makes bounded Java bookkeeping before its disabled return (`McNativeVkContext.java:92–100`), and release returns before waiting unless adopted (**287**).
- Level-render/close hooks call these guarded entries (`MixinLevelRenderer.java:34–62`). `VoxyClient.java:82–116` preserves supported GL selection and disables Voxy without Minecraft GL.

**CONFIRMED current ladder cannot act unflagged. CONFIRMED current ladder neither clears nor writes Minecraft's depth when flagged:** colour/depth LOAD uses empty clear optionals (**288–291**); all three depth states disable writes, bounds and stencil (**675–679**); production creator forwards them unchanged (**742–751**). It writes colour, opens/copies through Minecraft's machinery, and retires through Minecraft's queue or leaks rather than destroys on uncertain ownership (**815–865**). Other separately flagged probes can clear/write depth; their isolation remains necessary. Hypothetical restore mutations do not change this judgment about current source.

## 6. What could not be checked and why

The user prohibited Minecraft/native/live execution. No fresh live mixin application, original submission trace, normal GL visual parity, ordinary play, travel/update/reconnect/resize/reload behaviour, streamed LoD, scene coexistence, lighting/depth correctness, pressure/device-loss retirement or Z-direction experiment was performed. Offscreen JUnit is not a substitute.

Full original ladder readbacks/screenshots are not retained: quarter-scale means cannot authenticate individual-pixel ordering, boundary strips, alpha, or overwritten scene colours. No independent same-pixel scene-depth reference is available. Original depth float values cannot be recovered from quantized PGM. The isolated depth-copy-only run is unavailable. Fingerprints do not prove runtime binaries, native synchronization-validation activation, original GPU execution or authenticity against coherent rewritten records.

The production-call capture stub observes the Java structs at the invocation site, not the native Vulkan implementation/driver or resulting GPU state. Restoring mutations and all-violet/magenta synthetic packages show test/gate limits, not a newly measured defective GPU run or forgery in the committed package. All conclusions are specific to this checkout and HEAD.

Reproduction scripts, raw logs, instrumented-JVM outcomes, independent recounts and inspection thumbnails remain under `.agent-run/r12-*` (plus Java helper sources). Machine verdict: `.agent-run/native-integration-review.json`. No fix, promotion, merge, push, publication or Minecraft launch occurred.
