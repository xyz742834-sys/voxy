# Testing

Verification levels, current commands, and what each one actually proves. Numbers below
were re-verified on 2026-09-22 (macOS arm64, Apple M4 Pro, Vulkan 1.4, loader API
1.4.357, driver 0.2.2210) — re-run the commands yourself before trusting exact counts on
a different host or after source changes.

## Levels

### 1. Compile / package

```
./gradlew build
```

Runs Java compilation, the default (non-validation) JUnit suite, access-widener
validation, and produces the jar. This is what CI (`.github/workflows/`) runs, on
`ubuntu-latest` — no Vulkan-capable GPU, so this level says nothing about Vulkan/GPU
correctness, only that the code compiles and GPU-independent logic passes.

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

Last verified (re-run this session): **281 pass, 1 skip, 0 fail/error** — matches the
2026-09-22 audit exactly. The one remaining skip is
`VkBarriersTest.missingBarrierIsDetected`'s counterpart being replaced by the real
negative control now running.

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

**Known blind spot**: the descriptor-SSBO negative control test produces **zero**
hazard messages and is treated as passing — it is not currently proving what it's meant
to prove. A zero-message result from that specific control is weak evidence, not
confirmation of correctness.

### 4. Interop checks (macOS GL↔Vulkan offscreen)

```
./gradlew interopCompositeCheck --offline
./gradlew interopCompositeCheck --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true
```

45/45 checks pass both ways. With validation, 28 interop-metadata messages are reported
but explicitly/narrowly suppressed (documented IOSurface-related VUIDs) — no
unsuppressed validation errors observed. These are **offscreen, single-operation**
checks; they don't prove multi-frame image reuse/reallocation safety across queued GL
work (see gaps below).

### 5. Minecraft smoke/soak (manual, not automated)

`runClient` launches a real Minecraft client. Available tools:
`glToVkSyncBench`, `interopCompositeCheck` (as a Gradle task or standalone), the
installed Khronos loader/validation layer, and a Lavapipe software ICD for
non-hardware-accelerated coverage. **No automated harness drives this** — a real-world
run (travel, edits, world switches, reclamation under load) has to be done by hand and
its results captured manually (logs/screenshots), same as the 2026-09-22 audit did. Not
run as part of routine verification; do it when a change plausibly affects streaming,
world lifecycle, or long-session GPU-resource lifetime.

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
