VERDICT: REDESIGN

Independent round-17 review of **19b2444a5a5c40dca47160ece609970caf6a4c07**, 2026-10-09, in `/Users/xyz/orca/workspaces/voxy/native-review-r17`. Initial tree clean and HEAD matched the requested candidate. No implementation, test fixture or committed evidence was changed. No Minecraft/native/live stage was launched. Only review files and `.agent-run/` artifacts were written.

**CONFIRMED: the coexistence measurement stands, the attached short launch forms are repaired, and all five surviving test guards are now detected. REFUTED: complete Gradle launch semantics and blanket documentation closure.** The standing DELIVERY-BOUNDARY remains blocking. R16-COEXIST-LAUNCH-SEMANTICS remains open on other actual enabling CLI spellings.

| Candidate claim | Judgment |
| --- | --- |
| (a) Launch-semantics repair and all non-blocking items addressed | **REFUTED as a whole.** Short attached forms repaired; guard tests repaired; other enabling CLI forms still escape; two handoff details remain stale. |
| (b) Current retained, reconciled, required coexistence evidence establishes the bounded measurement | **CONFIRMED for the original retained run and exact-RGB composition; REFUTED for unconditional launch-required proof.** |
| (c) Survey claims no more than established evidence | **CONFIRMED for observation, historical figures and delivery exclusions; REFUTED for the complete launch-semantics repair claim.** |

## 1. R16-COEXIST-LAUNCH-SEMANTICS

**CONFIRMED — original round-16 short-form attacks are repaired.** `scripts/verify.py:1198–1212` accepts attached `-PharnessNativeCoexist=true`, `=false`, `=`, bare `-PharnessNativeCoexist` and arbitrary attached values. Replay uses the helper at `:2653`; the native stage uses it at `:2890`; `coexist_checks:1243` requires evidence when enabled. With off/empty report, all 48 coexist images and 24 recognized coexist log lines removed, and hashes refreshed, the original `=true`, `=false`, bare, `=` and literal `=off` commands all exit **1**. Missing command or omitted property exits **0**; the neighbour `-PharnessNativeCoexistence=true` exits **0**, appropriately not enabling coexist. Without silencing the log, even the omitted-property downgrade exits **1**. Keeping crops also exits **1** on orphan inventory. These packages retain the saved summary's enabled results, illustrating the stated limit on general saved-field reconciliation.

**REFUTED — helper agrees with Gradle for all enabling forms.** It recognizes only attached `-P` tokens (`verify.py:1210`). Actual Gradle accepts other forms that the helper calls false. Independently ran `./gradlew help --offline --console=plain -I .agent-run/r17-inspect-launch.gradle` with each of 15 constructed forms for **all nine** harness switch properties (`harnessNative`, Probe, Marker, Features, Adopt, Terrain, Depth, DepthLadder, Coexist). No game task. Every command exits 0; the init script retains actual `hasProperty` results and harnessClient VM args. All seven mismatching forms enable all nine properties, including `-Dvoxy.native.coexist=true` in the run configuration (`build.gradle:602–603`). Matrix: `.agent-run/r17-launch-matrix.{py,json,log}`; individual Gradle logs `r17-gradle-form-*.log`.

| Form, using coexist as the example | Gradle hasProperty | launch_enables | Judgment |
| --- | --- | --- | --- |
| attached `-PharnessNativeCoexist=true`, `=false`, `=`, bare, `=x=y` | true | true | CONFIRMED |
| `-PharnessNativeCoexistence=true`, lowercase property name | false | false | CONFIRMED |
| duplicate attached true then false | true | true | CONFIRMED |
| `-P harnessNativeCoexist=false` | true | false | REFUTED |
| `-P harnessNativeCoexist` | true | false | REFUTED |
| `--project-prop harnessNativeCoexist=false` | true | false | REFUTED |
| `--project-prop=harnessNativeCoexist=false` | true | false | REFUTED |
| `--project-prop harnessNativeCoexist` | true | false | REFUTED |
| `-Dorg.gradle.project.harnessNativeCoexist=false` | true | false | REFUTED |
| `--system-prop org.gradle.project.harnessNativeCoexist=false` | true | false | REFUTED |

Three complete retained-package counterexamples independently replay **0**: `downgrade_silent_log_command_long`, `..._split`, `..._system`. They differ from the accepted non-enabling-command rewrite: **their retained commands still launch the experiment**. All selected source fingerprints remain unchanged and match HEAD's sources. Reproduce:

```sh
python3 .agent-run/r17-attacks.py downgrade_silent_log_command_long downgrade_silent_log_command_split downgrade_silent_log_command_system
python3 scripts/verify.py --replay-evidence .agent-run/r17-attacks/downgrade_silent_log_command_long
```

The second command was independently executed as the real CLI and exited 0 (`.agent-run/r17-long-cli-replay.json`). This is the same launch-authority inconsistency as R16, now narrowed to additional spellings, so `round16_findings_closed.R16-COEXIST-LAUNCH-SEMANTICS` is **false**. No external environment/property-file resolver was tested; these counterexamples need only the command itself.

## 2. Coexistence gate and current measurement

**CONFIRMED — bounded per-pixel measurement.** Independent decoder `.agent-run/r17-independent.py` imports no verification gate. It decoded **all 24 original pairs**, **2,114,816 pixels**, zero wrong presence/absence, zero uncovered RGB changes, zero outside-palette pixels. All published counts agree. Mixed draws are **2, 3842, 4562, 4802**. All **388,539 complete 4×4 blocks** in the selected, rejected and coexist crops agree with their corresponding frame thumbnails. Independent images and histogram/count artifacts are retained under `.agent-run/r17-*`; before/after descend-4802 thumbnails were visually inspected. Original replay exits **0**, with ten named checks, **408** matching source inventory entries and **277** manifest members. Manifest records build revision `9c6cf401da235d41cff97dddda4cfb3b5937e54f`; `summary.json` records `changed_sources_during_run:[]`. This proves current selected source-byte equality, not that the later documentation-only HEAD hash already existed during capture.

**CONFIRMED — same frame/targets and sound bracket rule, source-scoped.** `McNativeDepthLadder.java:285–286` obtains the two views once; `:336–371` records first LOAD pass, first readback request, second LOAD pass, coexist draw and second readback request. The copy is requested after closing the pass. `:406–423` consumes pipeline 3, rung 4 (2⁻⁸), colour (0.5,1,0.5); `:130–132` selects `VkDepth.COMPARE_OP`, which is GREATER_OR_EQUAL (6) at `VkDepth.java:61`. LESS rung i survives when zi < d, so rung 3 means z3 < d ≤ z4, including equality; z4 ≥ d then passes the quad. LOW/rungs 0–3 require it; rungs 4–7 forbid it. At descend 4802, **44,694** rung-3 pixels become exactly (128,255,128), and **54,378** rung-4 pixels remain (0,255,0). Equality is sound by source semantics; no raw native equality pixel is retained.

**CONFIRMED — exact RGB, duplicate refusal, counts and log reconciliation.** `verify.py:1302–1339` compares exact quad bytes and byte-identical uncovered pixels; `:1262` rejects repeated results; `:1240` rejects duplicate log draws; `:1269` checks the log draw set; `:1331` compares each draw's counts. Java performs the corresponding exact comparisons in `McNativeDepthLadder.java:536–567`. RGB bucket attacks reconcile their altered report and log counts, use a partial boundary strip to isolate byte semantics from thumbnail anchoring, and still fail. The duplicate and off-by-one log-count attacks fail.

Full mutation table: complete copied packages, no shortened crops/fixtures, manifests refreshed after each mutation, source fingerprints unchanged. Exact errors and packages are retained in `.agent-run/r17-attack-*.json` and `.agent-run/r17-attacks/<name>/`; aggregate `.agent-run/r17-attacks-all.json`. **Only the six cases marked 0 below pass.** Three are enabling-command escapes; three are coordinated rewrites to absent or genuinely non-enabling commands.

| Mutation | Exit | Result |
| --- | ---: | --- |
| `coexist_rung_lie` | 1 | ValueError: ladder gate: ['ValueError: the ladder publishes coexistRung=3, coexistRgb=[0.5, 1.0, 0.5], not the 4/[0.5, 1.0, 0.5] its source lays out'] |
| `counts_lie` | 1 | ValueError: ladder gate: ['ValueError: coexist at draw 4562 reports present=44695 but the retained crops say 44694'] |
| `downgrade_command_off` | 1 | ValueError: ladder gate: ['ValueError: the ladder log holds coexist lines for draws [2, 242, 482, 722, 962, 1202, 1442, 1682, 1922, 2162, 2402, 2642, 2882, 3122, 3362, 3602, 3842, 4082, 4322, 4562, 4802, 5042, 5282, 5522] ... |
| `downgrade_keep_crops` | 1 | ValueError: ladder gate: ["ValueError: 48 retained ladder crop(s) belong to no listed sample, so the report omits measurements: ['native-depth-ladder-coexist-1202.ppm.gz', 'native-depth-ladder-coexist-1442.ppm.gz', 'native... |
| `downgrade_silent_log` | 1 | ValueError: ladder gate: ["ValueError: the ladder launch enabled the coexistence experiment but the report says coexistEnabled=false; the experiment's evidence is required"] |
| `downgrade_silent_log_command_bare` | 1 | ValueError: ladder gate: ["ValueError: the ladder launch enabled the coexistence experiment but the report says coexistEnabled=false; the experiment's evidence is required"] |
| `downgrade_silent_log_command_empty` | 1 | ValueError: ladder gate: ["ValueError: the ladder launch enabled the coexistence experiment but the report says coexistEnabled=false; the experiment's evidence is required"] |
| `downgrade_silent_log_command_false` | 1 | ValueError: ladder gate: ["ValueError: the ladder launch enabled the coexistence experiment but the report says coexistEnabled=false; the experiment's evidence is required"] |
| `downgrade_silent_log_command_literaloff` | 1 | ValueError: ladder gate: ["ValueError: the ladder launch enabled the coexistence experiment but the report says coexistEnabled=false; the experiment's evidence is required"] |
| `downgrade_silent_log_command_long` | 0 | Accepted; still-enabling command escape |
| `downgrade_silent_log_command_missing` | 0 | Accepted; absent/non-enabling command rewrite |
| `downgrade_silent_log_command_neighbour` | 0 | Accepted; absent/non-enabling command rewrite |
| `downgrade_silent_log_command_off` | 0 | Accepted; absent/non-enabling command rewrite |
| `downgrade_silent_log_command_split` | 0 | Accepted; still-enabling command escape |
| `downgrade_silent_log_command_system` | 0 | Accepted; still-enabling command escape |
| `duplicate_log` | 1 | ValueError: ladder gate: ['ValueError: the ladder log holds two coexist lines for draw 4562'] |
| `duplicate_result` | 1 | ValueError: ladder gate: ['ValueError: the coexist results repeat a draw: [2, 242, 482, 722, 962, 1202, 1442, 1682, 1922, 2162, 2402, 2642, 2882, 3122, 3362, 3602, 3842, 4082, 4322, 4562, 4562, 4802, 5042, 5282, 5522]'] |
| `extra_log` | 1 | ValueError: ladder gate: ['ValueError: the ladder log holds coexist lines for draws [2, 242, 482, 722, 962, 1202, 1442, 1682, 1922, 2162, 2402, 2642, 2882, 3122, 3362, 3602, 3842, 4082, 4322, 4562, 4802, 5042, 5282, 5522, ... |
| `flag_off_empty_report_remove_crops` | 1 | ValueError: ladder gate: ["ValueError: the ladder launch enabled the coexistence experiment but the report says coexistEnabled=false; the experiment's evidence is required"] |
| `flag_off_empty_report_saved_results` | 1 | ValueError: ladder gate: ["ValueError: 48 retained ladder crop(s) belong to no listed sample, so the report omits measurements: ['native-depth-ladder-coexist-1202.ppm.gz', 'native-depth-ladder-coexist-1442.ppm.gz', 'native... |
| `flag_off_results_listed` | 1 | ValueError: ladder gate: ["ValueError: the ladder launch enabled the coexistence experiment but the report says coexistEnabled=false; the experiment's evidence is required"] |
| `log_counts_lie` | 1 | ValueError: ladder gate: ["ValueError: the ladder log's coexist line for draw 4562 says [44695, 54378, 0, 44694, 54378, 0, 0, 54378] but the crops say [44694, 54378, 0, 44694, 54378, 0, 0, 54378]"] |
| `missing_crop` | 1 | ValueError: ladder gate: ['ValueError: the retained coexist crop native-depth-ladder-coexist-4562.ppm.gz is missing'] |
| `missing_entry` | 1 | ValueError: ladder gate: ["ValueError: 2 retained ladder crop(s) belong to no listed sample, so the report omits measurements: ['native-depth-ladder-coexist-4562.ppm.gz', 'native-depth-ladder-coexist-frame-4562.ppm.gz']"] |
| `missing_log_line` | 1 | ValueError: ladder gate: ['ValueError: the ladder log holds coexist lines for draws [2, 242, 482, 722, 962, 1202, 1442, 1682, 1922, 2162, 2402, 2642, 2882, 3122, 3362, 3602, 3842, 4082, 4322, 4802, 5042, 5282, 5522] but th... |
| `pixel_absent_in_front` | 1 | ValueError: ladder gate: ["ValueError: coexist at draw 4562: the quad is missing at 1 pixel(s) whose depth is <= z* and present at 0 pixel(s) whose depth is > z*; Voxy's compare against Minecraft's depth did not compose pe... |
| `pixel_outside` | 1 | ValueError: ladder gate: ['ValueError: coexist at draw 4562: 1 pixel(s) the quad did not cover are not byte-identical to the first crop, so something else changed the band'] |
| `pixel_present_behind` | 1 | ValueError: ladder gate: ["ValueError: coexist at draw 4562: the quad is missing at 0 pixel(s) whose depth is <= z* and present at 1 pixel(s) whose depth is > z*; Voxy's compare against Minecraft's depth did not compose pe... |
| `pixel_quad_bucket` | 1 | ValueError: ladder gate: ['ValueError: coexist at draw 4562: 1 pixel(s) the quad did not cover are not byte-identical to the first crop, so something else changed the band'] |
| `pixel_uncovered_bucket` | 1 | ValueError: ladder gate: ['ValueError: coexist at draw 4562: 1 pixel(s) the quad did not cover are not byte-identical to the first crop, so something else changed the band'] |
| `pixel_uncovered_changed` | 1 | ValueError: ladder gate: ['ValueError: coexist at draw 4562: 1 pixel(s) the quad did not cover are not byte-identical to the first crop, so something else changed the band'] |
| `swap_before_after` | 1 | ValueError: ladder gate: ['ValueError: recounting native-depth-ladder-4562.ppm.gz finds rungs=[0, 0, 0, 0, 54378, 0, 0, 0], not the published [0, 0, 0, 44694, 54378, 0, 0, 0]; the aggregate does not match the pixels'] |


## 3. Requested tests and refusal-guard removals

```sh
./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true
python3 -m unittest discover -s scripts/tests
python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261009T063708-667962Z
```

**CONFIRMED — JUnit 333, one documented skip, zero failures/errors; Python 199, zero failures/errors/skips (463.326 seconds); original replay 0.** Gradle test task executed, with two tasks executed and two from cache. Fresh XML independently totals 333/0/0/1; only `VkBarriersTest.missingBarrierIsDetected()` skipped. `verify.junit_result` on fresh XML separately reports success, the one known gap, zero unexpected diagnostics/skips, and executed fill-buffer negative control. Logs `.agent-run/r17-{gradle,python,replay}.log`, independent XML totals `r17-independent.json`, strict XML/output check `r17-junit-gate.json`.

**CONFIRMED — R15-TEST-COEXIST closes.** Replaced one refusal condition at a time with False, in memory only, against the complete unchanged **20-test LadderCoexistTest class**. All **23** current refusal guards produce a failing suite; **none survives**. This covers all nineteen original roles: eighteen distinct guards because the two former colour refusals now share `counts['other']`, plus five newer refusal guards; the five round-16 survivors are included in this table. Mapping `.agent-run/r17-original-guard-mapping.json`, definitions `r17-mutant-definitions.json`, exact traces `r17-mutant-*.{json,log}`, summary `r17-mutant-summary.json`. Command: `python3 .agent-run/r17-mutants.py --table`. The unchanged focused baseline passes 20; removing the entire coexist call also fails the class. Tests still use **960×540** frames (`test_ladder_gate.py:39`); original native bands remain derived from 1708×960/1920×1080 targets.

| Removed guard, verify.py line | Condition | Tests / failures / errors |
| --- | --- | --- |
| 1232 | `not isinstance(enabled, bool)` | 20 / 1 / 0 |
| 1235 | `not isinstance(entries, list)` | 20 / 1 / 0 |
| 1240 | `int(m[0]) in logged` | 20 / 1 / 0 |
| 1243 | `required and (not enabled)` | 20 / 1 / 0 |
| 1247 | `entries` | 20 / 1 / 0 |
| 1249 | `logged` | 20 / 1 / 0 |
| 1253 | `report.get('coexistRung') != COEXIST_RUNG or report.get('coexistRgb') != COEXIST_RGB` | 20 / 1 / 0 |
| 1259 | `not isinstance(entry, dict) or not finite_int(entry.get('at'))` | 20 / 1 / 0 |
| 1262 | `len(ats) != len(set(ats))` | 20 / 1 / 0 |
| 1266 | `sorted(by_at) != sorted(sample_ats)` | 20 / 1 / 0 |
| 1269 | `log_text is not None and sorted(logged) != sorted(sample_ats)` | 20 / 1 / 0 |
| 1277 | `not finite_int(entry.get(field)) or entry[field] < 0` | 20 / 1 / 0 |
| 1281 | `name != f"native-depth-ladder-coexist-{recount['at']}.ppm.gz"` | 20 / 1 / 0 |
| 1284 | `frame_name != f"native-depth-ladder-coexist-frame-{recount['at']}.ppm.gz"` | 20 / 1 / 0 |
| 1288 | `not after_path.is_file()` | 20 / 1 / 0 |
| 1292 | `(aw, ah) != (bw, bh)` | 20 / 0 / 1 |
| 1295 | `not frame_path.is_file()` | 20 / 1 / 0 |
| 1298 | `ladder_anchor_blocks(after, recount['rect'], trows) == 0` | 20 / 1 / 0 |
| 1324 | `entry[field] != value` | 20 / 1 / 0 |
| 1331 | `logged.get(recount['at']) != want` | 20 / 1 / 0 |
| 1334 | `counts['other']` | 20 / 3 / 0 |
| 1338 | `counts['absentWherePass'] or counts['presentWhereFail']` | 20 / 2 / 0 |
| 1347 | `not mixed` | 20 / 1 / 0 |


Each former survivor now has a detecting test: result-list type (`test_ladder_gate.py:957`), key-set equality without orphans (`:966`), both names with files renamed (`:990`), per-draw log counts (`:1018`). These anchors identify the detecting tests; tests can fail on error messages or downstream exceptions as well as successful admission. In particular size-guard removal causes an IndexError, which still fails the suite. This demonstrates mutation detection, not universal malformed-input safety or GPU call authentication. The existing recorder-binding JUnit limit remains disclosed; no JVM recorder mutation was rerun in round 17.

## 4. Survey and handoff scope

**CONFIRMED — replay table and historical attribution.** Replayed all eighteen retained directories: seventeen exit 1, only `20261009T063708-667962Z` exits 0 (`.agent-run/r17-replay-table.{py,json,log}`, all per-run outputs retained). Survey `:433–477` dates the gate 2026-10-09 and correctly tables each run. `:1278` and `:1352` now say older runs replayed in the checkout they were built from. Round-15's five mixed samples and round-16's four mixed samples belong to their expressly named historical runs, not today's new run. Their recount confirmations and old counterexamples are accurately represented in `:1356–1392` and the retained r15/r16 reports.

**CONFIRMED — measurement and delivery exclusions; REFUTED — complete semantics repair.** Survey `:1332–1350,1394–1400` describes one known-depth quad, loaded Minecraft depth, no depth write, one-value scale fact and no real terrain/native acceptance. Its method agrees with the current original evidence. `:1386–1389` accurately describes repaired attached short tokens and new guard tests, but calling this Gradle's semantics as a whole is contradicted by section 1. The round-15 presence attack with the original true token is repaired; full launch-required proof is still refuted. No bounded measurement upgrades the layer to accepted.

**CONFIRMED — owner directive and next experiment remain bounded.** Handoff `:36–44` directs the shortest safe route through real shared WorldEngine/meshing/NodeManager and VkTerrainRenderer, retains independent review, no GL dependency and GPU lifetime requirements, and allows experimental milestones while DELIVERY-BOUNDARY is open. It does not claim acceptance or waive failing gates. `:168–193` specifies an unimplemented **separate** `McNativeTerrainLoad` probe / `voxy.native.terrainload` / `-PharnessNativeTerrainLoad` flag: synthetic terrain through `VkTerrainRenderer.recordDrawsInRenderPass` in a third LOAD pass, reference colour/depth and per-pixel determinate/undetermined accounting. `VkTerrainRenderer.java:228–230` really uses GREATER_OR_EQUAL with depth writes ON, explaining why it must be separate. `handoff:194–195` says nothing in the ladder may ever write Minecraft depth. The next seam into real section data is explicit. This is a design statement, not source or runtime acceptance of terrain-LOAD.

**REFUTED — blanket R14-DOC-DRIFT closure; CONFIRMED — named prose/date fixes.** Handoff `:138–141` now includes coexist in launch-2 prose, but the manual command described as "what launch 2 does" (`:207–216`) still omits `-PharnessNativeCoexist`; it therefore measures a different experiment. Its directory inventory `:237` still says 17 runs, while there are 18. These are non-blocking documentation residuals, separate from the blocking semantic defect. The closure map records R14-DOC-DRIFT false for full documentation closure.

## 5. Normal-play safety and other retained observations

**CONFIRMED — dormant safety, source and offline-test scope.** With all eight native flags unset, `McNativeDepthLadder.java:263` returns before device access; `:353` separately requires coexist and a pending sampled readback before the quad; `:1083–1086` returns before device access on shutdown without an instance. Other probes independently return before GPU access (`McNativeVulkanProbe:296`, MarkerDraw:273, TerrainProbe:185, DepthProbe:99); feature augmentation returns the supplied feature set (`McNativeDeviceFeatures:78`); adopt-off publishes bounded Java status and returns (`McNativeVkContext:96–100`). Harness configuration is development-only (`build.gradle:521–538`). The coexist quad **cannot act unflagged**, including coexist alone without depthladder.

**CONFIRMED — flagged ladder/coexist never writes Minecraft depth in inspected production source.** Both passes LOAD colour and depth without clear (`McNativeDepthLadder:336–359`); all four create-info depth states disable writes (`:924`); `recordCoexist:411–424` only records viewport/scissor, pipeline bind, push and draw. Post-return state validation (`:953–975`) rejects persistent corruption but discloses restore-after-call limits. RGB evidence alone does not authenticate actual native driver depth-state consumption. This guarantee concerns ladder/coexist; separately enabled marker/terrain probes may write or clear depth.

**Would ship the dormant change on a supported established GL configuration: yes**, with all eight native flags unset and within the established renderer's limitations. This is not a fresh player regression certification. Minecraft GL on macOS may select the separate diagnostic Vulkan/GL interop path, which this review does not certify as a finished product. **Minecraft Vulkan: safe installed/inert, but would not ship as functioning Voxy.** `VoxyClient.java:96–112` disables Voxy because native production rendering is unimplemented. No packaged/player launch was performed.

**CONFIRMED — bounded reverse-Z and ladder brackets.** Last eligible near sample **4802**, camera y 80.61999988555908, ground 67, rungs 3/4 (44,694/54,378); far **5282**, camera y 176.61999988555908, same ground, rung 2 (99,072). Lower-camera near look is identified independently of depth. Every near bracket is greater than every far bracket; `verify.py:1461–1543` reconciles ground, camera, eligibility, order and direction; sample stage/camera/log/checkpoint reconciliation and saved-direction comparison pass original replay. The inspected thumbnails support the ground-look interpretation. General scale, exact per-ray distances, other hooks/worlds/devices remain unmeasured.

**CONFIRMED — synthetic terrain comparison only.** Independently decoded **21** retained native/reference pairs, all exact RGB equal. Selected draw 4104 is 1920×1080, zero mismatches, **26,116** non-background pixels (clear RGB (13,13,26)); independent count `r17-terrain-recount.json`. This experiment clears Minecraft targets and uses synthetic input. **REFUTED** as real native LoD or scene-coexistence acceptance; the proposed LOAD variant is absent from the candidate.

**CONFIRMED — all-zero quantized depth copy; REFUTED — cause/scale/direction inference from it.** The retained P5 image is 1708×960, max 65535, **3,279,360 zero body bytes**, i.e. **1,639,680 zero quantized samples**. Raw floats are producer-reported and not retained. The separate two-look measurement establishes bounded direction; the zero copy establishes no cause.

**CONFIRMED — DELIVERY-BOUNDARY remains blocking.** No independently accepted diagnostic layer, real-world native Voxy LoD, default native renderer, accepted native updates/lifecycle/lighting or native pressure/lifetime acceptance. This boundary governs even a perfect bounded gate. Overall verdict remains REDESIGN.

## What could not be checked and why

- Fresh Minecraft/native/live runs were prohibited. No fresh environment/validation-loader or diagnostic-stage acceptance, player GL regression, packaged Vulkan launch, sustained lifetime/pressure, updates/reconnect/reload matrix or native delivery acceptance.
- Full original readback buffers, native raw depth at equality, retained world/per-ray survey and general depth scale are absent. Thumbnail anchors cover complete 4×4 blocks; boundary blocks and within-block permutations remain outside that guarantee.
- Editable source inventories, manifests and post-return pipeline states do not authenticate runtime dependency jars, compiled JVM/classes, driver/GPU origin or actual native create/bind consumption. No creator restore-after-call or recorder-binding JVM mutation was rerun here.
- Full 199-case suite was run once for the candidate, not separately for each mutant. Every guard removal used the entire unchanged 20-case coexist class; no fixture was shrunk. Direction-specific mutation suites from earlier rounds were not rerun.
- Proposed terrain-LOAD source and runtime proof are absent. Its separate flag and writes-ON design are judged only as a disclosed next experiment, not implemented acceptance.

Machine verdict: `.agent-run/native-integration-review.json`. Review scripts, complete attack packages, Gradle property logs, full suite logs, independent counts/images and replay table remain under `.agent-run/r17*`. No fix, commit, push or publication was performed.
