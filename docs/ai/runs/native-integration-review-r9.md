VERDICT: REDESIGN

Independent round-9 review of **c05e0a94cb2318e94fdfb385880d633a7285983c**, in `/Users/xyz/orca/workspaces/voxy/native-review-r9`, branch `xyz742834-sys/native-review-r9`, 2026-10-07. HEAD matched and the initial tree was clean. No implementation, fixture, threshold, living specification or committed evidence was changed. No Minecraft, native/live harness or interop runner was launched. Supporting review experiments are in `.agent-run/` and mutate temporary evidence copies or JVM-loaded bytecode only.

The rounds 4–8 boundary remains: **an independently accepted diagnostic foundation would still be REDESIGN for the delivery target**. This review does not accept that foundation. The per-pixel draw mathematics, actual retained colour counts, retained isolated launch and decimal terrain-device repair are confirmed. Evidence completeness, orientation provenance and some survey claims remain refuted.

## 1. Each round-8 residual

“Closed” below assesses the specific round-8 defect, not every possible assertion about that subsystem.

| Finding | Judgment of repair | Closed | Evidence |
| --- | --- | --- | --- |
| B1 | **REFUTED as complete; named core-membership/capture repairs CONFIRMED** | No | Core entry-removal attacks fail, but any nested file named `MANIFEST.json` is exempt; removing fingerprint/log plus its entry still passes. |
| R6-TERRAIN-GATE | **CONFIRMED** | Yes | Equivalent decimal identity works; decimal plus foreign terrain identity fails; absent identity fails. |
| R8-LADDER-MECHANISM | **CONFIRMED for replacing different pixel sets with one field measurement** | Yes | All draws share one band, ascending LESS thresholds and disabled depth writes. The new control has the qualifications in §2; it is not an independent calibration of arbitrary broken LESS code. |
| R8-LADDER-GATE | **REFUTED as complete; round-8 NaN/extent/checkpoint attacks CONFIRMED repaired** | No | Coordinated orientation lies and omission of retained samples pass. |
| R8-LADDER-BOUND | **CONFIRMED for removing the prefix/80%-fill/common-depth inference** | Yes | Current report/gate emits per-pixel histograms, rejects bound keys and has no prefix test. The survey still has unsupported claims (§4). |
| R8-LADDER-CONCLUSION | **CONFIRMED for retracting hook-wide coexistence impossibility** | Yes | Explicit withdrawal at survey:1011–1013. No new hook-wide impossibility follows from this measurement. Other copy-attribution prose remains too strong (§4). |

### B1: membership and capture association

**CONFIRMED** `scripts/verify.py:2019–2035` checks hashes and membership of ordinary retained files. Removing each manifest entry while retaining its file returns **1** for: marker report; terrain report; adopted proof; summary; source fingerprint; main log; ladder report; ladder own result; ladder log; selected marker sample; rejected marker sample; terrain reference. An additional ordinary unlisted file also returns **1**. Commands/results: `python3 .agent-run/r9-attacks.py`, `.agent-run/r9-attacks.json`.

**CONFIRMED** selected and rejected marker naming checks at `verify.py:575–580,672–677`. Changing `readback.sampleAtDraw` to 2 while retaining the draw-3362 sample returns **1**. Pointing the rejected crop at the draw-2 name also returns **1**. Those are the requested capture-association repairs; names/counts alone cannot independently prove that pixel bytes came from that draw.

**REFUTED complete membership (B1 residual):** `verify.py:2027` excludes by **basename**, not by the one root manifest path. Add `ladder/MANIFEST.json` containing arbitrary unbound text, without adding an entry: **replay 0**, all nine checks, all 18 samples. The stated “every retained file” contract is false. No actual committed authoritative file is unlisted.

**REFUTED required evidence completeness (B1 residual):** separately delete `source-sha256.json`, `native.log`, or `ladder/native-ladder.log` **and its manifest entry**: each **replays 0**. Membership of surviving files is not a required-artifact inventory. Replay never requires the fingerprint, so it can return green without retained source/candidate binding. Log scanning is explicitly outside replay; nevertheless green replay cannot stand for a complete retained diagnostic foundation with the logs missing. Reproduce: `python3 .agent-run/r9-extra.py`, `.agent-run/r9-extra.json`. By contrast, deleting the enabled depth or ladder report and its entry now returns **1**, as claimed (`verify.py:2064–2066,2095–2097`).

### R6-TERRAIN-GATE: the decimal attack

**CONFIRMED** `device_handle_of` (`verify.py:867–884`) parses both spellings and raises on absent/unparseable identity; replay calls it at `2082–2084` before terrain acceptance.

| Mutation | Replay |
| --- | --- |
| Adopted `0x75edf80018` → equivalent decimal string; otherwise unchanged | **0** |
| Same decimal adopted identity, terrain device → `0xdead` | **1**, terrain-device disagreement |
| Adopted device field removed | **1**, missing device |

The specifically requested vacuous comparison is closed. Actual terrain, adopted and lifecycle identities agree. The ladder separately names `0x75c85a8018`, as expected for its second process.

## 2. Per-pixel ladder

### Mechanism and positive control

**CONFIRMED conditional mechanism:** `McNativeDepthLadder.java:269–280` opens colour and depth with empty clear options (LOAD); `293–324` draws ALWAYS white, GREATER grey at `z₀`, then eight ascending LESS thresholds over precisely the same rectangle. Shared shaders (`McNativeMarkerDraw.java:78–113`) use `gl_Position.z=depth`, `w=1`, and write the pushed colour. The viewport is `[0,1]` in depth; blending is disabled. `depthStencilState` at `609–613` enables testing and disables depth writes/bounds/stencil, and is passed to all three pipelines at `643,659`; creation supplies LESS/ALWAYS/GREATER at `566–571`.

For a stable finite attachment value `d` and the shown pipeline/shader execution:

- `d < z₀`: GREATER leaves grey, every LESS draw rejects.
- `d = z₀`: **white**, because GREATER and all LESS comparisons correctly reject.
- `zᵢ < d ≤ zᵢ₊₁`: the last passing LESS draw is colour `i`.
- `d > z₇`: colour 7 remains.

Thus the rung colour really encodes `max{i:zᵢ<d}` where that set is nonempty. This fixes the spatial-confound defect; there is no inference across different columns. Comparison semantics were checked against [Khronos VkCompareOp](https://docs.vulkan.org/refpages/latest/refpages/source/VkCompareOp.html).

**CONFIRMED complementary-control role; REFUTED unconditional positive-control wording:** GREATER makes the genuinely low-depth case distinguishable from both tested draws failing. But survey:1107–1108 says GREATER “must pass” wherever every LESS fails; exact equality disproves it. White is a reason to reject the measurement, not proof that the depth path is broken. The Java class comment correctly mentions the equality exception (`41–55`); the survey and gate failure text omit it. Zero anomaly in the retained samples means that this ambiguity did not appear there.

**REFUTED a general assertion that clean output proves Minecraft depth was measured.** A LESS pipeline accidentally constructed as ALWAYS, or depth testing disabled, leaves violet everywhere; the gate accepts those colours as `d>z₇`. Repainting every selected crop violet, changing its counts accordingly and refreshing hashes **replays 0** (`r9-extra.json`). This is a counterexample to control completeness, not proof that this checkout uses broken state: the actual source state is correct and retained samples have spatially varying colours. Similarly, GREATER reaching low pixels does not prove a missing LESS path worked on those pixels. Wrong/cleared attachment contents or wrongly delivered shader depths are additional prerequisites the complementary colours alone cannot calibrate. The gate cannot distinguish those possibilities from some valid depth fields without independent reference evidence.

**CONFIRMED colour classification for the actual raw crops; arbitrary final composition robustness unproven.** Python and Java use separated three-level channel ranges (`verify.py:821–840`, `McNativeDepthLadder.java:460–485`). Independent direct P6 decoding finds every selected pixel at an **exact** expected RGB palette value, including grey `(128,128,128)`. There is no reliance on screenshot colour fidelity. Copy is requested from the colour texture immediately after the pass (`329–345`), before later screenshot composition. Nonlinear transformations of middle channels could move them into another code or a gap; the quantizer is not a proof against arbitrary final composition. The retained raw images do not exhibit that problem.

### Isolation and identity

**CONFIRMED retained isolation and own-launch identity**, within recorded/source evidence. The recorded second-launch command has `.features`, `.adopt`, `.probe`, `.depthladder` and no terrain/marker/depth-copy flags. The report says terrain/marker disabled and draws zero. Both log and own result are retained. Current launch construction at `verify.py:2308–2345` matches that separation. The main and ladder devices differ, and the ladder gate is tied to its own eleven checkpoints and extents `{1708×960,1920×1080}` (`2104–2115`).

Each terrain/marker flag or nonzero count mutation returns **1**. Foreign ladder identity returns **1**. Changing either checkpoint copy alone returns **1**; changing both copies consistently to foreign device 77 still returns **1** against the unchanged ladder device. Removing own result is a source-confirmed failure (`2104–2106`). These replay results support the requested isolation/device repairs; they do not authenticate an arbitrary jointly forged report/log/checkpoint set or independently exercise Minecraft again.

### Gate mutation table

Mutations below run against temporary copies of the committed newest directory with modified-file hashes refreshed, so failures are acceptance failures rather than stale-hash failures.

| Attack | Replay | Judgment |
| --- | --- | --- |
| NaN in rung depths, band, palette; NaN sample at/extent/rejectedOther | **1** | **CONFIRMED** ladder finite/type checks |
| Self-consistent 80×100 frame, 48×8 selected/opposite crops and matching counts | **1** | **CONFIRMED** own-checkpoint extent pin |
| Balanced low→rung count transfer with unchanged pixels | **1** | **CONFIRMED** exact recount |
| Flip booleans, leave rectangles unchanged | **1** | **CONFIRMED** rectangle consistency |
| Flip booleans **and recompute both rectangles**, unchanged crop bytes/names/counts | **0**, 18 samples | **REFUTED** independent orientation provenance |
| Reverse samples | **1** | **CONFIRMED** strictly increasing capture counts |
| Remove 17 samples from report, leave all 36 crops retained and manifest-listed | **0**, one sample | **REFUTED** replay of every retained sample |
| Same removal, corrupt omitted draw-242 crop and rehash it | **0**, one sample | **REFUTED** completeness, even for malformed retained pixels |
| Rename first crop pair/count to draw 3 and update membership | **0** | Sampling schedule/capture count is not independently bound |
| Ordinary unlisted file or any tested ordinary core entry removal | **1** | **CONFIRMED** ordinary membership |
| Unlisted nested `ladder/MANIFEST.json` | **0** | **REFUTED** exhaustive membership |
| Foreign device / own-summary mismatch / terrain or marker contamination | **1** | **CONFIRMED** tested identity/isolation gates |
| Bound key or measured-convention claim | **1** | **CONFIRMED** scope refusal |

The orientation hole is concrete: draw 2 originally states `[342,576,1366,653]`, flipped=true. The passing lie states `[342,307,1366,384]`, flipped=false, keeping the same selected PPM. Both are 1024×77; the rejected rectangles also have equal dimensions. `recount_ladder_sample` (`verify.py:1070–1116`) knows only the cropped bytes and size, not their independently anchored position in a full frame. It trusts the producer's extraction/origin assertion. Checking the opposite crop's palette exclusion identifies which **supplied crop** looks drawn; it cannot establish where either crop came from. Full original readbacks are not retained.

Likewise `ladder_report_checks:975–1067` iterates only `report.samples`. Neither manifest sample inventory nor retained/logged capture inventory is reconciled with that list. The unmodified report does contain all 18 successful samples, confirmed against the log; the gate can silently stop checking 17 of them. Source requests readbacks at draw 2, then 240 draws after each request, guarded by in-flight/failure/sample limits (`282–285,110–114`). Exact spacing can legitimately slip when a callback is still in flight; increasing counts and names alone do not prove the retained capture list is complete. Neither `readbackInterval` nor `sampleLimit` is gated.

### Retained counts and claims

**CONFIRMED** independent exact-RGB recount of all **18 selected** P6 crops and inspection of all opposite crops. Every selected crop has anomaly=other=0. Selected dimensions are 1024×77 before resize, 1152×86 afterwards. In the unmodified committed directory the quantified claims are correct:

| Capture | Low (`d<2⁻¹⁶`) | Nonzero rung bins |
| --- | ---: | --- |
| 2 | 42,825 | r0=28,131; r1=7,892 |
| 242 | 14,139 | r2=64,709 |
| 1202 and 1442 | 78,848 each | none |
| 3122 | 14,439 | r2=84,633 |
| 3602 | 80,030 | r3=2,738; r4=16,304 |

Remaining counts agree exactly too, retained in `.agent-run/r9-independent-evidence.json`. The new crop is recognizably a cloud-shaped silhouette; the older re-rung crop visibly contains clouds over sky, with magenta on cloud geometry. The raw per-pixel crop alone does not retain the overwritten scene colours. Separate screenshot band crops provide scene context but are later captures, not same-draw proofs for every numbered sample.

**REFUTED** exact-clear attribution and unrestricted scene/copy causality; see §4. **CONFIRMED** that no Z direction was measured and no bound/convention is emitted by the current report/gate.

## 3. Tests and fixture discipline

Fresh requested commands:

| Command | Independent result |
| --- | --- |
| `./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true` | **BUILD SUCCESSFUL**, 331 cases: 330 passed, one documented `VkBarriersTest.missingBarrierIsDetected()` skip, zero failures/errors. |
| `python3 -m unittest discover -s scripts/tests` | **149 cases, OK**, 220.407 s. |
| `python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261007T020025-032770Z` | **0**, nine checks, 18 ladder samples. |

JUnit XML confirms validation=true and syncValidation=true on the offscreen Voxy-owned device. The intentional fill-buffer WAW negative control emitted the expected hazard; no other `[vk-validation]` diagnostics occurred. A loader/layer warning about deprecated settings appeared in the Gradle output. Green offscreen tests do not prove Minecraft-device synchronization, hooks or coexistence.

**CONFIRMED bytecode-flip repair.** `python3 .agent-run/r9-bytecode.py` uses an instrumentation agent on JVM-loaded classes, without changing checkout class files or touching a GPU. Normal ladder tests: **5 pass**. Flip the `depthWriteEnable(false)` argument in the actual state helper to true: **4 pass, 1 fail**, specifically `everyPipelineTestsDepthWithoutWritingIt`. That test examines the actual helper's state for LESS/ALWAYS/GREATER (`McNativeDepthLadderTest.java:52–69`); the prior constant-JSON escape is closed for this mutation. The other four tests remain green against this deliberately broken state and are not write-state regressions.

**CONFIRMED remaining test escape:** change the actual creation call's LESS compare op to ALWAYS (`McNativeDepthLadder.java:566–567`) while leaving the helper and `compareOps()` intact: **all five tests pass**, including `everyPipelineTestsDepthWithoutWritingIt`. Source inspection confirms the unmodified creation code is correct. The test iterates a separate operator list; it does not inspect what `create()` passes or execute draw ordering/bindings/push payloads. Logs: `.agent-run/r9-bytecode-{normal,write,createLessAlways}.log`.

**CONFIRMED improvement in Python fixture meaning:** `test_ladder_gate.py:48–88` now constructs depth values and derives final colours by strict comparisons. Its gradient covers all eight bins; no cross-column prefix/common-depth fixture remains. The zero and cloud fields are useful finite known-input cases. The depth-probe vacuous assertion is gone (`test_marker_gate.py:1129–1137` now requires completed-copy/readable and explicitly unmeasured convention).

**REFUTED coverage of producer-controlled orientation/source references:** the ladder fixtures generate selected crops directly from the field and opposite crops as uniform synthetic scene (`test_ladder_gate.py:91–115`); they do not extract both from one independently retained full frame. `test_lying_about_the_orientation_fails:241–246` changes booleans only and tests a rectangle mismatch. Updating both rectangles makes the unchanged unshrunk fixture pass. `test_a_gradient_field_is_bracketed_pixel_by_pixel` also passes when the Python gate's depth constants are all doubled in memory, because `D`, report and field palette reference the gate constants. That positive case is not independent evidence that those thresholds agree with Java. Results: `.agent-run/r9-python-escapes.json`.

**CONFIRMED no fixture shrinking.** Existing marker and ladder frame extents remain **960×540**; the marker pass cell remains **960 pixels**, safely above the 500 floor (640×360 would give 448). Ladder band is **576×43** in both orientations, large enough for all bins. Size is adequate; shape is the weakness: equal crop sizes and crops without full-frame provenance allow the coordinated orientation attack. The 48×32 depth-copy fixture has one-row mean bands (`test_marker_gate.py:1073–1095`) and derives summary statistics from already quantised values, so it cannot establish original-float histogram/quantisation-boundary correctness. No fixture or test was changed during review.

**Additional blocking R9-DEPTH-FINITE:** newest evidence with `topMean`, `bottomMean`, `clearedValue` or `clearedShare` changed individually to NaN, hashes refreshed, **replays 0** in every case (`r9-attacks.json`). `verify.py:1266–1269` checks numeric type but not finiteness; `1348–1360` uses `abs(NaN-x)>tolerance`, which is false. This does not reopen the specific round-7 three-contradiction closure, but it refutes the new survey's claim of NaN refusal everywhere and prevents accepting the overall fail-closed evidence gate. The ladder's required numerical fields do reject NaN.

## 4. Survey strength, ten runs, terrain and depth

**CONFIRMED** complete ten-run table (`survey:447–458`), independently replayed:

| Run | Exit / first refusal |
| --- | --- |
| 20261006T070311-099413Z | 1, missing closeFailures |
| 20261006T073859-396220Z | 1, missing leakedPipelines |
| 20261006T080931-279390Z | 1, missing leakedPipelines |
| 20261006T083136-838975Z | 1, opposite orientation sample absent |
| 20261006T084300-332469Z | 1, opposite orientation sample absent |
| 20261006T095436-054038Z | 1, depth lacks zConventionMeasuredHere |
| 20261006T165039-062378Z | 0, explicitly no ladder replay |
| 20261007T005606-024013Z | 1, ladder lacks palette/per-pixel schema |
| 20261007T011026-211934Z | 1, same schema refusal |
| 20261007T020025-032770Z | 0, per-pixel ladder included |

Results: `.agent-run/r9-all-replays.json`. Non-replaying historical runs retain readable pixels; failure under today's schema does not erase their bounded observations.

**CONFIRMED retained binding in the actual newest directory:** **177 manifest files** match their hashes; all **408 source/build/script fingerprints** match this reviewed checkout; changed_sources_during_run is empty. Recorded revision and final_revision are **25112164ca7d1483dbb1064b78a1d99c9a6e890b**, not reviewed HEAD. Matching fingerprints bind the candidate's relevant source to the run, without pretending c05e0a94 was the recorded launch commit. Logs identify Minecraft Vulkan, Apple M4 Pro/MoltenVK and layer insertion. Both retained logs have zero Vulkan validation diagnostics. Native adoption reports `syncValidation=false`; native synchronization-validation activation is not independently established by the offscreen JUnit setting.

**CONFIRMED terrain experiment:** all **19** newest retained native/reference PPM pairs have byte-identical decompressed images. The report-selected draw-3384 frame is 1920×1080 with 26,116 non-background pixels and zero RGB mismatches. This remains synthetic scene agreement through the real terrain renderer on the adopted device, with Minecraft's frame cleared by the experiment. It does not establish actual streamed LoD input, Minecraft terrain coexistence, alpha/depth parity, normal-play integration or pressure/lifetime stability.

**CONFIRMED bounded depth-copy observation:** retained 1708×960 16-bit depth has **1,639,680 quantised zeros**. This does not prove original float bits were exactly zero. Its device is now tied to main-launch checkpoints; finite contradictory cleared-value/share summaries fail. The depth-only observation still has no retained isolated launch, as the survey correctly admits. The ladder's isolated launch does not retroactively isolate that copy. No failure mode of the copy is established.

**CONFIRMED retraction:** survey:1011–1032 withdraws the universal earlier-hook requirement and distinguishes source facts from measured Z direction. That specific round-8 conclusion is closed. **REFUTED the paragraph's “both checkable” wording for the former terrain frame:** its screenshots are explicitly not retained (`1014–1018`), and later crops do not recover them.

**CONFIRMED local clouds reading; REFUTED stronger claims in survey:1043–1054.** The re-rung raw crop visibly supports “clouds over sky in this band,” and LESS-surviving pixels support nonzero attachment depth at those tested pixels under the inspected source mechanism. It does not establish no terrain was loaded anywhere in the frame/world. Nor can column 0 passing and column 1 mostly rejecting put one cloud pixel in `(2⁻¹⁶,2⁻¹⁴]`; those are different pixel sets, precisely the round-8 defect. The paragraph still uses that two-sided range to attribute distance and says the first run is “explained.” Those inferences are unsupported. Differing launches, captures, sample conditions and an unretained isolated copy prevent identifying the copy as the demonstrated broken component; calling it a suspect and leaving its failure mode unresolved is reasonable. The former prefix/band-bound/gate prose (`973–1009,1034–1039,1072–1086`) is contradicted by the newer repair section, so it must be read as refuted history, not surviving proof.

**REFUTED current per-pixel overstatement (R9-SURVEY-OVERCLAIM):** survey:1135 equates every low pixel at draws 1202/1442 with **“the cleared value.”** Grey encodes `d<2⁻¹⁶`, not `d=0`; positive sub-threshold scene depths produce the same grey. The numeric histograms are correct, but that attribution is unmeasured. Scene labels (“sky,” “near walls,” “chunks not yet loaded”) are interpretations, not independently same-capture scene/reference comparisons for every sample. The cloud silhouette and separate scene crops make the interpretation plausible without certifying all of it. Conditional distance illustrations do not measure convention. Also, the statements “orientation is identified from pixels,” “NaN is rejected everywhere,” and replay of every retained crop exceed the tested gate for the concrete attacks above (`1111–1124,1135`).

**CONFIRMED honest limits** at survey:1137–1141: the intended output is one band's depth brackets, no measured nearer direction and no identified copy failure. **REFUTED the claim that the survey as a whole says no more than the retained evidence.** Those limits do not erase its specific stronger statements.

Non-blocking documentation drift: survey:12 still says nothing is implemented. Handoff is headed with the round-8 SHA, still reports 329/148 tests and eight runs, describes the prior prefix experiment as next work, and repeats its old band bound (`handoff:3–4,89–95,123–144,191`). Current-state:52–58 also repeats the refuted band-wide bound. The new survey section is materially more current; none of these documents supersedes the explicit unaccepted/experimental boundary.

## 5. Safety for normal play

**CONFIRMED: I would ship these changes dormant on the established Voxy GL backend**, subject to ordinary release checks. **CONFIRMED: I would ship them dormant with Minecraft Vulkan as disabled Voxy.** This is a source/test-supported dormant-safety judgment; I would not advertise functioning native Voxy LoD, and no fresh normal-play session was run.

With `.probe`, `.marker`, `.features`, `.adopt`, `.terrain`, `.depth`, `.depthladder` all unset:

- Ladder returns before device/target/GPU access (`McNativeDepthLadder.java:196–197`); null-instance shutdown returns before acquiring a device (`709–713,726–729`). Tail/close hooks (`MixinLevelRenderer.java:37,54–62`) add bounded Java calls.
- Marker, terrain, depth-copy and environment probe all guard their entry (`McNativeMarkerDraw:273`, `McNativeTerrainProbe:185`, `McNativeDepthProbe:99`, `McNativeVulkanProbe:296`). Feature augmentation returns the original request without its flag (`McNativeDeviceFeatures:78`).
- Unflagged adoption sets bounded bookkeeping then returns (`McNativeVkContext:92–100`); release returns before device waits when not adopted (`287`).
- Existing backend choice preserves supported GL and disables Voxy on Minecraft Vulkan with no GL context (`VoxyClient.java:82–116`). No native LoD renderer is enabled.

**CONFIRMED even when flagged: the ladder itself never clears or writes Minecraft's depth.** LOAD pass, all three pipelines' shared write-disabled state, and the successful helper-flip regression establish the source contract. It writes colour and invokes Minecraft's attachment/transfer machinery; this is not a claim of no image-state transitions. Other separately enabled probes can modify depth, explaining the isolation requirement. Fresh GPU write-state/lifecycle behaviour was not tested here.

## 6. What could not be checked, and why

Minecraft/native/live execution was expressly prohibited. This review cannot newly certify live mixin application, scene/depth convention, command submission/lifetime under device loss, pressure, reload, rapid travel or ordinary play, GL visual parity, or native coexistence. It inspected/recounted committed evidence and permitted offscreen tests instead.

Full original colour readbacks and screenshots are not retained; independently re-extracting crops, their absolute orientation and exact scene association is impossible from these crops alone. Histograms preserve counts but do not by themselves prove corresponding Minecraft geometry. Quantised PGM cannot establish exact original float bits. Dependency/environment binaries are not all fingerprinted. Native synchronization-validation activation remains unestablished. The projection/distance illustrations were not promoted to independently measured physical ranges. JVM transformations and fixture mutations prove their specific test/gate escapes; they do not run the mutated renderer on hardware.

Reproduction artifacts: `.agent-run/r9-attacks.{py,json,log}`, `r9-extra.{py,json,log}`, `r9-all-replays.json`, `r9-independent-evidence.json`, `r9-python-escapes.json`, `r9-bytecode.py`, `R9LadderAgent.java`, `R9TestRunner.java`, `r9-classpath.gradle`, and bytecode logs. Requested final machine verdict: `.agent-run/native-integration-review.json`. No fixes, promotion, merge, push or publication occurred.
