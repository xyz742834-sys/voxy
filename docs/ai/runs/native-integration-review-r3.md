VERDICT: REDESIGN

Independent round-3 review, 2026-10-06. Reviewed checkout: `/Users/xyz/orca/workspaces/voxy/native-review-r3`. `git rev-parse HEAD` returned **a1d128f43001f693af17aa4dceaa106235891d6a**. The prompt's full SHA differs, although its short SHA matches; this review follows the explicit instruction to review this checkout's HEAD. The initial tracked tree was clean. Only the requested review artifacts were written. No implementation fixes, Minecraft launches, native/live runner stages, or GL interop checks were performed.

CONFIRMED/REFUTED judge the stated closure or proof claim, not the feasibility of native integration. **B2 is closed. B1, B3, B4 and B5 remain blocking**, with narrower defects than in round 2. The dormant normal-play paths remain acceptable from source inspection. Native LoD integration remains `BLOCKED_UNIMPLEMENTED`.

## 1. Round-1 and round-2 blocking findings

### B1 — sufficient, replayable committed evidence: REFUTED; open in both rounds

The new evidence is substantially better. **CONFIRMED:** independently hashing every `MANIFEST.json.files` entry in `docs/ai/runs/native-evidence/20261006T064519-555551Z/` found no mismatch. Independently comparing every `source-sha256.json` entry with the current checkout also found no mismatch. The recorded revision is the dirty parent `e06e983bcc4a9a436b202472e71aff9393f171e2`, not reviewed HEAD, but those matching source hashes establish a useful source binding. The retained log includes launch/backend selection, layer insertion, successful client completion, adopted-context release and loader teardown. Its 22 readback messages contain no reported pixel problem, and the exact diagnostic regex used at `scripts/verify.py:705` matches zero lines. This is meaningful evidence for that bounded execution, not merely hashes and success assertions.

**REFUTED:** the evidence can replay the pixel gates or independently check the authoritative readback. There is only one cropped composited PNG, `marker-crop-edit.png`; none of the eleven checkpoint PNGs or raw Minecraft-colour readbacks is retained. The crop visibly contains the magenta/cyan split and two yellow strips, but has no parent-image dimensions/origin or independently checkable link to the hashed parent PNG. It cannot establish the image content for the other checkpoints, either orientation of the raw image, or the 22 individual readbacks.

Directly calling the real functions on the retained directory, with checkpoints from `native-result.json`, returned:

| Function | Result |
| --- | --- |
| `native_environment_result(directory)` | false: missing `warmup.png` |
| `native_marker_result(directory, checkpoints)` | false: missing `warmup.png` |
| `native_proof_files_result(directory, checkpoints)` | true |

The retained `summary.json` is **not finished**: top-level `success` is false and `changed_sources_during_run` / `final_revision` are absent, while its native stage says success=true. Retention runs at `verify.py:723–725`, before the final success and source-change checks at 726–733. It copies the earlier summary and never refreshes the retained copy. This is a snapshot-ordering defect, not evidence that the actual stage failed. Retention errors also do not fail the stage (`520–555`, `723–726`). The dirty patch and original source snapshot directories are not retained, although current matching fingerprints partly compensate.

The survey still cites only ignored `build/` evidence at lines 169, 213, 282 and 323. Its round-1 passing-run link at 409 points to a directory no longer present in HEAD. The new run cannot retrospectively establish the old colour samples, failed launches or exact checkpoint counts. Closure remains unproven.

### B2 — no unflagged Minecraft-device effects: CONFIRMED; closed

The following normal production routes return before native/device work with all four flags absent:

| Route | Evidence | Judgment |
| --- | --- | --- |
| Initial adoption | `VoxyClient.java:71`; `McNativeVkContext.java:92–100` | No device lookup, Vulkan allocation or file output. |
| Tick probe | `VoxyClient.java:165–166`; `McNativeVulkanProbe.java:294–297` | Flag checked before probing or writing. |
| Level render mixin | `MixinLevelRenderer.java:47–49`; `McNativeMarkerDraw.java:212–218` | Marker returns before device/pass/readback work. |
| Level close | `MixinLevelRenderer.java:34–37`; `McNativeMarkerDraw.java:705–709` | Null marker returns before device lookup. |
| Client stop | `VoxyClient.java:171–172`; `McNativeVkContext.java:282–287`; `VkContext.java:101` | Unadopted context returns before `vkDeviceWaitIdle`. |
| Device-creation mixin | `MixinVulkanBackend.java:34–41`; `McNativeDeviceFeatures.java:76–78` | Returns the same feature-set object before native calibration. |

A literal claim of no native-package execution or no heap allocation is **REFUTED**: adoption updates `attempted` and creates a small Java list/status; feature augmentation sets `attempted`; classes/callbacks initialize bounded Java bookkeeping. No unflagged Minecraft-device query, GPU allocation, stall, mutation or file write was found. This conclusion excludes explicit calls to public diagnostic APIs and the separately enabled harness.

### B3 — every required proof and identity is checked: REFUTED; open in both rounds

**CONFIRMED:** all four original proof files plus marker/probe files must exist; missing typed fields used by `need` fail; explicit compute/shader failure, adopted=false, failed adoption proof, insufficient features and nonzero mismatch counts fail. Main actually conjuncts this verdict into native-stage success (`verify.py:697–703`). Null adopted identity, mismatching marker/probe identity and multiple checkpoint device handles now fail. The hex/decimal comparison is correct for ordinary handles.

**REFUTED:** the identity closure covers all proof producers. `McNativeComputeProbe.Result/json` contains no device/instance/run identity (`81–82`, `313–325`); `McNativeRealShaderProbe.Result/json` also contains none (`60–61`, `245–256`). The gate compares only adopted, marker, native-vulkan-probe and checkpoint handles (`verify.py:490–513`). Compute/shader evidence from another device or run therefore remains interchangeable. Adding a contradictory identity to either report is ignored. A device handle alone also does not establish a run identity across different processes.

Independent mutations of `ProofFileGateTest`'s otherwise passing fixture produced:

| Mutation | Actual gate success |
| --- | --- |
| compute `device="0xdead"` | true |
| real shader `device="0xdead"` | true |
| compute `attempted=false` | true |
| feature `attempted=false` | true |
| feature notes `["FAILED: injection did not execute"]` | true |
| adopted `readBack="0xdead"` | true |
| shader `firstMismatch="GPU != CPU"` | true |
| compute `expected=""`, `readBack=""` | true |

These are actual calls to `native_proof_files_result`, not a reimplementation. See reproduction below. `need` does not repair fields the code never asks for: attempted status, adopted readback, shader firstMismatch, feature failure notes and compute/shader identities remain unchecked. `expected` and `readBack` are compared only as strings; they are not validated against the probe's sentinel. A benign calibration note must not itself be rejected, but explicit failure needs a checked representation. Proof existence and partial identity agreement have improved; whole-proof consistency has not closed.

### B4 — authoritative marker/depth/readback proof: REFUTED; open in both rounds

#### Position and density: improved, still not authoritative

**CONFIRMED:** `relationshipProblem` was removed. Its replacement `measure/rect/count` measures specific rectangles and requires 80% near/far/cell/control occupancy, explicitly counting yellow in the box (`McNativeMarkerDraw.java:391–466`). The old distant-patches attack no longer satisfies those rectangles. I exercised the actual private Java methods by reflection on `build/classes/java/main`, with the cached dependencies, without a GPU or Minecraft process.

**REFUTED:** a passing classification proves that the current draw happened. A 1708×960 ByteBuffer filled only in the expected rectangles with `(200,60,200)` near, `(60,200,200)` far and `(200,200,60)` in cell/control returned:

    Readback[attempted=true, completed=true, near=9792, far=6528,
      rejectedInBox=0, control=3230, boxArea=16320,
      controlArea=3230, note=null]

No draw generated that buffer. The predicates accept these noticeably non-marker colours (`464–466`), and a stale marker image or unrelated matching scene content is indistinguishable. Position/density reduces accidental matches; there is no changing challenge, before/after comparison or frame/submission/image identity. The Python gate does not independently recheck the new density invariant: it accepts near=500/far=500/control=500 with note=null and timesClean=3 even when areas, rejectedInBox and timesWithAProblem are missing. It also accepts those counts with areas of a billion pixels (`verify.py:272–298`). Geometry comes from the same untrusted report, and the new pass-cell geometry is not checked by the screenshot gate.

#### Orientation selection can hide a depth failure

Using the same real Java methods, I retained a valid synthetic pattern in the unflipped rectangles and filled the **mirrored** box yellow, with yellow cell/control there. The unflipped `measure` returned note=null/rejectedInBox=0; the mirrored `measure` found rejectedInBox=16320 and failed. `classifyReadback` takes the first passing orientation and breaks (`359–364`), so it trusts the unrelated top pattern and ignores the actual bottom depth failure. It does not report which orientation was chosen, require consistency between samples, or fail on ambiguity.

The supporting gate also accepts eleven top-pattern frames with a yellow box at the bottom: I added yellow at x=9..104, y=HEIGHT−65..HEIGHT−6 to every `test_marker_gate.frame()` and obtained success=true. It only scans the reported top geometry. This supplies a concrete depth-failure counterexample for the new mechanisms. The stricter rejection of yellow **in the chosen screenshot box**, even with GUI/no-draw metadata, is nevertheless **CONFIRMED** at `verify.py:374–378`.

A correct draw can fail too: the Python gate's fixed 500-pixel floor rejects a valid small target even with 100% rectangle occupancy. Scene yellow/post-processing in the composited top box can fail a correct raw-image draw. At a 1×1 raw extent all rectangles collapse to zero area and Java returns note=null on an empty image; the Python floor prevents that particular degenerate report from passing. These are limits of the current classifier, not observed failures in the retained high-resolution run.

#### Pass cell does not prove the separate rejected-box command

**CONFIRMED:** the cyan base covers the cell at z=.6, the yellow pass-cell draw uses the LESS pipeline at z=.3, and control is spatially separate with ALWAYS (`record`, lines 490–509). With these exact rectangles, the cyan base and yellow control strip cannot themselves fill the yellow cell. A working rasterization of these commands makes cell yellow good evidence for that **cell draw**, and resets prior scene depth there.

**REFUTED:** that proves the yellow **box** draw was issued. The two outcomes use separate `quad`/`vkCmdDraw` calls: the rejected box at 499–500 and the pass cell at 504–505. Omitting only the former leaves exactly the same final colour and depth image. Every pixel classifier and gate therefore passes unchanged. The cell could also be painted by a different yellow operation; raw pixels contain no pipeline identity. The survey's statement that this is now measurement rather than inference (`438–444`) is false. The test also cannot prove near-quad depth writes: a .9 draw is rejected by the .6 base whether the .3 near draw writes depth or not.

#### Repeated readbacks: arithmetic fixed; lifecycle/final-result gaps remain

**CONFIRMED:** nextReadbackAt starts at 2, compares directly, and advances by 240 (`175–178`, `278–280`). For this bounded run it schedules 2, 242, 482, … correctly, without the Long.MIN_VALUE subtraction overflow. The retained log contains **22** clean readback messages.

**REFUTED:** three clean aggregate samples and zero recorded problems establish correctness throughout the run. Sampling at 240 rendered-frame intervals can miss failures between samples; three samples can all precede a later broken lifecycle stage. Counts survive world close/recreation and have no checkpoint, image, device, submission or completion identity. There is no pending-readback cap or final drain/evidence handshake.

There is a sharper end-of-run hole: marker JSON is written at frame 1 and each multiple of 600 (`780–785`), not on callback completion or harness finish. The committed marker report records frame **4800, 20 clean**; the final reconnect checkpoint records **5161**, and the log shows **22** readbacks. A failure at scheduled readback **5042**, followed by termination before 5400, leaves the clean 4800 JSON gateable. Classification failure prints `PROBLEM:` at INFO; the runner's diagnostic and application-error filters (`705–718`) do not reject that text. A request/classification exception sets a failed latest report and notes but does not increment readbackProblems (`331–333`, `376–378`); if no further 600-frame snapshot occurs, that too is lost. Even after a snapshot, missing problem-counter fields default to no problem in Python. The retained run's log is clean, but the mechanism can pass a late broken run.

#### Harness metadata is not bound to the captured frame

**REFUTED:** `markerDraws` says this particular image received a marker. It is a cumulative recorded-frame count sampled in the tick checkpoint, not a submitted/completed draw ID (`LiveWorldHarness.java:256–261`); several hundred earlier draws can advance it while the screenshot's frame has no marker. `frameCoveredByGui` samples screen/overlay presence before requesting the screenshot (`262–270`), not whether that screen covered the marker or what GUI state was rendered into the captured image. A screen may be translucent or leave the corner visible. The screenshot callback is asynchronous and no frame token connects it to these values. The gate trusts either reason to skip partial evidence, bounded to three checkpoints (`verify.py:379–403`). Yellow in its selected box is now rejected before any skip, but other partial draw failures can still be excused by inaccurate metadata. The retained checkpoints all say GUI=false while nether is nevertheless excused by zero matching colours, illustrating that the gate still infers an additional exemption from pixels.

### B5 — destruction only after confirmed completion: REFUTED as universal closure; open in both rounds

**CONFIRMED repairs:** compute/adoption fence timeouts retain objects (`McNativeComputeProbe.java:231–237`; `McNativeVkContext.java:247–253`). Real-shader cleanup now refuses destructive resource cleanup without a successful frame drain (`McNativeRealShaderProbe.java:203–228`). Frame-tracker destroy checks idle success before freeing its fence/command buffer (`VkFrameTracker.java:227–240`). Missing-current-device marker shutdown and retirement onto another device now deliberately abandon the old pipelines (`McNativeMarkerDraw.java:661–679`, `705–718`). Their previous immediate-free paths are gone.

**REFUTED:** every destructive path is tied to the owning device's observed completion. Adoption is session-once (`McNativeVkContext.java:92–95`, `VkContext.java:95`), while the marker explicitly follows a replaced Minecraft device (`MarkerDraw.java:245–256`). After adoption of device A, replacement by B, and creation/submission of a marker on B, `releaseAdopted` waits only **A** (`McNativeVkContext.java:293`), then calls `shutdownImmediate` (`308`), which destroys the current marker on **B** without any identity check/wait (`MarkerDraw.java:726–729`, `683–697`). Waiting A says nothing about B's submission. The new device-replacement retirement branch does not cover this shutdown branch. This is a source-level reachable scenario; I did not inject device replacement on hardware.

There is also a surviving unsafe public helper: `VkFrameTracker.waitIdle()` ignores the wait result, marks all work completed, and drains arbitrary pending frees (`220–224`). No production caller was found by `rg -n 'waitIdle\(' src/main/java`, so this is a latent API defect rather than evidence of a currently invoked native path. Direct `VkContext.shutdown()` also destroys its command pool without checking completion (`VkContext.java:103–106`, `692–700`); the ordinary adopted-stop caller currently performs a prior checked wait. The specific repaired callers should not be described as proof of safety “anywhere.”

## 2. New-code risks and smaller repair claims

| Claim / risk | Judgment and evidence |
| --- | --- |
| Readback leaks on every ordinary error | **REFUTED as that blanket claim.** Allocation/registration failure attempts close in finally (`MarkerDraw:322–338`); mapping/classification failure attempts close at `379–380`. Successful callback cleanup also closes. No evidence of a leak on every successful sample. |
| Every leak is bounded and reported | **REFUTED.** Both buffer-close exceptions are silently swallowed (`337`, `380`). Actual MC `VulkanGpuBuffer.Direct.close`, independently disassembled, sets closed=true before queueing destruction; queue failure leaves no retryable handle. The marker keeps requesting another buffer every 240 frames, with no failure latch or total-byte limit. Up to 40 MiB can be abandoned each attempt. Callback non-delivery likewise has no local pending-buffer accounting. Minecraft's normal bounded submissions constrain the healthy case, but the diagnostic does not prove bounded failure handling. Marker retirement refusal/replaced-device/world-close leaks can also recur across arbitrary format changes/world cycles, allocating replacements; NOTES stores at most 32 deduplicated messages and logs only the first warning (`735–742`), not cumulative objects/bytes. Compute/adoption/real-shader `runOnce` leaks are session-bounded; the marker/readback leaks are not. |
| Copy error must immediately destroy an in-flight buffer | **REFUTED for this candidate's MC implementation.** `javap -c -p` of VulkanCommandEncoder shows the copy command recorded before callback is queued; `VulkanGpuBuffer.Direct.close` queues destruction through the same device encoder. The error finally calls deferred close, not immediate vmaDestroyBuffer. Callback classification is also queued through destruction retirement. This is useful safety evidence; a queue-refusal leak remains. |
| Copy occurs inside an unsafe open render pass | **No such current path found; CONFIRMED placement.** Hook is level-render TAIL (`MixinLevelRenderer:47`); its own pass closes before request (`MarkerDraw:261–280`). MC's copy implementation records vkCmdCopyImageToBuffer in GENERAL and a memory barrier, then queues the callback. There is no explicit reentrant/target-replacement guard or per-request image/device token. Future hook changes, concurrent state transitions and callback loss were not hardware-tested. |
| Whole-image size no longer matters | **REFUTED.** Per-buffer budget is now correctly 40 MiB, so 8K is refused instead of allocating ~126.6 MiB (`310–320`); 3840×2160 still copies/maps ~31.6 MiB per sample, and the copy covers the whole image. Counts inspect small rectangles, but transfer/allocation are full-size. There is no aggregate outstanding-byte budget. |
| Classification cannot count unrelated colours | **REFUTED**, with the real Java synthetic pattern/mirror examples in B4. 80% occupancy is stronger than spans; the 200/60 thresholds still accept unrelated colours and have no draw provenance. |
| N1 strict reflection/shared helpers | **CONFIRMED** for ambiguity/statics: `McNativeVulkan.fieldOfType:154–169`; Compute/Feature/DeviceFeatures/Probe now share it. Command-buffer field cache is keyed by backend class (`126–141`). |
| N2 exact physical-device selection | **Partly CONFIRMED**, first-device fallback removed (`ComputeProbe:274–296`, `FeatureAudit:190–214`). **REFUTED as exact identity**: these and adoption return the first matching device name; two equal-model GPUs are ambiguous and not tied to a physical handle/UUID. No such host was exercised. |
| N4 adopted Metal-object capability | **CONFIRMED**: enabled-device-extension set controls hasMetalObjects (`VkContext:458–459`, `McNativeVkContext:343–354`); retained extensions lack VK_EXT_metal_objects. No new GL/IOSurface prerequisite was found. |
| N5 partial PNG bounds | **CONFIRMED** for CRC/IHDR/IEND, dimensions and requested-row bounds (`pixel_oracle.py:107–148`). Compressed input is still read in full without a file-byte budget, and a partial decode cannot certify the entire zlib stream. Native environment's full decode supplies the latter check on the stage route. |
| FeatureAudit describes actual enabled features | **REFUTED**: it reads static unaugmented REQUIRED_DEVICE_FEATURES (`FeatureAudit:155–165`). Retained audit reports the four injected features disabled despite the injection/compute success reports. It is a baseline-request audit, not a device-enabled-feature oracle. |

The repeated diagnostic leaks and silent close failures are new-risk findings, scoped to flags-on diagnostics. They do not overturn dormant-play B2.

## 3. Requested tests and whether regressions catch old behaviour

Executed exactly:

    ./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true
    python3 -m unittest discover -s scripts/tests

**CONFIRMED results:** Gradle BUILD SUCCESSFUL; fresh JUnit XML totals **313 tests, 0 failures, 0 errors, 1 skip**. Test execution ran; Java compilation was FROM-CACHE. The sole skip is `VkBarriersTest.missingBarrierIsDetected()`. The standalone device log identifies Apple M4 Pro, API 1.4.357, validation=true and syncValidation=true. The loader emitted its deprecated validation-settings warning. Python: **54 tests passed in 20.727 seconds**. These tests do not launch Minecraft or exercise adopted MC devices.

I loaded historical `scripts/verify.py` with `git show`, executed it as a temporary module, and rebound the current tests' imported gate functions to those real historical functions:

- Against round-1 behaviour at `5160f000`: 20 marker methods ran, 17 assertion failures, no errors. These counts include wording-only failures, not 17 old false acceptances.
- Against round-2 behaviour at `7a769ad5`: 32 marker/proof methods ran, 12 failure entries including subtests, no errors. Some failures likewise compare changed wording.
- **CONFIRMED meaningful new regressions:** `test_a_single_readback_is_not_enough`, `test_any_readback_problem_during_the_run_is_rejected`, `test_a_yellow_box_is_never_excused`, null adopted identity, marker/probe identity mismatch, missing mismatches and two-checkpoint-device cases expose actual old acceptances.
- **REFUTED original lone-far reproduction:** `frame(lone_far_pixel=True)` puts cyan at `(104,5)`, outside the old inset. The old gate already rejects it. Moving one cyan pixel to `(80,10)` makes the old gate pass. `test_one_far_pixel_instead_of_the_split_is_rejected` fails against the old gate only on expected error wording (`test_marker_gate.py:64–68`, `106–109`).
- **REFUTED three-dark-frame regression:** the current test expressly permits three black boxes against bright backgrounds; independently both old and new gates return true. Their fourth-frame rejection differs only in message. The module's opening claim “each must now fail” (`1–7`) is inaccurate.
- `test_a_partially_drawn_frame_is_still_a_failure` and `test_the_rejected_colour_inside_the_box_is_rejected` reject their fixtures under old behaviour too; historical assertion failure does not establish a regression.
- **Tests that pass against broken/old code:** `test_a_correct_frame_passes`, `test_a_gui_covered_frame_is_excused_with_the_implementations_reason`, and `test_the_readback_is_what_carries_the_proof_not_the_screenshots` pass against the round-1 gate. The last rejects eleven empty images even though that gate has no readback requirement. `test_notes_on_any_proof_fail` changes only compute notes, so it passes with broken feature/shader note handling. `GEOMETRY` and `frame()` omit the new depth-tested pass cell (`25–26`), so all screenshot tests can pass if the real cell draw is removed. Python tests fabricate the readback counters/notes and never invoke Java measure, classify, callback scheduling or final persistence. A classifier returning clean for every buffer can therefore leave them green.
- **Java scope CONFIRMED, broader claims REFUTED:** `McNativeMarkerDrawTest.theMarkerShadersCompileToSpirv` checks compilability, size and magic; wrong positions/colours/depth still compile. Its colour test only checks constants. Its unset-flag test meaningfully checks the marker guard but cannot certify stop-path waits, reads or lifetime. `McNativeVulkanProbeTest` directly probes without a device; it can pass with a broken probeOnce flag guard. `McNativeDeviceFeaturesTest.eachAddedFeatureSetsOnlyTheFieldItNames` independently checks named LWJGL fields and meaningfully catches the historical offset error, but does not apply the Minecraft mixin. `McNativeRealShaderProbeTest` executes a real GPU/CPU ordinal comparison on Voxy's own core-capable device; it passes with unsafe failure cleanup, a broken adopted identity or a broken KHR-only branch. There are no Java tests of the new positional classifier, mirror selection, rearm, copy failure, late evidence or unconfirmed-wait cleanup.

### Small reproducible gate attacks

Run from the checkout (imports functions only; does not invoke the runner):

```python
import sys
sys.path[:0] = ['scripts', 'scripts/tests']
from test_marker_gate import ProofFileGateTest, MarkerGateTest, report, frame, STAGES
p = ProofFileGateTest()
for patch in [
    {'native-compute-probe.json': {'device': '0xdead'}},
    {'native-real-shader.json': {'device': '0xdead'}},
    {'native-adopted-context.json': {'readBack': '0xdead'}},
    {'native-compute-probe.json': {'expected': '', 'readBack': ''}},
]:
    assert p.run_gate(patch)['success']
bad = report()
bad['readback'] = dict(attempted=True, completed=True, near=500, far=500,
                       control=500, note=None, timesClean=3)
assert MarkerGateTest().run_gate({s: frame() for s in STAGES}, bad)['success']
```

The Java attacks called private `rect(float,float,float,float,int,int,boolean)` and `measure(ByteBuffer,int,int,boolean)` via reflection. For each Rect accessor x0/y0/x1/y1, fill its half-open pixel range at RGBA offset `(y*width+x)*4`. Use the constants in `MarkerDraw.java:136–162` and the colours listed in B4. For the mirror attack, add a yellow mirrored box to that same buffer. Actual outcomes are recorded in B4; no independent reimplementation of measure was substituted.

## 4. Survey measured claims and whether committed evidence is sufficient

This table covers each distinct measured claim, grouping parallel values. Line references refer to `docs/ai/vulkan-native-integration-survey.md`. New retained evidence may support current execution without proving an older specifically dated run.

| Survey claim | Judgment / committed support |
| --- | --- |
| Preference produced OpenGL and options were rewritten (124–129) | **REFUTED as proven:** no failed-preference run/options/log retained. New native stage explicitly forces Vulkan and does not test that case. |
| Main accepts graphicsBackend/vulkanValidation; forced Vulkan log (130–135) | **CONFIRMED:** actual cached MC 26.2 `javap -c -p net.minecraft.client.main.Main`; retained native.log has the forced-backend and Vulkan-driver messages. |
| VulkanDevice/M4 Pro/MoltenVK, handles, queue families 0/3/3, RGBA8/D32 targets, usages and no notes (137–146) | **CONFIRMED for the new source-matching run:** native probe/checkpoints and raw stage log support these values, including resize. The original Oct-4 run is not independently retained. |
| No native Voxy LoD; GL-free target access (148–151) | **CONFIRMED source/log scope:** Voxy disables its production renderer; marker/probes use MC Vulkan handles and no GL composition. |
| Vulkan-first/OpenGL fallback and loader/GLFW availability tests (153–156) | **CONFIRMED API-level:** javap of `net.minecraft.client.PreferredGraphicsApi.getBackendsToTry` and `VulkanBackend.checkBackendAvailable`. Not a new fallback runtime experiment. |
| One magenta draw, no push constants, depth deliberately absent (160–165, 201–203) | **REFUTED as current description:** record issues six quads with 36-byte push constants and attaches depth. MC pass/device route and flag guard are source-CONFIRMED. |
| Specific Oct-4 pipeline/run, 4800 draws, all gates green, unchanged sources, zero diagnostics (169–183) | **REFUTED as that run's proof:** build-only run absent. New stage reports success, marker snapshot 4800, last checkpoint 5161, and a clean completed log; source fingerprints match current code. Final retained summary/change check and replayable pixels remain absent. Pipeline “live” is a Java state assertion, not a whole-lifecycle independent lifetime trace. |
| Ten full boxes/exact 15088/19136 counts (178–180) | **REFUTED as independently proven:** eleven originals absent; one crop cannot check every count. |
| y≈854 flip; composition values 239/242/253; black nether implies hook never ran (187–199) | **REFUTED as measured proof:** relevant originals absent; GUI composition can hide a rendered level. Round-1 repairs contradict the hook inference. Current nether has markerDraws=4245 and GUI=false and is still skipped. |
| All eight features physically supported; baseline four absent; extensions; zero-one depth (210–230) | **CONFIRMED as current retained audit values / baseline requests. REFUTED as actual enabled-device audit:** static REQUIRED_DEVICE_FEATURES does not reflect augmentation. Named feature tests/source calibration are checkable; the old specifically cited run is absent. |
| Offset bug caused FEATURE_NOT_PRESENT, then experimental calibration fixed it (237–247) | **CONFIRMED current calibration** by independent named-field Java tests and retained log. **REFUTED old startup incident as retained evidence:** failed log absent. |
| int64 compute .0123456789abcdef on MC compute family 3 and proves features enabled, same clean lifecycle (249–259) | **CONFIRMED bounded int64 success as reported/logged in new source-matching run. REFUTED broader complete-feature proof:** compute storage writes do not exercise fragmentStoresAndAtomics, vertexPipelineStoresAndAtomics or drawIndirectFirstInstance. All four actual shader-stage requirements are not tested; the compute proof also has no own device identity. Full pixel replay is missing. |
| VkContext adoption, re-queried state, borrowed device/instance not destroyed (265–273) | **CONFIRMED source and successful log path. REFUTED universal “behaves the same” safety inference:** physical identity ambiguity and B5 remain; failure/lifecycle paths were not hardware-exercised. |
| Family 0 has graphics+compute; therefore graphics families must support compute (275–279) | **CONFIRMED host choice and explicit validation** (`VkContext:437–451`). **REFUTED universal inference:** the constructor must independently check COMPUTE; a graphics bit alone is not the required evidence. Do not generalize this host result. |
| Voxy VkBuffer/compiler/pool sentinel proof (281–286) | **CONFIRMED as a bounded source/log result of new run**, with adopted/probe/checkpoint agreement. **REFUTED the cited older run and independent raw-buffer replay:** those artifacts are not retained. |
| Depth test AND depth write, 60/40 counts and rejected=0 at every checkpoint (288–299) | **REFUTED as complete proof:** missing rejected-box command gives same pixels, near write is not discriminated, selected orientation can conceal failure, and original checkpoint pixels/counts are absent. Current crop and counts support a pattern only. |
| Correct shutdown, old validation complaints, extra-negation failure and exact earlier bottom counts (301–311) | **CONFIRMED new successful release/teardown log. REFUTED universal closure and original incidents/exact measurements:** B5 persists; old failed logs/images absent. |
| Production shader/import/binding/frame/barrier stack, 189 ordinal CPU matches, one green lifecycle (313–327) | **CONFIRMED source, new reported/logged 189 and independent standalone GPU test. REFUTED exact old run/complete native acceptance:** no old artifacts, no compute/shader identity, missing pixel replay. It is index resolution, not terrain rendering. |
| Core/KHR fix, 22 migrated call sites and 313 suite unaffected (331–342) | **CONFIRMED dispatch by command-buffer capability** in `VkCmd`; current 313-test suite passes. KHR-only behaviour on MC is supported by source-matching log/probe success, not exercised by the standalone core-device suite. The original NPE/failure log is absent. |
| Shutdown cache order and ordinary standalone probe seam (344–359) | **CONFIRMED source/seam/successful shutdown log; REFUTED all-path lifetime guarantee** by B5. The historical complaints/NPE diagnoses are not retained. |
| Round-1 repairs: authoritative relationships/control, all proofs, inert flags, timeouts, retained run, tests cannot drift (368–421) | **CONFIRMED specific guards/fence changes and named-field/PNG repairs. REFUTED complete proof/evidence/test claims:** B1/B3/B4/B5 and section 3; the cited round-1 run is no longer in HEAD. |
| Round-2 repairs: density, cell makes box issuance provable, every-240 readback, identity, safe leaks, replayable finished evidence (429–471) | **CONFIRMED density/rearm source and partial guard repairs. REFUTED universal conclusions:** B1/B3/B4/B5 and section 2. Log contains 22 clean samples, while the gated JSON records 20. |
| Initial MIN_VALUE rearm bug was caught by gate (473–475) | **CONFIRMED corrected arithmetic and gate rejection of absent readback by tests/source; REFUTED historical failed-run proof:** failed snapshot/log was not retained. |

The survey also starts with “Nothing here is implemented” and ends with a proposed first step already implemented diagnostically. Those stale scope statements obscure the distinction between API investigation, implemented diagnostics and unimplemented native terrain. Keeping the unresolved hardware questions explicit is appropriate; none of this review certifies the native LoD goal.

## 5. Safety for normal play

**GL backend, no native flags: CONFIRMED safe to ship with respect to these additions, from source review and permitted tests.** The native callbacks are dormant and the established GL path is preserved. No new GL-player device mutation, native stall or native file output was found. This is not a new end-to-end GL runtime certification.

**Minecraft Vulkan backend, no native flags: CONFIRMED dormant safety on the same basis.** Device-creation mixin returns the original features; client-stop returns before device wait; marker/probe allocate no GPU resources. Production Voxy LoD still disables itself, so this does not mean Voxy works natively or satisfies project-goal.md. I would not ship the flags-on diagnostic as an accepted native integration: proof defects, cross-device shutdown destruction and unbounded failure leaks remain.

## 6. What could not be checked and why

- No new Minecraft/native/live run, renderer switch, GUI/capture synchronization, raw-depth test or fault injection was performed: the user prohibited native/live launches and asked to judge committed evidence.
- Full environment/marker replay, authoritative raw-readback verification and whole-lifecycle visual/depth claims cannot be checked: checkpoint PNGs/raw buffers are absent. One composited crop and reported aggregates are insufficient.
- Final run success/source stability cannot be certified from the retained summary: retention preceded finalization. Current fingerprints match all retained source entries, which narrows but does not remove this gap.
- GPU timeout/device loss, replaced-device shutdown, refusal of destruction queueing, delayed callbacks and equal-name multi-GPU selection were traced in source/disassembly, not exercised on hardware. B5's counterexample is a device-ownership control-flow proof, not a claimed observed crash.
- Historical build-only runs, removed round-1 evidence, failed launches/orientation experiments and their exact pixels cannot be reconstructed from prose.
- Native terrain, Sodium-pass interplay, normal-play updates/pressure/lifetime, scene-depth correctness and feature-stage coverage remain unaccepted. Successful standalone tests and bounded index/marker probes do not prove them.
