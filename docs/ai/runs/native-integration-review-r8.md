VERDICT: REDESIGN

Independent round-8 review of **3421640fad2e909f3c5661fea5e771dde601797d**, in `/Users/xyz/orca/workspaces/voxy/native-review-r8`, 2026-10-07. HEAD matched and the initial tree was clean. No implementation, fixture, threshold or committed evidence was changed. No Minecraft launch, native/live runner or interop runner was executed. Review artifacts and supporting experiments are under `.agent-run/`.

The rounds 4–7 boundary still applies: **even an accepted diagnostic foundation would remain REDESIGN for the delivery target**. This foundation is not accepted. Four of the six specifically requested round-7 repairs are confirmed; B1 and R6-TERRAIN-GATE retain residuals. The actual terrain RGB measurement, quantised depth-copy result, isolated ladder launch and ladder colour counts are confirmed. The ladder's band-wide bound and conclusion about the hook are refuted. Green replay and unit tests do not resolve these findings.

## 1. Each round-7 residual

| Finding | Repair claim | Closed in the requested scope | Result |
| --- | --- | --- | --- |
| B1 | REFUTED as complete; colour/sample-membership repairs CONFIRMED | No | Required raw samples are now members; required reports, summary and source binding can still be omitted from the manifest. Marker capture association also remains unchecked. |
| B3 | CONFIRMED | Yes | Feature/member offsets are exactly pinned; impossible offsets and vacuous proof sets fail. |
| B4 | CONFIRMED for the rejected-crop clamping residual | Yes | Every expected region must fit; the 1×1 black attack fails. This does not certify all marker provenance, which remains a B1 residual. |
| R6-TERRAIN-GATE | REFUTED as complete; canonical-hex identity and membership repairs CONFIRMED | No | Decimal adopted identity disables replay's expected-device comparison. |
| R6-TERRAIN-DEVICE | CONFIRMED | Yes | A→B refusal publishes failure to disk before stopping; A restoration preserves it. |
| R7-DEPTH-GATE | CONFIRMED for the three contradictions, removed heuristic and missing replay call | Yes | Raw depth is recounted; no convention is inferred; replay invokes the depth gate. Ancillary metadata/identity limits are listed separately below. |

### B1 — source colours and evidence membership

**CONFIRMED:** `scripts/verify.py:61–85,351–354` pins magenta/cyan/yellow to `McNativeMarkerDraw.java:124–133`, as geometry is pinned. The complete round-7 colour-swap attack replaces every retained magenta pixel with cyan and changes `markerRgb` to cyan, with refreshed hashes: **replay 1**, rejecting the published colour. Source constants agree; no remaining producer-selected marker RGB reference was found on this acceptance path.

**CONFIRMED:** `verify.py:1950–1964` requires selected marker, opposite marker, terrain native/reference, depth and ladder raw samples to be manifest members. Removing each selected entry, retaining its file, returns **1**. The opposite and reference removal attacks now fail as requested.

**REFUTED as complete (blocking B1):** the round-7 core-membership attack remains successful. Removing each of `native-marker-draw.json`, `native-terrain-probe.json`, `native-adopted-context.json`, `summary.json`, `source-sha256.json` and `native.log` from the manifest while leaving the file **replays 0**. The same is true for `ladder/native-depth-ladder.json`, `ladder/native-result.json` and `ladder/native-ladder.log`. `verify.py:1865–1873` validates whichever hashes are listed; `1952–1960` builds its required membership list only from samples. The summary supplies checkpoint identity, so its unbound status directly affects proof identity, not merely ancillary prose. All actual committed files are properly hashed; the defect is accepting alternatives with binding removed.

**Additional evidence association residual:** set the marker's `readback.sampleAtDraw=2` while retaining `native-marker-sample-4562.ppm`: **replay 0**. `verify.py:385–390` checks positivity/total, and `654–773` recounts pixels, but never compares the selected filename with the capture count. The source/name association claimed in round 7 is still not enforced. This review closes B4's explicitly requested clamping residual only; it does not call marker evidence binding complete.

### B3 — feature offsets and vacuity

**CONFIRMED:** `verify.py:1499–1549` requires exactly four requested features, exactly one valid verification note per feature, and its member offset equal to `VK_FEATURE_OFFSETS` (`71–76`). Offsets `1000001..1000004`, empty verification notes and jointly empty requested/verified sets each produce **replay 1**. A heap-only query of this checkout's resolved LWJGL classes reports `VkPhysicalDeviceFeatures.SIZEOF=220` and offsets **40/160/104/100**, agreeing with the gate and retained notes. No vacuous verification set was found to pass. This confirms the requested offset repair, not every enabled feature's independent runtime functionality.

### B4 — the rejected crop must contain the regions

**CONFIRMED:** `verify.py:591–608` rejects out-of-crop or empty regions rather than clamping them. Replace the actual opposite sample with gzipped P6 **1×1 black**, set its rect to `[0,0,1,1]`, and refresh hashes: **replay 1**, because the near region is absent from the crop.

Actual opposite recount is near **0/12420**, far **0/8316**, cell **0/4032**, strip **0/4224**, rejected-in-box **0**, satisfiedRegions **0**. Selected recount is near **12420/12420**, far **8316/8316**, cell **4224/4224**, strip **4032/4224**, rejected-in-box **0**. This supports exactly one pattern-bearing orientation and clean boxes in both, within the survey's honest attribution limit (`survey:901–903`). It does not identify unique draw provenance or independently prove LESS-pipeline depth writes.

### R6-TERRAIN-GATE — mutation table and adopted device

**CONFIRMED individual repairs:** replay calls `terrain_report_checks` with an adopted handle (`verify.py:1909–1921`), and reference/opposite membership is now required. On the newest retained run, each of these mutations produces **1**: enabled=false; attempted=false; built=false; drawsRecorded=0; timesClean=0; timesWithAProblem=3; closeFailures=99; leakedProbes=3; deviceDiverged=true; failure notes; device=`0xdead`; device=`0x0`; removed terrain-reference membership; removed rejected-marker membership.

**REFUTED as complete (blocking residual):** change only the adopted proof's handle spelling from hex to the equivalent decimal string, and terrain's device to `0xdead`: **replay 0**. Proof-file identity parsing accepts decimal (`verify.py:1468–1488,1586–1587`), so all other identities still agree. Replay parses an expected terrain device only when the adopted string starts with `0x`, otherwise passes **None** (`1915–1918`). `terrain_report_checks:1323` then skips equality. A valid alternative serialization makes the proof comparison vacuous. The actual retained canonical-hex run is consistent.

### R6-TERRAIN-DEVICE — reproduce A→B and inspect disk

**CONFIRMED:** `McNativeTerrainProbe.java:212–235` checks the current Minecraft handle, stops for the session on divergence, safely abandons stale resources, then calls `writeEvidence()` before returning. The fresh heap-only reflection probe invokes the actual candidate `renderIfEnabled`; no production bytecode is replaced and no Vulkan commands execute. It seeds disk with the actual clean report, keeps adopted A=111, substitutes active B=222, and constructs an uninitialised owned probe solely to exercise the guard.

Observed in `.agent-run/r8-device-injection.log`:

```
AtoB instanceNull=true diverged=true leaks=1 priorDiskUnchanged=false
BtoA stillStopped=true preservesFailureReport=true
```

The file read after A→B says `deviceDiverged:true`, `leakedProbes:1`, and records the refusal in notes. Its failure survives A restoration. The requested stale-clean-report residual closes. This is control-flow/failure-publication evidence, not a live device-loss or queued-destruction test.

### R7-DEPTH-GATE — three contradictions and raw depth

**CONFIRMED:** the convention inference is removed (`McNativeDepthProbe.java:210–234`), and the source retains 16-bit big-endian PGM (`267–295`). `verify.py:1138–1145` rejects convention claims, `1198–1255` recounts histogram/extrema/band means, and replay calls the same depth gate (`1901–1908`).

Round-7 contradictions on the newest report, refreshed hashes:

| Mutation | Replay |
| --- | --- |
| uniform=false, min=0, max=1, bottomMean=0.8, reversedZ=true; unchanged zero histogram | **1** |
| All 1,639,680 histogram entries in last bin while min=max=0 | **1** |
| depthVkFormat=37 and device=0x0 | **1** |
| Negative width/height with unchanged positive product | **1** |

The contradictory summary without `reversedZ` also fails the unit regression. Independent PGM decoding finds **1,639,680 quantised zeros**, 1708×960. It certifies the retained 16-bit values; it does not independently establish bit-exact original float zero. The gate allows two quantisation steps of summary error (`1217–1218`). Convention remains unmeasured.

**Remaining limits, separate from the named repair:** `clearedValue=1,clearedShare=0` **replays 0** despite the zero histogram, because those fields are type-checked but never recounted (`1171–1174,1245–1255`). Foreign nonzero depth device `0xdead` also **replays 0** (`1154–1162` never compares it with checkpoints). The former does not change the negative answer; the latter is an additional B1 evidence-identity residual. Removing the depth report and its manifest entry silently skips depth and **replays 0** (`1901–1902`) despite the retained summary enabling it. These limit any claim of comprehensive proof replay.

**Additional non-blocking quantisation defect:** a valid constant source float depth **0.0624999** is in histogram bin 0; 16-bit rounding produces **4096/65535 = 0.0625009537**, in bin 1. The source reports the original-float histogram (`DepthProbe:187–193`), but replay requires exact quantised histogram equality (`verify.py:1231,1242–1244`), so this valid retained measurement fails. Independently reproduced in `.agent-run/r8-depth-quantization.json`. This is a false rejection, not a false acceptance, and does not affect the current zero-depth artifact. The gradient fixture summarises already quantised values (`test_marker_gate.py:1081–1096`), so it cannot expose that producer/recount difference.

## 2. New depth ladder

### Mechanism

**CONFIRMED:** the source opens Minecraft's main colour/depth pass with both clear optionals empty (`McNativeDepthLadder.java:200–242`), records eight in-range NDC depths with viewport [0,1] (`266–295`), and creates LESS and ALWAYS pipelines through the same helper (`590–593`). Both have `depthTestEnable(true)`, **`depthWriteEnable(false)`**, depth-bounds and stencil disabled (`645–655`). There is no Minecraft-depth clear, copy-to-depth or shader depth-write path. It copies colour for readback after closing the pass (`312–328`). Minecraft owns pass transitions and submission.

**REFUTED that the controls differ only in comparison, or exclude all “nothing drawn/covered” explanations (blocking R8-LADDER-MECHANISM):** rungs occupy y **0.36..0.20**, inline controls **0.40..0.37**, separate control **0.16..0.06** (`74–86,278–295`). Their footprints do not overlap; the inline stripe is adjacent, not a test of the same pixels. They also use different colour, pipeline and separate draw commands. They demonstrate that ALWAYS colour work in nearby columns reached the copy; they do not supply a positive LESS-path control. A missing/broken LESS draw path, spatial masking/overwrite confined to the rung rows, or a wrong spatial interpretation can leave controls intact. Immediate source-ordered readback reduces later-overwrite risk, but the stripe alone does not exclude it. No such runtime defect is alleged in the committed run.

More decisively, **ordinary spatially varying depth** can explain all eight empty rungs without any broken draw: each column tests a different `d_mc(x,y)` at a different threshold. There is no measured common depth to bracket. A prefix is not a physical necessity across different columns; a deeper rung in a deeper region can pass after a shallower rung in a shallower region fails. The source's prefix attribution (`399–410`) and gate's corresponding claim (`verify.py:918–943`) treat different regions as one sample.

### Isolation and identity

**CONFIRMED for the actual retained launch:** `verify.py:2129–2157` launches a separate game directory with native/features/adopt/probe/depthladder; no terrain, marker or depth-copy property is passed. `summary.json` retains that distinct command. `ladder/native-depth-ladder.json` has both enable flags false and both draw counts zero. Its log contains the ladder measurement and no terrain/marker draw. These observations agree with source flag guards.

All eleven ladder summary checkpoints are **exactly equal** to the checkpoints in its own `ladder/native-result.json`. Their device is **0x77dfd4c018**, whereas the first launch's is **0x77b2c00018**. Replay compares against the ladder summary checkpoints (`verify.py:1929–1935`), not the first launch. Changing one of those summary checkpoints to another device fails (**1**). Isolation is retained and independently confirmed within the saved evidence; there was no fresh launch.

**Gate limitation:** modifying a device in the own `ladder/native-result.json`, with refreshed hash, **replays 0**; replay consumes only the summary copy of checkpoints, never requires their agreement with the own result. Removing own result/log/report manifest membership also passes. The committed copies agree; the general consistency claim is weaker than the retention claim.

### Gate and mutations

**CONFIRMED in finite, well-formed ordinary cases:** stage and replay share `ladder_report_checks` (`verify.py:1064,1935`); depths/bands are pinned (`891–902`), filenames/counts are checked (`968–974`), orientation is required and used (`827,980–991`), regions must fit (`992–996`), survivor/fill consistency and prefix are required (`920–943`), and fills are recounted (`1014–1046`). Actual crop is **1025×164**. Recount: all rungFill **0**, inline fills **0.9260–0.9333**, separate fill **0.9782**, agreeing with the report.

**REFUTED general gate soundness (blocking R8-LADDER-GATE):** the mutation table uses fresh copies of the newest full evidence directory, changes ladder files under `ladder/`, refreshes relevant hashes and calls full replay. Hash integrity alone is not the challenge. Complete results: `.agent-run/r8-attacks.json` and `r8-extra.json` (68 + 8 mutations).

| Ladder mutation | Replay |
| --- | --- |
| Writes on; convention-measured flag true; either contamination flag/count nonzero | **1** each |
| Foreign device; not completed; no draws; problem/close/leak/divergence/notes | **1** each |
| Wrong capture count/name; wrong orientation; shifted crop; 1×1 crop | **1** each |
| Wrong finite depths/band/bounds; inconsistent survivors; non-prefix; report fills 0.3 versus empty pixels; missing/weak controls | **1** each |
| `rungDepths=[NaN]*8`, `upperBound=NaN` | **0** |
| All three published bands `[NaN]*4` | **0** |
| All rung fills, inline fills and control fill NaN | **0** |
| Add `reversedZ:true` with `zConventionMeasuredHere:false` | **0** |
| Replace crop with fabricated **48×17** controls and empty rungs; report **80×100** target instead of 1708×960, matching small rect/fills | **0** |
| Make half of first rung magenta; publish rungFill[0]=0.5, retain “not survived” and upperBound=0.0625 | **0** |
| Own-result checkpoint identity contradicts summary | **0** |
| Remove referenced raw ladder sample's manifest membership | **1** |
| Remove ladder report and manifest entry entirely | **0**, now listed as no retained ladder |

NaN passes `abs(a-b)>tolerance`, `<floor` and fill agreement comparisons because no finite check exists (`verify.py:892–902,911–943,1043–1046`); the recount uses pinned bands, so it never trips over the NaN published ones. Python's JSON parser accepts these values, and Java float serialization can emit them. Thus the claimed pins and aggregate equality can become vacuous. The target extent remains a **producer-controlled reference** (`883–885,979–991`), never tied to the own launch/capture extent. A geometrically self-consistent tiny replacement can stand in for the real measured frame. No fixture or gate threshold was shrunk in this review.

The half-filled mutation demonstrates a separate semantic flaw: **“<80% survived” is not “depth rejected every pixel.”** `verify.py:926,1026–1029` accepts the former as a failed rung and emits an upper bound (`1067–1070`). Fifty percent can visibly survive a LESS test above the claimed upper bound. This is not floating-point tolerance or background quantisation.

### Bound, convention, and hook conclusion

**REFUTED band-wide ≤0.0625 (blocking R8-LADDER-BOUND):** even assuming perfect rasterisation and no overwritten pixels, all empty rungs imply only **column i has depth ≤z_i** at its covered pixels. Thresholds are **0.0625,0.1875,…,0.9375**, not eight tests of 0.0625 at every location. Concrete valid depth field `d_i=z_i/2` gives **0.03125,0.09375,0.15625,0.21875,0.28125,0.34375,0.40625,0.46875** across the eight columns. All LESS tests fail; all ALWAYS controls can fill; most columns exceed 0.0625. Running `LadderGateTest.run_gate(report(),crop())` with the identical colour outcome succeeds and claims upper=0.0625. The gate cannot distinguish that legitimate scene-depth field from uniform zero. Nonzero fills below 80% further invalidate pixel-wide upper bounds. The actual zero-magenta crop stands; its inferred uniform bound does not.

**REFUTED “this hook cannot support coexistence under either convention” (blocking R8-LADDER-CONCLUSION):** neither the invalid band-wide bound nor a valid ≤0.0625 observation in only one band establishes that the entire attachment lacks usable scene depth, throughout gameplay, or that no correct comparison/mapping could compose terrain at this hook. Even a true low-depth bound can occur on valid conventional-Z geometry. Reverse-Z scene depth can be small but nonzero. A test at depths below the smallest rung was not performed. Searching other hook positions is an investigation proposal, not a logically forced result.

The survey also makes an explicit comparison error (`1010–1012`): **LESS at positive z against zero rejects the fragments regardless of the convention**; the measured experiment demonstrates that. Reverse-Z proximity semantics do not make LESS “reject nothing.” An intended GREATER-based reverse-Z argument would still need its own justified evidence and mapping, and does not follow from these data.

**CONFIRMED:** Z convention is still **UNMEASURED**, source publishes `zConventionMeasuredHere:false`, and setting that field true fails. No known near/far scene geometry or controlled camera-distance change measures direction here. Extra `reversedZ:true` being ignored is a gate-schema hole, not an actual producer convention claim.

**REFUTED the additional claim that “the copy was not the broken part” (`survey:1007–1008`):** agreeing with a loose behavioural bound does not establish a correct whole-image float transfer. A varying field below its respective thresholds, wrong image, or bad copy can give the same colour observation. **CONFIRMED:** the survey explicitly leaves the cause unknown (`1023–1026`); it asserts no specific clear/discard mechanism. Its premise that the whole band reads near zero is nonetheless stronger than demonstrated.

## 3. Tests and fixture strength

| Requested command | Independently observed result |
| --- | --- |
| `./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true` | **0**; test task executed, 329 cases, **328 passed / 1 skipped**, 0 failures/errors; 29 seconds |
| `python3 -m unittest discover -s scripts/tests` | **0**; **148 cases**, 257.182 seconds, OK |
| `python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261007T005606-024013Z` | **0**; nine listed replay checks, ladder included |

**CONFIRMED counts**, from fresh JUnit XML and Python output, not handoff prose. JUnit's sole skip is `VkBarriersTest.missingBarrierIsDetected()`. `verify.junit_result` reports success, that known gap, and no unexpected diagnostics. The deliberate fill-buffer synchronization control passed. Native evidence is separately scoped and is not a fresh Minecraft test.

**Independently demonstrated test escape:** a temporary Java agent changes the candidate ladder pipeline bytecode's actual `.depthWriteEnable(false)` argument to **true**, without altering its JSON literal. All three `McNativeDepthLadderTest` methods still pass when invoked with their JUnit assertions: `thePublishedGeometryIsTheOneTheGatePins`, `theDefaultEvidenceClaimsNothingUnmeasured`, `theLadderIsInertWithoutItsFlag`. Log: `.agent-run/r8-broken-ladder-tests.log`; source: `.agent-run/R8BrokenLadderAgent.java`. These tests truthfully exercise constants/defaults/inertness; they do not guard the essential enabled no-write mechanism. No mutated pipeline was executed on a GPU and no class file in the checkout was edited.

Other weak or bounded coverage:

- `McNativeTerrainLifetimeTest.java:24–106` still checks defaults/unflagged/no-instance paths. Restoring broken enabled divergence publication or submitted-reference cleanup can leave all four tests green; none exercises those paths. The test comment's narrowed description is appropriate, but these are not lifetime regressions.
- `LadderGateTest.test_a_surviving_prefix_bounds_the_depth_from_both_sides` and `test_every_rung_surviving_gives_only_a_lower_bound` (`test_ladder_gate.py:148–158`) encode a common-depth inference across spatially distinct columns. The positive all-empty fixture (`134–146`) is equally compatible with an absent LESS path. The fixtures paint requested colours; they never represent a spatial depth field or independently execute the draws. These cases pass the broken inference they purport to validate.
- `DepthProbeGateTest.test_a_uniform_image_is_reported_as_not_observing_the_depth` has the vacuous assertion `assertFalse(result["readable"] and False)` (`test_marker_gate.py:1132`); either readable value satisfies it. Its other assertions remain useful. Here readable means completed-copy, so the test should not be cited as proving scene readability.
- Marker retention positive test (`test_marker_gate.py:798–814`) asserts selected recounts, not opposite recount results. A fresh in-memory substitution `recount_rejected_orientation=lambda *a:{}` leaves that test passing (`.agent-run/r8-test-escapes.log`); new specific crop-failure tests provide additional coverage, but do not make this one comprehensive.
- The full/split terrain test shares renderer helpers and warmed state. It proves bounded agreement, not independently correct synthetic geometry, fresh split-only initialisation, alpha or depth equivalence.

**CONFIRMED fixture-size discipline:** existing Python marker/ladder full-frame fixtures remain **960×540**, never modified. Marker pass-cell area is **960** there, versus **448** at 640×360, below the real 500 floor. Keep that floor and fixture size. The ladder fixture resolves all controls to nonempty rectangles; its defect is shape/assumption (different columns with painted prefix), not insufficient total area. The new depth PGM fixture is 48×32 with a row gradient; at H<50 its mean bands are one row. It can miss float/quantisation-boundary and NaN cases, but no unchanged marker fixture was shrunk to obtain a pass.

## 4. Terrain/depth results, survey accuracy, and retained identity

**CONFIRMED terrain experiment:** all **22** retained native/reference PPM pairs have byte-identical decompressed images. Non-background counts independently reproduced: **20663** at 1708×960 and **26116** at 1920×1080; report-selected capture **4795** has 0 RGB mismatches. Images are nonblank and were decoded/viewed. Source clears Minecraft colour/depth before the synthetic terrain draw; it does not copy reference pixels into Minecraft. This remains same-renderer RGB agreement on the adopted device. It does not establish live LoD input, atlas/lighting parity, scene coexistence, alpha/depth agreement or normal gameplay.

**CONFIRMED bounded depth-copy result:** the current combined launch retains completed D32_SFLOAT copy evidence and quantised all-zero pixels, with the source convention inference removed. **Isolation remains unconfirmed for that depth-copy launch:** terrain and marker were enabled there. The survey now correctly identifies the unretained depth-only run as narrative (`934–940`). Ladder isolation does not retroactively isolate the depth-copy experiment. Uniform depth at a hook is a negative observation for this attempted route, not a universal impossibility of buffer copies.

**CONFIRMED the survey's complete current replay table (`447–456`):** independently replayed all eight retained runs:

| Run | Exit / reason |
| --- | --- |
| 20261006T070311-099413Z | 1; missing closeFailures |
| 20261006T073859-396220Z | 1; missing leakedPipelines |
| 20261006T080931-279390Z | 1; missing leakedPipelines |
| 20261006T083136-838975Z | 1; missing opposite-orientation sample |
| 20261006T084300-332469Z | 1; missing opposite-orientation sample |
| 20261006T095436-054038Z | 1; depth lacks zConventionMeasuredHere |
| 20261006T165039-062378Z | 0; explicitly no ladder replay |
| 20261007T005606-024013Z | 0; ladder included |

The newest **134 manifest entries** match their files. All **408 source/build/script fingerprint entries** match this HEAD and changed_sources_during_run is empty. The retained run's revision/final_revision is **711c80371fa51408f13fbb40c2e4f32903a8c2ce**, with candidate code captured before its commit; source hashes provide current code binding, not a claim that HEAD itself was the launch revision. Native/ladder logs show actual Minecraft Vulkan, Apple M4 Pro, MoltenVK 1.4.2 and Khronos layer insertion; fresh diagnostic scanning finds zero Vulkan diagnostics in both. Adoption logs report `syncValidation=false`; this review does not separately establish native synchronization-validation activation from that label. Source hashes do not attest every dependency/environment binary.

**REFUTED ladder-section strength:** control exclusion (`974–979`), uniform band bound (`998,1005–1008`), copy correctness, and hook incompatibility (`1010–1018`) exceed the evidence for the reasons above. Gate-description mathematical necessity and aggregate guarantees (`1030–1035`) also overstate the mechanism. The isolated launch and recounted colour statistics themselves are honestly retained. Convention/cause limits are appropriate. Handoff/current-state repeat the unsupported band bound and coexistence conclusion.

Other non-blocking survey drift remains: “Nothing here is implemented” (`survey:12`); the dated round-7 paragraph's unqualified “replay returns 0” (`916–917`) although that directory now fails, notwithstanding the correct table; historical marker depth-write acceptance exceeds colour-only evidence. The explicit “not accepted”/experimental foundation boundary is correct.

## 5. Safety for normal play

**CONFIRMED: I would ship these changes dormant to a player on the established Voxy GL backend**, subject to ordinary release checks. **CONFIRMED: I would ship them dormant with Minecraft Vulkan as disabled Voxy**, clearly without native LoD functionality. I would not ship this as a functioning native Voxy integration. This is source/test-supported dormant safety, not a new play-session certification.

With `.probe`, `.marker`, `.features`, `.adopt`, `.terrain`, `.depth` and **`.depthladder` all unset**:

- Ladder returns before Minecraft/device access (`McNativeDepthLadder.java:179–182`). Null-instance shutdown returns before device acquisition (`719–724,734–738`). The render-tail and close hooks therefore add bounded Java calls but no ladder GPU/file work.
- Terrain, depth-copy and marker guards return before GPU work; feature augmentation preserves the requested set without its flag. `McNativeVkContext.adoptIfRequested:92–100` only updates bounded bookkeeping; release returns before device wait if not adopted (`287`).
- Existing backend selection preserves GL and disables Voxy when Minecraft has no GL context (`VoxyClient.java:82–116`). No new unflagged ladder path creates native resources or changes shared shaders/GL renderer code.

**CONFIRMED even when flagged: the ladder itself never writes/clears Minecraft depth**, by its LOAD pass and both pipeline write-disable settings. It writes colour and may perform attachment/transfer synchronization; “read-only depth” is not “no GPU state transitions.” Enabling terrain/marker simultaneously can independently modify depth, which is why isolation is required. The flagged ladder's mathematical/evidence failures are diagnostic acceptance blockers, not unflagged normal-play side effects.

## 6. What could not be checked, and why

Minecraft/native/live execution was expressly prohibited. No fresh hook, attachment state, live depth direction, lifecycle stress, device-loss, sustained-memory pressure, normal-play GL parity or native coexistence was measured. Saved evidence was inspected/recounted instead. Full original screenshots are not committed; their crops/origins cannot independently attest extraction from the original whole frames. The 16-bit depth artifact cannot establish original float bits or copy correctness. Temporary CPU reflection/bytecode probes establish their specific control-flow/test escapes, not GPU execution or live mixin application.

Reproduction artifacts: `.agent-run/r8-{junit,python,replay}.log`, `r8-junit-result.json`, `r8-all-replays.json`, `r8-attacks.{py,json,log}`, `r8-extra.{py,json,log}`, `R8DeviceProbe.java`, `R8BrokenLadderAgent.java`, and device/broken-test logs. Run `python3 .agent-run/r8-attacks.py` and `python3 .agent-run/r8-extra.py` from this checkout to recreate the hash-refreshed replay tables; they launch no game. Java probes use the test runtime classpath printed by the temporary `r8-classpath.gradle` task, compile into `/tmp`, and invoke the candidate classes there. No implementation was repaired or promoted.
