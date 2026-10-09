# Current state

Living source of truth. Historical `docs/*.md` reports are immutable evidence.
Updated 2026-10-04 on macOS arm64 / Apple M4 Pro. The 2026-09-22 audit and
context-review remain the baseline; fixes and current verification are described below.
See [testing.md](testing.md) and [harness.md](harness.md) for commands and limits.

## Project goal and development direction

Owner decision, 2026-10-04: Voxy must work with **Minecraft itself running Vulkan**
(`Prefer Vulkan`), through MoltenVK on macOS, without a runtime GL dependency.
Read [project-goal.md](project-goal.md) before planning further work. New integration
must target that route; do not expand the GL-hosted probe into a finished Mac product.

Native integration remains unimplemented. The working pipeline and live evidence
below describe the existing GL-hosted diagnostic route. They are reusable baseline
evidence, not acceptance of the project goal. Next priority is a minimal connection
to Minecraft's Vulkan device, color/depth targets and submission/resource lifetimes.

The API basis for the next priority is surveyed in [vulkan-native-integration-survey.md](vulkan-native-integration-survey.md),
read from the actual 26.2 / Sodium 0.9.2 artifacts rather than from the historical device-sharing survey.

A **diagnostic** native layer exists behind four default-off flags (`voxy.native.probe`,
`.marker`, `.features`, `.adopt`): it reaches Minecraft's own Vulkan device, injects the four
device features Minecraft leaves disabled, records a bounded depth-tested draw into Minecraft's
colour image, and runs Voxy's real shader stack on that device. It is **not accepted**. Four
independent review rounds returned REDESIGN; round 4
([native-integration-review-r4.md](runs/native-integration-review-r4.md)) closed its inertness
and destruction findings and stated the layer is *"not yet sound enough to use as the accepted
foundation for terrain work"*, with terrain investigation permitted as experimental. Round 5 is
and round 6 ([r6](runs/native-integration-review-r6.md)) returned REDESIGN too. Round 6 closed
the last lifetime finding, left the evidence findings open on narrower residuals, and added
three blocking findings against the terrain experiment's gate and lifetime while confirming its
pixel measurement. Nothing here may be cited as an accepted foundation until a round
says so — see [harness.md](harness.md) for the rule.

Behind a fifth default-off flag (`voxy.native.terrain`) there is now an **experiment**: Voxy's
real terrain pipeline, recorded into a pass Minecraft opens over its own colour and depth, comes
out RGB-identical to the same scene drawn by the same renderer on Voxy's own target on the same
device. Round 4 permits terrain investigation as experimental and that is all this is; the input
is synthetic and the probe clears Minecraft's frame to make the comparison meaningful.

Measured and negative, so it does not become a hidden assumption: **Minecraft's scene depth is
not observable through `copyTextureToBuffer`** at the level-render tail. The copy completes and
every pixel of the 1708x960 D32_SFLOAT image is 0.0 (its cause is not established; the direction
was later measured by the ladder, below).
See the survey section of the same name.

Measured behaviourally (2026-10-07), behind a sixth default-off flag (`voxy.native.depthladder`):
a ladder of eight known NDC depths drawn `LESS` with depth writes off against Minecraft's
`LOAD`ed attachment at that same hook were all rejected in the first run. ⚠ That was never a
band-wide bound (round 8: each column tested different pixels) and the linear rungs sat inside
the first block in front of the camera; a conclusion that the hook cannot support coexistence
was drawn from it and retracted the same day. Re-rung at 2⁻¹⁶…2⁻², the ladder found pixels with
**non-zero depth** over clouds in a frame from before the world loaded, where the buffer copy had
reported 0.0. The ladder is now **per pixel** — all thresholds over the same band, a GREATER
complementary control, colour-coded brackets counted exactly from retained crops anchored to a
retained thumbnail of the whole readback, sampled through the session. Measured (18 samples,
run `20261007T020025-032770Z` and later): sky pixels below 2⁻¹⁶, overworld terrain pixels in
(2⁻¹², 2⁻¹⁰], nether pixels in (2⁻¹⁰, 2⁻⁶] — the tail attachment holds scene depth that changes
with the scene; why the buffer copy read 0.0 there is not established. **The Z direction is
measured (2026-10-07): larger depth value = nearer, reverse-Z**, from two straight-down looks at
the same ground from 12 and 108 blocks up, judged by the gate from retained per-pixel counts;
the depth scale is not. The ladder runs in
its own launch inside `--only native` (terrain and marker off, the report says so, the gate
requires it) and that launch is retained and replays. The probe itself still asserts no convention and the
gate forbids claiming it. See the survey sections "Minecraft's depth at the level-render tail,
tested behaviourally" and "Round-8 review repairs, and the per-pixel ladder".

## What works

- **OpenGL renderer** remains the established path when GL capabilities suffice.
  Apple GL 4.1 lacks the compute/indirect-count capabilities, so macOS selects Vulkan.
- **Vulkan offscreen context, buffers, textures, shader compilation/reflection and
  hierarchical rendering** work on this host. The renderer remains diagnostic scope.
  Frame order: previous opaque draw → Voxy-depth HiZ → traversal → prep/cull → table
  generation → temporal/translucent draws → depth resolve → fence wait → CPU request
  service → GL composite. The GL completion wait now runs for all probe modes before
  Vulkan writes shared images, and before shared-image destruction/reallocation.
- **GL↔Vulkan IOSurface interop** now explicitly allocates/binds Vulkan image memory.
  This removes the unbound-image descriptor diagnostic without expanding suppressions.
  The existing 45 offscreen composite checks pass with clean validation on this host.
- **Real model baking/meshing and atlas** use the same CPU upload boundary as GL.
  The real atlas is 12288×8192 with four mip levels; lightmap input remains synthetic.
- **Diagnostic world updates** now use the shared `SectionUpdateRouter`. Ingestion
  threads enqueue mesh/child keys; bounded servicing happens after the GPU fence.
  Block packets are coalesced into up to 64 section snapshots per client tick by
  `VulkanWorldUpdates`, independently of the absent GL `VoxyRenderSystem`.
  Leaving the populated top-level region rebuilds the diagnostic scene at the new
  camera region. This is synchronous rebuilding, not the production async streaming
  architecture.
- **Lifecycle**: active-engine identity is checked before rendering; session end
  shuts down the probe before destroying the Voxy instance.
- **Automated verification**: `python3 scripts/verify.py` runs validation-enabled
  JUnit, interop and a real Minecraft scenario. It creates an isolated world, operates
  it without GUI clicks, saves logs/PNGs/JSON and returns a failure exit code for
  unexpected diagnostics, missing GPU execution, missing evidence or timeouts.
  The test suite has 306 cases including changing-count, visual/recovery, actual SPIR-V
  layout and native-probe regressions; the latest GPU gate observed 305 pass /
  1 documented skip, with no unexpected diagnostics.
  The live scenario has also passed all eleven checkpoints after connecting block
  ingestion and world updates. Logs include completed mesh versions for placement
  and removal. Consult `build/harness/*/summary.json` for exact runtime evidence.

## What's incomplete

- **No production Vulkan integration**: no native Minecraft Vulkan device borrowing,
  no normal Vulkan `VoxyRenderSystem`, no configuration-driven default real scene.
  `VkInteropProbe` still defaults to synthetic `PAIR`; only an explicit development
  property such as `voxy.5c4` selects hierarchy (any property value selects it).
- The diagnostic update path is synchronous, rebuilds the scene when leaving its
  region, and does not reuse the full GL async renderer / render-generation service.
  Rapid travel, sustained edits and long-session performance need further testing.
- **No Vulkan SSAO, generated depth bounds, Iris integration or real lightmap**.
  Within-bucket translucency ordering remains nondeterministic; oversized buckets
  clamp and discard excess quads.
- **Apple-shaped portability**: first physical device/graphics queue selection,
  unified-memory buffer allocation, Metal/IOSurface interop and subgroup-size-32
  assumptions. Instance API is min(loader version, 1.4); enabled device features
  include 1.1 shaderDrawParameters and 1.3 dynamicRendering/synchronization2.
  Timeline semaphores are not enabled. SPIR-V targets Vulkan 1.2.
- **Standalone visual/recovery gates**: `VkVisualRecoveryTest` checks every color and
  depth pixel against a literal analytic opaque fixture, with mirror, missing-draw,
  wrong-color and wrong-depth controls. The runner independently decodes PNGs and
  raw depth, checks three actual 3 KiB arena exhaustion/reclaim/re-request/recovery
  cycles, and retains reference/actual/diff images and allocation metrics.
  Admission uses the same `VkGeometryAdmission` policy as the hierarchical scene.
- **Verification limits**: no pixel-level live GL parity reference (horizontal
  bands are visible in the captured ocean scene; cause unproven), no forced arena
  recovery scenario inside live Minecraft, no full shader byte-layout reflection
  checks. Sync validation still misses descriptor-bound SSBO hazards on this stack.
  Application close without disconnect and long queued-resource lifetime remain
  unproven. Green tests do not settle these gaps.

## Audit defect disposition

| ID | Current disposition | Evidence / remaining limit |
|---|---|---|
| D1 | Fixed for table sizing | Prefix shaders derive section count from prep's current entry count; translucent dispatch uses prep's indirect command. New GPU regression covers 0→1→128→129→140→7→0→140 with deliberately stale host counts, checking opaque, temporal and translucent totals. |
| D2 | Fixed | Depth visualizer advertises GENERAL, matching its actual source layout. Validation-enabled suite is clean. |
| D3 | Fixed | LOAD transitions include previous writes and attachment READ access; depth includes early and late fragment stages. Validation-enabled suite is clean. |
| D4 | Shared admission recovery verified on a bounded standalone GPU fixture | Mark meshing complete only after acceptance; missing data remains retryable; remove the exhaustion early-return; clear accepted markers when geometry is unwatched or dirtied. Production admission now lives in `VkGeometryAdmission`; a 3 KiB real arena forces rejection and eviction, releases capacity, re-requests the same 2 KiB demand and verifies exact GPU color/depth through three cycles. Live-world request scheduling and previous-frame draw references under pressure remain unverified. |
| D5 | Engine/session fixes implemented | Identity checks and session-end probe shutdown are wired; the live harness exercises dimension switching and reconnect. Application-close/reload asset lifetime is not comprehensively proven. |
| D6 | Strict automated gate implemented | Runner checks per-test XML and setup/teardown output, rejects unexpected skips, requires active validation and the passing fill-buffer negative control. It scans interop/live output including shutdown. Plain Gradle tasks remain selective; use the runner for the strict gate. The formerly unsuppressed interop memory diagnostic is fixed through actual memory binding. |

The development run also gives Loom explicit main/harness mod roots, and config
serialization scans every mod root; reconnect must not silently reset the storage
configuration because no polymorphic types were discovered. The runner now rejects
Voxy application ERROR lines in addition to validation output.

The non-atomic multi-writer `translucentSlotCount` candidate was also removed:
only invocation zero writes it. Other baseline findings remain: latent GL debug
binding-name mismatch, Vulkan-only queue overflow guards, and missing root LICENSE
in the jar (`LICENSE` vs tracked `LICENSE.md`).

## Current integration status

Backend selection remains GL if capable → else Vulkan only when MC uses GL and real
Vulkan initialization succeeds → else disabled. MC native Vulkan cannot attach to this
interop path. Sodium's CUTOUT pass is still the actual probe entry point.

The three Ubuntu GitHub workflows run ordinary builds without a required GPU lane.
They do not invoke the macOS live harness. Vulkan-dependent tests still skip any
initialization Throwable in ordinary JUnit; the strict runner rejects such skips.
The sole allowed skip is the existing descriptor-SSBO negative control, explicitly
reported as a known gap; the fill-buffer WAW control must actually execute and pass.
