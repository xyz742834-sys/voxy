# Current state

Living status doc. Update this when behavior changes — not the historical `docs/*.md`
phase reports, which are frozen evidence (see [repo-map.md](repo-map.md)).

Last verified: 2026-09-22, on `vulkan-macos` @ `ad54dd8d` (macOS arm64, Apple M4 Pro,
observed device API 1.4.357, driver 0.2.2210). See [testing.md](testing.md) for exact
commands. Corrected 2026-09-22 against the independent
[context-review.md](context-review.md) (reviewed at `d476f559`, re-reviewed at
`d6955b69`): interop validation result, barrier-control naming, device-feature claims,
CI attribution, frame order, CI-skip wording (RR1/RR2 applied after the re-review).

## What works

- **OpenGL renderer** (`VoxyRenderSystem`, `NormalRenderPipeline`, `MDICSectionRenderer`,
  `AsyncNodeManager`): the established path, selected whenever GL capabilities suffice.
  Apple GL 4.1 lacks `glDispatchComputeIndirect` and indirect-count, so this path is
  never selected on macOS (`VoxyClient.initVoxyClient()`).
- **Vulkan context, buffers, textures, shader compile/reflect**: `VkContext` creates its
  own instance/device (first physical device; one queue from the first family with
  `VK_QUEUE_GRAPHICS_BIT`, also used for compute — no explicit combined-capability
  check). The instance requests `min(loader-reported instance version, 1.4)`, so
  "Vulkan 1.4" is the observed host, not an enforced minimum. Device creation enables
  Vulkan 1.1 `shaderDrawParameters`, Vulkan 1.3 `dynamicRendering`/`synchronization2`,
  and base `multiDrawIndirect`/`drawIndirectFirstInstance`/`shaderInt64`/
  `fragmentStoresAndAtomics`/`vertexPipelineStoresAndAtomics`. **Timeline semaphores
  are not enabled** (source comments mention them; submission uses a fence).
  `SpirvCompiler`/`VkShader` compile and reflect shaderc-built SPIR-V (Vulkan 1.2
  target). Builds and passes tests on this host; behavior on lower-version devices is
  unverified.
- **Vulkan hierarchical rendering pipeline**, end to end, in the recorded order of
  `VkHierarchicalScene.record()`: previous-table opaque draw → current Voxy-depth HiZ
  → hierarchy traversal → prep → raster cull (CULL mode only) → merged/temporal/
  translucent table generation → temporal opaque draw → translucent draw → depth
  resolve/reprojection → GL composite through IOSurface. HiZ is built from the
  previous-table opaque pass *before* traversal, not after the temporal/translucent
  draws (source order; see the frame sequence in
  [architecture.md](architecture.md#frame-sequence-hierarchical-mode)). Runs as a
  synthetic-scene / diagnostic path, not the default renderer — see gaps below.
- **GL↔Vulkan interop**: IOSurface-backed color (BGRA8) and depth (R32F) sharing,
  fence-based Vulkan→GL synchronization, GL state-preserving composite. 45
  `interopCompositeCheck` checks pass, with and without validation layers — but the
  validated run also emits **one unsuppressed validation diagnostic**
  (`UNASSIGNED-VkDescriptorImageInfo-BoundResourceFreedMemoryAccess`, during the C11
  depth-visualization path) that the checker's pass/fail result does not consider. See
  D6 and [testing.md](testing.md#4-interop-checks-macos-glvulkan-offscreen).
- **CPU geometry reclamation**: bounded reclamation with fragmentation-aware
  `canFit`/`canAlloc`, protection for top-level/requesting nodes. Unit-tested; not
  confirmed against a real, running-world reclaim/evict/re-request cycle (see D-numbers
  in [current-state.md#known-defects](#known-defects) — mainly D4).
- **Real model baking/meshing** and the real texture atlas (12288×8192, 4 mips) feed the
  Vulkan path via the same CPU boundary (`ModelUploadTarget`/`NodeUploadTarget`) the GL
  path uses.

## What's incomplete

- **No production integration.** There is no Minecraft-native-Vulkan device borrowing,
  no ordinary `VoxyRenderSystem` for Vulkan, and no configuration-driven default real
  scene. `MixinLevelRenderer` never constructs a Vulkan `VoxyRenderSystem` (its log
  string still says "not wired yet" — that message is stale in isolation; the actual
  Vulkan entry point is `MixinDefaultChunkRenderer.doRender()` calling
  `VkInteropProbe.composite()` at Sodium's CUTOUT pass). Scene selection is controlled
  by development system properties (`voxy.5c4`, `voxy.5c1e`, …), not normal mod config.
  `VkInteropProbe.resolveMode()` defaults to `PAIR` (a synthetic two-object scene); only
  an explicit `-Dvoxy.5c4=...` selects `HIERARCHICAL` (real traversal) — and any
  presence of that property selects it, including `-Pvoxy5c4=off`.
- **Streaming/world updates are not connected.** The Vulkan path meshes synchronously
  against a fixed initial top-level region with a local watcher map. It does not use the
  full async render-generation/update-router/visible-chunk system the GL path uses.
  Behavior for traveling, edits, new chunks, and world changes is unverified.
- **No Vulkan SSAO, no generated depth bounds, no Iris support** (Iris integration is an
  intentional stub, `IrisUtil`). Lightmap input on the Vulkan path is synthetic
  (`fillSyntheticLightmap` writes white) — no real per-block lighting yet.
- **Translucency ordering is nondeterministic** within a bucket; oversize buckets clamp
  (drop excess quads) rather than split.
- **Portability is Apple-shaped by design**, not yet generalized: first-physical-device
  selection, unified-memory (`DEVICE_LOCAL|HOST_VISIBLE|HOST_COHERENT`) buffer
  allocation, Metal/IOSurface interop, and subgroup-size-32 assumptions. The Ubuntu
  workflows in `.github/workflows/` configure no required GPU/validation lane.
  Vulkan-dependent tests can skip when `VkContext.init()` fails inside
  `VulkanTestSupport.requireVulkan()` (there is no unconditional Linux/Ubuntu skip),
  so a green build does not establish that GPU tests executed or that Vulkan/interop
  is correct. No CI runtime logs have been inspected to confirm either outcome.
- **GPU object lifecycle/diagnostics are thin**: buffer/texture debug naming is TODO;
  shutdown/reload/session teardown is unproven (see D5).

## Known defects

Carried forward from `docs/ai/bootstrap-audit.md` (2026-09-22 baseline), each
independently re-checked against current source at time of writing. Full detail,
evidence, and source anchors are in the audit; this is the persistent summary.

| ID | Defect | Severity | Status |
|---|---|---|---|
| D1 | `VkHierarchicalScene.prepare()` reads `lastDrawnSections` *before* resetting traversal, but `record()` passes that stale host count into `VkMergedTableBuilder.recordAfterPrep()`, which sizes `translucent_gen` dispatch and both prefix shaders from it — while `prep.comp`/`cmdgen.comp` use the current GPU section count. Counts disagree whenever the selected section count changes frame-to-frame (confirmed with a standalone probe: growing 0→9 emits 0 indices for 189 real quads). | High | Open, source-confirmed + bounded GPU reproduction |
| D2 | `VkDepthVisualise` binds its source depth as `SHADER_READ_ONLY_OPTIMAL` via `VkAutoBindingShader.texture()`, but `record()` keeps it in `GENERAL` for interop — `VkDepthVisualiseTest` passes while emitting `VUID-vkCmdDraw-imageLayout-00344` on all 4 cases. | High | Open, observed |
| D3 | The shared `VkRenderTarget.beginRendering(cmd, null, clearDepth)` helper treats a null color attachment as LOAD but only transitions for `COLOR_ATTACHMENT_WRITE`, not `_READ`. `VkHiZDepthSourceTest` emits 3 `SYNC-HAZARD-READ-AFTER-WRITE` diagnostics across its 2 passing cases. The same helper also selects depth LOAD for `clearDepth == null` while its depth transition advertises only `DEPTH_STENCIL_ATTACHMENT_WRITE` — a source-level sibling of the observed color case, no diagnostic reproduced for it yet. | High | Open, observed (color); source-level only (depth) |
| D4 | `serviceRequests()` adds a position to `pendingMesh` *before* `meshOne()` succeeds; a null return (missing section) never clears it, so a re-request is silently skipped as "already meshed." `geometryExhausted` similarly latches with no retry trigger once set. | High | Open, source-confirmed control flow |
| D5 | `VkInteropProbe` is a singleton whose `shutdown()` has no caller found in the repo. Hierarchy liveness only checks `worldIsLive()`, not engine identity — a stale engine can outlive a world/dimension switch. | Likely | Open, source inspection only |
| D6 | The validation-enabled test run is 281 pass / 1 skip but still emits 7 unexpected validation diagnostics (D2+D3); tests check validation messages selectively, so an unrelated case can emit an error without failing. The same gap applies to the standalone `interopCompositeCheck`: its validated run prints `ALL CHECKS PASSED` while emitting one unsuppressed `UNASSIGNED-VkDescriptorImageInfo-BoundResourceFreedMemoryAccess` diagnostic (C11 path); its result depends only on `failures`, not on the validation-message stream. Root cause unproven; do not broaden `VkContext`'s three-VUID suppression to hide it. | High | Open, observed test-gap (JUnit + interop checker) |

Other established, lower-drama findings (see audit for full list): translucent bucket
truncation is source-confirmed lossy by design; `translucent_gen.comp` has a
non-atomic multi-writer race candidate on `translucentSlotCount` (no corruption
reproduced); `node_outline.vert`/`node.glsl` binding-name mismatch is a latent GL debug
shader compile defect (uninstantiated, unverified); GL `queue.glsl` bounds checks are
`#ifdef VULKAN`-only, so the GL queue has no equivalent overflow guard; the jar task
packages `from("LICENSE")` but the tracked file is `LICENSE.md`, so shipped jars have no
root license file (confirmed still true — see [testing.md](testing.md)).

## Current integration status

- **Backend selection** (`VoxyClient.initVoxyClient()`): OpenGL if capable → else Vulkan
  if Minecraft itself is on OpenGL and a real `VkContext.init()` succeeds → else Voxy
  disables itself entirely. Voxy's Vulkan path requires MC to be on **OpenGL**, because
  color/depth interop is done through GL texture IDs; if MC runs its own native Vulkan
  backend, Voxy cannot attach and disables itself (this was previously an observed
  crash — casting a `VulkanGpuTextureView` to `GlTextureView` — now guarded against).
- **Test suite**: 282 JUnit tests, all under `src/test/java/me/cortex/voxy/vk/`. Ordinary
  run: 278 pass / 4 skip / 0 fail. With `-PvkValidation=true -PvkSyncEnv=true`: 281 pass
  / 1 skip. The one remaining skip is `VkBarriersTest.missingBarrierIsDetected`: it
  issues two unsynchronized descriptor-bound SSBO dispatches, receives zero validation
  messages on this stack, and ends in an unconditional `Assumptions.abort` — it is
  **skipped even with validation enabled** and cannot pass. The working negative
  control is `VkBarriersTest.plainBufferHazardIsNowDetected`, which issues two
  unsynchronized `vkCmdFillBuffer` calls and **passes by detecting their WAW hazard**.
  `interopCompositeCheck`: 45/45 pass with and without validation; the validated run
  also emits one unsuppressed diagnostic (see D6).
- **CI**: three GitHub Actions workflows, all `ubuntu-latest`:
  `check-does-build.yml` (push) runs `./gradlew -I init.gradle build`;
  `check-does-build-pr.yml` (PR) and `manual-artifact.yml` (dispatch) run plain
  `./gradlew build`. None configures a required GPU/validation lane, so CI is not
  evidence of Vulkan/interop correctness — a green CI run says nothing about it.
