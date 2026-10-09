VERDICT: REDESIGN

Independent round-15 review of **5509d19f8e5706e8972a69f2e01c6ecf7bf42db0**, 2026-10-09, in `/Users/xyz/orca/workspaces/voxy/native-review-r15`. HEAD matched the request and the initial tree was clean. No implementation, committed evidence or fixtures were edited. Only this report and review artifacts under `.agent-run/` were written. Minecraft/native/live stages were not launched.

**The original known-depth quad measurement is CONFIRMED, within its diagnostic scope.** All 24 original before/after pairs independently agree, including exact RGB preservation of uncovered pixels. R14-TEST-BINDINGS is closed; R14-DOC-DRIFT is not. REDESIGN retains the standing delivery boundary and adds **R15-COEXIST-PRESENCE**: replay can discard the entire coexist experiment while the saved launch, summary and log still say it ran. There are also non-blocking colour-integrity and test-coverage gaps.

| Candidate claim | Judgment |
| --- | --- |
| (a) Both round-14 non-blocking findings addressed | **REFUTED as a combined claim.** Two guard tests work; contradictory handoff passages remain. |
| (b) Voxy's compare op composes a known-depth quad per pixel against Minecraft's loaded depth at this hook | **CONFIRMED for the inspected source and original retained 24 samples**, including zero exact-RGB violations in independent recount. This does not authenticate GPU execution. **REFUTED** if extended to a gate that always requires the experiment or rejects every RGB change. |
| (c) The survey claims no more than the evidence | **CONFIRMED for the bounded original depth-composition observation and exclusion of an accepted layer/native terrain; REFUTED for blanket documentation repair and unconditional zero-tolerance gate wording.** |

## 1. Coexistence measurement

**CONFIRMED — same hook invocation, same targets, copy before quad before copy.** `McNativeDepthLadder.java:279–282` obtains Minecraft's main colour/depth views. `:331–342` closes the ladder LOAD pass; `:344–367` requests its colour copy, opens the second LOAD pass over the same local views, closes that pass, then requests the second copy. Neither clear is present. `:445–446,477–478` records two distinct buffer copies; callbacks retain/match draw number `at`, rather than rereading a later frame. `:507–510` refuses missing first-callback classification. `:626–640` retains first-copy pixel classes for that comparison.

This conclusion checks the dependency rather than assuming a request defers the copy. Disassembly of the local Minecraft 26.2 jar is retained in `.agent-run/r15-{VulkanDevice,VulkanCommandEncoder,CommandEncoder,DestructionQueue}.javap`. `VulkanDevice.createCommandEncoder()` returns its cached encoder (`r15-VulkanDevice.javap:347`); `VulkanCommandEncoder.copyTextureToBuffer()` immediately records `vkCmdCopyImageToBuffer` and a memory barrier, then queues the completion callback (`r15-VulkanCommandEncoder.javap:1661`). `submitRenderPass()` records end-rendering and a barrier (`:865`). Destruction callbacks are appended and consumed in list order. Therefore different Java wrapper requests do not put the first copy after the second draw. This is inspected local dependency behavior, not authentication of the retained run's jar.

**CONFIRMED — the first colour class still brackets Minecraft's depth.** The ALWAYS base, GREATER control at z0, and ascending LESS rungs all have writes off (`McNativeDepthLadder.java:375–397,901–905`). They overwrite colour only. Rung i is the last threshold with zi < d, so it means **zi < d ≤ z(i+1)**; rung 7 means d > z7; LOW means d < z0. The base/anomaly at exactly z0 is explicitly rejected (`:617–622`; `verify.py:1173`). The second pass therefore tests the original loaded depth, rather than depth written by a preceding ladder rung.

**CONFIRMED — no bracket straddles z* for GREATER_OR_EQUAL, including equality.** z* is z4 = 2^-8. At d = z4 the rung-4 LESS draw fails, leaving **rung 3**, and z* ≥ d passes the quad. Thus LOW/rungs 0–3 must show it; rungs 4–7 have d > z* and must hide it. The source binds `pipelines[OP_COEXIST]` and pushes `depths()[COEXIST_RUNG]` (`:411–413`); the table uses `VkDepth.COMPARE_OP` (`:128–130`), which is GREATER_OR_EQUAL (`VkDepth.java:61`). Both viewports use min/max depth 0/1. Independent adjacent-value/equality calculation: `.agent-run/r15-equality.json`. Equality is mathematically sound; a retained depth buffer proving an actual d == z* pixel is absent. A wrongly substituted strict GREATER could produce the same images if no pixel equals z*; the published fourth state and inspected source, rather than those images alone, distinguish it.

**CONFIRMED — original descend before/after crops.** Independently decoded lossless PNGs were visually inspected: `.agent-run/r15-descend-5042-{before,after,before-frame,after-frame}.png`. The red parts of the band become pale green at precisely the same positions; the green parts remain green. Both full thumbnails show the same close grass/trees/river scene. At draw 5042, band `[384,648,1536,734]`, 1152×86:

| First crop | Pixels | Second crop |
| --- | ---: | --- |
| Rung 3, red | 45,734 | All exactly RGB (128,255,128), quad present |
| Rung 4, green | 53,338 | All exactly RGB (0,255,0), unchanged |

The other descend sample, 4802, agrees. Independent recount covers **2,114,816 pixels in 24 pairs**, with no incorrect presence/absence, no out-of-palette RGB, and no changed uncovered RGB. Five mixed samples are **2, 3842, 4082, 4802, 5042**. The 19 remaining samples are entirely covered. Both copies and first-copy rejected crops match **388,539 complete 4×4 thumbnail blocks**. For descend 5042, 2,999 thumbnail blocks change and none outside the band changes. Commands/artifacts: `python3 .agent-run/r15-independent.py`, `r15-independent.json`, `r15-descend-frame-delta.json`.

**REFUTED — a passing pixel result alone proves the intended compare executed.** A colour-dependent screen mask could imitate the same transformation without depth testing; coordinated fabricated crops/counts/logs can do so too. Post-return `pipelineStates` cannot authenticate state changed and restored around the native create call. Current source performs a uniform-colour quad, with the intended depth state and no such mask. ALWAYS/no test would fail the original mixed samples; NEVER would fail expected-pass pixels; clearing to one constant would fail their split. These controls support the bounded original observation but do not authenticate executable binaries or GPU origin. The replay downgrade below can also pass while measuring no coexistence at all.

## 2. Gate and adversarial replay

**CONFIRMED — baseline replay exits 0.** Requested command:

```sh
python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261009T045515-905630Z
```

Artifact: `.agent-run/r15-replay.log`. All **277** manifest members reconcile; all **408** selected source inventory entries and bytes match this checkout. Recorded revision is the pre-commit `733dcbd9935fc7bc4840ba20e74f726315b3a746`, with dirty candidate files and `changed_sources_during_run: []`; it is not a record that this final HEAD SHA was already running. Replay excludes the environment gate, validation-loader/diagnostic scan, full screenshot checks and Minecraft execution. It prints **ten** named `replayed` checks, with coexist inside the ladder result; the survey's eleven-check count is inaccurate.

Mutations copy the **entire** package to `.agent-run/r15-attacks/<name>/`, preserve source fingerprints and refresh the complete manifest. Semantic pixel attacks also reconcile published counts and the coexist log, so they reach the intended semantic check. Single pixel edits use the bottom two-row strip outside complete thumbnail blocks to isolate per-pixel semantics. Commands: `python3 .agent-run/r15-attacks.py` and `python3 .agent-run/r15-attacks.py flag_off_empty_report_remove_crops`. Combined results and exact changed/deleted files: `.agent-run/r15-attacks-all.json`; per-case replay outputs: `r15-attack-<name>.json`.

| Mutation | Exit | First relevant result |
| --- | ---: | --- |
| `pixel_present_behind`: quad present at d > z*, behind nearer MC depth | 1 | One presentWhereFail violation |
| `pixel_absent_in_front`: quad missing at d ≤ z*, in front of farther MC depth | 1 | One absentWherePass violation |
| `pixel_uncovered_changed`: uncovered green becomes ladder blue | 1 | One changed uncovered colour class |
| `pixel_outside`: uncovered pixel becomes RGB (70,70,70) | 1 | One pixel outside both classes/palettes |
| `pixel_uncovered_bucket`: uncovered (0,255,0) → (64,192,64) | **0** | Different RGB accepted as unchanged green class |
| `pixel_quad_bucket`: (128,255,128) → (96,192,96) | **0** | Nonliteral quad RGB accepted as quad class |
| `swap_before_after` | 1 | First-crop ladder recount contradicts rungs |
| `counts_lie` | 1 | Published present count differs from retained crops |
| `missing_crop` | 1 | Required second crop missing |
| `missing_log_line` | 1 | Log line None differs from recount |
| `missing_entry` | 1 | Two retained coexist images omitted from inventory |
| `flag_off_results_listed` | 1 | Results listed in disabled report |
| `flag_off_empty_report_saved_results`, images still present | 1 | All 48 coexist images orphaned |
| `duplicate_result` | **0** | A 25th identical result silently collapses by draw number |
| `coexist_rung_lie` | 1 | Wrong published coexist rung refused |
| `flag_off_empty_report_remove_crops` | **0** | Entire coexist proof skipped despite saved launch/results/log |

For clarity, in reverse-Z Minecraft depth **farther than the quad** means d ≤ z* and the quad should be **present**; nearer MC depth means d > z* and it should be **absent**. The first two attacks invert these correct outcomes. The Python test method names at `test_ladder_gate.py:845,850` say “farther”/“nearer” in the opposite sense if read as MC depth; their fixtures and assertions use the correct inequalities.

**REFUTED — exact RGB/unchanged-pixel enforcement, non-blocking R15-COEXIST-RGB.** `verify.py:829–835` accepts channels 0–64, 96–160, 192–255 as the three levels. `:1265` uses these levels for quad detection; `:1278` tests class equality rather than RGB equality. The Java callback does the same (`McNativeDepthLadder.java:524,535–539`). The green mutation at crop pixel (1151,85), absolute (1535,733), changes colour substantially while all reported zero-violation counts remain unchanged; only its crop and manifest bytes change. The quad mutation at (1084,85) does likewise. Depth presence/absence **classes** remain correct, and the original images independently preserve exact RGB, so these escapes do not refute the original numerical measurement. They do refute literal zero-colour-tolerance/unchanged-RGB gate claims. Thumbnail boundary anchoring is the already disclosed positional limit, not the reason the classifier treats these different colours as equal.

**REFUTED — required coexist evidence cannot be removed, blocking R15-COEXIST-PRESENCE.** In the final attack, set `coexistEnabled:false`, set `coexist:[]`, delete the 24 second crops and 24 second thumbnails and their manifest entries. Leave the original **24 coexist log lines**, saved enabled report/counts/results, and the launch command **`-PharnessNativeCoexist=true`** in `summary.json` untouched. Replay exits **0**, returning `coexist:{enabled:false}`. This rewrites the report and manifest and deletes 48 files; it does not forge images, cameras, direction, source hashes, checkpoints, or summary results.

Cause: disabled coexist returns early at `verify.py:1210–1213`, without reconciling the log. The stage always requests coexist (`:2834`) but merely consumes `native_ladder_result.success` (`:2847–2851`), which permits disabled coexist. Replay reconciles saved **direction** only (`:2623–2629`), and never requires/reconciles saved coexist state. Its required-file inventory has no coexist requirement independent of the editable report. This is a new optional-proof downgrade, beyond the disclosed coordinated-image-forgery limit. Requiring every sample while enabled is sound; trusting a downgraded enabled bit defeats that requirement for the claimed experiment.

**CONFIRMED — enabled per-sample semantic checks otherwise work on the requested attacks.** The count/log/violation/unchanged-class checks are at `verify.py:1282–1306`; mixed presence at `:1310`. An identical duplicate entry is a non-blocking uniqueness gap (`:1218–1225`): draw-keyed lookup retains one correct result for every sample, but does not reject the contradictory raw list cardinality.

## 3. Tests and removal mutations

Requested commands rerun:

```sh
./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true
python3 -m unittest discover -s scripts/tests
```

**CONFIRMED — JUnit 333, one documented skip, zero failures/errors; Python 185, zero failures/errors/skips**, Python elapsed 401.477 seconds. Artifacts: `.agent-run/r15-gradle.log`, `r15-python.log`, `r15-independent.json`. Fresh per-test XML identifies only `VkBarriersTest.missingBarrierIsDetected()` as skipped; validation messages occur only in `plainBufferHazardIsNowDetected()`'s deliberate fill-buffer WAW control (`r15-junit-diagnostics.json`). The baseline test task executed. No fixture was shrunk: Python fixtures remain 960×540, real evidence remains 1708×960/1920×1080.

**CONFIRMED — R14-TEST-BINDINGS closed.** In-memory removal of the sample-log/report stage guard (`verify.py:1076`) now fails precisely `test_a_sample_log_stage_that_contradicts_the_report_fails` (`test_ladder_gate.py:725–736`), with success unexpectedly True. Removing sample/checkpoint cameraY eligibility (`verify.py:1471`) now fails precisely `test_a_sample_camera_that_disagrees_with_its_checkpoint_fails` (`test_ladder_gate.py:738–750`), also because the bad fixture becomes successful. Each run executes all 15 unchanged direction tests, with 14 pass / 1 fail. These are guard-isolating failures, not merely a different rejection message.

| Other direction mutation | Result: 15 unchanged direction tests |
| --- | --- |
| Remove x/z eligibility | 14 pass, 1 fail |
| Select first eligible instead of last | 14 pass, 1 fail |
| Remove shared-ground check | 14 pass, 1 fail |
| Remove checkpoint-geometry check | 14 pass, 1 fail |
| Remove harness-stage reconciliation | 14 pass, 1 fail |

The shared-ground/geometry tests still combine constraints and error wording; their failures do not independently prove every guard is essential. Commands: `python3 .agent-run/r15-mutants.py --table` or individual names `xz`, `last`, `ground`, `checkpoint_geometry`, `harness_stage`, `log_sample_stage`, `camera_checkpoint`. `r15-mutant-<name>.json/.log` retains exact failing tests and tracebacks. Disk implementation stays unchanged.

**REFUTED — LadderCoexistTest protects every coexist check.** Each direct refusal guard in `coexist_checks` was independently disabled in memory, without changing its eight tests. Results:

| Removed guard, `verify.py` line | Eight unchanged coexist tests |
| --- | --- |
| 1205: enabled boolean | **8 pass** |
| 1208: result list type | **8 pass** |
| 1211: results while disabled | 7 pass, 1 fail |
| 1214: pinned rung/RGB | **8 pass** |
| 1220: entry/draw type | **8 pass** |
| 1224: complete draw key set | **8 pass** |
| 1235: finite/nonnegative counts | **8 pass** |
| 1239: second crop filename | **8 pass** |
| 1242: second frame filename | **8 pass** |
| 1246: crop existence | 7 pass, 1 fail (message assertion; file read still rejects) |
| 1250: crop dimensions | **8 pass** |
| 1254: frame existence | **8 pass** |
| 1257: second-thumbnail anchoring call | **8 pass** |
| 1283: published counts equality | 7 pass, 1 fail |
| 1291: log counts equality | 7 pass, 1 fail |
| 1294: outside both palettes | **8 pass** |
| 1298: incorrect presence/absence | 6 pass, 2 fail |
| 1304: unchanged uncovered class | 7 pass, 1 fail |
| 1310: mixed sample | 7 pass, 1 fail |

Deleting the whole coexist call fails all eight, including the enabled happy path. Twelve of nineteen individual check removals leave all eight green. A surviving mutant does not establish an accepted bad package where another guard still rejects; the existence guard example explicitly demonstrates this distinction. Definitions and every result are retained in `r15-mutant-definitions.json`, `r15-mutant-table.json` and individual files.

**CONFIRMED — two meaningful surviving mutants also pass the entire unchanged 185-case Python suite**, zero failures/errors/skips: `python3 .agent-run/r15-mutants.py coexist_if_1214 --full` (401.932 seconds) and `python3 .agent-run/r15-mutants.py coexist_if_1294 --full` (401.673 seconds). Against those respective mutants, the full wrong-rung package and the reconciled out-of-palette pixel package each replay as **0**; baseline replay refuses both. Artifacts: `r15-mutant-coexist_if_{1214,1294}-full.{json,log}`, `r15-mutant-escapes.json`, `r15-mutant-escape-<name>.json`. The surviving check removals and recorder mutation are non-blocking **R15-TEST-COEXIST** coverage findings.

**REFUTED — Java unit tests protect the coexist recorder's pipeline binding.** A JVM class transformer changes only the in-memory `recordCoexist` pipeline index from OP_COEXIST (3) to OP_ALWAYS (1), leaving four correct create-infos and published pipeline states. The entire unchanged **333-test JUnit suite still passes**, with one skip. It would draw over every mixed pixel in a real run. The actual checked-in recorder is correct; original evidence and enabled replay semantics would reject that faulty runtime result. Non-blocking **R15-TEST-COEXIST** records the unit coverage gap, not a claim the current source binds ALWAYS.

Reproduction: build `.agent-run/CoexistMutantAgent.java` as `.agent-run/r15-coexist-mutant.jar` using cached ASM, then `./gradlew test --offline --rerun-tasks -I .agent-run/r15-java-mutant.gradle -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true`. `r15-java-mutant.log`, `r15-java-mutant-result.json` and `r15-java-mutant-xml/` retain success and the transformation marker. No production class on disk was modified; mutant XML is separate from baseline XML.

## 4. Survey and corrected passages

**CONFIRMED — sixteen retained runs; only newest replays.** Every directory was replayed against this checkout (`r15-replay-table.json`): fifteen exit 1, `20261009T045515-905630Z` exits 0. Historical source bytes cannot satisfy today's inventory. This supports the survey table's design and does not invalidate scoped historical observations. Its header still dates the “current gate” 2026-10-07 (`survey:448`), although today's row is 2026-10-09.

**CONFIRMED — bounded coexist section's original counts, mechanism and scope** (`survey:1330–1358`), including 24 samples, five mixed, no native terrain/accepted-layer claim, no general depth scale/other hooks. “Voxy's convention” here is direct numeric NDC depth with Voxy's compare op; it does not test Voxy terrain's vertex projection/reprojection or normal-play depth composition. The measurement indeed advances the goal by one diagnostic experiment. **REFUTED — unconditional zero-tolerance/unchanged-colour enforcement** unless expressly scoped to the quantized classes. The original images do satisfy the stricter exact-RGB reading independently; the gate does not. Also, there are ten named replay checks, not eleven; two words/labels are missing in `survey:1350` (“the two  looks”, “fourth pinned entry .”).

**CONFIRMED — listed source comment and survey sentence corrections.** `survey:1244–1249` now distinguishes the single band from direction inferred with two looks. `current-state.md:62` dates direction correctly to 2026-10-09. Source `McNativeDepthLadder.java:136–140,933–940,970` explicitly limits post-return observation and discloses restore-after-call. **REFUTED — R14-DOC-DRIFT fully closed / round-14 paragraph's blanket repair claim** (`survey:1321–1328`). The updated handoff still contains these current contradictions:

- `handoff.md:89` calls the measurement “in this HEAD” but gives the older 18-sample run, still says direction unmeasured, and gives old counts/replay facts.
- `handoff.md:109–111` says “The Z convention is unmeasured” under “What is NOT established”, despite its confirmed direction bullet at `:98–103`.
- `handoff.md:116–117` says the coexistence draw “is not yet attempted”, despite its measured bullet at `:93–97`.
- `handoff.md:124–125` and manual launch at `:187` omit the new coexist flag. Source comments still say three pipeline states at `McNativeDepthLadder.java:123,960`, although four now exist.

These are non-blocking documentation defects. The accurate new coexist section does not close the unchanged contradictory handoff prose. **R14-DOC-DRIFT remains false.** The standing accepted-layer boundary remains accurately stated (`handoff:118–119`; project goal).

## 5. Normal-play safety and standing measurements

**CONFIRMED — dormant safety with all eight native flags unset.** Ladder returns before looking up Minecraft/device/targets (`McNativeDepthLadder.java:258–259`). Coexist drawing is additionally guarded by its own flag and a successfully registered first readback (`:349`); `recordCoexist` has only that production call site. Coexist bookkeeping is flag-gated at `:626`. Marker/terrain/depth/probe return on their flags (`McNativeMarkerDraw.java:273`, `McNativeTerrainProbe.java:185`, `McNativeDepthProbe.java:99`, `McNativeVulkanProbe.java:296`). Adoption returns disabled (`McNativeVkContext.java:96–99`); feature augmentation returns the original set (`McNativeDeviceFeatures.java:78`). Unadopted release returns before device wait (`McNativeVkContext.java:287`). Uninitialized ladder shutdown returns without GPU work (`McNativeDepthLadder.java:1060–1084`). Render-tail calls (`client/mixin/minecraft/MixinLevelRenderer.java:56–62`) respect these entry guards.

**CONFIRMED — coexist cannot draw unflagged and never writes Minecraft depth in the inspected source, even flagged.** Both passes use LOAD and no clears (`:332–334,353–355`); all four pipelines use test on/write off (`:901–905`), and the quad binds the fourth (`:411`). Copy routines read colour to buffers only. JUnit checks all four creation states/compare ops (`McNativeDepthLadderTest.java:51–110`), while the recorder-binding mutation demonstrates the stated separate coverage limit. A simultaneously enabled terrain/marker experiment may clear/write depth; isolated ladder evidence forbids those flags. This judgment concerns this ladder/quad, not every experimental flag combination.

**Would ship on the GL backend:** yes for this dormant change on a supported established-GL configuration, within existing renderer limitations; no fresh player GL regression matrix was run. **On Minecraft's Vulkan backend:** safe installed/inert with these flags unset; **would not ship as functioning native Voxy**. Backend selection disables Voxy without Minecraft GL (`VoxyClient.java:96–112`). Harness teleports/checkpoints are development-only (`build.gradle:519–538`), not added to the production mod. No packaged-player launch was performed.

**CONFIRMED — terrain experiment remains bounded synthetic equality.** Independently decoded all **21** retained native/reference pairs and found exact RGB equality; selected 4111 reports 26,116 non-background pixels, zero mismatches. It clears Minecraft's target and tests synthetic input, not native LoD/coexistence/lifecycle delivery. **CONFIRMED — retained depth PGM holds 1,639,680 quantized zero samples**, 1708×960; original float zero remains producer-reported because no float buffer is retained. **REFUTED — this copy proves direction, scale or cause.** The separately retained two-look ladder supports bounded reverse-Z: last descend 5042 at cameraY 80.61999988555908, ground 67, brackets 3/4; last ascend 5522 at cameraY 176.61999988555908, same ground, bracket 2. Geometry, chronology and repaired bindings remain accepted with their stated tolerances/off-centre-footprint limits.

**REFUTED — diagnostics or this measurement are an accepted foundation/native integration.** The rounds 4–14 delivery boundary continues: native real-world Voxy LoD, normal configuration, update/lifecycle correctness and accepted native resource-lifetime/visual evidence remain unimplemented or unmeasured. Blocking **DELIVERY-BOUNDARY** stands regardless of passing unit tests/replay.

## What could not be checked, and why

- No Minecraft/native/live launch, by instruction. No fresh runtime submission, environment/validation-loader/diagnostic acceptance, GL player regression, packaged-jar Vulkan play, sustained lifetime/pressure, updates or resource reload proof.
- No full original before/after readback buffers, raw Minecraft depth for the measured pixels, retained world save or per-ray distance survey. Whole 4×4 thumbnail blocks anchor positions; partial boundary blocks and within-block rearrangements retain the prior positional limit. Equality at exactly z* is reasoned, not identified in retained native depth.
- No authentication of dependency jars, compiled runtime/JVM, driver, native-call create-info consumption or GPU origin. Source-byte binding is independently verified; hashes over editable records do not authenticate execution. Prior creator restore-after-call instrumentation was not rerun; the current source explicitly discloses that limit.
- The original pixel measurement is accepted only at this hook, one known NDC depth, retained bands and host/run. No Voxy terrain projection/depth-scale parity, real-world LoD, other hook/world/device, lighting, translucency or native delivery acceptance follows.

Machine verdict: `.agent-run/native-integration-review.json`. Review scripts, full mutation packages, logs, decoded original images and recounts remain under `.agent-run/r15-*`.
