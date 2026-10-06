VERDICT: REDESIGN

Independent round-6 review of `/Users/xyz/orca/workspaces/voxy/native-review-r6`, HEAD **35aff16fbda3e2f93564dfe7af3ccb8ee9cfc257**, 2026-10-06. `git rev-parse HEAD` matched the request; the initial tree was clean. No implementation changes, Minecraft launch, `--only native`, `--only live`, or interop runner were performed. The requested offscreen JUnit GPU suite was permitted and executed. Only the requested report and machine verdict are final workspace additions.

The round-5 boundary remains: **an independently accepted diagnostic foundation is still REDESIGN**. This candidate explicitly accepts that boundary. The five-findings-repaired claim is **REFUTED as a whole**: R5-LIFETIME's production route is closed; B1, B3, B4 and R4-L1 retain narrower residuals. The retained terrain experiment's image comparison is **CONFIRMED within its stated synthetic, scene-clearing scope**. Its general gate and lifetime soundness are **REFUTED**. Those judgments are separate from normal-play safety and native integration completion.

## 1. Each open round-5 finding

| Finding | Complete repair claim | Closed? | Result |
| --- | --- | --- | --- |
| B1 | REFUTED; specific repairs CONFIRMED | No | Original crop attack fails, but moving the reported geometry makes that same partial crop pass. An empty manifest also passes. |
| B3 | REFUTED; specific repairs CONFIRMED | No | Notes have exact syntax/coverage, but four distinct features can all claim offset 0 and pass; duplicate `added` entries pass. |
| B4 | REFUTED; callback repairs CONFIRMED | No | Yellow-in-either-box is rejected, but the selected good mirror can still hide an incorrect actual pattern. Opposite raw evidence is not retained. Filename/capture disagreement passes. |
| R4-L1 | REFUTED; ordinary retirement/request bounds CONFIRMED | No; low/non-blocking | Two shutdown leak paths bypass `leakedPipelines` and its build budget. |
| R5-LIFETIME | CONFIRMED for the production route | Yes | The render caller clears `instance` after retirement and before failed creation, and drops destroyed objects. The helper itself still does not clear it. |

### B1(a): crop completeness

**CONFIRMED:** `scripts/verify.py:509–534` rejects any expected region needing clamping; `572–576` checks `boxArea` and `controlArea` against resolved geometry. The original 20-column attack, reconstructed on the committed sample `native-marker-sample-3362.ppm`, now fails. Remove its first 20 columns, move `sampleRect` from `[19,896,211,1069]` to `[39,896,211,1069]`, and set near/control to 10260/3784. Replay returns **1**, because the near region starts at crop x=-20. Independent area-only mismatches are also rejected by the new comparison.

**REFUTED as complete (blocking):** the denominator is still defined by unvalidated producer geometry. Keep that cropped sample and counts, set `geometry.box[0] = 2*39/1920 - 1` (approximately -0.959375), and set `boxArea=18576`, `controlArea=3784`. Replay returns **0** with near **10260/10260**, far **8316/8316**, cell **3784/3784**, control strip **3612/3784**, and rejected-in-box 0. The missing columns can still contain yellow in the actual source-defined box. This requires a contradictory report, not a claim that the retained good producer emitted one. The gate claims to reject contradictory reports, and has the source fingerprint available, but never binds its expected geometry to the recording constants. `verify.py:497–505,562–568` trusts it; `McNativeMarkerDraw.java:546–550,1079–1086` uses/publishes fixed constants. The failure has moved from crop clipping to redefining the expected region.

### B1(b): replay acceptance and manifest

**CONFIRMED:** both `native_marker_result` (`verify.py:370`) and replay (`1102`) call the same `marker_report_checks`. Replay requires `MANIFEST.json` (`1083–1087`). All grouped round-5 table attacks now fail, including the failed readback group, disabled/dead/zero-draw/no-depth marker group, missing/future capture, 99 close failures, and missing manifest. I also separated the mutations:

| Actual retained-report mutation, rehashed before replay | Exit |
| --- | --- |
| `attempted=false` | 1 |
| `completed=false` | 1 |
| `timesClean=0` | 1 |
| `timesWithAProblem=3` | 1 |
| `note="FAILED"` | 1 |
| marker `enabled=false`, `pipelineLive=false`, `drawsRecorded=0`, `notes=["FAILED"]`, or `depthAttached=false`, each separately | 1 |
| `closeFailures=99` | 1 |
| missing/null `sampleAtDraw`, or `999999999` | 1 |
| remove `MANIFEST.json` | 1 |
| `firstProblem="FAILED"` while problem count remains zero | **0** |
| replace `MANIFEST.json` with `{}` | **0** |

**REFUTED as complete (blocking B1):** a manifest's presence does not require any binding. At `verify.py:1089`, absent `files` becomes `{}`, so an empty manifest verifies zero hashes and succeeds. Candidate/log/fingerprint/sample membership is not required. This defeats the stated purpose of requiring a manifest. The firstProblem-only acceptance is another contradictory state that neither marker nor proof checks rejects.

Replay honestly lists three omissions (`1073–1080`): full screenshot measurement, environment/loader/diagnostic scan, and fresh Minecraft execution. Those omissions are material: it does not rerun the whole native stage. Terrain introduces a further omission, **not disclosed there**: replay calls only `recompare_terrain_samples`, bypassing `native_terrain_result` (`1116–1121`). See item 2.

### B1(c): retention

**CONFIRMED for the requested referenced-pixel repair:** `verify.py:999–1012` requires the named marker sample and terrain sample/reference pair among the copied samples. In isolated temporary copies containing many other samples, deleting each referenced file separately produced a retention error naming that file. The round-5 decoy-sample attack no longer succeeds. Missing JSON/log/source evidence and manifest schema completeness are not equivalent to this referenced-sample check: logs/fingerprints are copied only if present (`957–961`), while replay's exclusions and empty-manifest acceptance still limit the broader proof.

### B3: proof consistency

**CONFIRMED:** `verify.py:807–830` requires enabled/attempted feature injection, all four names, exact note syntax and unique verification names; `840–845` fixes the sentinel to `0x123456789abcdef`; `860` reads adoption's attempted flag; `875–878` requires firstMismatch; `784–793` rejects zero handles. Empty notes, the negated substring note, unattempted adoption, missing firstMismatch, jointly empty sentinel strings and null device all independently fail. Marker/probe enabled/live/backend/notes checks are present at `891–902`.

**REFUTED as complete (blocking):** offset numbers are parsed into `verified` but never checked against their fields (`827–830`). Replacing the four notes with `<name>: offset 0 verified by read-back` makes the real `native_proof_files_result` return **success=true**. Four distinct VkPhysicalDeviceFeatures fields cannot all be one offset. The source selects distinct fields (`McNativeDeviceFeatures.java:56–61`) and its retained calibrated offsets are **40,160,104,100**. The proof helper accepts mutually impossible verification claims. Converting `added` to a set (`verify.py:811`) also lets a duplicated entry pass, so the claimed literal one-to-one relationship with the list is not enforced.

**Narrower limitation:** the helper's marker checks still ignore draw/depth/readback failure flags. With `drawsRecorded=0`, `depthAttached=false`, readback attempted/completed false and problems=3, this helper returns true. The full marker gate and repaired replay reject that report, so this particular helper-only inconsistency is not a standalone full-stage bypass. It does refute an unqualified claim that all marker failure flags are checked there.

### B4(a): fault persistence

**CONFIRMED for map/PPM/callback-close faults**, using the compiled candidate methods by reflection, heap ByteBuffers, throwing GpuBuffers and constructor-free device wrappers. No GPU submission or Minecraft instance was created. `/tmp/R6MarkerProbe.java` and `/tmp/R6ExtraProbe.java` invoke the real callback and real shutdown. Results:

```text
mapFailure live=true  problems=1 closed=true oldFileUnchanged=false
mapFailure live=false problems=1 closed=true oldFileUnchanged=false
closeFailure problems=1 closeFailures=1 oldFileUnchanged=false
PPMFailure problems=1 cleanCount=3 sample=null oldFileUnchanged=false
(after real shutdown) map fault: problems=1, invalidated=true
(after real shutdown) close fault: problems=1 closeFailures=1, invalidated=true
registration fault plus throwing close: problems=1 closeFailures=1 closed=true
```

`publishFailure` uses lastDevice/lastColourFormat without requiring instance (`MarkerDraw:613–619`); a failed PPM write invalidates the otherwise-clean readback (`450–468,653–656`); callback close failure increments the problem budget and immediately publishes (`481–493`). Capture count is passed into classification from registration (`399–401`).

**REFUTED for complete immediate close-failure publication:** the registration catch publishes before its finally block increments `readbackCloseFailures` (`403–415`). The real injection above left **closeFailures=0 on disk** while the static counter was 1. The request problem already invalidates the run and consumes its failure budget, so this is a non-blocking accounting/persistence residual rather than the old clean-proof bypass. JSON write failures remain swallowed by `McNativeVulkanProbe.java:359–369`; these injections used a writable JSON destination, not proof of durability through a failing filesystem.

### B4(b): orientation-free rejection

**CONFIRMED for the explicit yellow attack:** `MarkerDraw.select:512–541` counts both orientations and rejects yellow in either box before choosing a pattern. The original good-top/yellow-bottom attack returns a failure with 5184 rejected pixels. Two valid patterns are rejected as ambiguous. The new Java regression covers both mirror choices and did catch the insufficient first repair.

**REFUTED as a sufficient attribution rule (blocking B4):** rejected colour absent from both boxes is necessary, but does not identify the actual draw. Feed the same production `select` a complete expected top pattern and an all-cyan bottom box (actual orientation), with no bottom near quad or pass cell/control strip. It returns:

```text
goodMirrorBadActual: near=3132 far=2052 rejectedInBox=0 control=960
boxArea=5184 controlArea=960 note=null flipped=false sampleAtDraw=77
```

The actual bottom pattern is wrong; the selected top pattern is unrelated. Neither box is yellow, so the new rule has no way to reject it. This extends the round-5 attribution attack; it does not allege those pixels occur in the retained run. A colour-only oracle cannot establish unique provenance from one dense matching pattern.

Moreover the comment at `MarkerDraw:448–449` says the rejected orientation is retained, but `withSample:626–652` writes **only the selected crop**. The evidence directory likewise has one raw marker crop per capture, no opposite raw crop. Recount checks only that crop (`verify.py:493,501`). Thus “0 in both orientations” remains partly a producer assertion which replay cannot independently test. Post-composition screenshot crops at both ends are different evidence and do not restore the omitted raw pixels.

### B4(c): capture binding

**CONFIRMED:** registration captures `drawsRecorded` into final `at`, and report checks require a positive integer no larger than total recorded draws (`MarkerDraw:399–401`; `verify.py:319–325`).

**REFUTED as complete association:** the marker gate does not cross-check the filename against that number. A report naming `native-marker-sample-3362.ppm` but claiming `sampleAtDraw=1` replays as **0**. The terrain gate does perform a filename check, showing this is feasible (`verify.py:710–713`). Marker capture metadata still lacks bound source-image/device generation; lastDevice/size can advance before an old callback finishes. The specific global-count-at-callback bug is fixed; full capture attribution is not.

### R4-L1: bounded abandonment

**CONFIRMED:** one-in-flight, 40 MiB buffer limit and three-problem budget remain; callback close errors now participate. Registration failures already consume a problem before the throwing-close finally branch. Ordinary marker retirement increments `leakedPipelines` on device mismatch or queue refusal (`902–923`), and `create` refuses after three (`764–768`).

**REFUTED as a total bound; retained low/non-blocking classification:** `shutdown` when Minecraft's device is unreachable (`949–963`) and `shutdownImmediate` with a different waited device (`980–989`) deliberately leak without incrementing leakedPipelines. Four heap-only calls to real shutdown with separate dummy draws and no RenderSystem device yielded **leakedPipelines=0** and `instance=null`. Nothing in the build budget prevents a later reachable-device session creating another draw. This is a reachable conditional leak route, not a measured live leak storm. The request/ordinary-retire bound is repaired; total abandonment is not.

### R5-LIFETIME: failed replacement and format reversion

**CONFIRMED closed for production behavior:** `MarkerDraw.render:299–318` removes destroyed instances, and after `retire` clears the static reference at line 312 before calling `create`. Failed replacement therefore leaves null; reverting to the old format must try creation again, rather than re-recording the retired draw. Shutdown also clears first (`951,982`). All production callers are covered.

**REFUTED literal wording that retire itself clears instance:** it does not (`902–924`). Re-running the round-5 heap-only real-retire/real-DestructionQueue probe still yields `retireTwice instanceStillSame=true live=true`. Applying the production caller's line-312 clear before simulated failed creation yields `instanceNull=true`; after two queued callbacks, destroyed=true and instance remains null. That distinction matters: the helper-only reproduction is still possible, but the original production failed-replacement/format-reversion route is no longer possible. No real GPU use-after-free was induced.

## 2. The terrain experiment

### Split recording: CONFIRMED within the tested behavior

`VkTerrainRenderer.java:362–369,402–456` preserves uploads/dirty depth-bound clear/host barrier before rendering and retains pipeline/descriptor/index/indirect draw recording inside rendering. It now explicitly sets viewport/scissor to renderer dimensions. The full path delegates to the split methods. `VkTerrainRenderTest.theSplitRecordingPathDrawsTheSameImage` (`304–334`) executed and passed, with nonzero coverage and RGB comparison zero. Existing renderer tests also passed.

This test is bounded: the same renderer is first warmed through the full path, and both paths use the new methods. It does not compare with an independent pre-change implementation, exercise a fresh split-only renderer, test changed depth-bound state, mismatched extents, every pass/mode, or depth equivalence. A regression affecting both shared paths identically can leave this test green. This is not a reason to deny the measured RGB equality, but it limits “behavior-preserving” beyond that case.

### Actual retained result: CONFIRMED as a bounded experiment

`McNativeTerrainProbe:304–347` prepares boundaryCases, real resources/shaders/pipeline and CPU-produced indirect commands on the adopted context, submits/waits its reference, and stores its RGB. `239–255` opens Minecraft's own colour/depth pass with clears and records the real renderer; `401–416` registers readback from the supplied Minecraft colour texture immediately afterwards. No path copies the reference pixels into Minecraft's image. Under this inspected source and one stable device, skipping the native draws would leave the clear background and fail comparison against the visibly nonblank reference.

I decoded and viewed the current referenced sample and independently recomputed its pair: **1920x1080, 26116 non-background pixels on each side, 0 RGB mismatches**, `sampleAtDraw=3598`. The older terrain pair `3377` also recomputes those figures. The current report states 16 clean comparisons; the directory contains 18 retained terrain sample/reference pairs, including later captures than that snapshot. The eleven checkpoints and cumulative comparisons do not individually attest every checkpoint's terrain output or depth correctness.

Orientation handling is **CONFIRMED for equality up to a whole-image vertical flip**: Java compares the whole native image against the whole reference in both orientations (`436–465`), then writes the chosen orientation (`546–576`). Python compares the normalized retained pair directly (`714–747`). A correct small mirror patch cannot hide a differing remainder, unlike marker subset selection. Original row-order provenance is not retained; toggling/deleting comparison.flipped does not affect recompare. Alpha and depth are not compared. A common error in both reference and native rendering can pass: I replaced both files with identical flat `(40,40,40)` images, retained the true clear `(13,13,26)`, and updated set counts to 2073600; the gate passed. It proves agreement with a renderer-produced reference, not independent correctness of the synthetic scene. This common-mode limitation does not refute the real retained nonflat image.

### R6-TERRAIN-GATE: REFUTED fail-closed gate/replay (blocking)

Actual mutations of the current directory, with affected hashes recomputed:

| Mutation | native_terrain_result | Replay exit |
| --- | --- | --- |
| terrain enabled/attempted/built false, draws=0, clean=0, problems=3, closeFailures=99, leakedProbes=3, failure notes | false | **0** |
| terrain `device="0xdead"` | **true** | **0** |
| terrain `device="0x0"` | **true** | **0** |
| total terrain draws=1, comparison/file still at draw 3598 | **true** | **0** |
| omit comparison.flipped | **true** | **0** |

Replay repeats only pixel comparison (`verify.py:1119–1121`), not terrain acceptance checks. `native_terrain_result:659–660` requires only a device string, without nonzero identity or equality to Minecraft/checkpoint/adopted identities. Its capture number is checked against the filename, but not positivity or total draws (`666–673,710–713`). This is a new counterpart of round-5's marker replay hole, and a concrete fourth defect beyond the three author-noted terrain failures (wrong retirement mechanism, stale-sized sample, wrong capture pair). Invalid terrain evidence can receive a successful replay, and invalid identities/capture totals can receive a successful stage terrain gate.

**Gzip path:** valid P6 gzip pairs decode and reproduce the figures. `pixel_oracle.py:205–221` imposes a 64 MiB decompressed limit and reads the footer; pixel mismatch, missing pair, wrong size and inconsistent aggregate are rejected. Truncating the gzip trailer throws **uncaught EOFError**, since the result/replay wrappers catch OSError/ValueError/KeyError/TypeError (`verify.py:691,1122`) but not EOFError. It does not produce false success, but breaks the promised structured failure result; this is non-blocking. Paths are not constrained to a local basename, and a marker sample is similarly not required to be a manifest member; do not treat manifest success as provenance authentication.

### Retirement and device replacement

**CONFIRMED ordinary same-device retirement:** `TerrainProbe:596–623` clears the static reference before queueing through Minecraft's actual encoder. Queue refusal and failed context ownership deliberately leak, set destroyed and increment leakedProbes; build refuses at three (`278–282`). This fixes the known VkFrameTracker retirement error and the stale-static route. Same-device resize in the retained run completed without reported leaks or validation events.

**R6-TERRAIN-DEVICE — REFUTED device-change refusal (blocking).** `ownedByCurrentDevice:626–630` compares only to `VkContext.get().device`, not Minecraft's active device. Adoption is one-shot (`McNativeVkContext:92–97`) and initAdopted retains an existing context (`VkContext:95`). When Minecraft moves from A to B while Voxy retains A, `adoptedContextReady` still returns true (`TerrainProbe:757–761`). Old terrain retirement accepts A, and replacement build uses resource/context A while labelling its probe with Minecraft wrapper B (`187–228,304–347`). Native recording then uses B's command buffer with A's resources. Heap-only real ownership/retire injection reproduced:

```text
terrainActiveMinecraft=222 adopted=111 ownershipAccepted=true
terrainOldEncoderRetirement callbacks=1 leakedProbes=0
```

No native operations were called; the old encoder's real destruction queue accepted the retirement despite active Minecraft being B. The remaining wrong-device allocation/recording route is source-established, not an induced GPU crash. `destroy` also lacks an owner recheck (`639–651`), while resource free uses global VkContext (`VkBuffer:92–97`), so a context replacement before a queued destruction callback is another unsafe conditional route.

**R6-TERRAIN-WAIT — REFUTED completion-guarded cleanup (blocking; source-level).** Build calls `endFrame`, then `waitForFrame` (`327–328`); wait throws on non-success (`VkFrameTracker:163–166`). The catch returns null, but finally frees the reference target, renderer and resources unconditionally (`TerrainProbe:351–365`) before any fallback completion check. Thus a submitted reference whose wait fails reaches immediate resource destruction without observed completion. The later tracker shutdown's checked wait protects its own fence/command buffer only; it occurs after those frees (`367–375`). The comment that the fence has been waited is a happy-path assumption. No hardware wait/device-loss fault was induced. Also, contrary to `TerrainProbe:370–372`, shutdown **does call vkDeviceWaitIdle** through `VkFrameTracker.destroy:227–240`; this is a documentation/performance discrepancy.

## 3. Tests and independent mutation checks

| Requested command | Fresh result |
| --- | --- |
| `./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true` | Exit 0; test task executed; **322 cases, 321 passed, 1 skipped, no failures/errors**. |
| `python3 -m unittest discover -s scripts/tests` | Exit 0; **86 cases**, 93.067 s. |
| `python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261006T083136-838975Z` | Exit 0; five replayed checks listed; figures independently agree. |

The sole JUnit skip is `VkBarriersTest.missingBarrierIsDetected()`. Only the intentional fill-buffer WRITE_AFTER_WRITE diagnostic appeared; its repeated console rendering is not a second event. No unexpected JUnit validation diagnostic was observed. Permitted tests do not launch Minecraft or certify its lifecycle.

**CONFIRMED repairs to the round-5 weak tests:** counted readback overrides suppress autoAgree (`test_marker_gate.py:172–176`); a zero near override stays zero. Stubbing `verify.recount_marker_sample=lambda *args:{}` now makes `test_the_retained_directory_replays_on_its_own` error on missing near, rather than pass. Replacing all crop origins with `[0,0]` makes `test_every_crop_states_its_origin_rather_than_implying_it` fail on crop pixels. Bypassing screenshot checks while keeping marker_report_checks makes `test_the_readback_is_what_carries_the_proof_not_the_screenshots` fail on its success assertion. The __main__ guard is now after every class (`test_marker_gate.py:945–949`); all discovered classes are defined before direct execution reaches it. Capture fields are present in the positive fixtures. These are independent in-memory mutations of imported functions; repository code was not edited.

**Remaining tests which can pass broken behavior:** `McNativeTerrainProbeTest.theFailureBudgetIsBounded` only inspects the constant and zero initial close failures; it passes if the render loop ignores the budget. Its flags-off test never enables the probe and cannot catch wrong-device retirement or unsafe reference cleanup. Marker orientation tests cover yellow or two correct patterns, but omit the good-mirror/all-cyan-actual counterexample. Terrain Python cases never compare terrain device with checkpoint/adopted devices, never bound sampleAtDraw by drawsRecorded, and never replay a failed terrain report. The split recording test uses warmed shared methods as described above. Retention tests assert helper errors, not that its stage caller converts every error into stage/run failure; source inspection confirms that conversion currently exists. These limits remain R5-TEST (non-blocking), not an assertion that every passing test is meaningless.

Review reproduction artifacts are temporary: `/tmp/voxy-r6-{gradle,python,replay,adversarial,test-mutations,java,extra,retire}.log`, `/tmp/voxy-r6-adversarial.json`, `/tmp/voxy-r6-adversarial.py`, `/tmp/voxy-r6-test-mutations.py`, `/tmp/R6{Marker,Extra,Retire}Probe.java`. Java compilation/execution used the candidate's freshly resolved test runtime classpath via a temporary Gradle init script. Run `python3 /tmp/voxy-r6-adversarial.py` for the listed evidence/proof/retention mutations, or `python3 /tmp/voxy-r6-test-mutations.py` for the test-strength mutations while these files remain. Their substantive inputs, results and production locations are captured above; these temporary files are not committed native evidence.

## 4. Survey accuracy (R5-DOC)

**CONFIRMED:** the survey explicitly preserves experimental status and the REDESIGN boundary (`633–634,689,769–773,822–825`); it does not claim an accepted diagnostic foundation. The stale-run citation warning and all four replay statuses (`432–460`) are correct. Independently rerunning all four yields: 070311 exit 1 (missing closeFailures); 073859 and 080931 exit 1 (missing leakedPipelines); 083136 exit 0. The first directory also lacks raw samples, as the table says, although the stricter field check is reached first. Older unavailable runs are now labelled narrative, not proof. The dated marker description is explicitly superseded, and the dark nether frame no longer asserts a proven cause.

**CONFIRMED terrain figures and scope:** synthetic inputs, same-device RGB reference agreement, clearing both attachments, no real-world data/composition/performance/LoD or compute-fed draw-count claim. The newest evidence reproduces 26116/0 and is source-bound. This is useful experimental evidence and is not production terrain completion.

**REFUTED unqualified residual claims:** the terrain sentence saying retirement “refuses to destroy across a device change” (`833`) is broader than its Voxy-context-only check. “Counts every deliberate leak” (`834`) is false for the marker shutdown paths. The prose that the orientation-free rule “holds” (`733–736`) understates its remaining attribution ambiguity. The replay claims must distinguish successful pixel recomparison from successful terrain acceptance. Historical “depth test and depth write” (`298–307`) remains stronger than a colour-only depth test: disabling writes on the LESS pipeline can leave the same final colour pattern because the ALWAYS base already establishes box/cell depths. No depth sample is retained. “Nothing is destroyed after an unconfirmed wait, anywhere” (`498`) is contradicted by the new terrain build finally block. The initial “Nothing here is implemented” (`15`) is stale when read as a current status. R5-DOC remains non-blocking; the acceptance boundary itself is accurate.

Evidence binding: the newest manifest lists **88 files**, **22 crops**, **54 raw samples**, **2,005,548 bytes**. All listed hashes match. All **403 source/build/script fingerprint entries** match this HEAD; changed_sources_during_run is empty. The recorded revision/final revision is dirty parent **3313c8093ca6b06fd38d6f5d091f3e2b743d36df**, not HEAD. Listed-input hashes supply strong source binding, not a full dependency/environment attestation. Native log identifies Apple M4 Pro and Minecraft Vulkan/MoltenVK 1.4.2 with the Khronos validation layer inserted; no `[vk-validation]` diagnostics appear. Adoption reports validation=true and **syncValidation=false** (`native.log:369`); clean validation is not proof that native synchronization validation was enabled. Expected Voxy-disabled errors and profile-certificate HTTP failures are present, separate from validation diagnostics. Full screenshot files are absent; their hashes/crop origins cannot prove parent extraction independently.

## 5. Safety for normal play

**CONFIRMED: I would ship these diagnostic changes dormant to an existing GL-backend player**, under the project's ordinary release checks. **CONFIRMED: I would ship them dormant to a Minecraft-Vulkan player as disabled Voxy**, with no native terrain functionality advertised. This is source/test-supported flags-unset safety, not a newly measured game smoke/soak.

The terrain TAIL hook calls renderIfEnabled (`MixinLevelRenderer:53`), whose first action is `if (!Boolean.getBoolean(FLAG)) return` (`TerrainProbe:177`). The only Minecraft attachment clear is behind that guard at `242–245`. It **cannot clear colour/depth with terrain unset**, regardless of the other flags. Plain launch gains no terrain flag: build.gradle adds it only when harnessNativeTerrain is present (`585–586`). Shutdown with no instance returns before touching a device (`TerrainProbe:676–680`).

The other paths remain dormant: marker first checks its flag (`MarkerDraw:267`); probe tick first checks its flag (`VulkanProbe:294–297`); feature augmentation returns the original set before calibration/GPU work when unset (`DeviceFeatures:76–78`); adoption performs only bounded attempted/status bookkeeping when unset (`McNativeVkContext:92–100`); release returns before device operations without an adopted context (`287`). On Minecraft Vulkan, VoxyClient still refuses the GL-dependent renderer (`93–114`). No new native draw, readback, file I/O, queue work, or attachment clear runs with all five properties unset. Existing GL backend/shared shader behavior is preserved within the permitted tests. The enabled-experiment defects above do not establish an unset-flags regression.

R5-API (low) remains: unused VkFrameTracker.waitIdle discards its result and drains frees (`220–224`), and VkContext.shutdown/destroy lacks an internal completion guard (`103–106,692–695`). R5-IO (low) remains: synchronous sample/file I/O, non-atomic JSON publication, and total diagnostic disk growth are not bounded. Terrain's registration close exception is swallowed (`TerrainProbe:420–423`); the request failure already consumes its budget, but closeFailures stays inaccurate. These are not acceptance of those APIs for future normal-play integration.

## What could not be checked, and why

No fresh Minecraft startup, native/live stage, gameplay smoke/soak, real device replacement, real queue-failure/device-loss/fence-error behavior or timing-pressure test was run: the user prohibited Minecraft/GPU-taking launch stages. Offscreen tests and heap-only ownership/callback probes cannot substitute for those observations. The failed-reference-wait cleanup finding is source-derived; the actual hardware fault was not induced. The marker replacement route was checked against production callsites and heap queue behavior, not by executing a real format-change render frame.

No raw depth, original-orientation full marker readback, full parent screenshots, per-comparison device/image-generation/lifecycle identity, native synchronization-validation enablement, real-world mesh/atlas/LoD integration, coexistence with Minecraft's scene, alternate host/GPU, or long-session exhaustion proof is available. Earlier absent run directories cannot be reconstructed. Source hashes bind the listed inputs, not dependency binary hashes or the entire runtime environment. Consequently the retained synthetic RGB comparison is confirmed, dormant safety is supported, and the foundation/integration verdict remains REDESIGN.
