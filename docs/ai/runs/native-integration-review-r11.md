VERDICT: REDESIGN

Independent round-11 review of `867cd25d845eab4eef771fe8a479df5089200ad8`, branch `xyz742834-sys/native-review-r11`, in `/Users/xyz/orca/workspaces/voxy/native-review-r11`, 2026-10-07. Initial HEAD matched the request and the tree was clean. No implementation, committed evidence, fixture, threshold or living specification was changed. Experiments used temporary evidence copies, separate Python processes and JVM instrumentation. No Minecraft/native/live stage was launched.

The rounds 4–10 boundary remains: even an independently accepted diagnostic foundation is **REDESIGN** for the delivery target. This candidate correctly does not claim an accepted layer. There are also two narrower remaining review blockers, independent of that delivery boundary.

| Candidate claim | Judgment |
| --- | --- |
| (a) All round-10 blocking findings are repaired | **REFUTED as complete.** The original empty fingerprint and moved creation-call attacks fail, but incomplete source inventories still pass and the production creation callback remains outside the six ladder tests. The loaded-terrain sentence is repaired. |
| (b) Per-pixel measurement, isolation, block-grain crop provenance and sample inventory are retained and replayable here | **CONFIRMED for the actual retained package.** Its complete 408-entry fingerprint independently matches this checkout; all 18 histograms, both orientations and 186,450 complete thumbnail blocks agree. This does not certify that the replay gate requires an equally complete fingerprint from another package. |
| (c) The survey claims no more than retained evidence, with limits stated | **REFUTED as a whole.** The withdrawn sentence and disclosed provenance/forgery limits are sound. The assertion that replay succeeds only against the sources a run was built from exceeds the source inventory check; one old run is still incorrectly tabled as replaying. |

## 1. Each round-10 finding

### B1 — CONFIRMED original repair; REFUTED complete source-binding closure

`python3 .agent-run/r11-extra.py`, `empty_source_fingerprint`: replace the fingerprint by `{}`, refresh its manifest hash, and replay returns **1**, “names only 0.” A valid-format wrong digest for `McNativeDepthProbe.java` returns **1**; an entry naming a nonexistent different file returns **1**. A 100-entry fingerprint of existing unrelated files, without the four required names, returns **1**. Thus parsing, required names, digest syntax and comparison of **listed** files work (`scripts/verify.py:2087–2116,2199–2216`).

**Blocking residual B1:** replay does not require the fingerprint's key set to equal the checkout's source inventory. It only requires at least **100** map entries and four names, then compares those entries. Reproduce with `python3 .agent-run/r11-source-attacks.py` and `python3 .agent-run/r11-extra.py`:

| Fingerprint replacement / alteration, manifest refreshed | Replay |
| --- | --- |
| Four required source/build/runner names plus 100 existing unrelated files under `docs/` | **0**, 18 samples; 104 entries, **404 of the 408 actual source entries absent** |
| Remove only `src/main/java/me/cortex/voxy/client/core/vk/mcnative/McNativeDepthProbe.java` from the real fingerprint | **0**, 18 samples |
| Inflate the four required files to 103 names using `./` aliases of `scripts/verify.py` | **0**, 18 samples; only four distinct files |

These replace or erase the binding artifact without rewriting measured crops, report, thumbnails or ladder log. They are the narrower semantically incomplete-fingerprint escape, rather than the disclosed attack that forges every mutually consistent GPU record. The hash comparison still binds every *listed* path to current bytes. It does not bind omitted sources or require unique, normalized repository-relative names. `SOURCE_BINDING_MINIMUM = 100` is not a requirement for hundreds of distinct source entries. `source_fingerprints()` at `verify.py:308–312` already defines the fuller inventory, but `source_binding()` never compares key sets with it.

**CONFIRMED actual package binding independently:** all 195 manifest members have matching hashes; all **408** fingerprints match HEAD's file bytes, and the fingerprint key set equals the current selected source/build/script inventory (`.agent-run/r11-independent-evidence.json`, `.agent-run/r11-retained-details.json`). The actual package is complete; the gate's acceptance condition is incomplete. Recorded launch and final revisions are `b9c3754ba524dfc3cfffe16cd1a11c1b4b4b644d`, with `changed_sources_during_run: []`, rather than reviewed HEAD; matching fingerprints provide the byte-level source correspondence.

**CONFIRMED binding limits:** even the actual full fingerprint covers selected `src/`, `scripts/`, `build.gradle` and `gradle.properties` bytes, not commit identity or proof that the running class files were produced from them. It does not fingerprint dependency jars, Minecraft/Sodium binaries, Gradle caches, compiled artifacts, `settings.gradle`, the Gradle wrapper, JVM, driver/loader/layer binaries, machine environment or original GPU execution. The runner logs identify some of those but do not establish binary equivalence. Consistent forgery remains possible as explicitly disclosed.

### R10-CREATE-TEST — CONFIRMED original call-site repair; REFUTED coverage through the real creator

The actual builder is `McNativeDepthLadder.java:700–709`; `create()` uses it at `626–636`; `pipelineInfo()` attaches the state at `750`. The new test, `creationHandsTheCreatorTheTablesCompareOpsWithWritesOff` (`McNativeDepthLadderTest.java:89–117`), calls that builder and inspects all three create-infos through an injected creator. This is materially stronger than inspecting the table alone.

Reproduce with `python3 .agent-run/r11-bytecode.py`. Each non-baseline JVM log confirms exactly one class transformation. The runner invokes every `@Test` method and reports assertion outcomes; its process exit alone is not counted as success. No mutated GPU pipeline was created and production class files were untouched.

| JVM mutation in actual ladder bytecode | Passed / failed, out of six |
| --- | --- |
| Baseline | **6 / 0** |
| `depthStencilState`: writes false → true | **4 / 2** |
| `PIPELINE_COMPARE_OPS` initializer: LESS → ALWAYS | **4 / 2** |
| Original attack, moved into `buildPipelines` at its `pipelineInfo(..., PIPELINE_COMPARE_OPS[i])` call: substitute ALWAYS for LESS | **5 / 1** |
| Inside `pipelineInfo`, substitute ALWAYS for LESS at the `depthStencilState` call | **5 / 1** |
| Inside the **production** `info -> ... vkCreateGraphicsPipelines(...)` callback, substitute ALWAYS for LESS in the already-built depth-stencil state | **6 / 0** |
| Inside that production callback, enable depth writes in the already-built state | **6 / 0** |

**Blocking residual R10-CREATE-TEST:** the real callback at `McNativeDepthLadder.java:627–635` is replaced by the test's injected callback. An instrumentation insertion at the start of the synthetic `lambda$create$...` method is equivalent to `if (ds.depthCompareOp() == LESS) ds.depthCompareOp(ALWAYS);` before the real Vulkan call; another insertion calls `ds.depthWriteEnable(true)`. Both change exactly the state that Vulkan would receive and escape all six tests, including the new creation test. This is a remaining seam between the table and `vkCreateGraphicsPipelines`, as explicitly requested in the review brief.

**CONFIRMED current source state is correct.** The production callback currently forwards `info` without changing it, and the builder uses the intended table and writes-off helper. The experiments refute completeness of regression coverage, not the correctness of the unmutated inspected pipeline state or the retained colour measurements.

### R9-SURVEY-OVERCLAIM — CONFIRMED named sentence and stated-limit repair

`vulkan-native-integration-survey.md:1074–1077` now explicitly declines a claim about terrain loading elsewhere in the frame/world. The retained cloud-band crop supports the statement about that band only. The earlier “none was loaded in the sampled frame” assertion is withdrawn at `1187–1188`.

**CONFIRMED** the limits at `1191–1197`: only complete 4×4 blocks are anchored, boundary pixels and within-block permutations escape, and jointly rewritten editable records can pass without establishing GPU authenticity. The same-pixel GREATER control remains explicitly a **complement**, not calibration of arbitrary broken LESS (`1120–1124`). No surviving cloud/terrain-loading inference in the corrected survey requires proof about the rest of that earlier frame. This closes the named round-10 residual of R9-SURVEY-OVERCLAIM. The separate source-binding overstatement and stale replay-table cell are assessed below.

### Round-10 non-blocking findings

**R10-LOG-DETAILS: CONFIRMED repaired.** `verify.py:1004–1029` compares draw-number multiplicity and then the orientation plus all eleven counts on each sample line against the report. Independent parsing confirms all 18 actual log lines agree. Flipping orientation or individually changing `anomaly`, `low`, `r0` through `r7`, or `other` in the log returns **1** in all **12** full-replay cases (`python3 .agent-run/r11-log-attacks.py`). Changing the edge pixel/report without the log now also returns **1**. This is the specified sample-line reconciliation, not equality of every descriptive summary/manifest field or a trusted schedule record.

**R10-DEPTH-FIXTURE: CONFIRMED repaired.** `DepthProbeGateTest` now uses **48×100** (`test_marker_gate.py:1077–1080`). `band=1` fails `test_a_varying_image_is_accepted_without_a_convention`: published top mean `0.005050736247806519`, defective recount `0.0`. The other 14 depth tests pass that defect because they check other properties or use uniform input. The class as a whole no longer passes broken averaging. Fixtures were not shrunk.

**R10-ANCHOR-GRAIN and R10-CONSISTENT-FORGERY: CONFIRMED accurately disclosed limits, not purported repairs.** Exact demonstrations appear below. They do not refute the actual observations and do not require an unrequested signing system.

## 2. Per-pixel ladder and mutation table

**CONFIRMED mechanism and complementary control, conditional on inspected draw state.** The ladder LOADs colour and depth (`McNativeDepthLadder.java:280–284`). It draws white ALWAYS, grey GREATER at z₀, then the eight increasing LESS depths over exactly the same pixels (`312–331`), with depth writes disabled (`675–677`). The marker shader sets `gl_Position.z` from the supplied depth and does not write fragment depth. For a valid finite attachment value, the final colour encodes `d < z₀`, `zᵢ < d ≤ zᵢ₊₁`, or `d > z₇`. Equality at z₀ leaves white and is refused. This is not a measured near/far convention, a known scene-distance reference or a calibration against arbitrary incorrect LESS behaviour.

**CONFIRMED every actual per-pixel count:** independent exact-RGB decoding, without calling the gate's classifier, reproduces all **18** selected histograms. Every sample has `anomaly = other = 0`; independent rejected-crop classification also matches. Samples are draws **2, 242, …, 4082**. Selected crops are 1024×77 through draw 2882 and 1152×86 from draw 3122. The report, filenames and launch log contain that same inventory. The full independent histogram table is `.agent-run/r11-independent-evidence.json`.

Representative newest-run counts (the survey's older paragraph is explicitly about `020025`, not these numbers):

| Draw | Low | Nonzero rung counts |
| --- | ---: | --- |
| 2 | 42,552 | r0 33,833; r1 1,693; r5 308; r6 462 |
| 242 | 13,303 | r2 65,545 |
| 962 | 11,485 | r2 67,363 |
| 1202, 1442 | 78,848 each | none |
| 3122 | 14,439 | r2 84,633 |
| 3602 | 80,030 | r3 2,738; r4 16,304 |

**CONFIRMED crop provenance at the disclosed grain:** both crops of each sample independently average to their stated thumbnail positions. There are **186,450** matching complete blocks across the 36 crops. Each 1024×77 crop has **1,328** unanchored boundary pixels; each 1152×86 crop has **2,304**. The implementation at `verify.py:1191–1214` uses floored channel means and skips partial blocks. The approximate “1300–2300” statement is accurate. Thumbnail visual inspection of draws 2, 242 and 3602 shows the cloud, hill and nether contexts, while the ladder overwrites the tested scene colours. No individual-pixel positional authentication or comparison to the overwritten scene/depth is implied.

**CONFIRMED retained isolation:** the second-launch command enables probe/features/adoption/ladder, with marker, terrain and depth-copy flags absent. Its report has marker/terrain disabled and zero recorded draws. Its eleven checkpoints equal the summary's environment copy. Ladder device is `0x78f1d2c018`; the main launch/depth-copy device is `0x7c5970c018`. Replay uses the ladder launch's own device/extents (`verify.py:2288–2302`). Both logs show Khronos instance/device layer insertion and no matched VUID, validation-error/warning or synchronization-hazard lines. The adopted-context log records `validation=true syncValidation=false`; this is not proof of native synchronization-validation activation.

Run `python3 .agent-run/r11-attacks.py`, `r11-extra.py`, `r11-source-attacks.py`, and `r11-log-attacks.py`: **100** full-replay cases, with hashes refreshed except deliberate membership attacks. Complete results and refusal reasons are retained in their matching JSON files.

| Mutation group | Replay result |
| --- | --- |
| Unlisted nested manifest or ordinary file | **1** |
| Delete any of fourteen required artifacts together with its manifest entry | **1**; marker report absence **2** |
| Flip both orientations/rectangles, unchanged crops/thumbnails | **1** |
| Same, repaint thumbnails but leave log orientation unchanged | **1** |
| Omit 17 samples from report; or from report and files, unchanged log | **1** |
| Rename draw 2 without updating log; reverse report order; one-sided count transfer | **1** |
| All-violet crops plus counts, with or without changed thumbnail but unchanged log | **1** |
| Enabled depth writes/convention/terrain/marker, nonzero contamination counts, foreign device, band-wide bound claim | **1** |
| Every one of 27 depth NaN mutations and seven ladder NaN mutations | **1** |
| Producer-stated 80×100 extent, 1×1 crop, checkpoint-copy mismatch | **1** |
| Empty fingerprint, wrong listed hash, nonexistent listed path, unrelated fingerprint missing required names | **1** |
| Each of twelve sample-log detail mutations | **1** |

**Every mutation that replayed as 0:**

| Exact case name | What still passes / meaning |
| --- | --- |
| `omit_17_report_files_and_log` | Rewrite report, remove their 51 files and 17 log lines, refresh manifest: one sample passes. Disclosed coordinated inventory forgery; descriptive copies are not all reconciled. |
| `within_block_pixel_permutation` | Swap different colours at frame pixels (368,576) and (371,576), preserving counts and block mean. Disclosed loss of individual-pixel position. |
| `rename_draw_with_log` | Rename draw 2 and its three files to draw 3, update corresponding log draw: passes. Inventory is internally bound, not independently authenticated to a schedule. |
| `omit_depth_probe_source` | Remove one real source fingerprint entry: B1 residual. |
| `alias_inflated_fingerprint` | 103 names representing four distinct required files: B1 residual. |
| `four_required_plus_hundred_unrelated_files` | Required four plus unrelated hashed files, 404 actual source entries absent: B1 residual. |
| `edge_pixel_recount_report_and_log` | Frame pixel (342,576), magenta → violet, recount report and log, leave thumbnail unchanged: disclosed boundary gap. Without changing log this now fails. |
| `orientation_repainted_thumbnail_and_log` | Flip both orientations/rectangles and repaint corresponding thumbnail blocks, update log: disclosed consistent forgery. |
| `violet_crops_counts_thumbnail_and_log` | Paint selected crops all violet, update counts, thumbnail blocks and log: disclosed consistent forgery. |

**CONFIRMED the stated boundary/forgery limits; CONFIRMED the actual count/inventory/orientation gate within those limits.** The survey does not need excluded boundary-pixel anchoring or resistance to arbitrary consistent forgery to support the conditional histograms that were independently recounted here. **REFUTED complete source-bound replay acceptance** for B1. The literal “these records agree … with this checkout's sources” must be limited to listed sources; a passing package can omit almost all of them.

## 3. Required tests and regression escapes

| Command | Fresh outcome |
| --- | --- |
| `./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true` | **BUILD SUCCESSFUL**; XML: **332** tests, **1** skip, **0** failures/errors. Test task executed. |
| `python3 -m unittest discover -s scripts/tests` | **160 tests, OK**, 286.040 seconds. |
| `python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261007T025423-410921Z` | **0**, ten replay checks, 18 ladder samples. |

The documented skip is `VkBarriersTest.missingBarrierIsDetected()`: the deliberately unsynchronized descriptor-bound SSBO access produced no validation message. It limits conclusions from quiet validation. The offscreen context logs `validation=true syncValidation=true` and has an intentional buffer-hazard control; it is distinct from the retained Minecraft-adopted context. Passing these tests is not fresh Minecraft/native integration proof.

**CONFIRMED no fixture shrinking:** marker and ladder frame fixtures remain **960×540**, ladder band **576×43**; depth fixture grew to **48×100**. No fixture was changed during review.

**CONFIRMED literal depth pins:** in-memory doubling of ladder depths lets `test_a_gradient_field_is_bracketed_pixel_by_pixel` pass, because it shares constants, but fails `test_the_pinned_constants_are_the_literal_ones`. The suite detects this mutation. The depth `band=1` mutation is detected by the varying-image case as described above. `python3 .agent-run/r11-test-escapes.py` retains both outcomes.

**Tests that still pass broken code:** all six ladder JUnit methods, including `creationHandsTheCreatorTheTablesCompareOpsWithWritesOff`, pass both real-creator mutations in §1. Fourteen of the fifteen depth-copy cases pass `band=1`, but their class does not. The gradient positive ladder case alone passes doubled thresholds, but the independent literal pin does not. These distinguish an individual test's scope from an entire regression-suite escape.

## 4. Survey replay table, terrain and depth

**CONFIRMED all twelve retained run directories exist; REFUTED the table as entirely current.** Independent subprocess replay returns **1** for every older run and **0** only for `20261007T025423-410921Z`. The first seven fail the required ladder-source fingerprint entry; the next four fail comparison of five changed source/test files. Full commands/results are `.agent-run/r11-independent-evidence.json`.

`vulkan-native-integration-survey.md:456` still marks `20261006T165039-062378Z` **yes**. That cell is stale under this checkout's source gate, although the surrounding “newest only” rule at `441–445` is correct. The remaining historical “Replay returns 0” prose and older refusal reasons must be read as dated observations, not current successful replays. This is non-blocking table/documentation drift, not permission to discard old evidence.

**REFUTED complete source-binding restraint:** `1180–1182` correctly says every *listed* file is compared, but “only in the tree it was built from” promotes partial-list comparison to whole-source binding. B1's three passing mutations refute that promotion. **CONFIRMED** the description of shared builder/create-info test coverage at `1183–1186`, restricted to what the injected creator sees; it does not establish coverage of the actual callback after that injection seam. **CONFIRMED** the new loaded-terrain and block/forgery qualifications. The generic final “positively controlled” wording at `1201` retains the earlier complementary-control/equality/arbitrary-LESS limits; it is not an absolute calibration guarantee.

**CONFIRMED bounded terrain experiment:** all **19** newest native/reference PPM pairs are independently byte-identical after decompression. Gate-selected draw **3364**, 1920×1080, has **26,116** non-background pixels and zero RGB mismatches. This is synthetic input through Voxy's terrain pipeline on Minecraft's device, while the experiment clears Minecraft's frame/depth. It establishes neither streamed LoD nor coexistence or ordinary-play lifecycle/pressure acceptance. The stage still says `BLOCKED_UNIMPLEMENTED`.

**CONFIRMED bounded depth result:** independent P5 decoding finds **1,639,680 quantized zeros**, 1708×960, consistent with the finite report/histogram/band means. Original float bits are not retained. Uniformity alone cannot establish a copy failure: a cleared attachment also has uniform depth. The structured ladder samples observe nonzero values in a different launch/frame under the inspected mechanism; they do not establish what the earlier depth-copy frame contained or identify why the copy was zero. The depth-only isolation run remains unretained. Neither experiment measures the Z convention. Read the survey's dated negative copy conclusion as lack of a demonstrated scene-depth observation, not proof of impossibility or an identified failure mode.

**CONFIRMED handoff is stale, not current evidence:** `handoff.md:4` identifies the round-10 candidate, `87` repeats old counts and cleared-value/chunk-loading attribution, and `123–133` still describes dispatching/importing round 10. It is explicitly task state at `7`; it cannot certify HEAD's repaired survey or test totals.

## 5. Safety for normal play

**CONFIRMED: I would ship these changes dormant to a player on the established supported GL backend**, subject to ordinary release checks. **CONFIRMED: I would ship them dormant alongside Minecraft's Vulkan backend with Voxy disabled.** **REFUTED functioning native Voxy delivery:** the Vulkan player does not get Voxy LoD from this candidate. These are judgments about dormant source/test behaviour, not fresh normal-play observations or whole-project certification.

With `voxy.native.probe/.marker/.features/.adopt/.terrain/.depth/.depthladder` all unset:

- Ladder returns before device, target, GPU or file access (`McNativeDepthLadder.java:208`); its no-instance shutdown paths return before touching a device (`796,813`). Static state is bounded Java bookkeeping.
- Marker, terrain, depth-copy and environment entries have their flag guards (`McNativeMarkerDraw.java:273`, `McNativeTerrainProbe.java:185`, `McNativeDepthProbe.java:99`, `McNativeVulkanProbe.java:296`). Features return the original request (`McNativeDeviceFeatures.java:78`).
- Adoption does bounded bookkeeping and returns before device adoption (`McNativeVkContext.java:92–100`); release returns before waiting if no adoption occurred (`287`).
- Level-render/close hooks reach those guards (`MixinLevelRenderer.java:34–62`). `VoxyClient.java:82–116` preserves supported GL selection and disables Voxy when Minecraft has no GL context.

**CONFIRMED ladder cannot act unflagged. CONFIRMED current ladder never clears or writes Minecraft's depth even when flagged:** both attachment clear optionals are empty/LOAD, depth writes are false for all three pipeline states, depth bounds/stencil are disabled, and the real creator forwards that state unchanged. It writes colour and uses Minecraft's attachment/copy machinery. The bytecode escape demonstrates missing test coverage of a hypothetical changed creator, not a depth write in current source. Other separately flagged probes can modify depth, which is why their exclusion from the ladder launch matters.

## 6. What could not be checked and why

Minecraft/native/live execution was expressly prohibited. No fresh live mixin application, normal GL visual parity, ordinary play, travel/update/reconnect/resize/reload stability, scene coexistence, streamed LoD, pressure/device-loss retirement or Z-direction experiment was performed. Permitted offscreen JUnit execution does not replace those.

Full original ladder readbacks/screenshots are not retained. Quarter-scale thumbnails cannot reproduce individual pixel order, boundary strips, alpha or scene colours overwritten by the ladder. No independent same-pixel scene-depth reference is available. Original depth floats cannot be recovered from quantized PGM. The depth-copy-only isolation run is unavailable. Source fingerprints do not establish dependency/runtime binary or environmental equality, native sync-validation activation, or authenticity of arbitrary jointly rewritten records.

JVM mutations were inspected through instrumentation and assertions without executing the changed Vulkan calls. The passing mutations establish regression escapes, not that the unmutated source is defective or that retained committed data was forged. All judgments are bound to this checkout/HEAD.

Review reproduction scripts, 100 mutation outcomes, independent recounts, converted inspection thumbnails and fresh test logs are retained under `.agent-run/r11-*`. Final machine verdict: `.agent-run/native-integration-review.json`. Only review artifacts were written; no fix, promotion, merge, push, publication or Minecraft launch occurred.
