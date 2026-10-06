VERDICT: REDESIGN

Independent round-4 review, 2026-10-06. Reviewed `/Users/xyz/orca/workspaces/voxy/native-review-r4` at **132be67d94a8aa27a366061946169ba0b0984359**, obtained with `git rev-parse HEAD`. The prompt's full SHA differs; its short SHA matches. This review follows the instruction to review this checkout's HEAD. The initial tracked tree was clean. Only the two requested review artifacts were written. No implementation fixes, Minecraft launches, native/live stages, or interop runs were performed.

CONFIRMED/REFUTED below judge the stated repair/proof claim. **B2 and the invoked diagnostic B5 destruction defects are closed. B1, B3 and B4 remain blockers**, with important narrower repairs confirmed. The diagnostic layer is **not yet sound enough to use as the accepted foundation for terrain work**. The normal, flags-unset paths remain acceptable from source inspection and the permitted tests. Native Voxy terrain remains `BLOCKED_UNIMPLEMENTED`.

## 1. Blocking findings and round-3 repair disposition

| Finding | Round-4 judgment | What closed / what remains |
| --- | --- | --- |
| B1 sufficient retained evidence | **REFUTED; open** | Finished summary and per-checkpoint supporting pixels repaired. Authoritative raw colour-image samples are still absent; original environment/marker gates cannot be replayed directly. Retention failure still cannot fail the stage. |
| B2 flags-unset inertness | **CONFIRMED; closed** | No Minecraft-device query, native allocation, stall or file write found on the unflagged production call graph, including client stop and mixins. Bounded Java bookkeeping does execute. |
| B3 complete proof gating | **REFUTED; open** | Five logical-device identities now agree and absent/mismatching identities fail. Feature failure signals, adopted readback consistency and some required fields remain unchecked. |
| B4 authoritative marker/depth proof | **REFUTED; open** | Single spanning command closes the separate-box-command omission. Exceptions can still leave a stale clean proof; orientation selection and count gating admit counterexamples. Samples are not bound to lifecycle checkpoints. |
| B5 destruction after unconfirmed completion | **CONFIRMED for current diagnostic callers; closed** | Shutdown compares the waited device's handle to the marker owner's handle. Actual probe/retirement callers either confirm completion on their owner or abandon resources. Unsafe unused public helpers remain a non-blocking API risk, not proof of a currently invoked diagnostic destruction bug. |

### B1 — evidence retention and replayability

**CONFIRMED:** every manifest file hash and every retained source hash matches. I independently compared `MANIFEST.json.files` against file contents and `source-sha256.json` against this checkout. There were zero mismatches. The recorded revision is the dirty parent `fc7e44c85ebb6ae7c86e022e46d908751dd4cec9`; matching source hashes bind the measured source/build/script inputs to reviewed HEAD despite that parent revision. This does not reconstruct the unretained dirty patch or every dependency binary.

The retained `summary.json` now has `success:true`, `changed_sources_during_run:[]` and `final_revision`; the previous premature-summary defect is closed (`scripts/verify.py:759–766, 775–787`). The retained log contains actual Vulkan selection, Apple M4 Pro/MoltenVK identity, validation-layer insertion, sentinel computations, 189 ordinal matches, client completion and adopted-context release (`native.log:117, 374–394, 598–625`). The exact native diagnostic regex at `verify.py:740–741` matches zero lines. This is meaningful bounded-run evidence.

**CONFIRMED:** all eleven supporting screenshot judgments can be re-made from the 22 crops, with a qualification about their origin. I reconstructed crop origins from the independently inspected source constants (`McNativeMarkerDraw.java:135–174`), checkpoint dimensions and retention formula (`verify.py:599–606`), decoded the crops, and counted pixels without using the stored gate counts as an oracle. Every result matched `summary.json`:

| Checkpoints | Original extent | Near | Far | Control strip | Yellow in box |
| --- | --- | --- | --- | --- | --- |
| warmup, turn, travel, return, edit, remove | 1708×960 | 9690 | 6365 | 3230 | 0 |
| resize, reload, overworld, reconnect | 1920×1080 | 12305 | 8239 | 4032 | 0 |
| nether | 1920×1080 | 0 | 0 | 0 | 0 |

Top crop origins are `(15,7)` and `(17,8)` respectively; dimensions are 174×158 and 196×177. Visual inspection of edit shows the split box and two yellow bands; nether is dark. The summary skips nether only. Crop placement using published geometry is **not inherently circular** once that geometry is independently checked against recording code. The crop contains real pixels, not generated count assertions. Nevertheless, there is no explicit parent-size/origin metadata in each crop, and the absent original screenshot cannot be checked against its manifest hash. Reconstructed origin is a source-based assumption, not an independently retained capture fact.

**REFUTED:** these crops make the authoritative readback replayable. They are post-composition screenshot crops, not the raw Minecraft colour image copied before GUI/composition. No raw readback, per-sample ROI image, selected orientation or sample/submission identity is retained. The reader cannot independently classify the 17 logged raw samples or the 15 samples in the gated JSON. Direct calls on the committed directory return:

| Function | Actual result |
| --- | --- |
| `native_environment_result(directory)` | false: missing `warmup.png` |
| `native_marker_result(directory, checkpoints)` | false: missing `warmup.png` |
| `native_proof_files_result(directory, checkpoints)` | true |

Supporting pixel recount is therefore possible, but exact gate replay and independent authoritative-readback verification remain unavailable. `retain_native_evidence` catches errors and stores `error` (`verify.py:579–581`); the caller neither requires crops nor conjuncts retention success into stage success (`785–789`). A passing run can still retain insufficient evidence.

**CONFIRMED:** the survey no longer uses an ignored `build/` path as the sole explicit evidence link for its measured sections. Those references have been changed to repository evidence or qualified prose. **REFUTED:** every cited retained run exists. The round-1 link at survey line 409 and round-2 link at 475 point to directories absent from HEAD. Only `20261006T070311-099413Z` is retained. Later evidence cannot establish exact historical incidents, counts or colours that it did not reproduce.

### B2 — ordinary-play inertness

**CONFIRMED** on the actual production call graph with all four flags unset:

| Route | Guard before device/GPU/file work |
| --- | --- |
| Initial adoption | `VoxyClient.java:71`; `McNativeVkContext.java:92–100` |
| Tick probe and its compute/adoption/shader work | `VoxyClient.java:165–166`; `McNativeVulkanProbe.java:294–297` |
| Level-render tail mixin | `MixinLevelRenderer.java:47–49`; `McNativeMarkerDraw.java:224–225` |
| Level close | `MixinLevelRenderer.java:34–37`; `McNativeMarkerDraw.java:725–728` returns on null instance before device lookup |
| Client stop | `VoxyClient.java:171–172`; `McNativeVkContext.java:282–287` returns before idle wait |
| Vulkan device-creation mixin | `client/mixin/minecraft/vk/MixinVulkanBackend.java:34–41`; `McNativeDeviceFeatures.java:76–78` returns the original feature-set object |

The literal claim that no native-package method executes or no Java allocation occurs is **REFUTED**: adoption updates an attempted/status record and constructs a small list; feature augmentation sets `attempted`; registered callbacks and static bookkeeping exist. None touches Minecraft's device or files when the flags are unset. This conclusion excludes deliberate direct calls to public diagnostic APIs and the development-only harness.

### B3 — five-way identity agreement repaired; whole-proof validation still incomplete

**CONFIRMED:** six files are required (`verify.py:430–460`); explicit compute/shader failure and mismatch counts, failed adoption and incomplete feature lists fail. Main conjuncts all three gate results (`732–738`). Adopted, marker, Vulkan probe, compute and real-shader handles are now required, parsed numerically, and compared with the single checkpoint handle (`488–489, 505–535`). Changing either compute or shader `device` to `"0xdead"` now fails. Missing/null identities cannot bypass these checks. `McNativeComputeProbe.java:105–107` names its actual execution device; `McNativeRealShaderProbe.java:118–124, 255–262` names the current context's device.

The comparison establishes one **logical handle in the assumed process/run**, not globally unique device provenance. It contains no run token, device generation, instance/physical-device UUID or producer/sample identity. Separate processes can reuse the same numeric address. The fresh isolated runner directory reduces ordinary mixing risk, but numeric agreement alone does not establish that arbitrary supplied files came from one run. All-zero identities also pass this helper if checkpoints are zero; the separate environment gate rejects zero, so that is not a whole-stage false pass.

**REFUTED:** any failure or missing semantic field in the four original proof files fails. Actual mutations of `ProofFileGateTest`'s passing fixture produced:

| Mutation | Current proof gate success |
| --- | --- |
| compute or real shader `device="0xdead"` | false |
| feature `attempted=false` | **true** |
| feature `notes=["FAILED injection"]` | **true** |
| adopted `enabled=false, attempted=false` | **true** |
| adopted `readBack="0xdead"` | **true** |
| compute `expected="", readBack=""` | **true** |
| remove shader `firstMismatch` | **true** |

Features are only checked for `enabled` and `added` (`465–468`); their attempt/failure representation is ignored. Benign calibration notes must remain permitted, but an explicit failure must have a checked representation. Feature `ADDED` is accumulated and never reset (`McNativeDeviceFeatures.java:95–109, 170–172`), so an earlier successful augmentation can coexist with a later failed one. Adoption checks the success assertion, not the published readback or enabled/attempted fields (`481–489`). Compute compares arbitrary strings without validating the sentinel (`475–477`). Shader `firstMismatch` defaults to null when absent (`494`). These are schema/consistency failures, not an allegation that the retained successful run emitted contradictory results. The requested universal fail-closed proof claim remains false; B3 has moved to unchecked fields.

### B4 — attack of the new marker and readback mechanisms

#### Single rejected-colour quad: specific omission repair CONFIRMED

`record` writes cyan over the box at .6 and the cell at .95 using ALWAYS (`511–515`), then uses LESS for the near magenta quad at .3 and **one yellow quad spanning box through cell at .9** (`517–525`). Control uses a separate ALWAYS quad (`527–529`). The shader writes its push-constant colour uniformly (`103–114`); viewport/scissor cover the full target, culling/blending/stencil are disabled, and depth range is 0..1 (`493–501, 611–627`).

Under these exact commands, omitting the spanning quad leaves the cell cyan: the base resets previous scene colour there. Neither the cyan base nor the spatially separate yellow control strip can fill that cell. The old separate-box-command omission therefore no longer works. With equal base depths, .6 in both regions leaves the cell unfilled; .95 in both puts yellow in the box's far section. Disabling depth testing paints yellow in the box. These fail the positional classifier. The cell portion uses the same command, pipeline, push colour and depth as the box; its different result follows from the regional depth buffer, subject to geometry/rasterization being as inspected.

**REFUTED as an unconditional pixel-provenance claim:** a colour image cannot identify which command produced it. Another operation or pre-existing image can paint the expected pattern. The deterministic cyan reset makes a stale yellow cell impossible when the inspected base commands execute correctly; it does not prove that reset from a pixel-only report. Wrong base depths that preserve `.3 < box_base < .9 < cell_base` produce the same image, so the image does not measure the exact depths. More concretely, disabling depth writes on the LESS pipeline leaves all final colours unchanged: .9 is still rejected by the .6 box base, and passes the .95 cell base. Near-depth writing is not distinguished. The survey's general depth-test-and-depth-write proof exceeds what this colour oracle establishes (`survey:288–299`). No authoritative depth readback is retained.

#### Position/density and orientation: improvements CONFIRMED; soundness REFUTED

The named `relationshipProblem` no longer exists. Its replacement `measure/rect/count` checks expected rectangles and 80% occupancy (`MarkerDraw:412–487`), closing the old distant-patch/bounding-box attack. I invoked the **actual compiled private Java methods by reflection**, using cached dependencies, without Minecraft or a GPU. Results at 1920×1080:

| Synthetic raw image | Actual `measure` result |
| --- | --- |
| Expected top pattern, no draw issued at all | near=12420, far=8316, cell=4032, yellow-box=0, `note=null` |
| Same, missing pass cell | cell=0, note says depth-tested cell missing |
| Same pattern in threshold colours (200,0,200), (0,200,200), (200,200,0) | identical counts, `note=null` |
| Clean top pattern plus fully yellow bottom box | unflipped: `note=null`; flipped: yellow-box=20736, failure |

The first and third demonstrate that sufficient unrelated colour coverage in the expected regions can pass without draw provenance. They are controlled patterns, not a claim that an ordinary Minecraft scene will often contain them. The last is the sharper orientation attack: if the actual marker is at the bottom and depth fails there, a coincidental top pattern causes `classifyReadback` to stop at the first clean orientation (`372–375`), ignoring the failure in the actual orientation. Both candidate orientations must not be treated as interchangeable evidence of the current draw. Trying both as a discovery aid is reasonable; selecting any clean match without binding the real draw orientation is not a sound failure oracle.

A real correctly issued draw can also fail this classifier: excessive colour transformation, boundary rounding at small extents, unexpected format/channel ordering, or later overlay pixels in supporting screenshots can cross its thresholds. The retained raw target is RGBA8, full-sized and clean according to the log; these other cases were not hardware-exercised. Tiny zero-area rectangles can make Java's density checks vacuous, but the Python 500-pixel minima reject that case; it is not a demonstrated whole-stage false pass.

#### Python can accept a readback report proving nothing

`native_marker_result` requires typed counts, completion, no note, three clean samples and no problem count (`273–307`). It **does not enforce density using the published areas**, require nonnegative/plausible areas, require the pass-cell geometry, validate `firstProblem`, or independently recompute raw samples. Its `control` field is now the **cell** count from Java (`MarkerDraw:442–443`), while screenshots count the separate control strip. A fixture with near=500, far=500, control=500, boxArea=999999999, controlArea=-1 and firstProblem="FAILED before snapshot" still passes with `timesWithAProblem=0`.

The Python test `report()` does not publish the new pass cell at all, and `frame()` never draws it (`test_marker_gate.py:23–26, 49–84`). Eleven such frames and its invented clean readback still pass. Thus (a) an incoherent readback report passes, (b) pre-painted expected frames with no draw provenance pass, and (c) a broken mirrored draw can pass. The gate currently relies on the implementation's note and cumulative counters for the newest mechanism instead of independently validating its evidence.

#### Repeated readback and immediate write: arithmetic CONFIRMED; failure retention REFUTED

`nextReadbackAt=2`, direct comparison and `+240` schedule 2, 242, 482, … correctly (`MarkerDraw:187–190, 290–292`). There is no Long.MIN_VALUE subtraction. Long overflow is not material for this bounded run. Samples can still all be clean while unsampled frames between them are broken. At least three global samples is not a per-checkpoint guarantee; counts survive world/pipeline changes and have no image/device/frame/completion token.

The new measured-problem branch increments a sticky counter and immediately writes if an instance exists (`381–390`). **CONFIRMED:** ordinary density/depth problems with a live instance no longer wait for frame 600, and later clean samples do not clear the sticky count.

**REFUTED:** every readback problem is persisted immediately. Request exceptions (`343–345`) and map/classification exceptions (`397–399`) only change in-memory state/notes; they neither increment `readbackProblems` nor write evidence. A measured problem arriving after `shutdown()` has cleared `instance` also skips its immediate write (`387–388, 726–727`). Ordinary snapshots remain frame 1 / multiples of 600 (`818–825`); the harness does not perform a final marker snapshot/drain.

I exercised `classifyReadback` itself by reflection with a `GpuBuffer` subclass whose map throws `IllegalStateException("injected map failure")`, with a prior clean file in a temporary harness directory. Output was:

```text
Readback[attempted=true, completed=false, ..., note=readback classify failed: java.lang.IllegalStateException: injected map failure]
problemCount=0 closed=true old_file_unchanged=true
```

This verifies both callback-buffer close and actual failure-loss behavior without GPU work. In the retained run the last file says frame **3600 / 15 clean**, reconnect says **4012**, and the log contains **17** clean samples. A failure at sample **3842**, followed by termination before 4200, can leave the clean 3600 proof unchanged. A WARN/INFO native-probe failure is not rejected by the native stage's validation/ERROR filters (`verify.py:740–753`). The retained log does not contain this injected failure; this is an independently demonstrated reachable false-pass mechanism.

The new measured-problem write supplies `targetWidth=0,targetHeight=0` (`389–390`), so it is structurally complete JSON but has incomplete target metadata. `writeFile` uses direct truncate/write, not atomic replacement (`McNativeVulkanProbe.java:359–367`); I/O failure can leave a partial file. Malformed JSON is rejected, so truncation alone does not create a clean false pass. A failed write that preserves the previous clean file is swallowed as WARN and can lose the problem. Claiming all problems are durably persisted is unjustified.

#### Harness metadata: still REFUTED as frame-bound proof

`markerDraws` is a cumulative recording count observed at a tick checkpoint (`LiveWorldHarness.java:256–261`), not the draw/submission identity of the image requested at 270. A count can advance from earlier frames while this frame has no marker. `frameCoveredByGui` records presence of a screen/overlay (`262–267`), not whether it covered this image's marker; GUI can be translucent or changed before capture. The asynchronous screenshot callback carries neither frame token nor matching readback identity.

The gate trusts either value to skip partial evidence, bounded to three cases (`verify.py:362–411`). It rejects yellow in its selected screenshot box before any skip, which is a confirmed improvement. Other partial failures can still be excused by wrong metadata. All retained GUI flags are false; nether is skipped because there are no matching colours, not because a frame-bound fact proves the draw never ran. Supporting evidence must remain scoped accordingly.

### B5 — same-device completion and destruction

**CONFIRMED:** `shutdownImmediate(waitedDevice)` compares Vulkan handle addresses (`MarkerDraw:756–767`), which is the appropriate comparison even when two Java wrappers represent the same device. The caller reaches it only after a successful idle on `VkContext.get().device` (`McNativeVkContext:287–310`). Waiting old device A can no longer destroy a marker owned by B: it deliberately abandons B. There is no newly demonstrated invoked native destroy path after an unconfirmed wait in this candidate.

Other current diagnostic paths:

| Path | Completion / ownership protection |
| --- | --- |
| Raw compute cleanup | its own successful fence on captured `vk` or abandonment (`ComputeProbe:209–250`) |
| Adopted sentinel cleanup | its own successful fence on captured context or abandonment (`McNativeVkContext:229–262`) |
| Real-shader cleanup | successful tracked frame wait/drain before pipeline/shader/buffer frees; otherwise abandonment (`RealShaderProbe:193–238`) |
| Frame tracker destruction | successful device idle before fence/command-buffer free (`VkFrameTracker:216–240`) |
| Marker format/world retirement | same owning Minecraft device's `queueForDestroy`; wrong device/refusal abandons (`MarkerDraw:681–739`) |
| Construction failures | destroy only newly created, never-recorded marker objects (`MarkerDraw:547–589`) |
| Readback buffer close | deferred destruction by the owning Minecraft buffer's encoder, including copy-registration exceptions |
| Adopted cache/context release | follows the successful adopted-device idle (`McNativeVkContext:318–332`) |

The readback-close contract was independently checked with `javap -c -p` on cached Minecraft 26.2: `VulkanCommandEncoder.copyTextureToBuffer` records copy/barrier before queueing callback retirement; `VulkanGpuBuffer$Direct.close` queues destruction using its own device, rather than calling VMA destruction immediately. A copy that partially records then throws does not cause this finally block to immediately free an in-flight buffer. Resource abandonment is safer than premature destruction but is not a proof of bounded memory use.

**REFUTED** as a guarantee for every public API/future terrain caller: `VkFrameTracker.waitIdle()` still discards the wait result and drains pending frees (`220–224`), and direct `VkContext.shutdown()` destroys its command pool without a completion guard (`VkContext:103–106, 692–695`). `rg -n '\.waitIdle\(' src/main/java` found no caller of the former; the native context shutdown caller performs its checked wait first. These are retained **non-blocking latent API defects for the present diagnostic**, and must not become terrain-lifetime primitives. B5 closure in the JSON refers to the actually invoked diagnostic failures, including the previous round's cross-device path, not endorsement of these public helpers or a future submission model.

## 2. New-code risks and smaller findings

| Risk/claim | Judgment |
| --- | --- |
| Buffer leaks on every error | **REFUTED.** Before registration, finally attempts close (`MarkerDraw:334–350`); callback finally closes after map/classification failures (`366–402`). The injected map failure closed its buffer. |
| Every deliberate leak bounded and reported | **REFUTED; additional blocker R4-L1 for the enabled diagnostic foundation.** Close exceptions are swallowed (`349, 401`), callbacks have no outstanding-buffer/byte cap, and requests continue every 240 frames. MC's direct buffer marks itself closed before queueing destruction; if queueing fails, no retry exists. Repeated retirement refusal/device/format/world replacement can abandon another pipeline set repeatedly (`258–268, 681–699`). NOTES is capped/deduplicated and only the first marker warning is logged (`773–779`); no leaked-object/byte counter exists. This is not necessarily once per healthy frame, but failures/replacement cycles have no total bound. Compute and real-shader runOnce leaks are session-bounded; marker/readback leaks are not. |
| Copy during an unsafe open pass | **No present unsafe placement found; CONFIRMED bounded placement.** Level-render TAIL hook and try-with-resources close the marker pass before copy (`MixinLevelRenderer:47–49; MarkerDraw:273–292`). MC copy/barrier/callback retirement contract supports this route. Reentrancy, callback non-delivery and target/device changes were not fault-injected on hardware. It does not establish safety inside Minecraft's terrain pass. |
| Whole-image size no longer matters | **REFUTED.** 40 MiB per-buffer budget correctly refuses 8K (~126.6 MiB), but 4K still copies/maps ~31.6 MiB per sample (`MarkerDraw:323–341`). There is no aggregate outstanding-byte budget or pending flag. |
| Colour tests uniquely identify the diagnostic | **REFUTED**, by actual Java 200/60 threshold and mirrored-pattern attacks above (`485–487`). Density makes accidental matches much harder, not impossible. |
| N1 reflection ambiguity/statics repaired | **CONFIRMED** for strict field selection and command-buffer owner-class cache (`McNativeVulkan:126–169`; `McNativeDeviceFeatures:204–213`). **REFUTED** the survey's claim that all helpers are shared: local field selection remains duplicated, and `FeatureAudit.featureName:168–178` still selects the first suitable String accessor without ambiguity rejection. This is a smaller maintenance/shape-drift risk for the inspected artifacts. |
| N2 exact physical-device selection | **Partly CONFIRMED**: compute/audit no longer substitute a differently named first GPU (`ComputeProbe:276–298; FeatureAudit:190–214`). **REFUTED as exact identity**: the first equal-name GPU still wins; adoption also permits sole-device fallback if name is unavailable (`McNativeVkContext:368–396`). Equal-model multi-GPU ownership is unproven. Non-blocking for retained single-GPU host; not a portability guarantee. |
| N4 enabled Metal extension rather than physical support | **CONFIRMED** in adopted mode (`VkContext:454–459`; `McNativeVkContext:345–356`). |
| N5 partial PNG integrity/bounds | **CONFIRMED** for chunk CRC, IHDR/IEND, 32M-pixel and positive-row bounds (`pixel_oracle.py:107–148`). Partial decoding intentionally does not validate the complete deflate stream; the environment gate separately calls full `read_rgb(...validate_only=True)` (`verify.py:219–220`). It is not standalone full-image validation. |

## 3. Tests: execution, regression validity and blind spots

Executed exactly:

```sh
./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true
python3 -m unittest discover -s scripts/tests
```

Gradle exited 0, `BUILD SUCCESSFUL`: **313 cases, 312 passed, 1 skipped, 0 failures/errors**. Compilation came from cache; the test task executed and fresh XML was produced. Skip: `VkBarriersTest.missingBarrierIsDetected`, the documented descriptor-SSBO blind spot. The intentional fill-buffer WAW diagnostic occurred in `plainBufferHazardIsNowDetected`. Independently calling `junit_result(build/test-results/test)` returned success with this one known gap, no unexpected skips/diagnostics. The loader emitted a deprecated validation-settings warning outside test XML. Python: **56 tests, OK** (20.633 seconds). These are standalone tests, not Minecraft-device acceptance.

I loaded old `scripts/verify.py` from git into a temporary Python module with `__file__` set, then ran the current fixture/test methods against its actual functions. No old checkout or implementation was edited:

| Test / baseline | Result and meaning |
| --- | --- |
| `test_no_depth_attachment_is_rejected` against `5160f000` | Fails because old gate accepts the no-depth frame: a real behavior regression test. |
| `test_one_far_pixel_instead_of_the_split_is_rejected` against `5160f000` | Fails **only on wording assertion**, not behavior: old gate already rejects this fixture. Far pixel is at `(104,5)`, outside old inset x=[11,103), y=[6,58); old message says farther base nowhere visible. Moving one far pixel to `(30,10)` makes the old gate accept it. Current density gate rejects it. The test does not actually reproduce the named old counterexample. |
| `test_frames_showing_nothing_are_recorded_and_bounded` against `5160f000` | Also fails only on wording. Both accept three empty marker regions and reject four. Its introduction says the old bright-scene/dark-box counterexample must fail, but the current test explicitly accepts three such frames. |
| `test_a_yellow_box_is_never_excused` and `test_any_readback_problem_during_the_run_is_rejected` against `7a769ad5` | Fail behaviorally; these genuine regression checks pass on HEAD. |
| Named-mismatch and never-attempted tests against `a1d128f4` | Fail behaviorally; repaired on HEAD. |

Examples of tests that still pass against broken/incomplete code:

- `test_notes_on_any_proof_fail` only mutates compute notes (`342–344`); it passes a gate that ignores feature failure notes, as HEAD does.
- `test_a_proof_naming_a_different_device_fails` only checks marker/Vulkan-probe identities (`369–375`); it passes the old `a1d128f4` gate that ignores contradictory compute/shader identities. No new regression case protects the newest two identities.
- `test_a_correct_frame_passes` passes frames/report without any depth-tested pass cell. It cannot catch absence of the new spanning draw or false clean raw-readback assertions.
- Java `theMarkerShadersCompileToSpirv` checks valid compilation/magic/size, and passes a shader with wrong but valid colour/geometry (`McNativeMarkerDrawTest:24–32`). `theMarkerColourIsOneAScreenshotCanFind` checks constants, not shader output (`55–58`).
- Java `theDrawIsOffUnlessItsFlagIsSet` passes if rendering always returns, even with the flag enabled (`37–51`). It validly tests dormant behavior, not enabled drawing.
- `theProbeResolvesEveryQuadOrdinalThroughVoxysRealShaderStack` runs the actual GPU index proof against a Voxy-owned device (`McNativeRealShaderProbeTest:37–45`), which is useful. It cannot catch broken Minecraft adoption/mixins, device identity JSON, KHR-only execution, marker depth, sample association, or failure cleanup. It also does not assert published device/firstMismatch/notes consistency.

There is no Java unit coverage of `measure/rect/count`, the spanning command's two outcomes, callback failure persistence, rearm or cross-device shutdown identity. This review exercised selected real private methods independently; the green suites should not be described as protecting all those mechanisms against regression.

## 4. Survey measured claims: evidence sufficiency

The following judgments distinguish source confirmation, the retained new run and exact historical claims. Survey line references are to the reviewed HEAD.

| Section / claim | Judgment |
| --- | --- |
| Measured host: preference-only OpenGL fallback/rewrite (`123–130`) | **REFUTED as retained proof**: failed/preference-only run and options snapshots absent. New forced-backend log supports only the forced run. |
| Forced Vulkan, MoltenVK, Apple M4 Pro, nonzero instance/device/VMA, queue split, RGBA8/D32 targets and extents (`132–146`) | **CONFIRMED for retained new run** by log and checkpoint/probe files. Actual target pixels/depth values are not established by handle reports. |
| Native target access without GL; Voxy remains disabled (`148–151`) | **CONFIRMED** by source and native log. Native production terrain remains absent. |
| One magenta draw, no push constants (`160–165`) | **REFUTED for current source**: several quads and a 36-byte push payload (`MarkerDraw:511–544`). Flag guard and Minecraft-owned pass route are confirmed. Historical exact implementation has no retained run here. |
| Persistent pipeline, 4800 draws, exact ten-frame coverage/counts (`172–183`) | **CONFIRMED only for analogous bounded new execution**, whose JSON records 3600 and final checkpoint 4012, with ten visible crops. **REFUTED exact historical 4800/counts**; those artifacts are absent. Clean new log/loader evidence and integration-blocked scope are confirmed. |
| Exact original y-placement and colour samples (`187–194`) | **REFUTED exact historical measurements**: original images absent. New crops support a top screenshot marker; raw orientation is not independently retained. Source comment and shader now do not implement a second y negation. |
| Nether hook never ran (`195–199`) versus later draw-certain/GUI explanation (`368–372`) | **REFUTED as established causality**: new nether crop is dark, count advanced to 3555, GUI=false. These observations do not establish which frame rendered/did not render, or prove the historical explanation. |
| Eight physically supported features, four absent from Minecraft defaults, extension list and zero-to-one (`215–230`) | **CONFIRMED for physical/default declarations on retained host**, with limitation: `FeatureAudit:155–165` reads `REQUIRED_DEVICE_FEATURES`, not the augmented live device state. `enabledByMinecraft:false` does not mean the injected live device lacks them. |
| Four verified feature setters / guarded optional mixin (`232–247`) | **CONFIRMED source and meaningful independent field-setting tests**. **REFUTED retained evidence of the original failed-offset launch**: no failed-run log. |
| Int64 compute sentinel on compute family 3, clean lifecycle (`249–257`) | **CONFIRMED bounded current source/log/report**, and identity agreement. **REFUTED that this tests all shader feature requirements**: it does not exercise indirect-first-instance or vertex/fragment storage paths. |
| Adoption, borrowed ownership, re-queried limits/memory, queue 0 (`265–285`) | **CONFIRMED inspected source and new sentinel log**. **REFUTED universal identical behavior in both modes**: equal-name physical selection and submission ownership remain limitations. The queue constructor checks COMPUTE; the broad claim that every graphics family must support compute is not established by this evidence. |
| Depth test AND depth write, exact old counts at every checkpoint (`288–299`) | **REFUTED full proof**: LESS depth-write-disabled variant has identical colours; authoritative depth/raw samples absent. Current source/pixel split and single-command repair support the narrower depth-test diagnostic. |
| Correct release, old lifetime complaints, old extra-negation failure/exact bottom counts (`301–311`) | **CONFIRMED clean new release and same-device shutdown repair**. **REFUTED exact old incidents/counts**: artifacts absent. |
| Production loader/import/descriptor/frame/barrier machinery and 189 CPU matches (`313–327`) | **CONFIRMED bounded source, retained log/report and independent standalone test**. This is index resolution, not real terrain recorded in Minecraft's pass. |
| Core/KHR dispatch repair and 313-test suite (`331–342`) | **CONFIRMED source dispatch and current suite**. Native success supports the KHR route on this host; standalone tests do not directly exercise a KHR-only Minecraft device. Original failure diagnosis is unretained. |
| Cache teardown and standalone probe seam (`344–359`) | **CONFIRMED source/seam/new clean release**, subject to B5's caller scope. Universal lifetime safety is not established for the unused helpers/future terrain calls. |
| Round-1 repairs and cannot-drift tests (`361–421`) | **CONFIRMED specific flag, fence and strict-field changes; REFUTED broad complete proof/test/evidence closure**, as B1/B3/B4 and section 3 show. The cited run is absent; “47 Python tests” is outdated (56 now). |
| Round-2 density/cell/rearm/identities/replay claims (`435–475`) | **CONFIRMED density and rearm**. Separate-cell logic was insufficient and is superseded. Three global samples do not certify a lifecycle. The cited run is absent. Feature/semantic proof omissions and authoritative replay remain open. |
| Initial MIN_VALUE bug caught (`483–485`) | **CONFIRMED corrected arithmetic and rejection of no readback by source/tests; REFUTED historical failed-run evidence**, not retained. |
| Round-3 single command / immediate problem persistence (`493–501`) | **CONFIRMED specific spanning-command omission repair and ordinary measured-problem write; REFUTED universal provenance/durable problem retention**, especially exceptions and null-instance callbacks. |
| Five identities and attempt/firstMismatch checks (`503–506`) | **CONFIRMED all five handle fields and explicit shader mismatch/compute+shader attempted failures**. **REFUTED complete required-field/failure validation**: missing firstMismatch and other proof fields default/escape as described in B3. |
| Same-device shutdown (`508–512`) | **CONFIRMED** for invoked current diagnostics. |
| Per-frame crops, finished verdict/source check, latest clean run (`514–521`) | **CONFIRMED** supporting recount, final summary and source match. Manifest hashes 33 payload files including 22 crops (34 files with manifest); `du -sk` reports 704 KiB allocated, actual bytes total 637441. JSON reports 15 clean, while log shows 17; authoritative samples and final marker snapshot absent. **REFUTED full pixel-gate/authoritative replay**. |

The survey's qualification about reproducing older results with newer evidence is useful (`423–432`), but cannot prove measurements the newer run does not repeat. Its opening “Nothing here is implemented” and final proposed diagnostic first step are stale; API investigation, implemented diagnostic connection and missing native terrain should remain distinct. The explicit unresolved hardware questions are appropriate and are not settled by this review.

## 5. Safety for normal play and terrain-work decision

**GL backend, all native flags unset: CONFIRMED safe to ship with respect to these additions**, from the inspected dormant call graph and permitted tests. No new native GPU/file side effects were found. This is not an independent end-to-end GL gameplay certification.

**Minecraft Vulkan backend, all native flags unset: CONFIRMED dormant safety on the same basis.** The feature mixin returns its argument and client stop does not idle the device. Voxy still disables its production renderer, so shipping this dormant code does not deliver Vulkan LoD terrain.

**Enabled diagnostic as the accepted foundation for terrain work: REFUTED.** B1 authoritative evidence, B3 fail-closed proof consistency, B4 current failure persistence/orientation/sample association and R4-L1 unbounded failure abandonment remain blockers in my judgment. The new same-device shutdown and spanning-command repairs are useful foundations, but a green native stage still need not establish the draw/depth/current-lifecycle claims it is intended to guard. This is a gate/evidence verdict, not a claim that the native architecture is infeasible. Terrain investigation can be experimental; it should not be promoted as continuation from an independently accepted diagnostic layer.

## 6. What could not be checked and why

- No new Minecraft/native/live run, GUI/capture synchronization, device replacement, GPU timeout/loss or refused-retirement injection on hardware: excluded by the user. The Java reflection/map-failure exercises used no live device.
- Authoritative raw-image/depth reclassification, selected orientation, individual sample-to-checkpoint association and complete screenshot integrity: required original images/raw samples are absent. Supporting crop counts were checked, not substituted for those absent artifacts.
- Historical preference/failure/orientation runs and exact old counts: their cited directories/logs/images are absent from HEAD.
- Multi-GPU equal-name ownership and run-handle reuse: source-level limits only, not hardware observations.
- Native terrain, Sodium-pass ordering/layout/depth/descriptor interaction, updates/pressure and normal-play lifecycle correctness: remain unimplemented or expressly outside this diagnostic evidence. Successful standalone tests do not accept the project goal.

## Reproduction of gate counterexamples

The following read-only Python calls use the real gate functions and existing fixtures; they do not launch Minecraft:

```python
import sys
sys.path[:0] = ['scripts', 'scripts/tests']
from test_marker_gate import ProofFileGateTest, MarkerGateTest, STAGES, frame, report
p = ProofFileGateTest()
assert p.run_gate({'native-device-features.json': {'attempted': False}})['success']
assert p.run_gate({'native-device-features.json': {'notes': ['FAILED injection']}})['success']
assert p.run_gate({'native-adopted-context.json': {'readBack': '0xdead'}})['success']
assert not p.run_gate({'native-compute-probe.json': {'device': '0xdead'}})['success']
assert not p.run_gate({'native-real-shader.json': {'device': '0xdead'}})['success']
r = report()
r['readback'].update(near=500, far=500, control=500, boxArea=999999999,
                     controlArea=-1, firstProblem='FAILED before snapshot')
assert MarkerGateTest().run_gate({s: frame() for s in STAGES}, r)['success']
```

For historical regression inspection, `git show 5160f000:scripts/verify.py` supplies the exact old function. Execute that source into a module with `__file__` pointing at current `scripts/verify.py`, substitute its `native_marker_result` into `test_marker_gate`, and run the named tests above. Inspect the assertion that fails; merely counting test failures conceals wording-only differences.

The actual Java probes used reflection on `build/classes/java/main` with cached Minecraft/LWJGL dependencies: `getDeclaredMethod("measure", ByteBuffer.class,int.class,int.class,boolean.class)` and `getDeclaredMethod("classifyReadback", GpuBuffer.class,int.class,int.class)`, both made accessible. The positional patterns used source rectangles at 1920×1080; each colour rectangle was painted into an RGBA ByteBuffer, first in the unflipped coordinates and then their mirror. The failure buffer subclass implemented `map(long,long,boolean,boolean)` by throwing and `close()` by setting a checked boolean. All probes ran in temporary directories; no production code was modified.
