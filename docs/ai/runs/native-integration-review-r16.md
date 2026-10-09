VERDICT: REDESIGN

Independent round-16 review of **a49b926f0f596f504099f735219c91df9f0b2f78**, 2026-10-09, in `/Users/xyz/orca/workspaces/voxy/native-review-r16`. Initial tree clean; HEAD matched the request. Implementation, retained evidence and fixtures remain unchanged. Review artifacts alone were written. No Minecraft/native/live stage was launched.

**CONFIRMED: the bounded coexistence measurement stands, now with exact RGB semantics.** Independently decoded all 24 current pairs: 2,114,816 pixels, zero presence/absence or unchanged-RGB violations, four mixed samples, 388,539 matching complete 4×4 thumbnail blocks. **REFUTED: the full required-evidence repair, every-guard test claim, and complete documentation repair.** REDESIGN retains DELIVERY-BOUNDARY and adds **R16-COEXIST-LAUNCH-SEMANTICS**: launch forms that actually enable coexist are misread by replay as off.

| Candidate claim | Judgment |
| --- | --- |
| (a) R15-COEXIST-PRESENCE and all non-blocking items repaired | **REFUTED as a combined claim.** The exact original presence attack is refused; launch semantics leave a required-proof residual. RGB and duplicate findings close. Five guard removals survive; documentation drift remains. |
| (b) Bounded coexist measurement, exact bytes, retained reconciled required evidence | **CONFIRMED for original retained measurement and byte semantics; REFUTED for unconditional required evidence across actual enabling launch forms.** |
| (c) Survey claims no more than established evidence | **CONFIRMED for the diagnostic/one-quad/native-delivery boundary; REFUTED for repair/test/replay-current claims.** |

## 1. R15-COEXIST-PRESENCE and passing downgrade requirements

**CONFIRMED — original round-15 counterexample is repaired.** With the retained `-PharnessNativeCoexist=true` command untouched, an off/empty report fails both with images (48 orphaned crops) and without images (experiment required). Removing the log's coexist lines too still fails while that command remains. Editing the command to omit coexist but keeping the log fails the new log-presence reconciliation. `verify.py:1226` requires enabled evidence; `:1232` refuses log evidence behind a disabled report; replay supplies requirement from `:2636–2639`; stage supplies it at `:2873` from the command it just launched. The stage always constructs the literal true token (`:2857`).

**A passing downgrade now needs all of:** report `coexistEnabled:false` and `coexist:[]`; deletion of the 24 second crops and 24 second thumbnails plus their manifest entries; removal of all 24 recognized coexist log lines; a command that replay does not recognize as enabling coexist (or deletion of the entire command); refreshed hashes for changed report/log/summary. The summary's saved enabled report and saved coexist results need not be touched. Omitting the launch property or command with all those other edits passes. This is coordinated rewriting of retained records, not proof of GPU forgery resistance; the survey already excludes universal saved-field reconciliation/authentication (`survey:1201–1204`).

**REFUTED — launch-enabled evidence is always required, blocking R16-COEXIST-LAUNCH-SEMANTICS.** The command can remain an actual enabling command and still downgrade successfully: change the one token to `-PharnessNativeCoexist=false`, or to bare `-PharnessNativeCoexist`, alongside the above report/log/crop edits. Both replay **0**. `build.gradle:602–603` uses `project.hasProperty('harnessNativeCoexist')`, not its Boolean value, and adds `-Dvoxy.native.coexist=true` for either form. `verify.py:2636` instead tests membership of only the exact `=true` token. This is a demonstrable contradiction between the authoritative retained command's real meaning and replay, distinct from inventing an actually disabled command.

Independent Gradle configuration check, **no game task**:

```sh
./gradlew help --offline -PharnessNativeCoexist=false -I .agent-run/r16-inspect-launch.gradle
```

It prints `property exists=true value=false` and harnessClient VM arguments containing `-Dvoxy.native.coexist=true`. Artifact: `.agent-run/r16/gradle-launch-false.log`. Both counterexample packages retain the original saved enabled summary outputs and all unchanged source fingerprints. Reproduce with `python3 .agent-run/r16-attacks.py downgrade_silent_log_command_false downgrade_silent_log_command_bare`, or replay either full package under `.agent-run/r16-attacks/` with `scripts/verify.py`.

Machine closure `R15-COEXIST-PRESENCE:false` denotes this residual in the full launch-required claim; `coexist_judgment.original_round15_presence_attack_closed:true` records the successful literal original repair.

## 2. Coexistence gate and measurement

**CONFIRMED — same frame, same pixels, loaded depth, Voxy compare, no write.** `McNativeDepthLadder.java:288–289` obtains one pair of target views; `:334–371` records first LOAD pass, first copy request, second LOAD pass/quad, then second copy request. Draw number `at` ties both callbacks. `:404–405` exposes the draw plan and `:420–423` consumes it: pipeline 3, rung 4 depth 2⁻⁸, colour (0.5,1,0.5). `:130–132` selects `VkDepth.COMPARE_OP`, `VkDepth.java:61` is GREATER_OR_EQUAL (6). `:924` sets depth writes off for all four pipelines. Read-back checks reject persistent creator-state corruption (`:959–975`), not temporary native-call substitutions.

Re-inspected local Minecraft 26.2 dependency with `javap -c -p`: `.agent-run/r16-javap/VulkanDevice.txt:347–351` returns the cached encoder; `VulkanCommandEncoder.txt:1661–1753` records `vkCmdCopyImageToBuffer` immediately and queues the callback after recording it; `:865` closes the rendering pass/barrier. DestructionQueue appends and processes callbacks in order. These inspected semantics support copy/draw/copy ordering. They do not authenticate the dependency actually used by a retained process.

**CONFIRMED — equality rule.** Ladder LESS rung i leaves the last zi < d, so rung 3 means z3 < d ≤ z4. At d = z4, rung 4 fails, rung 3 remains, and the quad's z4 ≥ d passes. Thus LOW/rungs 0–3 require the quad; rungs 4–7 forbid it. Base/anomaly at z0 is refused. No bracket straddles z*. A real pixel exactly equal to z* is not identified from retained native depth; equality is sound by the inspected compare semantics, not independently sampled raw depth.

**CONFIRMED — exact bytes on both implementations.** Gate `verify.py:1288–1303` recognizes only `(128,255,128)` and requires every uncovered pixel equal its first RGB triplet. Java `McNativeDepthLadder.java:536–554` does the same using the first callback's saved RGB (`:640–661`), and fails nonzero other/violations (`:567`). The bucket attacks now fail after their published counts and log are reconciled with their altered pixels. Both preserve complete original fixtures and use a boundary-strip pixel to isolate byte semantics from thumbnail anchoring.

At descend draw 5042, 1152×86 band: 45,734 red/rung-3 pixels become exactly (128,255,128); 53,338 green/rung-4 pixels remain exactly (0,255,0). The four current mixed draws are 3842, 4082, 4802, 5042; draw 2 in this run is entirely covered. Independently inspected before/after thumbnails show the same grass/trees/river scene. Artifacts: `.agent-run/r16-independent.py`, `r16-independent.json`, `r16-descend-5042-{before,after,before-frame,after-frame}.png`. Full recount covers every original pair and all three crops' complete anchoring blocks.

**CONFIRMED — duplicate results and duplicate/extra log draws are refused** (`verify.py:1245`, `:1223`, `:1252`). Original replay exits 0 with ten named checks, 408 matching selected source entries, 301 manifest members. Source revision retained is `e3a89ea1fb99c77d2f98702c365dd8327916ad51`, with `changed_sources_during_run:[]`; source-byte equality does not establish that this final HEAD commit hash was already running.

Full replay mutation table (complete copied packages, refreshed manifests, unchanged source fingerprints; all original round-15 cases plus downgrade/log variants):

| Mutation | Exit | Result |
| --- | ---: | --- |
| `coexist_rung_lie` | 1 | ValueError: ladder gate: ['ValueError: the ladder publishes coexistRung=3, coexistRgb=[0.5, 1.0, 0.5], not the 4/[0.5, 1.0, 0.5] its source lays out'] |
| `counts_lie` | 1 | ValueError: ladder gate: ['ValueError: coexist at draw 5042 reports present=45735 but the retained crops say 45734'] |
| `downgrade_command_off` | 1 | ValueError: ladder gate: ['ValueError: the ladder log holds coexist lines for draws [2, 242, 482, 722, 962, 1202, 1442, 1682, 1922, 2162, 2402, 2642, 2882, 3122, 3362, 3602, 3842, 4082, 4322, 4562, 4802, 5042, 5282, 5522] but the report says the experiment was off'] |
| `downgrade_keep_crops` | 1 | ValueError: ladder gate: ["ValueError: 48 retained ladder crop(s) belong to no listed sample, so the report omits measurements: ['native-depth-ladder-coexist-1202.ppm.gz', 'native-depth-ladder-coexist-1442.ppm.gz', 'native-depth-ladder-coexist-1682.ppm.gz', 'native-depth-ladder-coexist-1922.ppm.gz', 'native-depth-ladder-coexist-2.ppm.gz', 'native-depth-ladder-coexist-2162.ppm.gz']"] |
| `downgrade_silent_log` | 1 | ValueError: ladder gate: ["ValueError: the ladder launch enabled the coexistence experiment but the report says coexistEnabled=false; the experiment's evidence is required"] |
| `downgrade_silent_log_command_bare` | 0 | Accepted; coexist disabled |
| `downgrade_silent_log_command_false` | 0 | Accepted; coexist disabled |
| `downgrade_silent_log_command_missing` | 0 | Accepted; coexist disabled |
| `downgrade_silent_log_command_off` | 0 | Accepted; coexist disabled |
| `duplicate_log` | 1 | ValueError: ladder gate: ['ValueError: the ladder log holds two coexist lines for draw 5042'] |
| `duplicate_result` | 1 | ValueError: ladder gate: ['ValueError: the coexist results repeat a draw: [2, 242, 482, 722, 962, 1202, 1442, 1682, 1922, 2162, 2402, 2642, 2882, 3122, 3362, 3602, 3842, 4082, 4322, 4562, 4802, 5042, 5042, 5282, 5522]'] |
| `extra_log` | 1 | ValueError: ladder gate: ['ValueError: the ladder log holds coexist lines for draws [2, 242, 482, 722, 962, 1202, 1442, 1682, 1922, 2162, 2402, 2642, 2882, 3122, 3362, 3602, 3842, 4082, 4322, 4562, 4802, 5042, 5282, 5522, 99999] but the ladder sampled draws [2, 242, 482, 722, 962, 1202, 1442, 1682, 1922, 2162, 2402, 2642, 2882, 3122, 3362, 3602, 3842, 4082, 4322, 4562, 4802, 5042, 5282, 5522]'] |
| `flag_off_empty_report_remove_crops` | 1 | ValueError: ladder gate: ["ValueError: the ladder launch enabled the coexistence experiment but the report says coexistEnabled=false; the experiment's evidence is required"] |
| `flag_off_empty_report_saved_results` | 1 | ValueError: ladder gate: ["ValueError: 48 retained ladder crop(s) belong to no listed sample, so the report omits measurements: ['native-depth-ladder-coexist-1202.ppm.gz', 'native-depth-ladder-coexist-1442.ppm.gz', 'native-depth-ladder-coexist-1682.ppm.gz', 'native-depth-ladder-coexist-1922.ppm.gz', 'native-depth-ladder-coexist-2.ppm.gz', 'native-depth-ladder-coexist-2162.ppm.gz']"] |
| `flag_off_results_listed` | 1 | ValueError: ladder gate: ["ValueError: the ladder launch enabled the coexistence experiment but the report says coexistEnabled=false; the experiment's evidence is required"] |
| `log_counts_lie` | 1 | ValueError: ladder gate: ["ValueError: the ladder log's coexist line for draw 5042 says [45735, 53338, 0, 45734, 53338, 0, 0, 53338] but the crops say [45734, 53338, 0, 45734, 53338, 0, 0, 53338]"] |
| `missing_crop` | 1 | ValueError: ladder gate: ['ValueError: the retained coexist crop native-depth-ladder-coexist-5042.ppm.gz is missing'] |
| `missing_entry` | 1 | ValueError: ladder gate: ["ValueError: 2 retained ladder crop(s) belong to no listed sample, so the report omits measurements: ['native-depth-ladder-coexist-5042.ppm.gz', 'native-depth-ladder-coexist-frame-5042.ppm.gz']"] |
| `missing_log_line` | 1 | ValueError: ladder gate: ['ValueError: the ladder log holds coexist lines for draws [2, 242, 482, 722, 962, 1202, 1442, 1682, 1922, 2162, 2402, 2642, 2882, 3122, 3362, 3602, 3842, 4082, 4322, 4562, 4802, 5282, 5522] but the ladder sampled draws [2, 242, 482, 722, 962, 1202, 1442, 1682, 1922, 2162, 2402, 2642, 2882, 3122, 3362, 3602, 3842, 4082, 4322, 4562, 4802, 5042, 5282, 5522]'] |
| `pixel_absent_in_front` | 1 | ValueError: ladder gate: ["ValueError: coexist at draw 5042: the quad is missing at 1 pixel(s) whose depth is <= z* and present at 0 pixel(s) whose depth is > z*; Voxy's compare against Minecraft's depth did not compose per pixel"] |
| `pixel_outside` | 1 | ValueError: ladder gate: ['ValueError: coexist at draw 5042: 1 pixel(s) the quad did not cover are not byte-identical to the first crop, so something else changed the band'] |
| `pixel_present_behind` | 1 | ValueError: ladder gate: ["ValueError: coexist at draw 5042: the quad is missing at 0 pixel(s) whose depth is <= z* and present at 1 pixel(s) whose depth is > z*; Voxy's compare against Minecraft's depth did not compose per pixel"] |
| `pixel_quad_bucket` | 1 | ValueError: ladder gate: ['ValueError: coexist at draw 5042: 1 pixel(s) the quad did not cover are not byte-identical to the first crop, so something else changed the band'] |
| `pixel_uncovered_bucket` | 1 | ValueError: ladder gate: ['ValueError: coexist at draw 5042: 1 pixel(s) the quad did not cover are not byte-identical to the first crop, so something else changed the band'] |
| `pixel_uncovered_changed` | 1 | ValueError: ladder gate: ['ValueError: coexist at draw 5042: 1 pixel(s) the quad did not cover are not byte-identical to the first crop, so something else changed the band'] |
| `swap_before_after` | 1 | ValueError: ladder gate: ['ValueError: recounting native-depth-ladder-5042.ppm.gz finds rungs=[0, 0, 0, 0, 53338, 0, 0, 0], not the published [0, 0, 0, 45734, 53338, 0, 0, 0]; the aggregate does not match the pixels'] |

The only **unmutated-gate** cases replaying 0 are `downgrade_silent_log_command_off`, `downgrade_silent_log_command_missing`, `downgrade_silent_log_command_false`, `downgrade_silent_log_command_bare`. The last two are the blocking semantic residual. No RGB attack or duplicate replays 0. Inventory: `.agent-run/r16-attacks-all.json`; exact outputs `r16-attack-<name>.json`; full packages `r16-attacks/<name>/`. `python3 .agent-run/r16-attacks.py` runs the inherited and new table; the two semantic variants and log-count case are explicit additional invocations.

## 3. Tests, nineteen original removals, and recorder mutation

Requested commands rerun:

```sh
./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true
python3 -m unittest discover -s scripts/tests
python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261009T060453-272917Z
```

**CONFIRMED — JUnit 333, one documented skip, zero failures/errors; Python 193, zero failures/errors/skips (432.746 seconds); replay 0.** Baseline test task executed. Only skipped case is `VkBarriersTest.missingBarrierIsDetected()`; the deliberate fill-buffer control ran. Artifacts: `.agent-run/r16/{junit,python,replay}.log`, `r16-baseline-junit.json`. No fixture was shrunk: Python dimensions remain 960×540 (`test_ladder_gate.py:39`); current retained bands derive from 1708×960/1920×1080 targets.

**REFUTED — every coexist guard has a test that fails without it, residual R15-TEST-COEXIST.** Re-ran all nineteen original guard roles in memory against the entire unchanged **15-test** LadderCoexistTest suite. Original unchanged-colour refusal is now consolidated into `counts['other']`; the 18 remaining distinct old guards plus five new guards yield 23 independent removal runs. `.agent-run/r16-original-guard-mapping.json` maps every old line/role. Each mutation replaces only one refusal condition by False; source files and fixtures are unchanged.

| Removed guard, current verify.py line | Condition | Unchanged coexist suite |
| --- | --- | --- |
| 1215 | `not isinstance(enabled, bool)` | 14 pass / 1 fail / 0 error |
| 1218 | `not isinstance(entries, list)` | 15 pass / 0 fail / 0 error |
| 1223 | `int(m[0]) in logged` | 14 pass / 1 fail / 0 error |
| 1226 | `required and (not enabled)` | 14 pass / 1 fail / 0 error |
| 1230 | `entries` | 14 pass / 1 fail / 0 error |
| 1232 | `logged` | 14 pass / 1 fail / 0 error |
| 1236 | `report.get('coexistRung') != COEXIST_RUNG or report.get('coexistRgb') != COEXIST_RGB` | 14 pass / 1 fail / 0 error |
| 1242 | `not isinstance(entry, dict) or not finite_int(entry.get('at'))` | 14 pass / 1 fail / 0 error |
| 1245 | `len(ats) != len(set(ats))` | 14 pass / 1 fail / 0 error |
| 1249 | `sorted(by_at) != sorted(sample_ats)` | 15 pass / 0 fail / 0 error |
| 1252 | `log_text is not None and sorted(logged) != sorted(sample_ats)` | 14 pass / 1 fail / 0 error |
| 1260 | `not finite_int(entry.get(field)) or entry[field] < 0` | 14 pass / 1 fail / 0 error |
| 1264 | `name != f"native-depth-ladder-coexist-{recount['at']}.ppm.gz"` | 15 pass / 0 fail / 0 error |
| 1267 | `frame_name != f"native-depth-ladder-coexist-frame-{recount['at']}.ppm.gz"` | 15 pass / 0 fail / 0 error |
| 1271 | `not after_path.is_file()` | 14 pass / 1 fail / 0 error |
| 1275 | `(aw, ah) != (bw, bh)` | 14 pass / 0 fail / 1 error |
| 1278 | `not frame_path.is_file()` | 14 pass / 1 fail / 0 error |
| 1281 | `ladder_anchor_blocks(after, recount['rect'], trows) == 0` | 14 pass / 1 fail / 0 error |
| 1307 | `entry[field] != value` | 14 pass / 1 fail / 0 error |
| 1314 | `logged.get(recount['at']) != want` | 15 pass / 0 fail / 0 error |
| 1317 | `counts['other']` | 12 pass / 3 fail / 0 error |
| 1321 | `counts['absentWherePass'] or counts['presentWhereFail']` | 13 pass / 2 fail / 0 error |
| 1330 | `not mixed` | 14 pass / 1 fail / 0 error |

**Five survive with all 15 tests green:** 1218 result-list type, 1249 complete result-draw key set, 1264 crop filename, 1267 frame filename, 1314 log count equality. `test_each_report_level_coexist_check_is_live` (`test_ladder_gate.py:927–951`) explicitly expects other inventory checks to refuse omitted/renamed files, so it cannot detect those individual guard removals. The missing-log test is now caught by the earlier log-draw-set guard and no longer pins the per-draw count comparison. Other guard removals sometimes fail on a changed error message or downstream exception (1275 gives an IndexError); these are test failures, not evidence that removing those guards admits every malformed package.

A concrete semantic escape for survivor 1314: change only the logged draw-5042 `present` count from 45,734 to 45,735, leave crop and report correct, refresh manifest. Baseline replay fails count agreement; the in-memory 1314 mutant replays **0** (`python3 .agent-run/r16-mutant-log-escape.py`; `r16-mutant-log-escape.json`). This is a **mutated gate** escape, not an escape in current candidate replay. The unchanged full 193-case suite was run once for the candidate, not separately for each mutant. All seven unchanged 15-case direction mutation suites also fail as expected (`r16-mutant-{xz,last,ground,checkpoint_geometry,harness_stage,log_sample_stage,camera_checkpoint}.json`). Definitions, counts, full tracebacks: `r16-mutant-definitions.json`, `r16-mutant-summary.json`, individual logs. Command: `python3 .agent-run/r16-mutants.py --table`.

**CONFIRMED — recorder-binding limit is stated accurately.** A JVM ASM transformer replaces the pipeline index consumed by `recordCoexist` at its LALOAD with OP_ALWAYS=1 while leaving the exposed draw plan and four create-infos correct. All **333** unchanged JUnit tests pass, one skip; transformation marker is retained in the ladder-test XML. No production class bytes on disk were changed. An actual ALWAYS draw would cover the original mixed hidden pixels, so current replay would refuse its real pixel output. The measurement cannot authenticate an alternate runtime/GPU.

Reproduction: `.agent-run/CoexistMutantAgent.java`, `r16-coexist-mutant.jar`, `r16-java-mutant.gradle`; `./gradlew test --offline --rerun-tasks -I .agent-run/r16-java-mutant.gradle` with the three requested Vulkan properties. XML/logs: `r16-java-mutant-xml/`, `r16/java-mutant.log`, `r16-binding-mutant-junit.json`.

**CONFIRMED — exposed draw plan is pinned.** A separate targeted JVM mutation changes only its pipeline entry 3→1 (preserves Object[3] length). JUnit gives 331 passed, one failure, one skip among 333 total; `theDefaultEvidenceClaimsNothingUnmeasured()` fails `expected:3 but was:1`. Plan depth and colour assertions are at `McNativeDepthLadderTest.java:253–256`. Corrected targeted mutant log/XML: `r16/java-plan-mutant.log`, `r16-java-plan-mutant-xml/`; init file `r16-java-plan-mutant.gradle`. Pinning a plan is useful but cannot test its later native bind consumption.

## 4. Survey and handoff scope

**CONFIRMED — seventeen retained runs, only newest replays by design.** `.agent-run/r16-replay-table.json` and every per-run output retain fresh results: sixteen exit 1, `20261009T060453-272917Z` exits 0. Older source fingerprints cannot match current sources. Historical measurements remain inspectable and are not invalidated by today's replay requirements.

**CONFIRMED — bounded observation and delivery exclusions.** Coexist section `survey:1334–1349,1371–1377` describes one known-depth quad, loaded scene depth, no write, no claim of real terrain or accepted layer; its byte semantics now agree with inspected source and original current evidence. Round-15 paragraph `:1353–1359` accurately records that review's confirmation and old counterexample. `:1363–1364` correctly describes RGB repairs; duplicates are now refused. Stated recorder-bind and GPU-authentication limits `:1367–1369` are honest.

**REFUTED — full repairs and current replay attribution.** `:1360–1362` is incomplete for actual Gradle enabling forms; `:1365–1366` says each of the twelve formerly unprotected checks has a failing test, contradicted by the five survivors. `:1351` still says the round-15 package replays 0 in this checkout; it exits 1 (four source mismatches). Its five-mixed-sample figures are accurate historical round-15 figures, **not** current run figures. `:1277` likewise calls the round-14 package current; `:448` dates the current gate 2026-10-07.

**CONFIRMED — handoff passages specifically named in round 15 are corrected.** `handoff:89–92` no longer attributes old 18-sample counts to HEAD; `:109–111` correctly distinguishes probe from measured direction; `:116–118` says real terrain in a LOAD pass is unattempted, rather than all coexistence. Current run/test counts at `:134–139` are accurate. **REFUTED — blanket documentation closure:** `:61` still claims a test per guard; `:125` describes the second launch as only depthladder (+native/adopt/features/probe), omitting coexist, although it is enabled in the retained command. Thus R14-DOC-DRIFT remains false in the machine closure map; its older handoff contradictions are fixed, while residual/new drift remains. No docs were repaired by this review.

## 5. Safety for normal play and other retained experiments

**CONFIRMED — coexist cannot act unflagged.** Ladder entry returns before Minecraft/Vulkan access unless `.depthladder` is true (`McNativeDepthLadder.java:263`); second pass is additionally behind `.coexist` and a pending sampled readback (`:353`); raw first-band storage is also behind `.coexist` (`:640`). With all eight native flags unset, no ladder/quad pass, copy or GPU allocation runs. Other entries return/gate independently: McNativeVulkanProbe:296, McNativeMarkerDraw:273, McNativeTerrainProbe:185, McNativeDepthProbe:99, McNativeDeviceFeatures:78; adopt-off returns with bounded Java status only (McNativeVkContext:96–100). Null-instance ladder shutdown returns before device access (McNativeDepthLadder:1083–1086). Harness-only configuration remains under the development source set/runs (`build.gradle:521–538`).

**CONFIRMED — flagged coexist quad never writes Minecraft depth in inspected production source.** Both passes LOAD without clear (`McNativeDepthLadder:334–371`), all four create-info depth states disable writes (`:924`), and recordCoexist only sets viewport/scissor, binds that pipeline, pushes payload and draws (`:409–424`). Inspection/test state checks support this source claim; retained RGBs alone or post-return create state cannot authenticate the native driver call. Independently enabled terrain/marker experiments can clear/write; this verdict concerns coexist/ladder, not arbitrary combined experiments.

**Would ship this dormant change on supported established GL:** yes, scoped to unchanged native flags and existing renderer limitations, based on source and fresh offline tests. No fresh player regression matrix. On macOS a Minecraft GL backend may instead select Voxy's separate diagnostic Vulkan/GL interop path; this is not acceptance of that path as a finished product. **Minecraft Vulkan:** safe installed/inert with all native flags unset, but **would not ship as functioning native Voxy**: `VoxyClient.java:96–112` disables Voxy without Minecraft GL. No packaged/player run was performed.

**CONFIRMED — bounded reverse-Z:** current last eligible descend 5042 (camera y 80.61999988555908, ground 67, brackets 3/4) vs last eligible ascend **5282** (camera y 176.61999988555908, same ground, bracket 2). Every near bracket exceeds every far bracket. Ascend draw 5522 has a later moved camera and is not eligible; source picks the last eligible camera. Retained log/camera/checkpoint/direction bindings pass. This proves bounded direction at this hook/these looks, not a projection scale, exact per-ray distance or arbitrary-world convention.

**CONFIRMED — terrain experiment:** independently decoded all **27** retained native/reference pairs, exact RGB equal. Selected draw 5787, 1920×1080, independently counted **26,116** pixels outside the published clear colour, zero mismatches. It clears Minecraft targets and uses synthetic geometry; native real-world LoD/coexistence acceptance is **REFUTED**.

**CONFIRMED — depth-copy result:** retained 1708×960 16-bit PGM contains **1,639,680 quantized zero samples** (3,279,360 zero bytes). Raw float zero is producer-reported because raw floats are not retained. Inferring cause, scale, or direction from that all-zero copy is **REFUTED**; separate two-look measurement establishes bounded direction.

**CONFIRMED — DELIVERY-BOUNDARY remains blocking.** No accepted diagnostic foundation, real-world native Voxy LoD, normal native configuration, update/lifecycle/lighting correctness or accepted native lifetime/pressure evidence. Candidate explicitly accepts the rounds 4–15 boundary (`project-goal`, `handoff:57,119–120`). No measurement/test/gate success upgrades this to PASS.

## What could not be checked and why

- Fresh Minecraft/native/live execution was prohibited. No environment/validation-loader/diagnostic replay acceptance, player GL regression, packaged-jar Vulkan launch, sustained pressure/lifetime, updates, reconnect/reload or native delivery acceptance was performed.
- No complete original readback buffers, raw native depth at z*, retained world save or per-ray distance survey. Complete 4×4 thumbnail blocks anchor pixels; partial boundary blocks and within-block rearrangement remain stated limits. Equality semantics are reasoned from source, not identified with retained native depth values.
- No authentication of runtime dependency jars, compiled classes/JVM, driver, GPU origin or native create/bind state consumption. Local dependency bytecode was independently inspected; selected source inventory/bytes were checked live. Editable manifests and post-return states cannot authenticate execution; creator restore-after-call is the prior disclosed limit, not rerun here.
- No separate full 193-case run for each guard mutant: all mutations ran the complete unchanged focused coexist/direction classes. These results are not claims that all 193 cases pass for the five survivors.
- Observation remains scoped to retained host/hook/bands and one known numeric depth. No real terrain projection parity, generalized depth scale, other hook/world/device, lighting/translucency or native-delivery acceptance follows.

Machine verdict: `.agent-run/native-integration-review.json`. Review scripts, complete copied attacks, fresh logs, XML, independent recounts/images and replay outputs are retained under `.agent-run/r16*`.
