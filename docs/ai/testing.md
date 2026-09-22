# Testing

Verification levels, current commands, and what each one actually proves. Numbers below
were re-verified on 2026-09-22 (macOS arm64, Apple M4 Pro, Vulkan 1.4, loader API
1.4.357, driver 0.2.2210) and independently re-run the same day by
[context-review.md](context-review.md) — re-run the commands yourself before trusting
exact counts on a different host or after source changes.

## Levels

### 1. Compile / package

```
./gradlew build
```

Runs Java compilation, the default (non-validation) JUnit suite, access-widener
validation, and produces the jar. This is what CI (`.github/workflows/`) runs on
`ubuntu-latest`: the push workflow (`check-does-build.yml`) as
`./gradlew -I init.gradle build`, the PR and manual-artifact workflows as plain
`./gradlew build`. No workflow configures a GPU/validation lane, so this level says
nothing about Vulkan/GPU correctness, only that the code compiles and GPU-independent
logic passes. (The produced jar has no root license file — `from("LICENSE")` vs.
tracked `LICENSE.md`.)

### 2. Default JUnit run (GPU required, no validation layer)

```
./gradlew test --offline --rerun-tasks
```

282 tests total. Last verified: 278 pass, 4 skip, 0 fail/error. The 4 skips are
`VkBarriersTest.{computeToComputeSilencesTheHazard, missingBarrierIsDetected,
conservativeBarrierAlsoCovers, plainBufferHazardIsNowDetected}` — these specifically
need the validation layer to mean anything and are gated on it being present.

### 3. Validation-layer run (the actually meaningful GPU gate)

```
./gradlew test --offline --rerun-tasks \
  -PvkLibname=/opt/homebrew/lib/libvulkan.dylib \
  -PvkValidation=true -PvkSyncEnv=true
```

Last verified (bootstrap audit and independent context review, both 2026-09-22):
**281 pass, 1 skip, 0 fail/error**. The one remaining skip is
`VkBarriersTest.missingBarrierIsDetected`. The two synchronization controls in
`VkBarriersTest` are different tests and must not be conflated:

- `missingBarrierIsDetected()` issues two unsynchronized **descriptor-bound SSBO**
  dispatches, receives zero validation messages on this stack, and then calls
  `Assumptions.abort` unconditionally after the dispatch/readback. It is **skipped
  even with validation enabled**, and cannot pass even if a future stack starts
  reporting the hazard. Its silence is a documented layer limitation, not evidence.
- `plainBufferHazardIsNowDetected()` issues two unsynchronized `vkCmdFillBuffer`
  calls on the same range and **passes by asserting their WAW hazard is reported**.
  This is the positive control proving the sync-validation layer is actually active.
  Its deliberate hazard message is *not* one of the 7 unexpected diagnostics below.

**This run is not clean** despite reporting `BUILD SUCCESSFUL`: it emits **7 unexpected
validation diagnostics**, all attributable to two known defects:

- **D2**: `VUID-vkCmdDraw-imageLayout-00344` ×4, from `VkDepthVisualiseTest` — the
  visualizer's depth source is bound as read-only-optimal but kept in `GENERAL` for
  interop.
- **D3**: `SYNC-HAZARD-READ-AFTER-WRITE` ×3, from `VkHiZDepthSourceTest` — the shared
  `VkRenderTarget.beginRendering(..., null, ...)` helper's LOAD-attachment transition
  omits the required `COLOR_ATTACHMENT_READ` access.

**Do not treat "BUILD SUCCESSFUL" or "N passed" as "clean validation."** Tests check
validation messages selectively (each test scopes its own expected messages), so an
unrelated case can print a diagnostic without failing. See D6 in
[current-state.md](current-state.md#known-defects).

**Known blind spot**: sync validation on this stack does not report hazards for
descriptor-bound SSBO writes (that is why `missingBarrierIsDetected` aborts as
skipped rather than passing). A zero-message result for any descriptor-bound buffer
access is weak evidence, not confirmation of barrier correctness.

### 4. Interop checks (macOS GL↔Vulkan offscreen)

```
./gradlew interopCompositeCheck --offline
./gradlew interopCompositeCheck --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true
```

45/45 checks pass both ways. With validation, 28 interop-metadata messages are reported
and narrowly suppressed (`VkContext.INTEROP_NOISE_VUIDS`: three IOSurface-related
VUIDs, only for registered interop image handles). **The validated run is not clean**:
it also emits **one unsuppressed validation diagnostic** while still printing
`ALL CHECKS PASSED` (observed twice by the 2026-09-22 context review; the bootstrap
audit's earlier "no unsuppressed validation error lines observed" is superseded):

```text
[UNASSIGNED-VkDescriptorImageInfo-BoundResourceFreedMemoryAccess]
vkUpdateDescriptorSets(): pDescriptorWrites[0].pImageInfo[0].image
VkImage 0x750000000075 used with no memory bound.
...
suppressed 28 known interop validation messages
ALL CHECKS PASSED
```

It appears during the C11 depth-visualization path (`InteropCompositeCheck` records
`VkDepthVisualise` against the imported depth image), not the deliberate
missing-depth-attachment control. The checker's exit status depends only on its
`failures` list, not on the validation-message stream — the same selective-checking gap
as D6. The root cause (possibly another external-memory metadata limitation, since
IOSurface-backed images never call `vkBindImageMemory`) is **unproven**; do not
broaden the suppression list to hide it. These are **offscreen, single-operation**
checks; they don't prove multi-frame image reuse/reallocation safety across queued GL
work (see gaps below).

### 5. Minecraft smoke/soak (manual, not automated)

`runClient` launches a real Minecraft client. Available tools:
`glToVkSyncBench`, `interopCompositeCheck` (as a Gradle task or standalone), the
installed Khronos loader/validation layer, and a Lavapipe software ICD for
non-hardware-accelerated coverage. **No automated harness drives this** — a real-world
run (travel, edits, world switches, reclamation under load) has to be done by hand and
its results captured manually (logs/screenshots with hardware and launch properties).
**Neither the 2026-09-22 bootstrap audit nor the context review ran Minecraft** — no
world was opened; the automated tests are offscreen or isolated fixtures. Live-world
evidence is therefore still outstanding (including post-fix verification of the latest
geometry-reclamation crash fixes). Not run as part of routine verification; do it when
a change plausibly affects streaming, world lifecycle, or long-session GPU-resource
lifetime.

## What's covered

Capabilities-without-GL; buffer/texture/context/frame/stream lifecycle; shader
guards/bindings/push-constant reflection; model/node upload and atlas scaling; packed
geometry/position invariants; CPU/GPU merged-table equivalence (synthetic fixtures);
index splitting; temporal visibility; translucent buckets; cull; HiZ/depth/
orientation/projection; traversal queue bounds; GPU timing; allocator
budget/fragmentation/reclamation (isolated, not full request→evict→request cycles).

## What's NOT covered (don't assume these are safe because CI is green)

- Full multi-frame hierarchy→table→draw sequence with a **changing** section count —
  this is exactly defect D1's shape (0→nonzero, growth, shrink across dispatch
  boundaries, rapid camera turns).
- Request→evict→re-request lifecycle in a running world; exhaustion→recovery (D4).
- Active-world identity across disconnect/reconnect, dimension switches, resource
  reload, resize (D5's territory).
- Automated per-test validation-message assertions with scoped expected-error lists
  (currently manual/selective — this is what causes D6).
- Reflected Java↔shader member offsets/strides/array lengths against real SPIR-V
  reflection (current guards check type/count/visibility, not byte layout).
- Cross-API multi-frame image reuse under **queued** GL load (only offscreen
  single-shot equivalence is tested).
- Real-world color/lighting/fog/SSAO/GL-parity comparison on a live scene.
- Persistence/storage: malformed or corrupted data, importer edge cases, mapper
  migration, concurrent ingestion/save/service-failure paths.
- Fresh-dependency-cache builds and the installed mod/loader/native-packaging matrix
  (offline/cached builds only prove the current local cache resolves).

## Adding a test

- Vulkan-dependent tests go under `src/test/java/me/cortex/voxy/vk/` and should use
  `VulkanTestSupport.requireVulkan()` to skip when no GPU is available — but see the
  [constraints.md](constraints.md) note that this catches *any* `Throwable`, not just
  missing hardware, so a genuine regression can masquerade as a skip.
- If a test intentionally provokes a validation-layer hazard as a negative control,
  scope its message-check narrowly (see existing `VkBarriersTest` negative controls) —
  a blanket "no validation messages" assertion would break every deliberate negative
  control in the suite.
- `TestNodeManager` (in `src/main/java/.../hierachical/`) has manual `main()` methods
  and is **not** part of the automated JUnit suite despite living under `src/main` —
  don't assume it runs in CI.
