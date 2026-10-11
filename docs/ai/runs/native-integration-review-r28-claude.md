VERDICT: REDESIGN

Independent review, round 28 (Claude reviewer; the owner has scheduled Codex as the later round of record), 2026-10-12, of `a68cf90ac34e6bce6af9b63946ce7d93b2f56488` in worktree `native-review-r28-claude`. The initial tree was clean apart from the copied round-27 tools (`.agent-run-r27-tools/`, untracked). Production source, tests, build configuration and committed evidence were not changed. Review reproductions are under `.agent-run/r28/`; required command logs are `.agent-run/r28-*.log`. No Minecraft, `--only native` or `--only live` launch occurred. During the run a coordinator opened an exclusive GPU window. By then the only Vulkan workload (the Gradle JUnit run) had finished, and no further GPU test was started.

**Verdict basis.** DELIVERY-BOUNDARY still governs: the native renderer is an opt-in switch (`voxy.native.render`, default false), and normal-play delivery is not accepted. All four round-27 findings are repaired (R27-CONTEXT-DRIFT only partly), and every round-27 counterexample I re-ran is now refused. Two **new blocking defects** were found in the changes since `10856154`:

- **R28-QUEUE-OVERFLOW**: unclamped render-queue count → out-of-bounds GPU writes at the new default capacity.
- **R28-FADE-BLEND**: the composite does not blend, contrary to GL and to its own claim.

Several non-blocking gate and recovery gaps are listed below. **Default-on is not ready.** Safety with every flag unset is confirmed.

Paths: Native = `src/main/java/me/cortex/voxy/client/core/vk/mcnative/`; Vk = `src/main/java/me/cortex/voxy/client/core/vk/`; shaders = `src/main/resources/assets/voxy/shaders/lod/`. Line numbers refer to the reviewed HEAD.

## Required commands

| Command | Result |
| --- | --- |
| `./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true` | Exit 0. 357 tests, 0 failures, 0 errors, 1 skip (`VkBarriersTest.missingBarrierIsDetected`, documented). Context `validation=true syncValidation=true`. The only hazard lines are the deliberate `vkCmdFillBuffer` WAW in the barrier tests. Log: `.agent-run/r28-gradle-test.log`. |
| `python3 -m unittest discover -s scripts/tests` | Exit 0. 335 tests in 998.6 s (Python 3.14.4), all passed (`.agent-run/r28-unittest.log`). |
| `python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261011T163645-918547Z` | Exit 0 (`.agent-run/r28-replay.log`). The run was built from `08a9501d`; `git diff 08a9501d HEAD -- src scripts build.gradle gradle.properties` is empty, and only docs changed since. |

## 1. Round-27 findings

**R27-EMPTY-REBUILD: CLOSED (CONFIRMED repaired).** `.agent-run/r28/empty/EmptyBuildProbe28.java` executes HEAD's unchanged decision block, extracted verbatim from `McNativeHierarchicalLoad.java:489-520`, plus `emptySuppressed` (`:362-365`), against CPU stubs.

- After one empty build with terrain then available, a stationary player rebuilds after **600** calls.
- A resize rebuilds after 60 calls, and so does an atlas-generation change.
- A permanently empty region retries roughly every 600 calls: 11 builds over 6,000.
- Under the ladder the suppression still holds indefinitely, by design (diagnostic budget).

With streaming (`build` → `startStreaming`, `:827-828`), `build()` now returns null only when it throws, so an "empty build" in normal play is in practice a failed build (counted as a problem). Residuals:

- The clock counts render calls, not time (600 calls ≈ 10 s at 60 FPS).
- After three problems the path stops for the session (`FAILURE_BUDGET`, `:428`). It stops without retiring the live scene and without counting the frame anywhere.

**R27-RENDER-DISABLE: CLOSED (CONFIRMED by source).** `render()` checks `VoxyConfig.CONFIG.isRenderingEnabled()` every call (`:402-407`); that covers `available && enabled && enableRendering` (`VoxyConfig.java:117-118`). It then retires the live scene and skips with `rendering-disabled`. The kill switch `voxy.native.disable` lives in `McNativeRender.on()` (`McNativeRender.java:26`), and every product consumer goes through `on()`:

- feature injection (`McNativeDeviceFeatures.java:78`)
- adoption (`McNativeVkContext.java:96`)
- instance mode (`VoxyClient.java:110`)
- hierarchy (`:344`, `:347`)
- lightmap usage (`MixinLightmap.java:22`)

No live menu toggle was performed.

**R27-RENDER-GATE: CLOSED (CONFIRMED).** I ported the round-27 counterexamples to this run: `.agent-run/r28/render_review28.py`, on isolated copies, refreshing only changed or deleted manifest members (results in `.agent-run/r28/render/`). All are now refused with exit 1:

- diagnostic command flag
- contradictory off report
- off-report `atlasCloseFailures=1`
- deleted thumbnails
- appended VUID and Voxy ERROR
- failed own scenario
- one checkpoint
- snapshot moved before its harness stage
- impossible warmup skip of 999,999,999

**R26-LADDER-GUARD-COVERAGE: CLOSED.** Removing `recount_ladder_sample:3559` (whole thumbnail block) and `ladder_z_direction:3676` (descend below ascend) is detected by `LadderGuardCoverageTest` (`.agent-run/r28/r26-ladder-two.json`).

**R24-HIER-GUARD-COVERAGE: CLOSED.** The round-27 `r24_guards.py`, unchanged and rooted at this checkout, removed all 37 shared-helper predicates (`.agent-run/r28/r24-guard-summary.json`, `r24-guards.log`): 37/37 are detected, **36/37 by `HierLoadGateTest`**. The survivor is `real_load_skip_provenance:2197` (`extra` keys). I confirmed by computation that `HIER_LOAD_SKIP_KEYS | HIER_LOAD_EXTENT_KEYS ⊆ REAL_LOAD_SKIP_KEYS`, so the hierarchical gate's own refusal (`verify.py` hier skip check) always fires first; the predicate is unreachable from the hierarchy, as claimed and as the test comment at `test_hier_load_gate.py:298` pins.

**R27-CONTEXT-DRIFT: PARTLY closed (non-blocking residual).** `current-state.md` and `project-goal.md` now describe the experimental native path. `architecture.md:17` still says "That connection is not implemented." `architecture.md:69-71` still says Voxy's Vulkan path "does not borrow Minecraft's device". The survey (`vulkan-native-integration-survey.md:2068-2069`) claims "the composite blends that image with GL's blend", which is false (see R28-FADE-BLEND). The handoff header still names `10856154` as the candidate.

## 2. Defects in the changes since 10856154

### R28-QUEUE-OVERFLOW: CONFIRMED by source, blocking

`d9ef4af8` sizes `indirectLookup`, `mergedEntry`/`mergedPrefix` and the translucent buffers by `renderQueueCapacity = min(maxSections, MAX_QUEUE_SIZE=200000)` (`VkHierarchicalScene.java:461-466`; `VkTerrainResources` `renderQueueCapacity` constructor). The traversal can write a count larger than that capacity:

- **The count is unclamped.** The traversal's `enqueueSelfForRender` checks `renderQueueIndex < renderQueueMaxSize` non-atomically, then `atomicAdd`s unconditionally; only the store is guarded (`hierarchical/traversal_dev.comp:79-84`). So `renderQueueIndex`, which is `indirectLookup[0]` = `sectionCount`, can exceed the capacity by up to the number of concurrent invocations.
- **No clamp anywhere downstream.** `VkTraversal.reset/record` (`VkTraversal.java:213-260`) and `prep.comp:64-78` both use the raw count: `cullInstanceCount = sectionCount`, `cmdGenDispatchX = (sectionCount+127)/128`, `mergedEntryCount = sectionCount*7`.
- **The resulting accesses go out of bounds:**
  - `cmdgen.comp:78-90` reads `indirectLookup[i]` for `i < sectionCount` and writes `mergedEntries[face*sectionCount + i]`, `mergedCounts`, `temporalCounts` and `positionBuffer[i]` beyond their allocations.
  - The cull raster draws `sectionCount` instances reading `indirectLookup[gl_InstanceIndex]` and then writes `visibility[sid]` with garbage `sid`.
- **Reachability.** Each enqueued node carries a distinct geometry id, so the count cannot exceed the number of geometry sections. Before `d9ef4af8` the queue held `maxSections`, so overflow was impossible. Under `08a9501d`'s default (1 << 20 sections, queue 200,000), any frame selecting more than 200,000 meshed nodes overflows. 512 MiB of geometry holds about 200k sections at the measured ~185 quads/section, and GL caps its queue at exactly that number because it is reachable at large render distances.
- **Not exercised.** The retained run peaks at `maxMeshed` 8,420.

GL tolerates the same counter overshoot because it does not index a dense table by it. Natively the result is undefined GPU out-of-bounds writes on Minecraft's device. Clamp `sectionCount` (in prep, or after traversal) to the capacity.

### R28-FADE-BLEND: CONFIRMED by source, blocking

GL's final blit blends Voxy's fogged/faded colour with `glBlendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA)` (`NormalRenderPipeline.java:127-133`), and `voxy_post.frag` reproduces HAS_FADE by scaling alpha. The native composite pipeline does not blend:

- `McNativeComposite.java:57-64` never calls `alphaBlend(true)`; the builder default is `blend=false` (`VkGraphicsPipeline.java:58`, `:114-117`).
- `2fb1737a` did not touch `McNativeComposite`, although its message and the survey say "the composite blends with GL's blend".

Consequences:

- **Visual.** Voxy's render-distance edge (fade from 90% of `sectionRenderDistance*512-32` ≈ 7,344 blocks at the default 16) is drawn opaque, a hard edge instead of a fade. Voxy's fade alpha is also written into Minecraft's colour alpha.
- **The gate enforces the deviation.** The judge compares the composited RGB exactly to the post output's RGB (`McNativeHierarchicalLoad.java:607`, `McNativeTerrainLoad.judge`), so it would *refuse* a GL-faithful blended pixel with alpha < 1.
- **Unexercised.** No judged sample reaches the fade zone: the harness's terrain ends near x 2,700, and the far look's 3,328 beyond-far pixels all have alpha 1. Evidence therefore neither shows nor refutes it.

This deviates from Voxy's behaviour in a feature claimed done.

### Non-blocking findings

**R28-FAILED-FRAME-STATE: CONFIRMED by source (recovery scope).** `VkFrameTracker.endFrame` now closes the recording window before a failing submit, and the unsubmitted fence is no longer waited (`VkFrameTracker.java:173-197`, `:205-209`). That logic is correct for a failed `vkEndCommandBuffer` or `vkQueueSubmit`. Two cases remain:

1. **An exception between `beginFrame` and `endFrame` still breaks the tracker.** For example from `scene.record`, `recordResolve`, `post.record` or `prepareSources` (`McNativeHierarchicalLoad.java:713-729`). It leaves `recording=true`, every later `beginFrame` throws "called twice", and after three `frame-failed` problems the native path is off for the session. That is fail-closed, but not recovery.
2. **A failed submission drops work the scene thinks is done, and the scene is kept.** Work recorded into the dropped command buffer and drained host-side is lost:
   - `bakery.recordUploads` (model atlas tiles, `VkHierarchicalScene.java:779`)
   - the lightmap upload (`lightmapDirty` cleared at record)

   Tiles baked in that frame plausibly stay missing until the scene is retired (PLAUSIBLE; not reproduced).

The injection launch shows Minecraft and Voxy *carry on* compositing (4,963 frames after the failure). It has no ladder, so it does not show the content stays correct. It also injects `VK_ERROR_DEVICE_LOST`, which in reality is unrecoverable; only the control flow is exercised.

**R28-WORKER-LIFETIME: PLAUSIBLE.** `retire()` stops the mesher workers (`:1157`), but nothing calls `retire()` on disconnect or level change. Native work runs only from Sodium's cutout pass, so with no level there are no calls. The `NO_ENGINE` skip (`:460-463`) also does not retire. Up to `MAX_IN_FLIGHT`=512 queued `meshAsync` tasks can therefore run while the world engine shuts down. They check `isLive()` only at task start (`VkRealMesher.meshAsync`). The scene's code comment says workers must stop before the world closes; GL stops its generation service with the renderer on `setWorld`. Also, `stopWorkers` blocks the render thread for up to 10 s.

**Inherited race (not native-introduced).** Workers read `ModelFactory.idMappings` (plain `int[]`, `ModelFactory.java:951`) while the render thread bakes (`:691`). GL's workers share this.

**Stale-result handling: CONFIRMED correct.** Edits polled before results mark in-flight positions stale, and their results are dropped. An edit arriving after the poll is re-meshed next frame.

**R28-LIGHTMAP-FIRST-FRAMES: CONFIRMED by source, minor.** `lightmapSeen` is static (`:217`), while each new scene's resources start with the synthetic full-bright lightmap. `newerThan(lightmapSeen)` stays null until the next readback lands, so every rebuild (resize, reload, dimension change, reconnect) renders its first 1–3 frames full-bright. The lightmap also lags Minecraft's by the readback latency, whereas GL samples the live texture.

**R28-GATE-RESIDUALS: CONFIRMED by replay counterexample, non-blocking.**

- **Unexplained `rendering-disabled` skips are accepted.** A product report claiming 5,000 such skips, with no log or configuration evidence, replays **0** (`render/row-render_rendering_disabled_skips.json`). The reason is in `RENDER_SKIPS` and is not reconciled.
- **The soak does not require compositing per cycle.** A cycle-3 `reconnect` that composited **zero** frames replays **0** (`row-pressure_cycle3_reconnect_composites_nothing.json`), because per-stage growth is summed across cycles (`verify.py` `growth[stage] += …`).
- **A 0.8% per-size growth in cycle 4 passes.** This is within the stated 1% `RENDER_SOAK_SLACK`, so it is a limit, not a defect.
- **Constant self-reports.** `streaming`, `vanillaBound` and `postPass` are constants the Java always writes `true`; they are not evidence of behaviour.

**Composite at the cutout hook: CONFIRMED for ordering and state.**

- **No pass is open at the hook.** javap of Sodium `mc26.2-0.9.2-alpha.3` `DefaultChunkRenderer.render` shows `ShaderChunkRenderer.end` at offset 195, before `createRenderPass` at 246 (`.agent-run/r28/javap/dcr.javap`).
- **Ordering matches GL.** The composite opens its own LOAD/LOAD pass after Minecraft's solid terrain and before cutout and translucent terrain. It writes Voxy's reprojected depth with GREATER_OR_EQUAL and writes on, so later Minecraft draws test against it.
- **No stencil.** Unlike GL's stencil from Minecraft's depth, nothing confines Voxy to clear pixels in ordinary frames; only the vanilla bound (near cut) does. The 12 judged samples hold the clear-only rule.
- **Barriers are as stated.** Voxy's submission precedes Minecraft's on the adopted queue; `prepareSources` uses ALL_COMMANDS → FRAGMENT_SHADER.

**Allocation plateau and 0x0 handling: CONFIRMED.**

- **Per-size rule.** Runs are keyed by the live scene's size. A resize may add at most `2×pixelΔ×4` bytes.
- **0x0 snapshots.** They stay in the current run and cannot start a run.
- **Probe.** A 0x0 snapshot carrying +64 MiB is refused (`row-pressure_zero_size_masks.json`).
- **Retained pressure run.** All 55 snapshots are exactly flat per size (51,421,288 / 592,201,856 bytes at 1708×960; 54,892,648 / 607,520,768 at 1920×1080) across 4 cycles and 21 builds.
- **Guard coverage gap.** The within-run growth predicate (`verify.py:3240`) has no detecting test (see item 3).
- **Product scope.** The product launch at full capacity (733.8 MB buffers) covers a single lifecycle; the soak runs at the pressure capacity (4,096 sections).

**Soak cycle rules: CONFIRMED except the per-cycle gap above.** Removing cycle 4 is refused, and 2% growth is refused. Each snapshot must lie inside its own harness stage and cycle.

**Injection exemptions: CONFIRMED tight.** Exactly one problem with the exact text, one `frame-failed`, one ERROR line, one control hazard plus one control record, and at least 1,000 frames after. Every probe is refused (exit 1):

- a second ERROR line
- a second hazard
- a missing hazard
- `problems=2`
- 999 frames after

A real failure in the same launch would add a problem and be refused. One caveat: the control is identified by message text, not by its buffer handle.

**Broadened validation pattern: CONFIRMED.** It now catches Minecraft's bare-text reports. A bare `(Minecraft) vkCmdDraw(): … hazard detected` line appended to the product log is refused. The pattern is still enumerated text: a validation message that names no `vkXxx()`, VUID or hazard would pass, for example a best-practices or performance warning. Those are not enabled here.

**Far stage: CONFIRMED, weak floor.**

- **Routing.** Far-stage samples go to hierarchical-LOAD (`ladder_expected_consumers`).
- **Requirement.** At least one judged far sample with at least **1** beyond-far pixel.
- **The run.** Draw 3602 holds 3,328, all over Minecraft's CLEAR band, all clamped to depth 2⁻²³.
- **Relabelling.** Moving the far sample to another stage is refused by the ladder cross-binding.
- Removing the far predicate (`hier_load_checks:2840`) is detected by `HierLoadGateTest` (`.agent-run/r28/guards28-far.json`).

**Atlas pool: CONFIRMED exclusive and lifetime-safe.** `take()` throws while lent. `giveBack()` happens at retire; this is safe because Minecraft's composite never samples the atlas, and the next scene's first submission follows `waitForFrame` on Voxy's single in-flight fence (`:684`) or `waitIdleChecked` (`:564`). Shutdown frees it after the idle wait and only when no leaked scene holds it.

**Device change: CONFIRMED.** javap of the 26.2 jar shows `RenderSystem.DEVICE` is private, with its only `putstatic` in `initRenderer`, which throws "already initialized" (`.agent-run/r28/javap/rs.javap:199-209`).

## 3. Guard-removal audit (verify.py)

`.agent-run/r28/guards28.py` removed, one at a time and in memory, every refusing `if` in these functions:

- `native_render_result`, `native_inject_result`, `native_pressure_result`
- `render_off_report_problems`, `render_performance_problem`
- `native_log_problems`, `native_log_checks`
- `hier_stream_problem`

It ran the unchanged `RenderGateTest` and `NativeLogChecksTest`: **57/61 detected** (`.agent-run/r28/guards28-product.json`). Survivors:

- **Equivalent mutants.** `native_pressure_result:2945` and `native_inject_result:3019` (`if not result["success"]: return result`) cannot change the outcome, because nothing later sets success.
- **Real, untested: `native_render_result:3240`** (within-run allocation growth). Its distinct case, growth over 5% inside one run that a later run at the same size returns, is caught by no other predicate.
- **Real, untested: `hier_stream_problem:2531`** (`streaming is not True`, a constant self-report).

Far predicate (`hier_load_checks`, removed against `HierLoadGateTest`): see item 2. R24: 36/37 by hierarchy tests, the last unreachable (item 1).

## 4. Independent recount and thumbnails

`.agent-run/r28/recount28.py` is round 27's `recount.py` repointed to this run; it imports neither the verifier nor the fixtures. It checks:

- 584 manifest hashes, with complete membership
- 442 source fingerprints, all equal to this checkout
- every ladder and coexist sample
- all **12** hierarchical samples, **including the far look (draw 3602)**

Every published counter matches. Totals: **264,862 geometry**, 782,434 no geometry, **23,701 must-show shown**, **241,161 must-hide unchanged**, and zero ambiguous, other or violation pixels. Frames reconcile: 7,215 composited + 528 skipped + 12 handed = 7,755 ladder draws.

Far sample 3602, band 1024×77, all CLEAR:

- 3,328 geometry pixels, all must-show and shown
- all 3,328 reprojections clamped just inside Minecraft's far plane (depth 1.19e-7)
- max reprojection relative error 5.96e-8
- 4,845 anchored thumbnail blocks

Across samples the max error is 4.4e-7. One travel sample has no geometry. Descend and ascend have only must-hide pixels, and reconnect only must-show.

I visually inspected `render/frame-far.png`, `frame-horizon.png`, `frame-reload.png`, `frame-nether.png` and `inject/frame-reconnect.png`:

- **Horizon** shows the stone wall.
- **Far:** the distant terrain is fogged into the sky colour and is not separately visible at thumbnail scale.
- **Reload and reconnect** still show floating rock pillars in the sky (upper right). Round 27 noted the same "floating pieces"; they are unexplained.
- **Nether** is black: 289–293 `fog-covers-all` skips per launch, matching GL's `fogCoversAllRendering` skip.

## 5. Default-on readiness: REFUTED

What still blocks a default change:

1. **Clamp the render-queue count.** R28-QUEUE-OVERFLOW must be fixed before any default with more than 200,000 sections.
2. **Blend the composite as GL does.** Fix R28-FADE-BLEND, adapt the judge to the blend, and retain a sample in the fade zone.
3. **Recover from recording-time exceptions, and retire the scene after a failed frame.** Prove content correctness after injection with a judged sample, not just continued compositing.
4. **Stop the mesher workers on disconnect and level change** before the engine closes, without blocking the render thread for up to 10 s.
5. **Rebuild only the size-dependent resources on resize.** Today a resize retires and re-streams the whole scene (`:482`), which `VkHierarchicalScene`'s own resize contract and GL avoid. Remove the rebuild-on-new-scene full-bright lightmap frames.
6. **Explain the floating terrain pieces** seen in reload and reconnect thumbnails. Keep fade, SSAO (not ported), translucency ordering, night, water and sky references.
7. **Close the gate gaps:** reconcile `rendering-disabled` skips; require compositing per soak cycle per stage; test the within-run growth predicate; strengthen the far floor beyond one pixel.
8. **Fail closed on device features.** `augment` adds features after an offset self-test, and I saw no check against the physical device's supported features. A default-on build must refuse rather than fail device creation on hardware lacking them. Not established here.
9. **Run the soak at the product capacity.** It currently runs at 4,096 sections, while the product default allocates about 1.34 GB of unified memory (owner option B). Time-based rebuild rate limits are still frame-counted.
10. **DELIVERY-BOUNDARY:** normally enabled native LoD and normal-play acceptance, including an independent round of record.

Performance (recorded, no budget judged): product render-thread mean 1.24 ms, p95 1.75 ms, max 26.5 ms; GPU mean 2.70 ms, p95 3.41 ms, max 5.73 ms. Pressure render-thread max 69.8 ms.

## 6. Safety with every voxy.native.* flag unset: CONFIRMED (source and offline)

- `McNativeRender.on()` is false, so `nativeRequested` is false (`VoxyClient.java:109-110`).
- **GL:** selection and log lines are unchanged. The `Capabilities` warn→info change is log level only.
- **Minecraft Vulkan:** the previous "Voxy will disable itself" path still applies and instance mode stays off.
- `MixinLightmap` returns the original usage, and `McNativeBounds.collecting()` is false.
- The Sodium cutout hook takes the native branch only in instance mode.
- Feature augmentation returns the requested set, and adoption returns without a context.
- `MixinLightmap` is a new mandatory mixin applied on every backend. Its target was matched in the retained Vulkan launches; a GL launch was not run.

## What could not be checked and why

- **No Minecraft launch of any kind** (`--only native/live` prohibited), and no further GPU test after the coordinator's exclusive window opened. Hence no live configuration toggle, disconnect race, fade-zone image, queue-overflow reproduction on hardware, or GL-backend launch with the new mixin.
- **R28-QUEUE-OVERFLOW** is established from shader and host source with a reachability argument; it is not observed.
- **R28-FAILED-FRAME-STATE (dropped tiles) and R28-WORKER-LIFETIME** are source-reasoned (PLAUSIBLE).
- **Evidence limits.** Retained evidence is quarter-scale thumbnails plus judged band crops; ordinary frames are not judged per pixel. Editable records and hashes do not authenticate JAR or native-call provenance against coordinated fabrication.
- **Not checked:** MoltenVK `robustBufferAccess` behaviour (which would bound the overflow's damage), and Iris or shader-pack interaction.
