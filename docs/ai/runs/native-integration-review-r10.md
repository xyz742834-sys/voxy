VERDICT: REDESIGN

Independent round-10 review of `6b2d6cc92fe9407be8361b928a5ffd7a1e4d2df0`, branch `xyz742834-sys/native-review-r10`, in `/Users/xyz/orca/workspaces/voxy/native-review-r10`, 2026-10-07. Initial HEAD matched the request and the tree was clean. No implementation, fixture, threshold, historical report, living specification or committed evidence was changed. Review experiments only changed temporary evidence copies, Python objects in separate processes, or JVM-loaded bytecode. No Minecraft/native/live stage was launched.

The rounds 4–9 boundary still applies: an independently accepted diagnostic foundation would still be **REDESIGN** for the delivery target. This candidate expressly does not claim an accepted layer. Its narrower claims fare as follows:

| Candidate claim | Judgment |
| --- | --- |
| All round-9 findings and advertised regression escapes are repaired | **REFUTED as complete.** The named nested-manifest, deletion, orientation, omission and NaN attacks are repaired. A semantically empty source fingerprint still passes, the actual creation-call mutation still escapes JUnit, and one unsupported survey claim survives. |
| Per-pixel measurement, isolated launch and crop provenance are retained and replayable | **CONFIRMED with a precise provenance limit.** All 18 current histograms and both crops' block means independently agree. Provenance is anchored at complete 4×4 blocks; it does not authenticate individual pixel positions or uncovered boundary strips. |
| The survey claims no more than retained evidence | **REFUTED.** Its named edits improve the argument substantially, but the surviving “none was loaded in the sampled frame” assertion is unsupported, and the test-creation guarantee exceeds its coverage. |

## 1. Each round-9 finding

“Closed” distinguishes the two requested old gate attacks from any narrower residual uncovered here. No passing forgery below is evidence that the committed run was forged.

| Finding | Judgment of repair | Closed | Evidence |
| --- | --- | --- | --- |
| B1 | **CONFIRMED named membership/inventory repairs; REFUTED complete source-binding gate** | No | `scripts/verify.py:2112–2144`; every requested deletion-with-entry fails. Replacing `source-sha256.json` with `{}` and refreshing its manifest hash still replays 0. |
| R8-LADDER-GATE | **CONFIRMED the round-9 orientation and omission repairs** | Yes, for those two specific defects | `verify.py:991–1006,1142–1192,2227–2231`; unchanged thumbnails defeat the coordinated lie, and unchanged crops/log defeat omission. The lossy anchoring and joint-forgery limits below remain. |
| R9-DEPTH-FINITE | **CONFIRMED** | Yes | `verify.py:1330–1340`; NaN is refused in all 11 scalar numeric fields and every one of the 16 histogram entries of the current depth report. |
| R9-SURVEY-OVERCLAIM | **CONFIRMED named edits; REFUTED whole-document closure** | No | `vulkan-native-integration-survey.md:1014–1018,1049–1059,1111–1118,1149` repairs the examples. `1074–1075` still asserts that no terrain was loaded in the sampled frame. |

### B1: membership, required inventory, and a remaining empty-fingerprint escape

**CONFIRMED** only the root `MANIFEST.json` is exempt. An unlisted `ladder/MANIFEST.json` containing arbitrary text returns **1**. An ordinary unlisted file also returns **1**. Dropping the source-fingerprint entry while retaining the file returns **1**, identifying the unlisted file. The actual newest directory has **195** manifest members, all hashes correct; no retained ordinary file is unlisted.

**CONFIRMED** deletion together with its entry now fails for all fourteen required artifacts exercised independently: summary; source fingerprint; main log; marker report; adopted context; environment probe; feature proof; compute proof; real-shader proof; enabled terrain report; enabled depth report; ladder report; ladder own result; ladder log. The marker-report absence returns **2** before structured replay, and the other cases return **1**. This closes the exact round-9 delete-with-entry attacks, including the fingerprint and both logs. Conditional inventory follows the summary's recorded terrain/depth/ladder stages; the files they reference are separately required by their gates.

**REFUTED complete source binding (B1 residual).** Replay requires that a fingerprint *file* exist and be hash-listed; it never parses or validates its contents. Replace only that file with `{}` and refresh its manifest hash: **replay 0**, nine checks, all 18 samples. The core source-binding evidence has been erased while the file remains present. This is a semantically missing artifact, not a request to authenticate an arbitrary jointly rewritten world. The current directory really has **408** source/build/script entries, independently checked against this exact checkout with **zero mismatches**; the defect is the acceptance gate, not missing actual current evidence. Reproduce with `python3 .agent-run/r10-extra.py`, case `empty_source_fingerprint`; see `verify.py:2124,2138` and the absence of any content consumer in `replay_evidence`.

Recorded launch revision and final revision are both `fd1e10ccf679bb46dfc18379c59b9089c94ffe20`, with a recorded dirty tree containing the repair files. They are not reviewed HEAD. `changed_sources_during_run` is empty, and the matching 408 fingerprints supply the independently checked source binding; the recorded commit alone does not.

### R8-LADDER-GATE: original attacks and consistent forgery

**CONFIRMED** the original coordinated orientation lie now returns **1**. Flip both booleans and recompute both rectangles for all 18 samples, leaving crop/thumbnail bytes intact: the first selected crop moves from `[342,576,1366,653]` to `[342,307,1366,384]` and fails at thumbnail block `(86,77)`: crop mean `(128,128,128)`, scene thumbnail `(184,183,189)`. Rectangle arithmetic alone no longer supplies provenance.

**CONFIRMED** the original 17-of-18 omission returns **1**: 51 retained PPM files belong to omitted samples. Removing their files as well still returns **1**, because the original launch log records all 18 draws. Reversing the sample order, renaming draw 2 to draw 3 without changing the log, changing counts without pixels, a foreign device, checkpoint-copy disagreement, a 1×1 crop, and a producer-stated 80×100 extent also fail.

**CONFIRMED scope of log reconciliation; REFUTED full log/report equality.** `LADDER_SAMPLE_LOG` at `verify.py:888` extracts draw numbers only, and `1001–1006` compares the sorted lists, preserving duplicate multiplicity. It does not compare orientation or counts on those same log lines, the summary's stored ladder report/counts, the log's final draw total, or the reported scheduling constants. Changing every logged `flipped=true` to `false` and draw-2 `low=41612` to `999999` **replays 0** against unchanged report/crops/thumbnail. Thus the sampled-draw inventory cannot drift one-sided and pass, but other log fields can. This is a limitation of the documented *inventory* repair, not failure of its draw-list comparison.

**CONFIRMED that a consistent forgery is possible, and what it takes.** In isolated copies with refreshed manifest hashes:

- Flip both rectangles/booleans and repaint every thumbnail's fully covered crop blocks at the new positions: **0**, 18 samples. No log change is required because its orientation fields are not checked. The rest of each thumbnail can remain unchanged; the forged images then look inconsistent with scene context on visual inspection.
- Remove 17 report samples, their 51 raw files, and their 17 sample-log lines: **0**, one sample. The summary and manifest's descriptive ladder list still describe the old inventory; replay does not reconcile those descriptive copies. To make the entire retained package narratively consistent, those copies must also be rewritten. A one-sided omission no longer suffices.
- Rename the first sample/crop pair/thumbnail to draw 3 and change the corresponding log draw number: **0**. Replay does not pin the readback schedule to an immutable external record.
- Repaint selected crops all violet and recount the report, with original thumbnails: **1**. Repaint their corresponding thumbnail blocks as well: **0**. A wholly forged report/log/image/manifest set has no immutable authenticity anchor.

These are internal-consistency gates over retained, editable records. No hash scheme rooted in an editable manifest can establish that an arbitrary mutually consistent replacement was emitted by the GPU. These coordinated rewrites do not refute the independently inspected committed observations or demand an unrequested signing infrastructure.

### R9-DEPTH-FINITE

**CONFIRMED** `finite_number` rejects bool, NaN and infinities; `finite_int` rejects bool and floating NaN. Full replay mutations individually set NaN in `min`, `max`, `topMean`, `bottomMean`, `clearedValue`, `clearedShare`, `depthVkFormat`, `width`, `height`, `bins`, `closeFailures`, and histogram entries 0–15. All **27** return **1**. Required six measured scalars now use the finite check before range, uniformity or tolerance comparisons. The former `abs(NaN-x)>tolerance` escape is closed for the completed report reviewed here. The incomplete-copy branch intentionally represents “not observed,” rather than validating absent measured statistics.

### R9-SURVEY-OVERCLAIM

**CONFIRMED** the per-pixel paragraph now says low pixels are merely consistent with a cleared value, not established to equal it (`1149`). The clouds paragraph no longer derives a two-sided cloud depth/distance from two different column pixel sets (`1049–1059`). The screenshot statement explicitly identifies its missing retention rather than calling both facts checkable (`1014–1018`). The current complementary-control explanation includes strict equality and says it cannot calibrate an arbitrarily broken LESS pipeline (`1111–1118`). The later-hook impossibility remains explicitly withdrawn (`1012–1036`). These are substantial, correct repairs.

**REFUTED complete closure.** The same old experiment's limiting paragraph still says “none was loaded in the sampled frame” about terrain (`1074–1075`). A raw crop of clouds over sky cannot establish whether terrain was loaded elsewhere in that frame/world. This directly exceeds both the retained crop and the corrected paragraph's own refusal to claim anything about the rest of the frame (`1055`). Treat the old cross-column bound/prefix paragraphs as refuted dated history, as the following round-8 section instructs; they are not current accepted inferences. The unqualified whole-frame loading claim is still unsupported even in that historical account.

**REFUTED the advertised creation-test guarantee** in the round-9 repairs paragraph (`1166–1168`) when read as “a LESS→ALWAYS substitution at creation cannot escape.” The table corruption is caught, but a substitution at its actual consumer still escapes; §3 demonstrates it. This is a further reason the document cannot claim all advertised repairs are verified.

## 2. The per-pixel ladder

### Mechanism, complement, and interpretation

**CONFIRMED conditional measurement mechanism.** `McNativeDepthLadder.java:281–290` opens Minecraft's colour/depth pass with empty clear options (LOAD). `304–325` sets a `[0,1]` depth viewport and draws over one fixed band: ALWAYS white, GREATER grey at z₀, then eight ascending LESS thresholds in distinct colours. `329–335` supplies the same band and pushed depth to the marker shaders. `665–669,699,715` enable depth testing, disable depth writes/bounds/stencil, and pass that state to the graphics pipeline; blending is off at `700–703`. `623–625` creates the three table-indexed pipelines and `315–321` binds the corresponding slots.

For stable finite attachment value d and the inspected state/shader execution, `d<z₀` finishes grey; `d=z₀` finishes white and is refused; `zᵢ<d≤zᵢ₊₁` finishes rung colour i; `d>z₇` finishes colour 7. No inference connects different pixel columns. The raw retained pixel then encodes a per-pixel value bracket, and the report publishes its histogram. Comparison/write semantics agree with [Khronos VkCompareOp](https://docs.vulkan.org/refpages/latest/refpages/source/VkCompareOp.html) and [depth-stencil state](https://docs.vulkan.org/refpages/latest/refpages/source/VkPipelineDepthStencilStateCreateInfo.html).

**CONFIRMED complementary-control wording as now qualified.** Grey distinguishes genuinely low depth from both comparison paths rejecting. White is equality or an undecided/failed path, not proof of a broken depth engine. GREATER's success is not a general calibration of LESS, shader depth delivery or which attachment contains scene depth. The document now acknowledges the ALWAYS-like LESS counterexample. All current retained samples have no white or outside-palette pixels, and their source states are correct on inspection. No measured Z direction follows; the report and gate explicitly require `zConventionMeasuredHere:false` and forbid convention/band-bound keys.

### What exact thumbnail anchoring binds

**CONFIRMED exact arithmetic for every compared block.** Java `writeFrame` (`McNativeDepthLadder.java:534–567`) computes each channel's unsigned 4×4 sum divided by 16 with integer truncation. Python `ladder_anchor_blocks` (`verify.py:1170–1193`) computes precisely that same integer quotient, requires exact RGB equality and uses no tolerance. Independent P6 decoding and an independently written block-sum loop agree for both crops of all 18 samples.

**CONFIRMED coarse position anchoring; REFUTED exact pixel-origin identity if inferred from it.** Only blocks wholly inside each rectangle are compared. A 1024×77 crop supplies **4,845** checked blocks and **1,328** boundary pixels outside them; a 1152×86 crop supplies **6,048** blocks and **2,304** excluded boundary pixels. The scale also discards the full image's incomplete edge blocks, and preserves no alpha. Even within checked blocks, different arrangements and different sums rounding to the same integer mean can collide.

Two concrete unchanged-thumbnail escapes:

| Mutation of draw 2 | Replay | Consequence |
| --- | --- | --- |
| Swap unequal pixels at full-frame `(364,576)` and `(367,576)` inside one 4×4 block; report/counts/log/thumbnail unchanged; refresh crop hash | **0** | Exact per-pixel positions change, but counts and block means do not. |
| Boundary pixel `(342,576)`: grey→violet; decrement report low and increment rung-7 count; leave thumbnail and log unchanged; refresh hashes | **0** | A per-pixel bracket/count can change outside the compared blocks. The log's old counts also remain accepted. |

The first preserves the histogram being claimed; the second shows why complete per-pixel provenance must not be inferred from a block-mean anchor. The actual committed counts remain independently confirmed. This reduction is a declared coarse anchor, not a full readback, so these are retained limitations of that claim, not evidence of an actual bad sample.

### Counts, own launch, and isolation

**CONFIRMED** independent exact-RGB recount of every selected P6 crop, without using the gate's quantizer or palette constants. All 18 samples have `anomaly=other=0`; no selected pixel requires a tolerance to match its expected colour. All opposite crops and every compared thumbnail block were checked. The newest run's counts differ from the separately cited older run; the review did not substitute the old counts for them.

| Draw(s) in newest run | Low | Nonzero rung counts |
| --- | ---: | --- |
| 2 | 41,612 | r0=27,071; r1=9,143; r5=1,022 |
| 242 | 21,602 | r2=57,246 |
| 482 | 36,767 | r2=42,081 |
| 722 | 38,181 | r2=40,667 |
| 962; 1682–2882 every 240 | 11,485 | r2=67,363 |
| 1202, 1442 | 78,848 | none |
| 3122, 3362, 3842, 4082 | 14,439 | r2=84,633 |
| 3602 | 80,030 | r3=2,738; r4=16,304 |

Full independent results are `.agent-run/r10-independent-evidence.json`. Whole thumbnails at draws 2, 242 and 3602 were visually inspected: clouds/sky, overworld hills/trees and nether geometry provide scene context outside the overwritten measurement band. The thumbnail cannot recover the pre-ladder colour or reference depth at overwritten pixels. Scene names and conditional near/d distance illustrations are interpretations, not measured geometry distances or nearer direction.

**CONFIRMED recorded isolation.** The retained second-launch command uses native probe/features/adoption/ladder flags, with terrain, marker and depth-copy absent. Its report has terrain/marker flags false and recorded draws zero. Main device is `0x79677e0018`; ladder device is `0x748b364018`. The ladder's own eleven checkpoints agree with the summary copy and tie its identity and extents to its separate process. Both retained logs and their validation-layer insertion evidence were inspected; no VUID/validation-error/synchronization-hazard lines were found. The source stage passes its own log/device/extents into the same gate used in replay (`verify.py:2431–2449`). Each separately enabled terrain/marker flag/count mutation fails. This is retained/source isolation evidence, not a new Minecraft execution.

### Mutation inventory

Run `python3 .agent-run/r10-attacks.py` and `python3 .agent-run/r10-extra.py`. The first retains 70 results; the second seven. Modified-file hashes are refreshed unless the attack deliberately tests missing membership. These exercise full `replay_evidence`, not just a substitute helper.

| Mutation group | Replay |
| --- | --- |
| Unlisted nested manifest / ordinary file; retained fingerprint without entry | **1** |
| All fourteen required deletion-with-entry cases | **1**, except absent marker report **2** |
| Coordinated orientation lie with original thumbnail | **1** |
| Omit 17 samples, original files retained; omit them and files, original log retained | **1** |
| Counts transferred with unchanged pixels; reverse samples; rename draw without log | **1** |
| Depth writes, convention claim, bound key, marker/terrain contamination | **1** |
| Foreign device; own/summary checkpoint mismatch; tiny producer extent; 1×1 crop | **1** |
| All 27 depth NaN mutations; ladder depth/band/palette/at/extent/rejected-count NaN | **1** |
| All-violet crop and counts, original thumbnail | **1** |
| Orientation plus rewritten thumbnail | **0** |
| Report/files/log jointly omitted; draw rename plus log rename | **0** |
| All-violet crop/counts plus rewritten thumbnail | **0** |
| Within-block pixel permutation; edge-pixel/count alteration | **0** |
| Log orientation/count drift | **0** |
| Empty source fingerprint | **0** |

The green cases are explicitly named rather than absorbed into an “all attacks fail” assertion.

## 3. Tests and fixture discipline

Fresh requested commands and results:

| Command | Result |
| --- | --- |
| `./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true` | **BUILD SUCCESSFUL**; XML: 331 tests, zero failures/errors, one documented skip (`VkBarriersTest.missingBarrierIsDetected()`). The test task executed; build compilation reused cached outputs. |
| `python3 -m unittest discover -s scripts/tests` | **158 tests, OK**, 272.251 seconds. |
| `python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261007T022908-162255Z` | **0**, nine checks, 18 ladder samples. |

Gradle log confirms `validation=true syncValidation=true` on the offscreen Voxy-owned context. The fill-buffer negative control emitted its intentional WRITE_AFTER_WRITE hazard. These settings/tests do not prove native synchronization-validation activation, scene coexistence or Minecraft-device lifetime behaviour. Logs are `.agent-run/gradle.log` and `.agent-run/python.log`.

### Bytecode flips

Run `python3 .agent-run/r10-bytecode.py`; an instrumentation agent transforms only the ladder as it loads, with production class files untouched and no GPU creation call made. The runner reports test pass/fail counts; its process exit alone is not used as a verdict. Each mutation log confirms exactly one transformation.

| JVM mutation | Ladder JUnit outcome |
| --- | --- |
| Normal | **5 pass** |
| Actual `depthStencilState` helper: `depthWriteEnable(false)`→true | **4 pass, 1 fail**: `everyPipelineTestsDepthWithoutWritingIt` |
| LESS entry in `PIPELINE_COMPARE_OPS` initializer→ALWAYS | **4 pass, 1 fail**, same test |
| At actual `create()` pipeline call, substitute ALWAYS when table-derived argument equals LESS, leaving the table/helper intact | **all 5 pass** |

**CONFIRMED** the shared table/index test at `McNativeDepthLadderTest.java:52–80` catches table corruption and the helper write flip. **REFUTED creation-use coverage (R10-CREATE-TEST).** The last mutation changes only what creation sends into the actual pipeline builder (`McNativeDepthLadder.java:625`); its input table still passes every assertion. Neither creation nor recording executes in these tests. The production creation code is correct on source inspection, but the claimed “cannot escape” regression guarantee is false. In particular, `everyPipelineTestsDepthWithoutWritingIt` still passes against broken creation code; the other four tests also pass and do not test creation. This is the round-9 call-site coverage problem in a narrower table-consumer form.

### Python flips and fixture shape

**CONFIRMED literal pins.** `test_the_pinned_constants_are_the_literal_ones` at `test_ladder_gate.py:334–348` fails when the gate's depths are doubled in memory; the gradient positive case still passes. That positive test shares the gate's constants and is not itself an independent threshold pin. The added literal test closes the former whole-suite escape.

**CONFIRMED no fixture shrinking.** Marker and ladder frame fixtures remain **960×540** (`test_marker_gate.py:25–29`, `test_ladder_gate.py:33–36`). The marker pass cell retains its 960-pixel coverage above the 500 floor; the ladder band is **576×43**, with gradient, zero and cloud fields. Current ladder fixtures derive an entire thumbnail frame from the crop placement (`test_ladder_gate.py:105–130`) and can now reject an orientation lie against the unchanged synthetic frame. Their uniform synthetic scene, no pipeline execution and integer block-mean design cannot independently certify real scene/attachment/pixel identity. The preserved sizes are adequate for the claimed gates.

**REFUTED depth-copy fixture coverage of real multi-row bands (R10-DEPTH-FIXTURE, non-blocking).** Depth fixtures remain **48×32**, so `max(1,height//50)` always selects one row (`test_marker_gate.py:1074–1095`). Replace only `recount_depth_sample`'s band calculation with the defective `band=1` in memory: **all 15 DepthProbeGateTest cases pass**, including the varying-gradient case. Real 960-high evidence requires 19 rows and 1080-high images 21. This is a concrete size-dependent defect escape, not an objection to small fixtures generally. The actual source uses the correct formula (`verify.py:1401`). Because the retained depth image is uniform zero, even that actual image does not exercise this averaging distinction. The fixture also derives histogram summaries after 16-bit quantisation, so original-float bin-boundary parity remains outside its proof.

Reproduce both Python experiments with `python3 .agent-run/r10-test-escapes.py`. No fixture was modified or shrunk.

## 4. Survey, eleven replays, terrain and depth

**CONFIRMED** all eleven directories in the survey replay table (`450–460`) exist and their stated replay statuses match independent subprocess replays:

| Run | Replay / first refusal |
| --- | --- |
| 20261006T070311-099413Z | **1**, missing closeFailures |
| 20261006T073859-396220Z | **1**, missing leakedPipelines |
| 20261006T080931-279390Z | **1**, missing leakedPipelines |
| 20261006T083136-838975Z | **1**, missing opposite-orientation sample |
| 20261006T084300-332469Z | **1**, same |
| 20261006T095436-054038Z | **1**, missing depth zConventionMeasuredHere |
| 20261006T165039-062378Z | **0**, ladder explicitly not replayed |
| 20261007T005606-024013Z | **1**, missing ladder palette |
| 20261007T011026-211934Z | **1**, same |
| 20261007T020025-032770Z | **1**, no frameScale/thumbnails |
| 20261007T022908-162255Z | **0**, current per-pixel ladder included |

Results are `.agent-run/r10-independent-evidence.json`. The older run's 18-sample paragraph at survey:1149 is explicitly linked to `020025`; its counts are not asserted to describe the new `022908` run. Schema refusal does not erase historical raw evidence or the round-9 independent recount, but that paragraph's past-tense “Replay returns 0” must be read with the current table's explicit refusal. Handoff remains headed by the round-9 SHA and repeats old cleared-value attribution and review/task status (`handoff.md:3–4,89–95,123–144`); it is stale task state, not evidence for HEAD. These are non-blocking documentation ambiguities. The survey's final generic “positive control” wording at `1174` must retain its earlier explicit complement/equality/arbitrary-LESS qualifications, not be promoted to a calibrated reference test.

**CONFIRMED bounded terrain experiment.** All **19** newest retained native/reference pairs have byte-identical decompressed PPMs, independently checked. The gate-selected draw-3384 frame is 1920×1080 with **26,116** non-background pixels and zero mismatches. This is synthetic input through Voxy's real terrain pipeline on Minecraft's device, with the experiment clearing Minecraft's frame/depth. It is not streamed LoD input, depth coexistence, ordinary-play integration or lifetime/pressure acceptance. The current native stage still says `BLOCKED_UNIMPLEMENTED`.

**CONFIRMED bounded depth-copy result.** Replay independently reads the retained 1708×960 P5 image: **1,639,680 quantised zeros**, histogram consistent, finite extrema/means/cleared summaries consistent. The report's original float extrema say 0.0, but the retained quantised image cannot reproduce original float bit patterns. A uniform image can be a legitimate cleared/flat depth field; uniformity alone is not proof of a broken copy. The later structured isolated ladder samples make nonzero attachment values plausible/observed under its source mechanism in different launches. They do not identify why the copy was zero or prove the two launches/frames held the same scene/depth. The copy's separate isolated run is still not retained, as the survey admits. No Z convention was measured by either experiment.

**REFUTED complete survey restraint**, for the specific surviving loaded-terrain assertion and creation-test guarantee in §1/§3. The corrected cleared-value, cross-column range, unretained screenshot and complementary-control passages are confirmed. Exact measurements should retain their launch/sample/source prerequisites and coarse provenance limit; the review does not promote cloud/terrain silhouettes to same-pixel scene-depth reference measurements.

## 5. Safety for normal play

**CONFIRMED: I would ship these changes dormant on the established supported Voxy GL backend**, subject to ordinary release checks. **CONFIRMED: I would ship them dormant alongside Minecraft's Vulkan backend as disabled Voxy**, not advertise functioning native Voxy LoD. These are source/test-supported judgments about this change, not freshly observed normal-play sessions or whole-project production certification.

With `voxy.native.probe/.marker/.features/.adopt/.terrain/.depth/.depthladder` all unset:

- Ladder returns before device/target/GPU access (`McNativeDepthLadder.java:208`); no-instance shutdown and immediate-shutdown return before acquiring/waiting on a device (`769–794`). Its static state is bounded Java bookkeeping.
- Marker, terrain, depth and environment entries are flag-guarded (`McNativeMarkerDraw.java:273`, `McNativeTerrainProbe.java:185`, `McNativeDepthProbe.java:99`, `McNativeVulkanProbe.java:296`). Feature augmentation returns the original request without its flag (`McNativeDeviceFeatures.java:78`).
- Adoption does bounded bookkeeping and returns (`McNativeVkContext.java:92–100`); release returns before device waits if no adoption occurred (`287`).
- Tail and close hooks only reach those guards (`client/mixin/minecraft/MixinLevelRenderer.java:37–39,54–62`). `VoxyClient.java:82–116` preserves supported GL selection and disables Voxy when Minecraft has no GL context. The dormant diagnostics add no native renderer.

**CONFIRMED ladder cannot act unflagged. CONFIRMED ladder itself does not clear or write Minecraft depth even flagged.** It LOADs both attachments and all three current pipeline states disable depth writes, bounds and stencil; the state-helper mutation test detects enabling writes. It writes colour and uses Minecraft attachment/transfer machinery, so this statement does not mean “no image transitions.” Separately flagged marker/terrain probes can modify depth, which is why their isolation is necessary. Actual hardware behaviour under arbitrary driver defects or a future changed pipeline is not certified by source inspection.

## 6. What could not be checked and why

Minecraft/native/live execution was expressly prohibited. No new live mixin application, normal GL visual parity, ordinary play, pressure/device-loss retirement, world transitions/reload stability, native scene coexistence or convention experiment was performed. Permitted offscreen JUnit results do not replace those.

Full original ladder readbacks are not retained: the quarter-scale RGB thumbnails preserve block means, not alpha, original individual pixel order, boundary pixels or overwritten scene colours. Full screenshots are not retained either. Consequently exact re-extraction, independently authenticating an arbitrary consistently forged set, and same-pixel comparison to pre-ladder Minecraft geometry/depth are unavailable. Quantised depth cannot reproduce original floats. The depth-only isolation experiment is unretained. Dependency/runtime binaries are not all source-fingerprinted, and native synchronization-validation activation was not newly established.

The JVM call-site mutation proves its regression-test escape without executing mutated pipelines; it does not show a defect in the actual inspected creation source. Synthetic fixture flips establish only the specific misses named. All blocking and non-blocking observations are bound to this checkout/HEAD. No fix, promotion, merge, push, publication or Minecraft launch occurred.

Review reproduction artifacts are under `.agent-run/`: `r10-attacks.{py,json,log}`, `r10-extra.{py,json,log}`, `r10-evidence.py`, `r10-independent-evidence.{json,log}`, `r10-bytecode.{py,json}`, `R10LadderAgent.java`, `R9TestRunner.java`, `r10-classpath.{gradle,txt}`, bytecode logs, `r10-test-escapes.{py,json}`, the inspection PNGs, and test logs. Final machine verdict: `.agent-run/native-integration-review.json`.
