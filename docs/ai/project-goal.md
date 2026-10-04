# Vulkan integration goal

Owner decision recorded 2026-10-04. This is the development direction for the
Vulkan/macOS port. It supersedes treating the GL-hosted diagnostic path as a
finished Mac product. Implementation status remains in [current-state.md](current-state.md).

## Completion target

Minecraft must actually run its own Vulkan backend, selected through `Prefer Vulkan`,
and Voxy must render real LoD terrain within that Vulkan rendering path. On macOS,
MoltenVK translates Vulkan to Metal; it is part of the intended configuration.
The target must work without an OpenGL context, GL texture IDs/casts, or GL composition.

Normal configuration must enable real terrain rendering, and normal play must
support travel, world/block updates, dimension changes, reconnect, resize and
resource reload, with correct lighting/depth and safe GPU resource lifetimes.
Track visual correctness, stability and remaining feature gaps separately from
build/startup success. Do not quote an overall completion percentage without a
defined breakdown against this target.

## Rules for further work

- Direct new Vulkan integration work toward Minecraft's Vulkan device, render
  targets, command submission and resource ownership. New production code must
  not introduce GL calls, `GlTextureView` casts, GL texture IDs, or IOSurface/CGL
  composition as a prerequisite for the Vulkan target.
- Do not expand, optimize or productize the GL-hosted diagnostic integration as
  the next delivery milestone. Do not add a GL fallback requirement to the target.
  Preserve existing diagnostics/evidence for comparison; their existence does not
  authorize further GL-specific development.
- Reuse the shared world/meshing/model boundary and Vulkan shader, traversal,
  culling and table-generation logic. Correctness fixes in this reusable code
  can advance the target. Adapt uploads, descriptors and destruction to the
  actual submission lifetime; current explicit fence waits are not a contract
  supplied by Minecraft's Vulkan backend.
- Preserve the established OpenGL renderer and shared-source invariants. This
  direction does not authorize wholesale removal of GL code or historical reports.
  Existing `gl46/` includes and shared Java classes may contain reusable logic;
  a path name alone does not make that logic an unwanted runtime GL dependency.
- Evaluate proposed Vulkan work against this goal before implementing it. Historical
  phase plans and the existing GL-hosted repair loop do not override this direction.

## Next implementation priority

Establish a minimal real connection to Minecraft's Vulkan backend before expanding
the diagnostic path: access the active device and color/depth targets, record a
bounded Voxy draw at the correct render point, and verify submission ordering,
image-state ownership and resource lifetime on MoltenVK. Use those results to
decide which existing controller/resource code to adapt.

The historical [device-sharing survey](../phase6-device-sharing-survey.md) is
investigation, not implemented or accepted integration. Recheck its API and
submission assumptions against the actual Minecraft/Sodium candidate. Its line
estimates are not a verified migration effort or completion percentage.

This document records direction. Native integration remains unimplemented;
the minimal connection and its acceptance evidence are future implementation work.

## Required evidence

- Record the exact candidate, actual Minecraft graphics backend/device, Voxy's
  integration route and active validation settings. `Prefer Vulkan` is a preference:
  setting it, or observing `VoxyClient.Backend.VULKAN`, alone is insufficient proof.
  A run that falls back to Minecraft OpenGL cannot pass native integration acceptance.
- Demonstrate Voxy terrain with correct color/depth composition while Minecraft
  itself uses Vulkan, then exercise normal-play updates and lifecycle transitions.
  Retain images, diagnostics and resource-lifetime evidence with explicit gaps.
- Keep offscreen Vulkan tests, GL interop checks, GL-hosted live smoke results and
  Minecraft-native-Vulkan integration results separately scoped. Passing any of
  the first three does not complete the fourth.

The runner's usual live stage writes `preferredGraphicsBackend:"default"` and checks
Voxy's diagnostic probe. Its `--only native` stage instead observes Minecraft's actual
Vulkan device and native image/view handles through an isolated world lifecycle,
while Voxy remains disabled. These are environment observations, not native LoD
integration acceptance. `--only required` retains an explicit `INCOMPLETE` verdict
for the missing native rendering, reference pixels/depth and pressure/lifetime
requirements. See [testing.md](testing.md) and [harness.md](harness.md).
