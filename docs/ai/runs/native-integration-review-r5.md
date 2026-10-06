VERDICT: REDESIGN

Independent round-5 review, 2026-10-06, of `/Users/xyz/orca/workspaces/voxy/native-review-r5` at **c480046928f73a0600a97ef544135f9ce14c4685**, verified with `git rev-parse HEAD`. The initial tracked tree was clean. Only this report and `.agent-run/native-integration-review.json` are final workspace changes. No implementation was repaired and no Minecraft, native/live stage, or interop runner was launched. Permitted JUnit GPU tests did run.

CONFIRMED/REFUTED judge the stated repair or evidence claim, not the author's intent. Raw pixels, explicit crop origins and several failure checks are real improvements. **B1, B3, B4 and R4-L1 remain open**, on narrower residuals described below. An additional enabled-marker lifetime defect is recorded as **R5-LIFETIME**. R4-L1 retains the requested low/non-blocking classification; B1/B3/B4 and R5-LIFETIME block accepting this diagnostic foundation.

The survey correctly preserves round 4's acceptance boundary. This candidate does not claim an independently accepted layer, and this review does not grant one. Experimental terrain investigation remains distinguishable from continuation from an accepted diagnostic foundation. Flags-unset dormant safety remains acceptable; Minecraft-native Voxy terrain remains `BLOCKED_UNIMPLEMENTED`.

## 1. Each open round-4 finding

| Finding | Repair claim | Closed? | Remaining issue |
| --- | --- | --- | --- |
| B1 authoritative, replayable evidence | **REFUTED as complete; partial repairs CONFIRMED** | No | Recount clips expected regions to a producer-supplied crop; replay bypasses marker failure checks; retention can succeed after losing the referenced sample. |
| B3 fail-closed proof consistency | **REFUTED as complete; specific new checks CONFIRMED** | No | An unattempted adoption, vacuous feature verification and jointly empty sentinel values pass. |
| B4 failure persistence, orientation, association | **REFUTED as complete; live-instance map/request repair CONFIRMED** | No | Null-instance callbacks, PPM retention failure and close failure leave stale files; orientation and capture association remain unsound. |
| R4-L1 bounded abandonment | **REFUTED as complete; request/size bounds CONFIRMED** | No | Close failures escape the problem budget, registration-path close errors remain swallowed, and marker abandonment has no total bound. |

### B1 — raw evidence exists, but the recount can accept an incomplete proof

**CONFIRMED:** `McNativeMarkerDraw.withSample` saves a P6 RGB crop from mapped data, not from aggregates (`McNativeMarkerDraw.java:509–539`). `recount_marker_sample` reads it, maps NDC rectangles to pixels, counts near/far/cell/control and rejected pixels, requires 80% density, and compares near/far/cell counts (`scripts/verify.py:434–519`). The current retained sample reproduces near **12420/12420**, far **8316/8316**, cell **4224/4224**, control strip **4032/4224**, rejected-in-box **0**. Missing files and a changed near aggregate are rejected. The control strip is distinct from JSON `control`, which means the depth-tested pass cell.

**REFUTED:** the density denominator necessarily represents the full expected region. `to_rect` intersects each expected region with `sampleRect` (`verify.py:462–472`), then `tally` computes the area of that intersection (`481–489`). The gate never requires the crop to cover the entire box, cell and strip, lie inside the parent image, or agree with their original areas. A producer can omit a failing part of the box and have the remaining pixels declared 100% dense. This is a spatial completeness defect, independent of whether the retained good run exercised it.

Constructed counterexample using the committed sample: remove its leftmost 20 columns, change `sampleRect` from `[19,896,211,1069]` to `[39,896,211,1069]`, change `near` to **10260** and `control` to **3784**, leave the geometry and far count unchanged. Recount succeeds:

```text
near=10260/10260 far=8316/8316 cell=3784/3784
controlStrip=3612/3784 rejectedInBox=0
```

The omitted columns can contain yellow throughout the actual box; the retained sample is identical either way. The original `boxArea=20736` is not checked against the sampled box. I also exercised the **full** `native_marker_result` with the test fixture's analogous crop `[29,5,105,91]`, near/far **2052/2052**, cell **760/760**, correct supporting screenshots and no capture identity: **success=true**. Those screenshots could be later repaired/composited images and cannot establish the omitted raw pixels. No fake hashes or mocked recount were used for these acceptance results.

**CONFIRMED:** the requested command returns 0 and launches nothing:

```sh
python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261006T073859-396220Z
```

**REFUTED:** it rechecks the authoritative marker's acceptance conditions. Replay invokes `recount_marker_sample` directly and the proof-file helper (`verify.py:782–800`), bypassing `native_marker_result:254–326`. In temporary copies, after recomputing the altered report's manifest hash, all of these returned **0**:

| Mutation of retained report/directory | Replay result |
| --- | --- |
| `attempted=false, completed=false, timesClean=0, timesWithAProblem=3, note="FAILED", firstProblem="FAILED"` | **0** |
| marker `enabled=false, pipelineLive=false, drawsRecorded=0, notes=["FAILED"], depthAttached=false` | **0** |
| remove `sampleAtDraw`, or set it to `999999999` | **0** |
| `closeFailures=99` | **0** |
| remove `MANIFEST.json` altogether | **0** |

Changing hashes intentionally makes these schema/semantic challenges, not allegations of undetected byte tampering. Replay correctly rejects mismatching hashes when they are present, and says screenshot measurement is not replayed. Its output fails to disclose that marker attempt/completion, sticky failures, live/enabled/depth state, minimum sample count, environment completion and diagnostics are also not re-evaluated. A failed diagnostic can therefore be described as an authoritative replay success.

**CONFIRMED:** ordinary retention errors and absence of *all* samples now force stage/run failure (`verify.py:977–981`). **REFUTED:** loss of the actual authoritative sample always fails retention. `retain_native_evidence` requires only a nonempty glob (`699–707`). I populated the committed `EvidenceRetentionTest` fixture, duplicated its sample as `native-marker-sample-1.ppm`, then removed the report's referenced `native-marker-sample-2900.ppm`. Retention returned **no error**, retained only sample 1, and subsequent replay returned **1**, "the retained raw sample ...2900.ppm is missing". The caller would keep the stage successful. This also describes loss between the original gate and retention when other samples remain.

**CONFIRMED:** crop origins are now retained facts about the runner's actual crop operation: `retain_marker_crops` writes parent extent, origin, size and end label at `verify.py:729–748`; `MANIFEST.json.crop_origins` contains all 22 records. For edit, top is parent `[1708,960]`, origin `[15,7]`, size `[174,158]`; bottom origin `[15,795]`. They no longer require reconstruction from recording constants. The full parent screenshots remain absent, so their published hashes cannot independently prove the crops were cut from those parent files.

Evidence integrity: all **51 manifest file hashes** match. All **400 source/build/script fingerprints** match this HEAD. The retained revision/final revision is dirty parent `4d0ef19b755cd6e259ec6aa14f3bf95c35041796`; source hashes bind the measured inputs to the candidate despite that parent revision. This does not fingerprint every dependency binary or reconstruct the dirty patch. There are **22 crops**, **18 PPMs**, **2,155,005 bytes** among manifest-listed files. These are inspectable evidence, not just counts. I inspected the edit crop and decoded/visually inspected sample 3363.

### B3 — the new checks reject their exact examples, but contradictory proofs still pass

**CONFIRMED:** feature `attempted=false` fails; a plain failure note without the accepted phrase fails (`verify.py:569–574`). Adopted `enabled=false` and `readBack="0xdead"` fail (`596–601`). Compute and shader `device="0xdead"` both fail the five-device comparison (`621–649`). Missing/null device fields also fail. These repairs were independently exercised, not inferred from green tests.

**REFUTED:** a never-attempted or contradictory proof cannot pass. Actual mutations of `ProofFileGateTest` produced:

| Mutation | `native_proof_files_result.success` |
| --- | --- |
| feature `attempted=false` | false |
| feature `notes=[]` with all four `added` fields | **true** |
| feature `notes=["FAILED: not verified by read-back"]` | **true** |
| adopted `attempted=false` with adopted/proven assertions true | **true** |
| adopted disabled or wrong readback | false |
| compute `expected="", readBack=""` AND adopted `readBack=""` | **true** |
| compute/shader different device | false |

Adoption's `attempted` is still ignored. Verification notes are a substring match with no required one-to-one coverage of `added`; an empty list vacuously passes, and negation containing the phrase passes. Compute/adoption compare arbitrary strings instead of the fixed sentinel the source writes. Shader `firstMismatch` is still optional via `.get()` (`608–609`); deleting it also escapes the required-field discipline. Marker and Vulkan-probe failure flags/notes are not checked by this helper, which matters when replay calls it without the other gates.

Logical-device handle agreement is useful within the assumed run/process. It is not a globally unique run/device-generation token. All-zero agreeing handles can pass this helper while the original environment gate rejects zero; replay omits that environment gate. These are gate completeness findings, not a claim that the retained successful run contains a contradictory device.

### B4 — current failures are counted more often, but disk can still hold a clean proof

**CONFIRMED:** request failures, oversized requests, and map/classification exceptions now invoke `failReadback`, increment the sticky counter, and immediately write when `instance` exists (`MarkerDraw:347–350,363–365,421–423,496–506`). Independently invoking the real `classifyReadback` with a map-throwing `GpuBuffer` yielded:

```text
mapFailure live=true problems=1 closed=true oldFileUnchanged=false
```

**REFUTED:** every failure passes through that path and immediately invalidates disk. The same real callback after clearing `instance` yielded:

```text
mapFailure live=false problems=1 closed=true oldFileUnchanged=true
closeFailure problems=0 closeFailures=1 oldFileUnchanged=true
PPMFailure problems=0 cleanCount=4 sample=null oldFileUnchanged=true
```

The latter two used a clean mapped image with a throwing `close()`, and a directory placed at the expected PPM file path, respectively. PPM retention catches the write exception, appends a global note and returns the original successful `Readback` (`509–543`); classification then increments clean count and writes no immediate failure proof (`400–416`). Callback close failure occurs in `finally`, increments only `readbackCloseFailures`, appends a note and does not publish immediately (`424–433`). Registration-path close exceptions remain silently swallowed (`367–370`). With a live instance and writable JSON path, PPM/close failures can still leave an old clean JSON until the next multiple of 600 draws. A final failure followed by exit before that snapshot can be missed. WARN/INFO are not rejected by the native stage's error/validation filters (`verify.py:927–940`).

When a map failure arrives after `shutdown` has cleared `instance`, `failReadback` skips writing altogether (`MarkerDraw:502–506,825–828`). The report writer also catches its own failures and only warns (`McNativeVulkanProbe.java:359–368`); attempted writing is not confirmed durability.

These tests used the compiled candidate methods by reflection, heap byte buffers and constructor-free dummy device wrappers. No Minecraft instance, native device, Vulkan submission or GPU fault was created. Temporary programs/outputs were `/tmp/R5MarkerProbe.java` and `/tmp/voxy-r5-java.log`; the source references and results above describe precisely which production branches were exercised.

**REFUTED:** trying both orientations necessarily selects the correct one. The classifier chooses the first orientation whose local regions satisfy its thresholds (`MarkerDraw:391–396`). I fed the actual classifier an image with a correct pattern at the top and an entirely yellow box at the bottom, treating bottom as the actual marker orientation observed in the retained run. Result:

```text
badActualBottomGoodMirror chosenFlip=false note=null problems=0
```

Density helps reject unrelated sparse patches; it cannot identify which of two dense patterns belongs to the submitted marker. Only the selected crop is retained, so recount cannot inspect the failing opposite orientation. The retained PPM demonstrates a correct local pattern in its declared region, not unique causal provenance or correctness elsewhere in the image. Java's thresholds (200/60; `585–587`) and Python's ±60 tests (`verify.py:478–479`) also differ at 195–199/56–60: an implementation failure with such pixels could look acceptable to recount, especially because replay ignores its failure fields.

**CONFIRMED:** the report now publishes `flipped`, `sampleRect` and a draw counter (`MarkerDraw:980–989`). **REFUTED:** that establishes capture/submission/checkpoint association. `measure` reads global `drawsRecorded` when the callback runs (`480–482`), rather than receiving the counter/device/image token when the copy is registered (`360–361`). The first request can be at draw 2 while its file is named sample 3. The gate neither requires nor bounds `sampleAtDraw`; the ordinary positive Python fixture passes with it absent. No source image handle, owner generation, captured target extent or lifecycle token is bound to the sample. The retained JSON is the draw-3600 snapshot with sample **3363**, 15 clean reads, while checkpoints continue to **4177** and the log/PPMs contain **18** samples through **4083**. Extra PPMs retain pixels but lack their own per-sample report/extent/device/orientation metadata; replay recounts only the referenced one. Three cumulative successes do not prove each lifecycle checkpoint or intervening frames.

## 2. R4-L1 and the new code's risks

**CONFIRMED:** the render loop requests only with `!readbackInFlight` and `readbackProblems<3` (`MarkerDraw:307–310`). There is no second normal-production caller of `requestReadback`. It sets the pending flag before registration, clears it in callback `finally`, and refuses allocations above **40 MiB** (`345–361,425`). A never-delivered callback leaves one buffer pending rather than allowing an unbounded queue. A copy-registration exception after setting the flag leaves it stuck true; that abandons future requests rather than retrying indefinitely. That is bounded, but pending/non-delivery is not published as an outstanding-buffer count or required final drain.

**REFUTED:** all failed/unclosed buffers participate in abandonment and publication. Close failures are not added to `readbackProblems`, so otherwise successful callbacks with failing closes can leak another buffer every interval indefinitely. The request-finally close exception has no count at all. The callback counter is written only on a subsequent evidence snapshot, can be lost at exit, and is ignored by both marker/replay gates. The one-flight limit bounds concurrent *pending* callbacks, not the accumulated buffers whose close failed. R4-L1 is still open.

**REFUTED:** marker abandonment has become bounded. Same-device retirement refusal still deliberately leaks, without a leaked-object/byte counter or session budget (`MarkerDraw:781–799`). Repeated format/world/device changes can allocate and abandon repeated pipeline sets. The readback budget does not cover those resources.

### R5-LIFETIME — retirement can leave a retired draw eligible for recording

**CONFIRMED:** cross-device abandonment, same-device queue ownership, and checked-idle shutdown protect the previously reviewed wait/destruction defects (`MarkerDraw:785–799,856–867; McNativeVkContext:287–310`). `destroyed` prevents actual destruction from running twice (`MarkerDraw:803–807`). Queue refusal does not fall back to immediate destruction.

**REFUTED:** format replacement cannot reuse retired resources. In `render`, `retire(draw,device)` clears only the local `draw`, not static `instance` (`MarkerDraw:274–285`). If replacement `create` returns null (`647–680`), render returns with the old draw still in `instance`. Same-device retirement does not mark it as queued/unusable. Subsequent failed replacements enqueue it again. If the target format returns to the old value, the mismatch branch is skipped and `draw.record` runs on that old object (`301`), even if its deferred destruction has already occurred: render never checks `draw.destroyed`. If it has not yet retired, new commands can extend use beyond the retirement queued for the earlier submission.

I exercised real `retire` with a heap-only dummy Minecraft encoder containing the actual `DestructionQueue` and a marker with zero native handles:

```text
retireTwice instanceStillSame=true live=true
afterRetirement callbacks=2 destroyed=true instanceStillSame=true
```

The duplicate callbacks themselves do not double-free because `destroy` is idempotent. **Re-recording the stale object** is the unsafe path. Cached Minecraft bytecode confirms `queueForDestroy` merely adds to the destruction queue and does not detach Voxy's static reference (`javap -c -p ...VulkanCommandEncoder`). Source establishes the failed-create/format-reversion route; no real Vulkan use-after-free was induced. This is an additional blocker for the enabled diagnostic. It does not reopen the old unconfirmed-wait defect or affect flags-unset normal play.

### PPM I/O, partial files, and target sizes

**CONFIRMED bounded individual writes:** normal render dimensions are positive; the full RGBA readback is capped at 40 MiB, and the fixed crop spans approximately 10% width × 16% height. Its RGB body is approximately 1.2% of the full readback allocation, about 0.5 MiB at the cap. Header/body arrays and mapped-buffer accesses occur while the mapped view is open (`MarkerDraw:386–400,518–536`). I/O is synchronous in the retirement callback, so slow directory creation/write can delay Minecraft's callback/render work. There is no elapsed-time bound, atomic rename or total disk-retention budget; raw files accumulate every successful interval in a long diagnostic session. This is a non-blocking diagnostic operational limitation, not flags-unset player work.

**CONFIRMED property-unset behavior:** null/blank `voxy.harness.output` returns the measured readback without a sample file or filesystem access (`511–512`). Classification can still report in-memory success, which is reasonable for a diagnostic without retention. Supplying that report to the acceptance gate fails for lack of raw pixels; this is not a hidden normal-play write.

**CONFIRMED actual truncated-file rejection:** output writes the header then the full declared body, and returns a sample name only after stream closure succeeds. `read_ppm` requires the declared byte count (`scripts/pixel_oracle.py:204–237`); deleting the final byte of sample 3363 yielded `ValueError: truncated PPM body`. A partially written short PPM with its unchanged header is not accepted. The parser tolerates trailing bytes, which does not rescue a short body. In-memory short reads instead break the inner copy loop and leave zero-padding (`MarkerDraw:525`); that is a complete-length file with missing pixels, not a truncated file. Its acceptability then depends on density and the incomplete-crop defect above. The concrete unsafe write-failure behavior is stale JSON, already demonstrated under B4.

**CONFIRMED positive-target-size rejection in the normal gate:** zero/negative sizes fail at `verify.py:268–271`. The zero-size test fails when that guard is removed. Direct replay does not run this guard. `isinstance(...,int)` also admits booleans there, although subsequent nontrivial-pixel requirements prevent a normal one-pixel false pass.

**REFUTED exact current dimensions on all failure paths:** `lastWidth/lastHeight` are assigned *after* `requestReadback` (`MarkerDraw:307–314`). An oversized/allocation/registration failure writes the previous frame's extent; the first such failure writes initial zeros. A delayed callback can see dimensions/device from a later frame. The new path reports a remembered size rather than universal fabricated zeros, but not necessarily the size of the failed request. Since these paths set completed=false, this discrepancy alone does not make the ordinary gate pass.

## 3. Tests, including whether new tests detect their stated failures

All requested commands were executed exactly as authorized:

| Command | Independently observed result |
| --- | --- |
| `./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true` | Exit **0**; test task executed; **313 tests, 312 passed, 1 skipped, 0 failures/errors**. |
| `python3 -m unittest discover -s scripts/tests` | Exit **0**; **64 tests**, 42 of them in `test_marker_gate.py`; 42.795 s. |
| `python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261006T073859-396220Z` | Exit **0**; no manifest mismatches; the reported recount and 11-checkpoint identity check pass. |

JUnit XML identifies the sole skip as `VkBarriersTest.missingBarrierIsDetected()`: the intentionally unsynchronized descriptor-bound SSBO pair emits no diagnostic. The suite logs one deliberate `SYNC-HAZARD-WRITE-AFTER-WRITE` control from `plainBufferHazardIsNowDetected`; duplicated printing is not two independent hazards. No unexpected `[vk-validation]` event was observed. Bare `SYNC-HAZARD-READ-AFTER-WRITE` strings in the LOAD tests report **zero**, not actual diagnostics. These offscreen tests do not establish Minecraft-native lifecycle or terrain acceptance.

Temporary logs: `/tmp/voxy-r5-gradle.log`, `/tmp/voxy-r5-python.log`, `/tmp/voxy-r5-replay.log`. Additional gate/fault/mutation observations were retained during review in `/tmp/voxy-r5-adversarial.json`, `/tmp/voxy-r5-java.log`, `/tmp/voxy-r5-retire.log`, `/tmp/voxy-r5-mutations.json`. These temporary files are not committed evidence; the substantive results and production locations are recorded here.

**CONFIRMED meaningful new negative cases:** removing the positive-size guard makes `test_a_zero_target_size_is_rejected` fail. Ignoring feature verification notes makes `test_a_feature_note_that_is_not_a_verification_fails` fail. Removing the no-samples error makes `test_a_run_that_retains_no_raw_sample_reports_an_error` fail. The wrong-adopted-value case targets the added comparison (`test_marker_gate.py:474–477`). Missing raw files and aggregate disagreement were independently rejected in this review.

**REFUTED comprehensive regression coverage**, with observed examples:

| Test | Broken behavior it still accepts / fails to test |
| --- | --- |
| `test_the_retained_directory_replays_on_its_own` (`589–594`) | **Passed** with `verify.recount_marker_sample=lambda *args:{}`; no pixels were read. It checks a printed label/checkpoint count, not a recount result. |
| `test_replay_fails_when_a_retained_file_was_altered` (`596–602`) | **Passed** with the same disabled recount because hashes alone detect the alteration. Useful integrity test; not a semantic pixel test. |
| `test_a_run_that_retains_no_raw_sample_reports_an_error` (`578–581`) | Exercises only the helper's error string; would pass if the stage caller stopped turning that error into failure. Does not test loss of the *referenced* sample while another sample remains. |
| `test_every_crop_states_its_origin_rather_than_implying_it` (`570–576`) | Checks parent size and origin list length, not actual position/pixels. A hardcoded `[0,0]` origin of length two passes. |
| `test_the_readback_is_what_carries_the_proof_not_the_screenshots` (`232–237`) | Claims to test all-empty screenshots with a good readback, but `run_gate` builds the raw sample from the same empty frame and `autoAgree` changes the counts to zero. Its observed failure is **"too few near/far pixels"**, before any screenshot is checked. It passes a broken screenshot gate. |
| `test_a_correct_frame_passes` and `run_gate` (`147–177`) | Fixtures omit `sampleAtDraw` entirely and manufacture aggregates from samples. They cannot protect capture identity or the Java producer's callback persistence/orientation. |
| `test_a_proof_that_was_never_attempted_fails` (`485–489`) | Covers compute/shader only, not adopted or the new feature attempt check. |
| `test_a_proof_naming_a_different_device_fails` (`460–466`) | Covers marker/probe, not compute/shader identities; independent mutations here confirm the latter, but this test does not. |

`EvidenceRetentionTest` is defined after the file's `if __name__ == "__main__": unittest.main()` (`510–514`). Discovery includes it, as requested; running the file directly exits before defining it and misses its six cases. Java shader compilation/constants/off-flag tests (`McNativeMarkerDrawTest.java:24–58`) would pass valid-but-wrong shader geometry or an enabled draw that always returns. There is still no committed Java regression for the callback faults, orientation attack, failure budget, per-sample association or failed-replacement retirement route demonstrated here.

## 4. Survey claims versus committed evidence

The survey's dated historical sections and its later caveat at `vulkan-native-integration-survey.md:423–431` must be read together. The caveat correctly limits missing older runs to later reproductions. It cannot independently establish exact older counts, failures or causal explanations. These remain **unproven findings**, even where the claims may be true.

| Section/claim (survey lines) | Judgment and evidence limit |
| --- | --- |
| Preference-only OpenGL fallback and options rewrite (`124–129`) | **REFUTED as retained historical proof.** That failed run/options snapshot is absent. The new forced-Vulkan log cannot reproduce the preference-only case. |
| Forced Vulkan, Apple M4 Pro/MoltenVK, validation layer, nonzero device/targets, queue families 0/3/3, RGBA8/D32 at 1708×960 (`130–146`) | **CONFIRMED for the later retained run**, from `native.log:117,323,341,369–394` and checkpoint/probe JSON. Handle reports do not expose raw depth values. |
| Device/target access without GL; production Voxy disabled (`148–151`) | **CONFIRMED** source and new log. The word "rendering" must remain scoped to terrain here because the flagged marker does render. |
| One magenta quad, no push constants/descriptors/buffers, optional flag, MC-owned pass (`160–165`) | **CONFIRMED flag/pass ownership and no vertex buffers/descriptors; REFUTED as present implementation and unproven exact historical implementation.** Current source records six quads using a 36-byte push payload (`MarkerDraw:593–628,658–662`). It is explicitly a dated paragraph, not proof of today's commands. |
| Persistent pipeline, exact 4800 draws and old ten-frame pixel counts, clean validation (`172–183`) | **CONFIRMED analogous later bounded success/ten visible supporting crops; REFUTED exact historical counts.** New JSON snapshot is 3600; final checkpoint 4177. New log has no native validation diagnostics. `BLOCKED_UNIMPLEMENTED` is correctly retained. |
| Exact original y-placement and colour transformations (`187–194`) | **REFUTED as retained original measurements.** New crops/raw sample support differing raw/composited row placement, not those absent original images' exact values. |
| Nether was black because the level/hook never ran (`195–199`) | **REFUTED as established causality.** New nether crop carries no visible marker while cumulative draw count rises to 3721 and GUI flag is false. Neither darkness nor a cumulative counter proves what happened in the captured frame. |
| Eight supported features, four absent from Minecraft defaults, listed extensions and zero-to-one (`215–230`) | **CONFIRMED retained physical support/default declarations. REFUTED interpretation as live enabled-feature state:** `McNativeFeatureAudit:155–165` reads `REQUIRED_DEVICE_FEATURES`, before/without representing injection. |
| Four optional verified setters and their calibration; historical bad-offset refusal (`232–247`) | **CONFIRMED inspected injection/source and field-calibration tests. REFUTED retained original failed-launch proof:** absent log. Fail-closed gate residuals remain B3. |
| Int64 sentinel on compute queue family 3 and clean bounded lifecycle (`249–257`) | **CONFIRMED** report/source/log and identity agreement. It demonstrates int64 compute storage, not independent exercise of all four injected graphics/indirect features. |
| Borrowed context, preserved external ownership, re-queried state, same behavior in both modes (`265–274`) | **CONFIRMED borrowed ownership/state/sentinel. REFUTED universal equivalence/proven portability:** physical-device matching by name and submission ownership remain limitations. |
| Graphics queue 0 checked for GRAPHICS and COMPUTE, sharing avoids transfers (`275–279`) | **CONFIRMED for this host and constructor guards** (`VkContext:437–450`). A universal claim about every graphics family is not established by these measurements. Sharing this one queue avoids that cross-family problem, not every future concurrency problem. |
| Voxy buffer/shader/pool sentinel on MC's device (`281–286`) | **CONFIRMED bounded source/log proof.** Broader terrain integration is not measured. |
| Depth test AND depth write, exact old counts in every checkpoint (`288–299`) | **CONFIRMED source uses both and selected current raw colour pattern is compatible with depth testing; REFUTED complete measurement.** Disabling writes on the LESS pipeline leaves the final colours unchanged because the ALWAYS base already separates box/cell depths. Colour images do not measure those writes or exact depths. No raw depth image is retained. Old counts absent; B1/B4 limit authority. |
| Clean release, historical child-object complaint, old double-negation/bottom counts (`301–311`) | **CONFIRMED current checked release and clean new log (`native.log:603`). REFUTED independently retained historical failures/counts:** absent. |
| Production shader loading/binding/frame/barrier/index machinery and 189 CPU matches (`313–327`) | **CONFIRMED bounded source, standalone JUnit, retained native log/report.** An index-resolution probe is not terrain draw/composition. |
| Core/KHR dispatch fix and 313-test suite (`331–342`) | **CONFIRMED source and new standalone suite/later native success. REFUTED original failure narrative as retained proof:** original NPE run absent; standalone device is not MC's KHR-only path. |
| Device-owner teardown/cache list and runnable probe seam (`344–359`) | **CONFIRMED source/seam/clean new release. REFUTED universal lifetime foundation**, owing to R5-LIFETIME and the unused unsafe helper APIs below. |
| Round-1 repair/evidence/test claims (`361–421`) | **CONFIRMED specific guards/ownership/calibration/PNG checks. REFUTED complete proof/never-regress language.** B1/B3/B4 remain open; linked `20261005T050713-618859Z` is absent. "47 Python tests" is historical; now 64. |
| Round-2 density/cell/rearm/identity/replay and MIN_VALUE incident (`433–485`) | **CONFIRMED implemented position/density/rearm and stronger identity checks. REFUTED complete lifecycle/replay closure.** Linked `20261006T064519-555551Z` and original failed-overflow run are absent. The separate cell command is superseded by the spanning command. |
| Round-3 spanning draw, all failures immediate, identities, same-device release, retained run (`487–521`) | **CONFIRMED spanning source/identity and same-device waited shutdown; REFUTED universal persistence/association** under B4. Round-3 directory exists, but lacks current raw evidence; its exact authoritative counts cannot be independently recounted. |
| Round-4 acceptance boundary (`523–530`) | **CONFIRMED.** Explicitly retains REDESIGN and offers repairs to round 5 without self-granted acceptance. This wording should stand. |
| Round-4 raw retention/counts/density/aggregate comparison (`532–542`) | **CONFIRMED mechanisms and sample-3363 recount; REFUTED sufficient authoritative proof** because clipped crops pass. Only near/far/cell aggregates are compared; other area/counter/association assertions are not independently established. |
| Round-4 all failures immediate/current dimensions/positive size (`544–551`) | **CONFIRMED particular request/live-map changes and positive normal-gate guard; REFUTED universal persistence and exact failure dimensions**, as B4/section 2 demonstrate. |
| Round-4 never-attempted/contradictory proofs fail (`553–557`) | **REFUTED universal claim; new enabled/value/feature-attempt checks CONFIRMED.** Unattempted adoption and jointly empty values still pass. |
| Round-4 bounded abandonment/unclosed-buffer publication (`559–563`) | **CONFIRMED one pending request/40 MiB/three counted problems; REFUTED coverage of close failures and total abandonment.** |
| Round-4 gate ordering (`565–567`) | **CONFIRMED narrower ordering change:** explicit rejected-in-box is checked before aggregate thresholds (`verify.py:314–326`). Attempt/completion/note/counter checks precede it too, and Java `measure` still checks density first (`MarkerDraw:463–479`); the sentence describes only the Python gate. |
| Round-4 retained standalone replay, origins, loss fails (`569–580`) | **CONFIRMED genuine no-launch hash/recount/device replay and origin fields. REFUTED complete rejection/failure guarantees** under B1/B4. Screenshot exclusion is disclosed; the additional omitted checks are not. |
| Round-4 new run: 51 files, 22 crops, 18 raw samples, 2.2 MB, near/far/cell counts, 15 clean/no problems/no unclosed buffers, green gates/no diagnostics, replay 0 (`582–588`) | **CONFIRMED inventory, selected pixels, stored 15-clean/zero-counter report, stored green gates, no matching diagnostics, actual replay exit 0. REFUTED interpretation of counters as a complete final-session audit:** log has 18 samples, report is at 3600, final checkpoint 4177; close failures can evade publication. |

The survey's remaining unmeasured terrain-pass placement, descriptor interplay, real indirect/compute integration and completion target remain appropriately unresolved (`590–608`). The proposed first step is historically stale in parts now implemented, but does not declare production success. The clean native log contains `syncValidation=false` for the adopted context; validation-layer presence alone does not establish synchronization-validation coverage for Minecraft's submitted frames.

## 5. Safety for normal play, all four native flags unset

**CONFIRMED: I would ship this diagnostic change dormant to an existing GL-backend player**, subject to the existing project's normal release requirements. **CONFIRMED: I would also ship it dormant to a Minecraft-Vulkan player as an inert/disabled Voxy state**, not advertise usable native LoD terrain. This is source-and-test-supported dormant safety, not a new live smoke/soak certification.

The actual call graph still guards before diagnostic device queries, allocation, stalls or file I/O:

| Route | Evidence |
| --- | --- |
| Initial adoption | `VoxyClient:71`; `McNativeVkContext:92–100` returns before device access. |
| Tick native probe and dependent diagnostics | `VoxyClient:165–166`; `McNativeVulkanProbe:294–297` returns on unset flag. |
| Level render marker | `MixinLevelRenderer:47–49`; `MarkerDraw:241–242` returns before render. |
| Level close | `MixinLevelRenderer:34–37`; `MarkerDraw:825–828` returns on null instance before lookup. |
| Client stop | `VoxyClient:171–172`; `McNativeVkContext:287` returns before idle wait unless adopted. |
| Device-feature mixin | `MixinVulkanBackend:34–41`; `McNativeDeviceFeatures:76–78` returns the original set without calibration/native writes. |
| MC Vulkan production Voxy | `VoxyClient:96–108` preserves the GL prerequisite and disables production terrain; no marker instance is created. |

Bounded Java bookkeeping/callback registration still occurs; literal zero execution/allocation would overstate inertness. R5-LIFETIME and readback faults require enabling the diagnostic. They do not demonstrate an unset-flags regression. Previously closed B2 and the specific B5 checked-completion defects stay closed within their original scope.

## 6. Non-blocking findings and limits of this review

**R4-L1 (low, still open):** abandonment/publication is incomplete as detailed above. **R5-TEST (medium):** regression tests can stay green with no recount and do not cover key producer faults. **R5-DOC (medium):** exact historical measurements/causes and full depth-write/lifecycle claims remain unproven, despite the correct acceptance boundary. **R5-API (low, carried):** unused `VkFrameTracker.waitIdle` still discards its result and drains frees (`VkFrameTracker:220–224`); direct `VkContext.shutdown/destroy` has no internal completion guard (`VkContext:103–106,692–695`). `rg -n '\.waitIdle\(' src/main/java` found no caller of the former; the reviewed native shutdown caller confirms idle first. Do not promote those helpers into future terrain lifetime guarantees. **R5-IO (low):** synchronous callback I/O and total diagnostic disk growth are unbounded. Strict-field duplication/name-based physical-device ambiguity and feature-audit accessor ambiguity (`McNativeFeatureAudit:168–178`) remain narrower existing portability risks.

Could not check: new live startup/gameplay, flags-unset GL/Vulkan smoke/soak, actual GPU timeout/device loss, real retirement/callback timing across target/device replacement, parent screenshots against their hashes, native sync-validation configuration, or exact older absent runs. Minecraft/native/live launches were explicitly prohibited; offscreen JUnit and heap-only fault injection do not substitute for those observations. No real depth readback, LoD terrain draw/composition, host terrain-pass barrier chain, alternate GPU/OS or long-session exhaustion evidence is committed for this candidate. Missing original screenshots are explicitly excluded by replay. The source fingerprint gives strong binding for its listed inputs, not a complete environment/dependency attestation.

No implementation fixes, publication, pushes or merges were performed. The repair claim is judged against its stated scope: improved local evidence and narrower failures are confirmed, but an independently accepted diagnostic foundation is still **REDESIGN**.
