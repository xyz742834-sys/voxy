VERDICT: REDESIGN

Independent round-7 review, 2026-10-06, of `/Users/xyz/orca/workspaces/voxy/native-review-r7`, exact HEAD **3ac2c7b39535b9fcb97a4f5cf41f00973d2af2a0**. HEAD matched and the initial tree was clean. Implementation was not changed. No Minecraft launch, `--only native`, `--only live`, or interop runner was executed. The requested offscreen JUnit suite was executed. Only this report and `.agent-run/native-integration-review.json` are review additions.

The round-4–6 boundary still governs: **an independently accepted diagnostic foundation is still REDESIGN**. The candidate correctly does not claim one. Its claim that all seven open findings are repaired is **REFUTED**: two close, five retain narrower residuals. Its synthetic, scene-clearing terrain RGB measurement is **CONFIRMED**; general gate soundness is **REFUTED**. Its reported uniform-depth result warrants a negative result and an unmeasured convention; isolation and the depth gate's claimed measurement strictness are not established. Dormant normal-play safety is judged separately.

## 1. Each open finding from round 6

| Finding | Complete repair claim | Closed | Judgment |
| --- | --- | --- | --- |
| B1 | REFUTED; named geometry/empty-manifest repairs CONFIRMED | No | Geometry shift now fails, but source-defined expected colours remain producer-controlled; core manifest membership remains optional. |
| B3 | REFUTED; duplicate/negative-offset and duplicate-added repairs CONFIRMED | No | Distinct impossible feature offsets still pass, including offsets outside the entire struct. |
| B4 | REFUTED as a complete evidence gate; actual retained two-crop result and honest orientation limit CONFIRMED | No | Opposite crop can be one unrelated pixel; clipping still stands in for absence. |
| R4-L1 | CONFIRMED | Yes | Both marker shutdown abandonment paths count; creation stops at three. |
| R6-TERRAIN-GATE | REFUTED; most individual checks CONFIRMED | No | Replay omits adopted-device argument; reference and rejected-marker samples need not be manifest members. |
| R6-TERRAIN-DEVICE | REFUTED as a complete repair; recording halt CONFIRMED | No | A→B stops recording and leaks safely, but leaves the previous clean on-disk report unchanged. |
| R6-TERRAIN-WAIT | CONFIRMED | Yes | Submitted reference plus unsuccessful fence wait frees no target, renderer or resources. |

### B1 — source-bound reference values and manifest binding

**CONFIRMED:** `scripts/verify.py:45–71,310–312` asserts every geometry field against source constants, refuses unknown fields, and agrees with `McNativeMarkerDraw.java:136–139,161–181`. Reproducing round 6's original attack against the current 1920×1080 marker sample: remove 20 left columns, move the crop origin from x=19 to 39, set near=10260/control=3784 and corresponding areas; replay returns **1**, because the source-defined near region is incomplete. Also move `geometry.box[0]` to `2*39/1920-1`; replay now returns **1**, specifically rejecting the geometry. `MANIFEST.json={}` returns **1** (`verify.py:1377–1385`).

**REFUTED as complete (blocking B1):** expected RGB values remain supplied by the same producer whose pixels are judged (`verify.py:576–577,657–670`), unlike geometry. Source fixes magenta/cyan/yellow at `McNativeMarkerDraw.java:124–133` and records them at `768–797`. Replace every magenta pixel in the selected retained sample with cyan, retaining the yellow cell/strip and the valid opposite crop. Change only `markerRgb` to `[0,255,255]`, and recompute affected hashes. All source-defined near pixels are now absent, but **replay returns 0**, with near=12420, far=8316, cell=4224. The gate accepts identical colours for two draws required to be distinguishable. This is a contradictory evidence mutation, not an allegation that the committed image is bad.

The empty-manifest repair is a cardinality heuristic, not complete membership. Removing `native-marker-draw.json`, `native-terrain-probe.json`, `native-adopted-context.json`, `summary.json`, `source-sha256.json` and `native.log` from the manifest's map, leaving their actual files and the other 115 entries, also **replays as 0**. `verify.py:1381–1389` checks whichever hashes are listed, and `1423–1429` requires only the selected marker and terrain sample names. Candidate metadata, summary, source fingerprint and consulted reports can still be unbound. The actual committed manifest binds these correctly; this finding is about acceptance of incomplete/contradictory alternatives.

### B3 — one-to-one feature proof and numerical consistency

**CONFIRMED:** `verify.py:1084–1117` rejects repeated `added` entries, repeated verification names, missing/empty notes, duplicate offsets and negative offsets (the exact note regexp rejects the minus sign before the explicit nonnegative check). Reproduced empty notes, duplicate-added, all-four-offsets-0 and negative-offset attacks: all fail. Existing sentinel, attempted/adoption and identity checks remain present.

**REFUTED as complete (blocking B3):** four notes with offsets **1000001,1000002,1000003,1000004**, each assigned to a different requested feature, pass `native_proof_files_result` and the full retained replay (**0**). These offsets are distinct and nonnegative but cannot verify those fields. `McNativeDeviceFeatures.java:124–142` only experiments with `lwjglOffset` and `VkPhysicalDeviceFeatures2.FEATURES+lwjglOffset`; the source does not propose arbitrary offsets. A heap-only query of this checkout's resolved LWJGL classes gives `VkPhysicalDeviceFeatures.SIZEOF=220`, `FEATURES=16`, wanted offsets **40,160,104,100**. The gate never checks alignment, range, field identity or either candidate offset. Even swapping the four real offsets would contradict which fields were verified. Distinctness alone is insufficient proof consistency.

### B4 — both orientations, absent-pattern proof, and honest limits

**CONFIRMED for the actual retained pixels:** `McNativeMarkerDraw.java:450–465,645–669` writes the selected raw crop and the other orientation's raw crop from the same mapped buffer and capture count. `select:526–555` rejects yellow inside either box and two dense valid patterns. Independent recount of the current selected sample gives near **12420/12420**, far **8316/8316**, cell **4224/4224**, control strip **4032/4224**, rejected-in-box **0**. Recount of `native-marker-rejected-4562.ppm.gz` gives near **0/12420**, far **0/8316**, cell **0/4032**, strip **0/4224**, rejected-in-box **0**, satisfiedRegions **0**. Its pixels were inspected/decoded, rather than inferred from the producer's aggregate.

Thus the actual pair supports **exactly one orientation satisfies the defined pattern, and neither box contains the rejected colour**. It does not establish WHICH orientation actually produced a draw. The survey's explicit limit at `vulkan-native-integration-survey.md:896–901` is **CONFIRMED** and resolves round 6's attribution overclaim within this narrower scope. The good-mirror/all-cyan-actual counterexample remains possible, but is not a refutation of that deliberately narrowed claim. It must not be promoted to unique draw provenance.

**REFUTED as a complete gate (blocking B4):** `recount_rejected_orientation.region` still clamps coordinates (`verify.py:551–572`), unlike selected-crop recount (`630–655`). Replace the opposite sample with a gzipped P6 **1×1 black image**, set `rejectedOrientationRect=[0,0,1,1]`, leave its opposite-orientation boolean, and rehash sample/report. **Replay returns 0.** All expected regions fall outside the crop; empty loops return zero, and clipping produces nonphysical areas. Unobserved box pixels can therefore contain yellow, or an omitted complete second pattern, while the gate claims absence. Absence requires coverage of the entire expected region too.

The opposite sample need not be a manifest member (removing only its entry still returns **0**), and selected marker filename/capture agreement remains unchecked. These are evidence-binding residuals, not failures of the committed good pair. The survey's narrowed claim is no stronger than this pair, but its assertion that the gate generally substantiates it is too strong.

### R4-L1 — abandonment budget on both shutdown routes

**CONFIRMED closed for the named production abandonment routes:** `McNativeMarkerDraw.java:1012–1018` increments `leakedPipelines` when Minecraft's device is unreachable; `1040–1049` increments it when the waited device differs. Both cut the static reference first and mark abandoned draws unusable. Ordinary retirement mismatch/refusal already counts (`956–977`); creation returns before device access once the count reaches three (`815–823`). Counters are cumulative, not reset by shutdown.

Heap-only calls to the freshly compiled production methods, using constructor-free wrappers and no Vulkan commands, gave:

```text
threeUnreachableShutdowns leaks=3
threeWrongWaitedDeviceShutdowns leaks=3
budgetCreateWithNullDeviceReturnsNull=true
```

The last call invokes real `create(null,37,126)` at the exhausted budget; its null return before dereferencing the device confirms enforcement. Readback abandonment remains one-in-flight, byte-bounded and failure-budgeted. This closes the two round-6 missing-accounting paths; it does not prove a successful destruction or zero leak in every possible hardware failure. The shutdown counters are not necessarily published immediately, which remains an accounting/durability limit under R5-IO.

### R6-TERRAIN-GATE — stage versus replay mutation table

**CONFIRMED:** the same `terrain_report_checks` performs report/type/pixel checks in the stage and replay (`verify.py:837–939,961,1416`). Truncated gzip is a structured `EOFError` failure (`964–965,1430–1435`). The capture bound and required boolean `flipped` are enforced. Replay has repaired the old wholesale report-check bypass.

Independent mutations of the current retained run, with changed report/sample hashes recomputed:

| Mutation | Stage terrain gate, with adopted device | Replay exit |
| --- | --- | --- |
| Entire round-6 failed-report group | false | 1 |
| enabled=false, attempted=false, built=false, draws=0, clean=0, problems=3, closeFailures=99, leakedProbes=3, notes=[FAILED], each separately | false | 1 each |
| device=0x0 | false | 1 |
| device=0xdead | false | **0** |
| recorded total=1, retained capture=4580 | false | 1 |
| sampleAtDraw=0 or -1 | false | 1 each |
| omit comparison.flipped | false | 1 |
| deviceDiverged=true | false | 1 |
| Remove five gzip trailer bytes | false, structured EOFError | 1, structured EOFError |
| Remove selected terrain sample's manifest entry; file still present | pixel/report helper succeeds; retention supplies membership | 1 |
| Remove reference sample's manifest entry; file still present | pixel/report helper succeeds; retention supplies membership | **0** |

**REFUTED complete equivalence/provenance (blocking R6-TERRAIN-GATE):** stage extracts the adopted identity and passes it at `verify.py:1570–1574`. Replay calls `terrain_report_checks(directory,terrain)` **without** expected_device (`1416`), so `893` skips the comparison. The proof helper does not include terrain among its identities. This is the same round-6 wrong-device attack surviving one caller's configuration, despite shared code.

Replay membership checks cover the selected marker and selected terrain sample, but **not the terrain reference or opposite marker crop** (`1423–1429`). Removing either of those manifest entries leaves replay at **0**. The stage's retention code expressly requires the terrain reference (`1297–1301`) and the opposite marker sample (`1287–1296`). Thus replay still skips a stage requirement. Merely changing the reference/pixels and not its listed hash is rejected; the bypass is omitting the reference from what is bound.

### R6-TERRAIN-DEVICE — safe recording refusal versus failure publication

**CONFIRMED recording/ownership repair:** `McNativeVulkan.vkDeviceHandle` reads the supplied active Minecraft wrapper's handle; terrain compares it with adopted context before any build/pass/draw (`McNativeTerrainProbe.java:212–230`). Divergence is sticky for the session; stale instance is dropped, marked destroyed and counted as a leak. Retirement ownership additionally compares Minecraft's current handle (`684–691`). The real A→B heap injection now yields:

```text
terrainAtoB ownershipAccepted=false
AtoB instanceNull=true deviceDiverged=true leaks=1 priorDiskUnchanged=true
BtoA stillStopped=true diskUnchanged=true
```

**REFUTED the accompanying “fails the gate” guarantee (blocking residual):** the same route returns at line 228 without `writeEvidence`; sticky refusal returns at 230. `note()` only adds a note/logs WARN (`844–850`); it does not publish JSON. The injection seeded the evidence destination with the actual committed clean terrain report, and its bytes remained unchanged after divergence and after return to A. That file still says built=true, deviceDiverged=false, leakedProbes=0 and no notes. The gate tests the FILE, not static state. A permanent replacement observed at a lifecycle checkpoint can fail other identity checks, but a transient A→B→A between checkpoints need not; the new terrain halt itself is not durably represented. No claim of live wrong-device recording remains: the recording guard fixes that part.

`destroy()` still has no internal owner/context recheck (`707–720`); `VkBuffer.free` uses global `VkContext` (`VkBuffer.java:92–97`). Queued destruction across a later context replacement is not independently certified. One-shot adoption and the intended old-encoder lifetime constrain this route; the demonstrated blocking residual here is failure publication, not an induced native destruction crash.

### R6-TERRAIN-WAIT — unobserved reference completion

**CONFIRMED closed:** `build` records submitted only after `endFrame`, and waitObserved only after `waitForFrame` returns (`TerrainProbe:334–335,364–367`). The finally block counts/leaks the reference, renderer and resources on submitted && !waitObserved (`395–415`); it does not free them and does not read mapped reference pixels. If the tracker was started here, later tracker shutdown performs its own checked idle wait (`VkFrameTracker.java:227–240`), rather than making the earlier unsuccessful fence wait count as completion. The corrected comment at `TerrainProbe:419–423` acknowledges that idle wait.

Reproduced without a GPU submission using a temporary Java instrumentation agent: **the candidate's `McNativeTerrainProbe.build` bytecode was unchanged**; only its GPU-dependent constructors/recording/submit operations were stubbed, waitForFrame injected an exception, and resource free calls were counted. Result:

```text
buildNull=true calls={submitted=1, tracker.shutdown=1}
leakedProbes=1
```

No target/renderer/resource free call occurred. Control with waitForFrame returning successfully reached an injected/empty-reference read failure and gave:

```text
calls={submitted=1, waitObserved=1, VkRenderTarget.free=1,
       VkTerrainRenderer.free=1, VkTerrainResources.free=1, tracker.shutdown=1}
```

This control confirms free counters detect the cleanup route. The instrumentation proves production control flow around a throwing wait, not actual GPU/device-loss behavior. No implementation files were instrumented on disk.

## 2. Experimental terrain judgment

**CONFIRMED within round 6's stated scope.** `TerrainProbe:341–389` uses boundaryCases, real Voxy resources/shaders/renderer and CPU-produced indirect commands, on the adopted device. It draws its reference through the complete renderer and waits before readback. `271–286` then opens Minecraft's pass with both attachments cleared and records the real terrain draw seam. `452–467` requests Minecraft colour readback. No source path copies the reference pixels into Minecraft's image.

I decoded/viewed the nonblank retained image, and independently compared **all 22 retained native/reference pairs**. Every pair has **0 RGB mismatches**. At 1708×960 each has **20663** non-background pixels; at 1920×1080 each has **26116**. The report's referenced pair is capture **4580**, 1920×1080, 26116 each side, 0 mismatches. The report snapshot has 21 clean comparisons, while a later 4820 pair is also retained. The sample lists are inspectable evidence, not only assertions about one successful frame.

`VkTerrainRenderTest.theSplitRecordingPathDrawsTheSameImage` executed and passed (`src/test/java/me/cortex/voxy/vk/VkTerrainRenderTest.java:304–334`). It is bounded: the full and split routes share the new draw helpers and a renderer warmed by the first render. It does not independently establish all fresh split-only state, different extents, alpha/depth equivalence or freedom from a common renderer defect.

The experiment proves same-renderer RGB agreement up to normalized whole-image vertical orientation, with synthetic input and a stable adopted device. It does not prove real-world meshing/atlas/LoD selection, normal gameplay, Minecraft scene coexistence, independent scene correctness, performance or a compute-fed production draw count. Alpha and depth are not compared; clearing both attachments is essential to its scope. These limits are honestly stated in the survey. The gate residuals above do not erase this retained measurement, and the measurement does not validate the general gate.

## 3. Depth result and gate asymmetry

### Reported negative result

**CONFIRMED as the reported bounded negative observation, with evidence limits:** both retained depth reports, in `20261006T084300-332469Z` and `20261006T095436-054038Z`, and their logs report a completed 1708×960 D32_SFLOAT copy, min=max=topMean=bottomMean=0, histogram first bin=1639680 and all other bins=0. The newest report has uniform=true and reversedZ=null. `McNativeDepthProbe.java:171–247` computes those statistics after mapping the callback's buffer. This is evidence for a completed attempted readback with no measured depth variation; **leaving Z convention unmeasured and the cause unknown is appropriate**, not an underclaim.

There is no retained raw depth buffer, so exact per-pixel depth cannot be independently recounted as marker/terrain pixels can. Histogram/bin and extrema are producer measurements corroborated by the log, not an independent physical observation. The appropriate conclusion is “these captures did not provide usable scene-depth evidence”; a general impossibility of copying scene depth is not established. A uniform attachment can be an actual clear/empty image, and neither source-image contents at the hook nor why the result is uniform was measured independently. The survey appropriately retains possible wrong-hook/image or copy-behavior explanations.

**REFUTED as retained proof of isolation:** both committed depth runs also contain enabled terrain and marker reports, terrain reference/draw logs and native-stage flags. No committed depth-only run, isolated invocation, bound source/log/raw artifact or distinct isolated measurement is supplied. The current hook does request depth **before** terrain and marker (`MixinLevelRenderer.java:53–57`), reducing same-hook contamination; that does not turn the run into one with those flags unset. `DepthProbe:218–220` appends “measured with nothing else writing to the image” unconditionally, even in the combined run. That string is narrative, not a flag observation. Survey `926–929` and current-state's isolation claim therefore cannot be independently confirmed from committed evidence. This does not establish contamination; it leaves its exclusion unproven.

### “Strict measurement, permissive answer”

**CONFIRMED as a sensible policy; REFUTED as the implemented guarantee (new blocking R7-DEPTH-GATE).** Failed copy or a uniform image can legitimately yield “unknown / no useful observation”; it need not make a diagnostic run fail merely for that answer. The gate correctly rejects attempted=false, uniform+reversedZ, incomplete+reversedZ, insufficient totals and close failures.

But `native_depth_result` never remeasures pixels, does not tie histogram to extrema/means, and does not establish which band is near/far (`verify.py:738–831`). Reproduce on the real zero-depth report, without changing its all-zero histogram:

```text
uniform=false; min=0; max=1; topMean=0; bottomMean=0.8; reversedZ=true
native_depth_result -> success=true, readable=true,
answer="Minecraft's scene depth is readable; reverse-Z (larger is closer)"
```

This contradicts the histogram's 100% zero-bin population and launders an unmeasured convention. Conversely, keep uniform=true and min=max=means=0 but put all 1639680 histogram entries in the last bin: the gate still succeeds. Changing depthVkFormat to colour format 37 and device to 0x0 also succeeds. Negative width/height whose product remains correct succeed. Those contradict the purported measurement, irrespective of whether the answer is negative.

There is a deeper inference error even with consistent real nonuniform pixels: `DepthProbe.java:227–236` equates lower-screen depth with known-near ground and upper-screen depth with known-far sky solely because band means separate. Separation does not identify ground/sky, world distance, or row orientation. A wall, cave, tilted camera or vertically flipped transfer can reverse the inferred convention. The gate actually REJECTS “unknown” when means separate (`verify.py:816–818`), forcing certainty from an unproven premise. Its positive fixtures encode that premise rather than measure it.

Replay **does not call the depth gate at all** (`verify.py:1394–1437`) and does not list depth validation among its omissions. All three contradictory depth mutations above, rehashed in otherwise fresh copies, return replay **0**. Successful replay therefore does not revalidate all five native-stage gates.

### Read-only behavior and next route

**CONFIRMED:** the enabled probe allocates a destination buffer, copies FROM the depth texture, maps it for reading and closes the buffer (`DepthProbe:144–168,171–174,250–258`). It contains no Minecraft attachment clear, render pass, draw or image-write command. “Read-only” means no image-content writes; the encoder may still perform transfer synchronization/layout work. Unflagged `probeOnce` returns before device acquisition (`97–101`).

**CONFIRMED as a plan, not a result:** survey `939–942` proposes known-depth draws against a LOADed attachment. No completed Minecraft scene-convention experiment using that method is claimed there. The existing marker demonstrates attachment access and controlled depth testing after writing its own base; it does not yet infer untouched Minecraft scene-depth convention. That distinction must persist in the planned route.

## 4. Tests and fixture strength

| Requested command | Fresh result |
| --- | --- |
| `./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true` | Exit 0; test task executed; **326 cases, 325 passed, 1 skipped**, 0 failures/errors. |
| `python3 -m unittest discover -s scripts/tests` | Exit 0; **107 cases**, 91.125 seconds, OK. |
| `python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261006T095436-054038Z` | Exit 0; six listed checks; marker recount and terrain pair agree. |

The sole JUnit skip is `VkBarriersTest.missingBarrierIsDetected()`. Fresh XML run through `verify.junit_result(build/test-results/test)` gives success=true, known_gaps containing only that skip, and no unexpected diagnostics. The intentional fill-buffer WRITE_AFTER_WRITE control executed exactly once; duplicated console text is not a second diagnostic. Offscreen validation and synchronization validation were enabled. Native retained adoption logs instead report validation=true **syncValidation=false**; a clean native log does not independently establish active native synchronization validation.

**REFUTED that the named lifetime test verifies enabled lifetime correctness:** every case in `McNativeTerrainLifetimeTest.java:24–79` checks initial state, flags-off behavior, no instance, or initial JSON. None calls build, observes submission/wait facts, injects a current-device change or constructs a live instance for shutdown. All four cases would pass with round 6's broken ownership and unconditional cleanup restored. `everyEntryPointIsInertWithoutItsFlag` passes through null-instance shutdown, so it does not test a mismatching owner. These are useful dormant safety tests but not regressions for the bugs named in the class comment. The older `McNativeTerrainProbeTest.theFailureBudgetIsBounded` remains a constant/initial-state check.

**CONFIRMED useful marker orientation coverage:** `McNativeMarkerOrientationTest` exercises the actual classifier with correct patterns in both directions, yellow in the other box, dual patterns and no pattern. Its 240×160 image is fine for the Java density-only classifier; it does not exercise Python's absolute 500-pixel acceptance floor, opposite-crop retention/completeness or capture binding. Its passing good-mirror result is consistent with the narrowed orientation limit.

**Additional independently demonstrated weak test:** replace `verify.recount_rejected_orientation` in memory with `lambda *args: {}`. `EvidenceRetentionTest.test_the_retained_directory_replays_on_its_own` still **passes**; it checks only selected-orientation numbers (`test_marker_gate.py:784–800`). The retention test hashes both samples, but does not prove the other crop covers and fails the expected pattern. No production source was edited for this mutation.

The stage terrain identity test DOES fail if terrain_report_checks ignores its expected_device argument. Its existence does not catch replay forgetting to supply the argument; replay fixtures contain marker/proof files but no terrain (`test_marker_gate.py:689–722`). Likewise the depth uniform-convention rejection test detects removal of its uniform rule, but the positive convention tests accept band assumptions, inconsistent histograms and unsupported near/far attribution. `DepthProbeGateTest.test_a_measured_reversed_convention_passes` uses topMean=0.02 below its unchanged min=0.08; the original positive fixture's clearedShare=0.8 also differs from its fullest-bin share 2060/2560=0.8046875. The feature fixture publishes offsets **44,160,128,124** (`469–472`) although this classpath/source permits candidate pairs **40/56,160/176,104/120,100/116**. Its success accepts impossible field claims.

**CONFIRMED the 640×360 floor warning:** independent integer resolution gives cell area **448** at 640×360 and **960** at the current 960×540. Shrinking the Python fixture without changing the gate would invalidate its positive cases for the stated wrong reason. The current size clears the real floor and does not require weakening it. The remaining escapes come from untested colour references, opposite-crop coverage, absent terrain replay fixtures, default-state lifetime tests and depth assumptions; larger fixtures do not cover those defects. Full/split renderer comparisons also share implementation and warmed state, so common-mode defects can remain green.

## 5. Survey accuracy and evidence binding

**CONFIRMED:** the acceptance boundary and experimental label remain explicit (`survey:769–774,824–825,837–849`). The dated one-quad/no-push description is marked superseded (`174–178`). The terrain figures and synthetic/scene-clearing limits agree with inspected source and actual nonblank retained pairs. The newest “Measured:” paragraph at `904–913` accurately reports the test totals, retained successful stage snapshot, marker numbers, opposite crop's empty pattern, and 26116/0 terrain pair. Those are measurements/pass results, not proof that the gates reject every contradiction. It should not imply stronger closure of the seven findings than this review demonstrates.

**REFUTED the claim that the replay table describes every retained run under the current gate:** actual results are:

| Retained run | Current replay |
| --- | --- |
| 20261006T070311-099413Z | 1, missing closeFailures (also lacks raw samples) |
| 20261006T073859-396220Z | 1, missing leakedPipelines |
| 20261006T080931-279390Z | 1, missing leakedPipelines |
| 20261006T083136-838975Z | **1, no rejected-orientation sample** |
| 20261006T084300-332469Z | **1, no rejected-orientation sample** |
| 20261006T095436-054038Z | 0 |

Survey `452` still says 083136 **yes**, and terrain `819–820` still offers it as the citable replay. The table omits the last two directories. Dated measurements remain readable; those current replay statements are false.

Remaining overstatements: the survey's “refuses to destroy across a device change” (`833`) exceeds destroy's missing internal owner check, and its new gate-equivalence/membership prose (`857–865`) exceeds the reproduced replay checks. Historical depth-test AND depth-write proof (`297–307`) still overstates colour evidence: disabling LESS-pipeline writes can preserve the final pattern because ALWAYS base writes already establish box/cell depths. No marker depth sample proves those writes. “Nothing here is implemented” at line 12 remains stale as a current-status statement. These are non-blocking documentation findings, alongside the blocking implementation/evidence findings. The depth narrative explicitly retains unknown convention/cause, but its isolation assertion and the unconditional isolation basis string require evidence; current retained runs do not provide it.

For the newest run, all **121 listed file hashes** match, with **22 screenshot crops** and **86 raw colour samples**, 2,359,194 listed payload bytes. All **405 source/build/script fingerprint entries match this HEAD**, and changed_sources_during_run is empty. Recorded revision/final revision is dirty parent **35aff16fbda3e2f93564dfe7af3ccb8ee9cfc257**, not the final HEAD; source hashes provide exact binding of the listed candidate inputs despite that pre-commit run. They do not attest every dependency binary or the whole environment.

The native log identifies actual Minecraft Vulkan, Apple M4 Pro and MoltenVK 1.4.2, with the Khronos validation layer inserted and no native validation diagnostic. Expected Voxy-disabled errors and external profile-certificate HTTP failures are separate. Original full screenshots are absent; their hashes and crop origins do not independently establish parent extraction. No isolated depth evidence is retained. None of these facts converts the experiment into normal-play native terrain acceptance.

## 6. Safety for normal play

**CONFIRMED: I would ship these diagnostic changes dormant to an existing GL-backend player**, subject to ordinary project release checks. **CONFIRMED: I would ship them dormant with Minecraft's Vulkan backend as disabled Voxy**, without advertising native LoD functionality. These are source/test-supported dormant-safety judgments, not a fresh game smoke/soak or certification of every pre-existing renderer defect.

With `.probe`, `.marker`, `.features`, `.adopt`, `.terrain` and `.depth` all unset:

- Terrain returns before device access/allocation/files at `TerrainProbe:184–187`; Minecraft colour/depth clears exist only deeper inside flagged render (`274–277`). No terrain instance exists for shutdown to retire (`745–748`).
- Depth returns at `DepthProbe:98`, before request/device/buffer/copy/file operations. The new hook cannot read back depth unflagged.
- Marker returns at `MarkerDraw:273`; probe returns at `VulkanProbe:294–297`; feature augmentation returns the original set before GPU calibration (`DeviceFeatures:76–78`). Adoption does only bounded initial attempted/status bookkeeping (`McNativeVkContext:92–100`), and release returns before device operations without an adopted context (`287`).
- Build launch properties add terrain/depth only under their explicit harness switches (`build.gradle:585–591`). The normal GL renderer is preserved. On Minecraft Vulkan, `VoxyClient:96–112` still selects no Voxy backend.

The enabled-probe findings do not demonstrate a flags-unset regression. They prevent acceptance of enabled diagnostic proof, not this narrow dormant shipping judgment.

## What could not be checked, and why

No fresh Minecraft native/live stage, GL gameplay smoke/soak, real device replacement, native validation-under-fault, GPU device-loss/fence-error timing or long-session memory/disk exhaustion was run: the user expressly prohibited the Minecraft launch stages and requested judgment of committed evidence. The permitted offscreen suite cannot substitute for them. Heap-only wrappers and instrumentation establish conditional production control flow, not actual hardware recovery.

No raw scene-depth buffer, isolated depth-only invocation/log/fingerprint, original full screenshots, per-capture device/image-generation identity, independent world-scene reference, real-world mesh/atlas/LoD path, alpha/depth terrain equivalence, loaded-scene depth coexistence or native sync-validation enablement proof is available. The negative depth report cannot establish why the values are uniform or a universally unreadable path. The proposed behavioural convention experiment has not been measured.

R5-API/R5-IO limitations persist outside the requested repair scope: unchecked legacy `VkFrameTracker.waitIdle` (`220–224`), direct context destruction without its own completion guard, synchronous/unbounded cumulative diagnostic sample I/O, swallowed/non-atomic JSON publication, and incomplete registration-close accounting (`TerrainProbe:469–474`; marker registration failure publication precedes final close accounting). They are not accepted interfaces for future normal-play integration.

Reproduction inputs and results are in the report above. Temporary review-only scripts/logs remain under `/tmp`: `voxy-r7-{gradle,python,replay,adversarial,residuals,test-mutations,device,wait,wait-control,offsets}.log`, `voxy-r7-{adversarial,residuals}.json`, and the corresponding Python scripts. Java heap checks used freshly resolved candidate test runtime classpath (`/tmp/voxy-r7-classpath`, obtained by a temporary Gradle init task), `/tmp/R7DeviceProbe.java`, `/tmp/R7Offsets.java` and reused reflection helpers recompiled against round 7. The wait check used `/tmp/R7FaultAgent.java`, `/tmp/R7WaitProbe.java` and `/tmp/voxy-r7-fault-agent.jar`. These temporary files are not committed native evidence. The definitive conclusions are the scoped findings above and the requested machine verdict.
