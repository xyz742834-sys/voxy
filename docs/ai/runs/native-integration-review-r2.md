VERDICT: REDESIGN

Independent round-2 review, 2026-10-05. Reviewed commit: 7a769ad58d6f66da8c73930f7f48c34609b5932e, verified with git rev-parse HEAD. Branch: xyz742834-sys/native-review-r2. Initial tracked tree was clean. Read project-goal.md and vulkan-native-integration-survey.md first, followed by the required project context and both round-1 artifacts. No implementation or test files were changed. Minecraft, the native/live runner stages, and GL interop checks were not launched.

CONFIRMED / REFUTED below judge the stated acceptance claim, with source-level facts distinguished from measured hardware claims. REFUTED as proven does not assert that the hardware capability is impossible.

Only B2 is closed. B1, B3, B4 and B5 remain blocking. The no-flags normal-play paths are acceptable from this source review; the opted-in native diagnostic and its acceptance evidence are not.

## 1. Each round-1 blocking finding

### B1 — evidence retention: REFUTED; still blocking

The retained directory docs/ai/runs/native-evidence/20261005T050713-618859Z contains eight JSON reports and MANIFEST.json. Independently recalculating all eight JSON SHA-256 values matched the manifest: CONFIRMED that the committed JSON bytes are retained intact.

It contains no PNG, raw colour readback, span coordinates, native.log, summary.json, launch command/environment, source fingerprints, tracked patch, or shutdown log. Screenshot hashes do not let a reviewer inspect pixels or rerun a pixel gate. The report's readback is a count/boolean summary produced by the implementation under review, not independent pixel evidence. It does not record its own image handle, extent, frame/submission identity or device; the surrounding marker report is rewritten later with the current target extent.

Directly replaying the actual functions against the retained directory gives:

    native_environment_result(retained_directory):
      success=False, missing warmup.png
    native_marker_result(retained_directory, retained_checkpoints):
      success=False, missing warmup.png
    native_proof_files_result(retained_directory, retained_checkpoints):
      success=True

The manifest cannot establish that this candidate produced those reports or that validation and shutdown were clean. The retained in-client result is written before mc.stop(), not after device teardown: LiveWorldHarness.java:278–297. Empty checkpoint debug-message lists establish only what that callback reported at those points.

Older measured claims still cite only ignored build output: survey:169,213,282,323. Their run directories are not committed. The new retention function copies native-output JSONs and hashes screenshots, but excludes the parent native.log/summary and source evidence: verify.py:474–495,603–633. Retention failure is recorded as an error without failing the native stage: 493–495,631–633.

Closure requires checkable evidence, not just retained success assertions. This finding also covers the survey's unsupported measured claims listed in section 4.

### B2 — inertness with all four native flags unset: CONFIRMED, closed for GPU/device/file effects

Source traces of normal entry, render, tick and client stop show:

| Path | Evidence | Result with flags unset |
| --- | --- | --- |
| Initial adoption | VoxyClient.java:71; McNativeVkContext.java:92–100 | Returns before Minecraft-device lookup or GPU allocation. |
| Tick diagnostic | VoxyClient.java:165–166; McNativeVulkanProbe.java:286–289 | Returns before reflection, feature queries, optional proofs and file writing. |
| Level-render hook | MixinLevelRenderer.java:47–49; McNativeMarkerDraw.java:191–192 | Returns before obtaining the device or creating a pass/pipeline/readback. |
| Level close | MixinLevelRenderer.java:34–37; McNativeMarkerDraw.java:601–606 | Null instance returns before device lookup. No marker instance can be created without its flag. |
| Client stop | VoxyClient.java:171–172; McNativeVkContext.java:282–303; VkContext.java:100–101 | isAdopted() is false, including with an independently owned diagnostic context; returns before vkDeviceWaitIdle. |
| Device-creation mixin | MixinVulkanBackend.java:34–41; McNativeDeviceFeatures.java:76–78 | Returns the identical requested feature set before calibration/native allocation or file writing. |

The mixin still invokes augment, sets attempted=true, and initializes Java collections. adoptIfRequested also allocates a Java ArrayList and updates status before returning. Thus a literal claim of zero native-package Java execution or zero heap allocation is REFUTED. These are bounded bookkeeping, not the previous driver query/stall, GPU allocation, device mutation or file-writing blocker. No unflagged Minecraft-device side effect was found. The feature identity test passes independently.

This judgment applies to normal production callbacks with all native flags absent, not to explicitly called public measurement APIs or the separately enabled development harness.

### B3 — proof gating: REFUTED; partially repaired, still blocking

CONFIRMED: verify.py:402–444 requires the four files, and main includes the proof verdict in stage success at 614–615. Missing files, explicit compute/shader succeeded=false, adopted=false, provenByVoxyBufferAndShader=false, partial added features and shader mismatches are now rejected. The retained four reports pass this subgate.

REFUTED: identity and report consistency are still incomplete.

- Only adopted.device is compared, and a missing/null device skips that comparison: verify.py:458–467. The comment at 445 says marker identity is checked, but the function never reads native-marker-draw.json.
- Compute and real-shader reports contain no device identity at all: McNativeComputeProbe.java:300–308 and McNativeRealShaderProbe.java:234 onward. Neither can be matched to Minecraft's observed device. Adding a contradictory device field is ignored.
- Shader notes and firstMismatch are ignored; feature attempted and shader/compute attempted are not required. Compute expected and readBack can both be absent/null and pass the equality test: verify.py:424–442.
- The adopted proof's readBack value is not verified. Feature notes include benign calibration messages in the real report, so blanket rejection of all feature notes would be inappropriate, but the gate also has no failure-versus-calibration distinction.

Observed direct calls of ProofFileGateTest.run_gate against the real current function:

| Mutation to its otherwise passing fixture | Observed success |
| --- | --- |
| adopted.device=null | true |
| compute.device=0xdeadbeef | true |
| shader.device=0xdeadbeef | true |
| shader.notes contains “FAILED: fence never completed”, firstMismatch non-null | true |
| compute.expected=null and readBack=null | true |
| features.attempted=false | true |

An unreadable non-null adopted handle and a readable mismatching adopted handle do correctly fail. That partial repair does not establish the broader claim.

I also combined actual environment, marker and proof gates in one eleven-checkpoint fixture: Minecraft device=12, marker device=0xdeadbeef, adopted device=null. All three returned true. The full stage's remaining prerequisites concern subprocess exit/log diagnostics and would not detect this identity mismatch.

### B4 — marker/readback/depth gate: REFUTED; still blocking

The new control strip, spatial screenshot checks and required readback are improvements. They do not make the claimed proof authoritative.

#### (a) A readback that passes while proving no marker geometry

McNativeMarkerDraw.java:307–335 classifies the entire image and reduces each colour to a count and bounding rectangle. relationshipProblem at 354–371 checks only partial row overlap, non-overlapping x spans (either order), and yellow outside the shared row band. It enforces no marker location, width, height, adjacency, rectangular occupancy, 60/40 ratio, strip dimensions or proximity. Its colour thresholds at 374–376 allow any matching saturated scene pixels.

A reflection-only Java invocation of the actual private method, with no GPU/device creation, accepted these three Span(count,x0,y0,x1,y1) values:

    near     = (500, 400, 100, 409, 149)
    far      = (500, 800, 100, 809, 149)
    rejected = (500, 100, 400, 109, 449)
    relationshipProblem(...) = null

These are three 10×50 patches hundreds of pixels apart, outside the marker's expected region. They can be arbitrary scene content, not a bounded Voxy draw. Turning those actual accepted spans into the implementation's Readback produces near=500, far=500, control=500, rejectedInBox=0, boxArea=1000, controlArea=500, note=null. The real Python marker gate accepted this report with eleven ordinary passing supporting fixtures. Counts at least 500 and a null implementation note are its entire authoritative readback check: verify.py:272–291. boxArea/controlArea are counts, not geometric areas, and the gate does not validate them.

A second accepted relationship example had near rows 5..104, far rows 5..54, and yellow rows 70..119. Yellow overlaps rows occupied by the near half but avoids the intersection band. The method returned null, and classifyReadback would report rejectedInBox=0. That field is not an independently counted rejected-in-box measurement: it is assigned zero whenever the relationship function returns null at 333.

The readback scans once, after the second recording, and is never reset after level teardown, reconnect or resize: 153–158,254–258,601–617. Its early result is later attached to evidence describing a different current target extent and later draw count: 665–711. It cannot validate later lifecycle frames.

#### (b) Frames that pass although the relevant draw never happened

The rejected depth-tested draw and yellow control are separate commands: McNativeMarkerDraw.java:407–413. Omit only the depth-tested yellow quad at 407–408, retain the control at 412–413, and both the final image and readback remain exactly the expected image. The gate cannot distinguish that broken sequence from successful depth rejection. The survey's “A third draw that is simply absent now fails” at 382–385 is REFUTED. The strip proves the fourth draw can produce yellow, not that the third command was executed.

More generally, increasing drawsRecorded establishes Java recording attempts, not GPU execution. It increments before pass closure at 251–253, without a frame/submission identity. The same eleven synthetic images with expected coloured rectangles and increasing counters pass regardless of whether their colours came from these vkCmdDraw calls or unrelated existing content. This is a counterexample to causal attribution, not a claim that the retained run was forged. There is no pre-draw/control-frame difference or varying per-frame identity to disambiguate existing pixels.

#### (c) A depth failure that passes

Observed using actual native_marker_result and the committed fixture helpers:

| Counterexample at nether; other ten frames correct | Observed success |
| --- | --- |
| Box entirely yellow, control absent, increasing counter, frameCoveredByGui=false | true; nether skipped |
| Box entirely yellow with yellow control, frameCoveredByGui=true | true; nether skipped |
| Box entirely yellow with control, counter repeats reload's counter | true; nether skipped |

verify.py:367–371 skips before checking rejected_in_box at 373–375. Its zero-near/zero-far/zero-control exemption ignores rejected_in_box, so a conspicuous failed depth image is treated as carrying no marker. GUI presence and repeated counters likewise override positive depth-failure pixels. Three such critical frames can still be exempted at 390–395.

Turning depth writes off only for the near quad also preserves the expected pattern: the base z=0.6 already rejects yellow z=0.9. Even perfect retained pixels would not prove the nearer draw's depth write. The pattern overwrites scene depth with an ALWAYS base, so it does not prove ordinary scene-depth composition or Minecraft's depth convention.

### B5 — destruction after an unconfirmed submission: REFUTED; still blocking

CONFIRMED repairs:

- McNativeComputeProbe.java:205–241 and McNativeVkContext.java:227–254 track submitted/retired and deliberately retain resources if their five-second fence wait fails.
- releaseAdopted now waits on the adopted context's own device and returns without destruction on a non-success wait or exception: McNativeVkContext.java:287–303.
- Failed queueForDestroy no longer immediately destroys the marker: McNativeMarkerDraw.java:565–575.

Remaining paths:

1. McNativeRealShaderProbe.java:182–183 submits and waits. If waitForFrame throws after a successful endFrame, recording is already false. finally checks only isRecordingFrame, skips waiting, and destroys pipeline, descriptors, results and terrain resources at 201–218. If its cleanup drain itself throws, the catch at 212–214 also falls through to those destroys. There is no submitted/retired guard in this probe.
2. VkFrameTracker.waitIdle ignores vkDeviceWaitIdle's result, advances completed and drains frees at 211–215; destroy proceeds to destroy the fence/free the command buffer at 217–221. Real-shader probe shutdown calls this at McNativeRealShaderProbe.java:103–109, contrary to its adjacent “waitIdle is not used” comment. This was not repaired by 7a769ad5.
3. McNativeMarkerDraw.shutdown still uses draw.destroy() when it cannot obtain an active device: 601–606. Loss of a wrapper/reference is not observation of submission completion.
4. If Minecraft's device changes, render queues the old marker's destruction on the newly observed device's encoder: 224–229. That new encoder's completion does not retire old-device submissions. The marker destroys using its stored old device at 579–588. This is a lifecycle compatibility path, not a reproduced device-switch event on the reviewed host.

No timeout/device-loss injection was performed. These are source-confirmed missing retirement guards; a successful run cannot certify their failure branches. Intentionally retaining uncertain resources is safer than premature destruction, but the adopted proof's leaked command buffer still belongs to the shared context pool, so a later successful device-idle observation is needed before destroying that pool.

## 2. New code's risks and the smaller repair claims

### Readback cleanup, timing and scale

- **REFUTED that every error path releases the buffer.** requestReadback creates a local buffer at 291–292, then records/registers its callback at 293–294. If copy or callback registration throws, catch at 295–298 has no buffer reference and cannot release it. If the callback is never processed, no owner outside its closure can release it. readbackRequested is set before the request and never cleared: 255–257. This is at most one request per class lifetime, not a per-frame leak.
- **CONFIRMED that callback classification errors normally close it.** classifyReadback has a mapped-view try-with-resources and buffer.close in finally at 307–344. A map/classification exception reaches the close. Close failures are swallowed and leave no cleanup failure evidence.
- **CONFIRMED, artifact-level, that the intended copy is outside its rendering pass.** The call follows try-with-resources pass closure at 240–258. javap -c -private on the actual minecraft-merged.jar shows CommandEncoder.copyTextureToBuffer rejects an open wrapper pass; VulkanCommandEncoder.submitRenderPass ends dynamic rendering and emits a memory barrier; its copy records vkCmdCopyImageToBuffer in GENERAL, emits another barrier, and puts the callback into queueForDestroy. Callback dispatch therefore uses Minecraft's deferred submission lifetime, rather than immediately mapping an unfinished transfer.
- **No demonstrated unsafe-frame copy in the intended current hook.** It is at LevelRenderer.renderLevel TAIL, not inside an open Sodium pass. A GUI-covered frame is not by itself an unsafe Vulkan frame. A changed hook/API, copy-registration failure or teardown before callback remains untested. The source/artifact route is plausible; missing native/shutdown validation logs prevent accepting validation-clean runtime timing.
- **CONFIRMED that size can matter.** The one-shot allocation and scan are width×height×4 bytes and O(width×height) on the callback thread, with no explicit diagnostic budget: 289–292,307–328. 1920×1080 is 7.91 MiB; 3840×2160 is 31.64 MiB; 7680×4320 is 126.56 MiB. There is no format/block-size check: classification assumes tightly packed RGBA8. That matches the retained format 37, not arbitrary render targets. Integer row offsets can also overflow at very large images; ByteBuffer mapping cannot represent an arbitrary long-sized allocation.
- **REFUTED that the classification isolates marker pixels.** It scans all pixels using loose per-channel thresholds. B4's unrelated-patch example and false zero rejectedInBox show both false acceptance and possible false rejection from unrelated saturated scene colours. Raw pre-composition pixels have no retained evidence justifying the same broad tolerance used for post-composition screenshots.

These readback resource/budget concerns are non-blocking in the no-flags product configuration; classification correctness is already the B4 blocker.

### Harness fields

**REFUTED that markerDraws identifies a draw in the captured frame.** LiveWorldHarness.java:83–88,128–131 waits for two cumulative recordings since entering the stage, not the submitted/completed screenshot frame. nativeCheckpoint samples a counter at 260–261 during END_CLIENT_TICK, then requests an asynchronous screenshot at 270–274. Multiple draws may have occurred earlier; none proves this captured frame contained one. The counter is not reset on level shutdown.

**REFUTED that frameCoveredByGui means the marker was covered.** At 266–267 it means any screen or overlay object exists. A transparent/partial screen can be present without covering the marker. Conversely, GUI state sampled at tick time is not stamped into the copied/rendered frame, and later composition can hide pixels without such an object. The committed nether checkpoint says frameCoveredByGui=false; it supplies no actual covering cause.

The gate trusts both values before examining depth-failure pixels. They can therefore be wrong in a way that matters to acceptance; the B4 counterexamples reproduce that consequence.

### N1/N2/N4/N5

| Claimed smaller repair | Judgment and remaining limit |
| --- | --- |
| N1: reject ambiguous/static reflection, key command-buffer cache by class | **CONFIRMED in McNativeVulkan.java:126–169. REFUTED globally:** McNativeVulkanProbe.backendOf at 187–203 and McNativeDeviceFeatures.readFieldOfType at 200–210 still select the first typed field and include statics. Present artifact fields are unique, so this remains non-blocking compatibility risk. |
| N2: select Minecraft's actual physical device | **Partially CONFIRMED** in McNativeVkContext.java:373–396 by device-name matching. **REFUTED as fully repaired:** McNativeComputeProbe.java:274–284 still selects the first enumerated physical device; FeatureAudit.java:190–216 still falls back to the first. Identical-name GPUs are also ambiguous in adoption. No multi-GPU execution was done. |
| N4: adopted hasMetalObjects uses enabled extensions | **CONFIRMED** in VkContext.java:454–459 and McNativeVkContext.java:343–354. Retained underlyingExtensions has no VK_EXT_metal_objects. No new native GL/IOSurface prerequisite found. |
| N5: partial PNG CRC/IEND/dimension/row bounds | **CONFIRMED for those specific bounds** in pixel_oracle.py:107–147. It still intentionally stops after requested rows and does not establish whole-zlib-stream completeness; native_environment_result's full PNG decoder supplies that check on the stage route. read_bytes/compressed input itself has no byte-size budget. |

N3 remains: FeatureAudit reads static unaugmented REQUIRED_DEVICE_FEATURES at 155–165, not actual augmented device requests. N7 remains: the depth pattern cannot prove near writes or normal scene composition.

## 3. Tests and independent executions

Both requested commands were executed once and completed successfully:

    ./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true
    python3 -m unittest discover -s scripts/tests

Java: 313 tests, 312 passed, 1 skipped, 0 failures/errors, from fresh build/test-results/test/TEST-*.xml. Gradle ran the test task; Java compilation was FROM-CACHE. Device output identifies Apple M4 Pro, API 1.4.357, validation=true and syncValidation=true. The sole skip is VkBarriersTest.missingBarrierIsDetected(), the documented descriptor-SSBO blind spot. XML includes the intentional fill-buffer WRITE-AFTER-WRITE control; the inspected XML has no other validation-error identifier. The loader also emitted a deprecated validation-settings warning. These are standalone Voxy-device tests, not a new Minecraft run.

Python: 47 tests passed in 18.532 seconds.

### Do the marker regressions detect the old behavior they describe?

I loaded HEAD^:scripts/verify.py into a temporary Python module and rebound test_marker_gate.native_marker_result to its old function. HEAD^ is 59a6a78d0c8bad687fca5da21bf1a7b433599ea0; git diff 5160f0007a74d562e84e403891196d74925467d8 HEAD^ -- scripts/verify.py scripts/pixel_oracle.py is empty, so this is the exact round-1 gate behavior.

Of 17 MarkerGateTest methods, 14 fail and 3 pass under that substitution. Several “regression failures” detect only changed error text, not an old false acceptance:

- **REFUTED:** test_one_far_pixel_instead_of_the_split_is_rejected reproduces the old accepting counterexample. The helper puts the only cyan pixel at (104,5), outside the old inset x=11..102/y=6..58. The old gate returns false because it sees zero cyan pixels. The test fails solely because its new “half of the box” message is absent. Moving a cyan pixel inside the old region to (80,10) makes the actual old gate return true, reproducing the genuine defect. Source: test_marker_gate.py:64–68,106–109.
- **REFUTED:** test_frames_showing_nothing_are_recorded_and_bounded locks out the old three-dark-critical-frames acceptance. It expressly accepts nether, overworld and reconnect with black boxes and bright backgrounds at 119–124; both old and new gates accept those three. With a fourth frame, both gates reject; the old substitution fails only on wording.
- test_a_partially_drawn_frame_is_still_a_failure and test_the_rejected_colour_inside_the_box_is_rejected reject their fixtures in both versions; their old-substitution failures are message comparisons.
- **CONFIRMED:** the no-depth, missing-control and readback-negative tests reject fixtures that the old gate accepts. These are meaningful gate regressions, but the readback cases supply a hand-authored note/count summary and do not call the Java classifier.
- Tests that pass unchanged against the old gate include test_a_correct_frame_passes, test_a_gui_covered_frame_is_excused_with_the_implementations_reason, and test_the_readback_is_what_carries_the_proof_not_the_screenshots. The last rejects eleven empty screenshots even though the old code has no readback check.
- **REFUTED:** test_notes_on_any_proof_fail checks notes on any proof. It changes only compute notes at 304–306; a gate broken for shader notes still passes that test, as the current implementation does.
- The proof-file unit tests call the helper directly, not the native-stage conjunction. A runner that ceased using the helper could pass them. Source inspection confirms current main does use it.

The file's opening assertion that every original counterexample is encoded and “each must now fail” is inaccurate. Its three-frame case has been redefined as accepted, and the lone-cyan fixture moved the relevant pixel outside the old oracle.

### Java test scope and examples that pass against broken code

- McNativeMarkerDrawTest.theMarkerShadersCompileToSpirv checks compilability, size and magic. A shader producing the wrong position/depth/colour can still compile and pass. theMarkerColourIsOneAScreenshotCanFind checks constants only.
- theDrawIsOffUnlessItsFlagIsSet genuinely exercises the unset marker guard, but does not inspect device touches, stop-path waits, readback cleanup, retirement or depth correctness. It does not test relationshipProblem.
- McNativeVulkanProbeTest.probingWithoutADeviceReportsWhyInsteadOfThrowing meaningfully checks the null-device route. It would pass with a broken probeOnce flag guard because it never calls probeOnce. Its “well formed JSON” tests check strings/bracket counts rather than parsing all legal inputs.
- McNativeDeviceFeaturesTest.eachAddedFeatureSetsOnlyTheFieldItNames independently checks named LWJGL fields and catches the historical wrong offsets: CONFIRMED. The unset-feature identity test also remains meaningful. Neither test runs the mixin in Minecraft or establishes physical support/actual enabled device features.
- McNativeRealShaderProbeTest exercises actual GPU index resolution against the linear CPU reference on Voxy's own device: CONFIRMED for its bounded 189-ordinal fixture. It passes with the unsafe cleanup paths in B5, broken adopted-device selection, or a broken KHR-only branch because it exercises a successful independent core-capable device.
- There are no Java classifier/relationship, copy-registration-error or unconfirmed-wait destruction tests. A relationship function returning null for all spans would pass the present Java tests and the Python tests supplied with independently fabricated readback notes.

## 4. Survey measured claims versus committed evidence

The table judges every distinct claim in the measured sections, grouping parallel data points. File/line references refer to docs/ai/vulkan-native-integration-survey.md. “Reported only” means JSON contains the assertion but the measured execution cannot be independently certified with the retained artifacts.

| Claim | Judgment | Available evidence / limit |
| --- | --- | --- |
| options.txt Vulkan preference produced GL and was rewritten to default (124–129) | **REFUTED as proven** | No failed-preference run/options/log is committed. |
| Main accepts --graphicsBackend and --vulkanValidation (130–135) | **CONFIRMED API-level** | javap -c -private net.minecraft.client.main.Main on the actual cached minecraft-merged.jar exposes both arguments. The quoted runtime selection message and original launch are not retained. |
| Actual VulkanDevice on Apple M4 Pro/MoltenVK 1.4.2; nonzero instance/device/VMA; families 0/3/3; RGBA8/D32 images 1708×960, usage 15; no notes (137–146) | **CONFIRMED as values in retained native-result.json / native-vulkan-probe.json; REFUTED as independently bound run evidence** | Eleven reports retain the values, including resized 1920×1080 targets. No candidate/source/launch binding or raw log. javap confirms native accessor APIs exist, so GL-free reachability is source-confirmed. |
| No Voxy terrain on this backend (148–151) | **CONFIRMED source/report scope** | VoxyClient.java disables ordinary native LoD; retained voxyIntegrationStatus is BLOCKED_UNIMPLEMENTED. Native diagnostic draws are distinct. |
| Bounded native pass/pipeline route (160–165) | **CONFIRMED source-level** | Marker opens MC's pass and uses MC colour/depth; no GL prerequisite. “No push constants” is stale: current shaders and quad use them at MarkerDraw:87–90,423–428. |
| Pipeline format 37/live entire lifecycle, 4800 draws, zero diagnostics, all gates green, unchanged sources (169–183) | **REFUTED as proven** | Original build run absent; new retained report says 3000 recordings and last checkpoint 3471. No whole-run gate/log/source evidence. |
| Ten visible full boxes and exact screenshot counts (178–180) | **REFUTED as proven** | PNGs absent; hashes cannot establish counts. |
| NDC/final-frame vertical flip and specific y≈854 (187–189) | **REFUTED as measured proof** | Original images absent; current coordinate choice is visible in source. |
| Composition darkens magenta to specified colours (190–194) | **REFUTED as proven** | No retained images or sampled colour distributions. |
| Nether was black because level was not rendered/hook never ran (195–199) | **REFUTED** | No original frame evidence; repair section later says the draw certainly ran. A dark image alone proves neither explanation. Current checkpoint has a cumulative recording count, not a frame identity. |
| All eight features supported, stock MC enables four, zZeroToOne=true, queue split and enabled extensions (210–230) | **CONFIRMED as retained audit values/static source; REFUTED as actual augmented enabled-state measurement** | native-feature-audit.json preserves these values but reads unaugmented static requests. No actual creation request trace; N2 remains for compute. |
| Feature mixin adds four behind flag and offsets are verified (232–247) | **CONFIRMED current construction** | Source and independent Java tests. Historical startup failure/wrong-offset experiment logs absent: **REFUTED as retained measurement**. require=0 does not prove injection happened. |
| int64 compute readback equals 0x0123456789abcdef on family 3 (249–257) | **CONFIRMED as retained report; REFUTED as fully bound native execution** | Compute JSON retains matching values but no device identity. It does not exercise fragment/vertex stores or nonzero firstInstance indirect drawing, so it cannot alone prove all four features. Zero diagnostics/same-run gates unproven. |
| Borrowed VkContext/device/instance ownership and queried derived state (265–273) | **CONFIRMED source-level, subject to N2/B5** | Constructor re-queries state and destroy skips borrowed device/instance. It creates its own command pool; no allocator-adoption API is demonstrated. |
| Graphics queue explicitly has GRAPHICS+COMPUTE (275–279) | **CONFIRMED explicit source check** | VkContext.java:437–450. The general assertion “graphics-capable family must support compute” is unnecessary and not established by this run; rely on the actual explicit check. |
| Voxy VkBuffer/shader/queue write-read proof on adopted MC device (281–286) | **CONFIRMED as retained adopted report; REFUTED as independently certified original run** | Its device matches retained checkpoints, but original cited run absent and no candidate/log binding. |
| Depth near/far exact lifecycle counts and zero rejected pixels; proves test AND write (288–299) | **REFUTED** | Raw images absent, B4 false acceptance, and near-write failure is observationally indistinguishable even with ideal images. |
| Clean child-object shutdown and corrected y negation measured (301–311) | **REFUTED as proven measurements** | Current shutdown/coordinate source exists; original failure/success logs and alternate-orientation frames absent. B5 remains. |
| Real loader/import/auto-binding/tracker/barrier/buffer fixture (315–321) | **CONFIRMED source and fresh independent-device test** | McNativeRealShaderProbe.run uses these layers and index_probe/resolveQuad. This is not complete terrain rendering. |
| 189 native ordinals, CPU agreement, unchanged sources, zero diagnostics and all lifecycle gates in same run (323–327) | **CONFIRMED as 189/zero-mismatch retained report; REFUTED as independently certified full native run** | No device identity in shader report or source/gate/log proof. Fresh core-device test does not establish native KHR execution. |
| Core/KHR dispatch repair and unchanged 313-test suite (331–341) | **CONFIRMED source/core-device suite** | rg finds the three direct core calls only in VkCmd's guarded branches; fresh 313 total. Original native NPE/KHR branch run not independently retained. |
| Destruction now follows completion/cache order (344–352) | **Partially CONFIRMED successful client-stop order; REFUTED universal safety** | releaseAdopted has the successful idle guard; B5 still has unguarded cleanup. Original two diagnostics and clean shutdown absent. |
| Probe runnable without Minecraft (354–359) | **CONFIRMED** | Fresh McNativeRealShaderProbeTest succeeds without Minecraft. Claimed historical one-iteration diagnosis is not retained. |
| New whole-image MC copy after marker, orientation-independent relationship check (368–380) | **CONFIRMED route; REFUTED adequacy/measurement claims** | Current source and MC bytecode support the intended copy timing. Neither original zero-count readback nor raw final pixels/spans is retained; B4 shows inadequate relationships. |
| Third-draw absence fails (382–385) | **REFUTED** | Independent control draw survives omission of tested draw. |
| Required proofs / adopted identity (387–392) | **Partially CONFIRMED; REFUTED comprehensive binding** | B3 direct counterexamples. |
| No native device effects without flags (394–398) | **CONFIRMED with Java-bookkeeping limit** | B2. |
| All unconfirmed waits now leak rather than destroy (400–403) | **REFUTED** | B5 real-shader/tracker/marker residual paths. |
| New passing run has counts 9888/6528/3249/0, null note, all gates green and zero diagnostics (405–411) | **CONFIRMED counts/null note as JSON contents; REFUTED green/clean/candidate-bound evidence** | Eight hashes match; environment/marker cannot replay; no raw readback/log/summary. |
| Smaller repairs and counterexamples prevent drift (413–421) | **Partially CONFIRMED, broader claim REFUTED** | Sections 2–3: N1/N2 incomplete, N4/specified N5 bounds repaired, faulty historical counterexample tests. |

The document's introductory “Nothing here is implemented” and proposed-first-step prose also lag the existing diagnostic implementation. This is documentation drift, not completion of native terrain.

## 5. Would I ship this for normal play?

**GL backend, with all native flags unset: yes for this reviewed change's flag confinement.** No new native diagnostic GPU/device/file effect is reachable, and the established GL selection remains. This is source-based judgment; no fresh GL gameplay/parity session was run.

**Minecraft Vulkan backend, with all native flags unset: yes for safety of the dormant diagnostic additions.** The previous unconditional native probe and exit idle wait are gone. Voxy LoD remains disabled on this backend; shipping safely dormant code is not shipping the owner's native LoD goal.

**Opted-in native diagnostic configuration: no.** B3/B4 can falsely certify it, B5 leaves unsafe failure cleanup, and B1 prevents independent native acceptance. The machine field safe_for_normal_play=true is deliberately scoped to both requested no-native-flags configurations. It is not terrain/integration acceptance or a claim of comprehensive gameplay certification.

## 6. Reproduction and what could not be checked

The deterministic gate experiments use the committed fixture helpers; they launch no Minecraft process. For example:

    python3 - <<'PY'
    import sys
    sys.path[:0] = ['scripts/tests', 'scripts']
    import test_marker_gate as t
    p = t.ProofFileGateTest()
    print(p.run_gate({'native-adopted-context.json': {'device': None}})['success'])
    print(p.run_gate({'native-real-shader.json': {
        'notes': ['FAILED: fence never completed'], 'firstMismatch': 'ordinal 0 wrong'
    }})['success'])
    frames = {s: t.frame() for s in t.STAGES}
    frames['nether'] = t.frame(box_fill=t.REJECTED, control_fill=None)
    r = t.MarkerGateTest().run_gate(frames, t.report())
    print(r['success'], r['frames_without_level_content'])
    PY

Observed: true, true, and true with nether skipped.

For the Java relationship experiment, reflection accessed McNativeMarkerDraw$Span's private five-int constructor and McNativeMarkerDraw.relationshipProblem(Span,Span,Span), instantiated the exact tuples above and invoked the method. Runtime classpath was build/classes/java/main, the cached minecraft-merged.jar and cached dependency jars. This executed Java classification relationships only; it performed no Vulkan initialization or draw. All three tested sets returned null.

Unavailable or deliberately excluded:

- No native/live hardware replay: explicitly forbidden by this review request. Consequently no new Minecraft-Vulkan visual, lifecycle, GUI-timing, shader-KHR, native validation or shutdown acceptance.
- Original cited build runs, screenshots and raw readback are not committed. Their hashes cannot reconstruct them.
- No native.log/summary/source snapshot binds the retained run to 7a769ad5 or proves validation settings and shutdown diagnostics.
- No GPU timeout, device-loss, allocation-error, callback-registration-error or device-replacement injection. B5/error-leak findings follow source control flow.
- No multi-GPU/name-collision or alternate target-format/large-display experiment.
- No ordinary GL or flagless Minecraft-Vulkan gameplay soak. Normal-play judgment is confined to reviewed source reachability.
- No real Voxy native terrain, scene-depth integration, world-pressure recovery or complete renderer-stack acceptance. The retained result correctly keeps BLOCKED_UNIMPLEMENTED.

Only the requested review Markdown and machine verdict were written.

