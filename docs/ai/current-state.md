# Current state

Living status doc. Update this when behavior changes — not the historical `docs/*.md`
phase reports, which are frozen evidence (see [repo-map.md](repo-map.md)).

Last verified: 2026-09-22, on `vulkan-macos` @ `ad54dd8d` (macOS arm64, Apple M4 Pro,
Vulkan 1.4, loader 1.4.357). See [testing.md](testing.md) for exact commands.

## What works

- **OpenGL renderer** (`VoxyRenderSystem`, `NormalRenderPipeline`, `MDICSectionRenderer`,
  `AsyncNodeManager`): the established path, selected whenever GL capabilities suffice.
  Apple GL 4.1 lacks `glDispatchComputeIndirect` and indirect-count, so this path is
  never selected on macOS (`VoxyClient.initVoxyClient()`).
- **Vulkan context, buffers, textures, shader compile/reflect**: `VkContext` creates its
  own Vulkan 1.4 instance/device (first physical device, one graphics/compute queue);
  `SpirvCompiler`/`VkShader` compile and reflect shaderc-built SPIR-V. Builds and passes
  tests on this host.
- **Vulkan hierarchical rendering pipeline**, end to end: hierarchy traversal → raster
  cull → merged-table command generation → opaque/temporal/translucent draws → HiZ →
  depth resolve/reprojection → GL/Vulkan composite through IOSurface. Runs as a
  synthetic-scene / diagnostic path, not the default renderer — see gaps below.
- **GL↔Vulkan interop**: IOSurface-backed color (BGRA8) and depth (R32F) sharing,
  fence-based Vulkan→GL synchronization, GL state-preserving composite. 45
  `interopCompositeCheck` checks pass, with and without validation layers.
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
  allocation, Metal/IOSurface interop, and subgroup-size-32 assumptions. Ubuntu CI
  builds and unit-tests the code but exercises none of the GPU/interop paths (no
  Vulkan-capable GPU lane in `.github/workflows/`).
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
| D3 | The shared `VkRenderTarget.beginRendering(cmd, null, clearDepth)` helper treats a null color attachment as LOAD but only transitions for `COLOR_ATTACHMENT_WRITE`, not `_READ`. `VkHiZDepthSourceTest` emits 3 `SYNC-HAZARD-READ-AFTER-WRITE` diagnostics across its 2 passing cases. | High | Open, observed |
| D4 | `serviceRequests()` adds a position to `pendingMesh` *before* `meshOne()` succeeds; a null return (missing section) never clears it, so a re-request is silently skipped as "already meshed." `geometryExhausted` similarly latches with no retry trigger once set. | High | Open, source-confirmed control flow |
| D5 | `VkInteropProbe` is a singleton whose `shutdown()` has no caller found in the repo. Hierarchy liveness only checks `worldIsLive()`, not engine identity — a stale engine can outlive a world/dimension switch. | Likely | Open, source inspection only |
| D6 | The validation-enabled test run is 281 pass / 1 skip but still emits 7 unexpected validation diagnostics (D2+D3); tests check validation messages selectively, so an unrelated case can emit an error without failing. | High | Open, observed test-gap |

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
  / 1 skip (`VkBarriersTest.missingBarrierIsDetected` is the one intentional-hazard skip
  when validation is off — with validation on it runs and passes as a negative control).
  `interopCompositeCheck`: 45/45 pass, with and without validation.
- **CI**: three GitHub Actions workflows (push, PR, manual-artifact), all
  `ubuntu-latest`, plain `./gradlew build`. No Vulkan-capable runner, so CI never
  exercises the GPU/interop code paths — a green CI run says nothing about Vulkan
  correctness.
