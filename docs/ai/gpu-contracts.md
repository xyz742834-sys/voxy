# GPU contracts

Java ↔ shader layouts, descriptor binding rules, push constants, synchronization
expectations, and GPU-object lifetime assumptions for the Vulkan path
(`client/core/vk/`). This is the ABI surface: a change to any of these has to be
mirrored on both the Java-writer side and every shader that reads it, and often on the
GL-side equivalent too (see [constraints.md](constraints.md)).

Verified against current source at `vulkan-macos` @ `ad54dd8d`, 2026-09-22, and
corrected the same day per [context-review.md](context-review.md) (source anchors,
barrier-control naming, frame-order preconditions). When in doubt, the source anchors
listed under each section are the ground truth — this doc summarizes them. Updated 2026-10-04 for table sizing,
attachment LOAD barriers, shared-image memory binding and diagnostic lifecycle.

## Java ↔ shader buffer layouts

| Structure | Layout | Source |
|---|---|---|
| Terrain scene UBO | `mat4 MVP` @0 (64B, column-major) · `ivec3 baseSectionPos` @64 · `uint frameId` @76 · `vec3 cameraSubPos` @80. Written span (`VkSceneUniform.SIZE`) = **92 bytes**; descriptors bind range 92. `VkTerrainResources` allocates 1024 bytes for the buffer — that padding is not evidence of a 96-byte std140 descriptor range; don't assume bytes past 92 are meaningful. | `VkSceneUniform.write`, `lod/gl46/bindings.glsl` |
| Traversal UBO | 208 bytes total: MVP @0, section origin @64, packed HiZ dimensions @76, camera @80, minimum screen size @92, six `vec4` frustum planes @96..191, render-queue limit @192, frame @196, request limit @200, distance @204. | `VkTraversal.writeUniform`/`reset` |
| Packed quad | 8 bytes/quad. Shared format between GL and Vulkan. Geometry offsets are **element indices** — multiply by 8 to get byte offsets for uploads. | `quad_format.glsl`, mesher |
| Section metadata | 32 bytes = two `uvec4`. First: packed position, AABB, geometry start. Second: eight packed 16-bit run counts, in this order: translucent, double-sided, down, up, north, south, west, east. | `BasicAsyncGeometryManager.writeMetadata`, `section.glsl` |
| Block model | 64 bytes: six face words, flags, tint, custom ID, seven padding words. Model color table entries are 4 bytes each. | `block_model.glsl` |
| Node | 16-byte `uvec4`. Packed position in `xy`; 24-bit mesh pointer and 24-bit child pointer in `z`/`w`, with flags packed into the high byte of each. Sentinel values `0xFFFFFF` (null geometry) and `0xFFFFFE` (empty geometry) must stay consistent between `NodeStore` (Java) and `node.glsl`/`VkNodeTree` (GPU-side packing). | `NodeStore.writeNode` (`SENTINEL_NULL_GEOMETRY_ID`/`SENTINEL_EMPTY_GEOMETRY_ID`), `VkNodeTree`, `node.glsl` |
| Queue metadata | Five 16-byte records: `xyz` = indirect dispatch dimensions, `w` = count. Top-level/source/sink node IDs are 4 bytes each. | `hierarchical/queue.glsl` (`NodeQueueMeta`/`NodeQueueSource`/`NodeQueueSink`), `VkTraversal` |
| Requests / render queue | Requests: 8-byte header (`uvec2 requestQueueIndex`) + 8-byte packed positions. Render queue / indirect lookup: 4-byte count followed by 4-byte section IDs. **World-key word order and GPU-position word order differ** — don't assume they're interchangeable. | Declared in `hierarchical/traversal_dev.comp` (`requestQueueStruct`, `renderQueueStruct`), **not** in `queue.glsl`; written by `VkTraversal` |
| Merged entries / prefix | Entry = 8 bytes `(quadStart, drawId)`; dense, face-major, `7 * sectionCount` slots. Prefix buffer = 4-byte entry count + 4-byte exclusive offsets + terminal sentinel. Empty runs legitimately produce equal adjacent prefix values — **do not** re-add a strict-monotonicity check; that was an earlier design, deliberately replaced (see [current-state.md](current-state.md) superseded-decisions note in the audit). | Generated on the GPU by `vk/cmdgen.comp` (entries/counts) and `vk/merged_prefix.comp` (in-place prefix); sized/allocated by `VkTerrainResources`/`VkMergedTableBuilder`. `VkGeometryFlush.flush` uploads geometry and section metadata, not merged entries. |
| Indirect commands | Indexed draw = 20 bytes: index count, instance count, first index, signed vertex offset, first instance. Compute dispatch = 12 bytes. Shared quad-index buffer capacity defaults to `1<<20`; opaque/temporal draws split beyond that. | `quad_index.glsl`, `cmdgen.comp` |
| Visibility / translucent | Visibility = 4 bytes/section: low 31 bits = frame, high bit = previous visibility. 1024 translucent buckets; slot list has a count header; stats track max bucket-quad count and clamped-bucket count. | `VkTerrainResources`, `translucent_gen.comp` |

Texture atlas: the real atlas is a **256×256 grid of model tiles; each tile is 48×32
texels at mip 0** (six 16×16 face cells laid out 3×2 per model), giving 12288×8192 with
4 allocated mip levels (~534MB decimal RGBA mip storage, consistent with those four
levels). Synthetic test atlas is 768×512 (same 256×256 grid, 1-texel face cells).
`ModelAtlasLayout` (Java) centralizes these numbers (`TILES`, `FACE_COLS`/`FACE_ROWS`,
`FACE_TEXELS`), but the shader-side `1.0/256.0` constant is a **separate ABI surface** —
a dimension change must be checked in both places. Note the production comment names a
`ModelAtlasLayoutTest` that does not exist; related atlas tests do.

**Verify by reflection, not by trusting the Java constant.** `VkShader`/`SpirvReflect`
reflect descriptor types/counts/stage visibility from the compiled SPIR-V, but do
**not** compare Java struct offsets against reflected UBO/SSBO member layouts. A
passing build does not prove a Java writer and a GLSL reader agree on byte layout —
only a passing runtime test with actual data does.

`ShaderMemoryLayoutTest` now independently decodes real compiled SPIR-V decorations
for terrain/traversal uniforms, node/request/render arrays, and shared
DrawCommand/SectionMeta/BlockModel layouts. Its four passing tests include a changed
shader-member control. This verifies the listed shader offsets/strides against the
host packing contract; it does not instrument every host writer or cover every
buffer in this document. The analytic GPU pixel/recovery tests separately exercise
actual terrain data through the production upload and draw path.

## Descriptor binding rules

- **Binding numbers are pipeline-local**, not a global resource-ID space. The same
  number means different things in different pipelines.
- **All current pipelines use descriptor set 0.** `VkShader` rejects a nonzero
  descriptor set and rejects type/count collisions at build time.

| Pipeline | Bindings |
|---|---|
| Terrain vertex/fragment | 0 scene UBO · 1 quads · 2 depth-bound sampler · 3 models · 4 model colors · 5 position scratch · 6 light sampler · 7 atlas sampler. Merged draws additionally use 10 (entries) and 11 (prefix). |
| Table generation | 0 scene UBO (where reflected) · 3 section metadata · 4 visibility · 5 section lookup · 6 position scratch · 10 merged entries · 11 merged prefix · 12 dispatch command · 13 opaque draws. |
| Translucent/temporal generation | 14 buckets · 15 translucent section list · 16 translucent entries · 17 translucent prefix · 18 translucent draws · 19 diagnostics · 20 temporal prefix · 21 temporal draws · 22 cull indirect command. Only the bindings each stage actually needs are declared. |
| Cull | 0 scene UBO · 1 metadata · 2 visibility · 3 section lookup. |
| Traversal | 0 HiZ sampler · 1 traversal UBO · 2 requests · 3 render queue · 4 nodes · 6 queue metadata · 7 source queue · 8 sink queue · 9 render tracker · 10 overflow diagnostics. **Binding 5 is no longer a Vulkan descriptor** — don't reuse it assuming it's free without checking why it was retired. |
| HiZ / depth resolve / depth visualizer | Source sampler at binding 0, in their own independent pipeline layouts. |

`VkAutoBindingShader` supports typed buffers/images, explicit ranges, dynamic SSBO
offsets, and a push-constant shadow buffer. `VkDescriptorSetGroup` pre-builds three
traversal queue variants and per-mip HiZ variants specifically to avoid mutating
descriptors between recorded dispatches; submission-generation checks reject many
in-flight descriptor updates. `unboundBindings()` exists as a diagnostic but is **not**
automatically asserted inside `bind()` — call it explicitly if you need the check.

`VkDepthVisualise` now uses an explicit GENERAL sampled-image descriptor layout,
matching its externally shared source. Other callers keep the default read-only
layout. Descriptor declarations do not perform transitions; actual image state is
still caller-managed. This fixes baseline D2 without changing GL/shared shaders.

## Push constants

| Shader path | Bytes / layout |
|---|---|
| Traversal queue index | 4 bytes @0, flushed once per traversal iteration. |
| Merged opaque/temporal prefix | 12 bytes: legacy/reserved host count @0, quad capacity @4, max draw slots @8. Current section count is derived from prep's GPU entry count / 7; the host slot is ignored. |
| Translucent prefix | 4 bytes: quad capacity @0. |
| Depth reprojection variant | 128 bytes: inverse source MVP @0, destination MVP @64. The non-reprojecting variant needs neither. |
| Index probe (test-only, `vk/index_probe.comp`) | Exactly 4 bytes: `uint totalQuads` @0. Not a rendering dependency. |

Device limit observed on this host (bundled and loader paths both): **4096 bytes**.
`VkContextTest` currently asserts this exact value (and subgroup size 32) — treat that
as **this device's** capability, not a portable assumption; a different GPU/driver can
legitimately report a smaller `maxPushConstantsSize` (a 256-byte loader-path failure
was previously observed historically, though it didn't reproduce on this host/session).
Cleaner/scatter/legacy-GL-postprocess shaders still use default-block uniforms, not
push constants — some of those paths are bypassed on Vulkan (CPU reclamation/direct
mapped uploads, dedicated Vulkan depth resolve) rather than ported; SSAO in particular
is unported to Vulkan, so its historical "six-matrix/384-byte" push-constant
requirement is hypothetical, not implemented.

## Synchronization / barrier expectations

Implemented barrier helpers: compute→compute, compute→indirect, consumer-specific
indirect-draw barriers, conservative `ALL_COMMANDS` barriers, per-mip image
transitions, upload/copy visibility barriers, mapped-readback barriers, and
submission/fence tracking. Hierarchical table/cull/terrain objects use
`CONSERVATIVE` explicitly; traversal uses compute→indirect barriers. Narrower barrier
variants exist mainly as test/comparison paths — **do not replace a `CONSERVATIVE`
barrier with a narrower one without validation-layer evidence it's still correct**
(see [constraints.md](constraints.md)).

The dependency chain that must stay correct:

```
host writes                    -> shader reads
previous opaque indirect/vertex reads -> table overwrite      (WAR)
prep                            -> indirect dispatch / cull reads
fragment visibility writes       -> compute
prefix writes                   -> indirect / vertex reads
depth attachment                -> HiZ
each HiZ mip                    -> next mip
HiZ                             -> traversal
resolve                         -> interop consumption
```

A mechanical translation of GL barrier bits does not prove any of these edges —
they've been individually reasoned about and partially validation-tested.

**Current verification and remaining blind spot** (2026-10-04): the validation-enabled
suite has 298 cases, 297 pass / 1 documented skip, with no unexpected diagnostics.
The 45 offscreen interop checks also pass with clean validation. `scripts/verify.py`
adds a strict output gate and independent analytic PNG/raw-depth checks; plain Gradle tasks still check validation selectively.

- Baseline D2 was fixed by declaring GENERAL for the visualizer's depth source.
- D3 was fixed with attachment READ access, previous-write visibility for LOAD, and
  both early/late depth stages. Clear operations retain execution ordering for WAR.
- D1 was fixed by deriving opaque/temporal face boundaries from prep's GPU entry
  count and using its indirect dispatch for the translucent subset. The new test
  spans growth/shrink and the 128-thread dispatch boundary with stale host counts.
- The interop unbound-image diagnostic was fixed with actual Vulkan memory allocation
  and binding. IOSurface backing remains shared; the equality tests continue to pass.
  Existing narrow suppressions were not expanded.
- Descriptor-bound SSBO hazards remain invisible on this stack. The corresponding
  negative control skips and is reported as a known gap; the deliberate fill-buffer
  WAW control must execute and detect its hazard. Zero SSBO messages is weak evidence.

Additional source-level risks not yet runtime-verified (see
[current-state.md](current-state.md) and [testing.md](testing.md) for what test
coverage would close each):

- `VkHiZ.record()` transitions intermediate mip levels for fragment reads, but only
  the *final* mip explicitly includes the compute consumer — visibility of every mip
  to traversal needs checking, especially without a surrounding broad barrier.
- GL completion is now awaited before shared-image writes/destruction/reallocation
  in every probe mode. Long queued-load stress is still not established by the bounded
  live smoke scenario.
- Fence completion protects execution lifetime but not **logical** references — next
  frame's opaque pass draws from the *previous* frame's table, which can reference
  geometry that CPU reclamation has since changed. This needs multi-frame eviction
  testing, not just fence-wait correctness.
- `free()` methods and raw mapped addresses are not universally guarded against
  in-flight or after-free use. The single-frame-in-flight convention (below) is part
  of correctness here, not a removable performance detail.

## GPU lifetime assumptions

These are the **current diagnostic path's** assumptions. The target in
[project-goal.md](project-goal.md) uses Minecraft's Vulkan submission/resource
ownership. Do not carry the probe's single-frame explicit-wait or GL interop
contracts into native integration by assumption. Recheck host writes, descriptor
reuse, image ownership, readback and destruction against actual submissions before
enabling overlap; native integration is not implemented here yet.

- **Single command buffer, single fence, single frame in flight.** `VkFrameTracker`
  owns exactly one command buffer + fence. Code that assumes multiple frames can be in
  flight simultaneously is assuming something the current implementation does not
  provide — see `phase6-device-sharing-survey.md` for the (still-unimplemented)
  analysis of what multi-frame-in-flight would actually require (mainly
  resource-ownership/host-write ordering, not per-mip layout-tracking replacement).
  "Single frame in flight" is **not** automatic protection for every host write; the
  exact preconditions are:
  - `endFrame()` submits with the fence and **returns without waiting**.
    `beginFrame()` waits on the fence before resetting/reusing the command buffer, then
    runs frame-begin hooks and drains deferred frees. `waitForFrame()` is a separate,
    explicit caller action (the interop probe calls it right after `endFrame()`).
  - Deferred frees (`freeAtFrameEnd`) are drained only at the **next `beginFrame()`**
    or by `waitIdle()` — not by `endFrame()` and not by `waitForFrame()`.
  - The interop probe writes uniforms and prepares geometry **before** `beginFrame()`
    of the frame that consumes them. That is safe only because the *previous* frame's
    explicit `waitForFrame()` already completed; a caller that skips that wait (or
    writes host memory between `endFrame()` and the next wait) is racing the GPU.
  - Actual per-frame order in `VkInteropProbe.composite()` (HIERARCHICAL mode):
    scene liveness check / uniform + visibility setup → `beginFrame()` → record
    (hierarchical scene, depth resolve, color→GENERAL) → `endFrame()` →
    `waitForFrame()` → diagnostics and `serviceRequests()` (CPU meshing, which reads
    the now-complete request queue) → GL composite of the shared color/depth. CPU
    request servicing happens **before** the GL composite, not after it.
  - Imported (GL-written) depth must be primed `UNDEFINED → GENERAL` **before GL first
    writes it**; otherwise the first frame's GL write is discarded.
    `VkInteropImage.primeLayout()` submits its own frame and waits, and **throws if
    called while a frame is recording** — call it at allocation, as the probe does.
- **Previous-frame draw table + current-frame reclamation is a live hazard, not
  handled by fences.** See the "logical references" point above.
- **`VkInteropProbe` remains a singleton**, but session end now calls shutdown and
  rendering checks active-engine identity. Leaving the populated camera region
  rebuilds the diagnostic scene. These fixes are exercised by the automated live
  scenario; application close without disconnect and resource-pack replacement
  still need dedicated lifetime assertions.
- **Buffers are individually allocated and persistently mapped**, requiring
  `DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT` memory (`VkBuffer`). This is a
  unified-memory assumption; optimally-tiled images still need staging/copy. Don't
  assume a suballocator or a staging-buffer path exists where one doesn't.
- **Host-side section counts can still lag.** `lastDrawnSections` and the reserved
  push-constant slot are diagnostic/compatibility fields; prefix sizing and dispatch
  now use prep's GPU count. New current-frame consumers must use that same source.
- The scene owns the engine dirty callback. Producers only enqueue keys through the
  shared update router; child changes/remeshing/geometry uploads run after fence
  completion. Disposal detaches the callback before freeing GPU objects. Callback
  publication is volatile and invocation captures one reference, so concurrent
  detach cannot null-dereference. This is synchronous diagnostic servicing, not the
  full async GL render-generation service.

## Source anchors

`VkSceneUniform.write`, `VkTraversal.writeUniform`/`reset`, `NodeStore.writeNode`,
`BasicAsyncGeometryManager.writeMetadata`, `VkGeometryFlush.flush` (geometry + section
metadata upload), `VkTerrainResources`, `VkMergedTableBuilder`, `VkHierarchicalScene`,
`VkFrameTracker`, `VkInteropProbe.composite`, `VkInteropImage.primeLayout`,
`VkRenderTarget.beginRendering`; shader files `lod/gl46/bindings.glsl`,
`lod/section.glsl`, `lod/block_model.glsl`, `lod/hierarchical/node.glsl`,
`lod/hierarchical/queue.glsl`, `lod/hierarchical/traversal_dev.comp` (request/render
queue declarations), `lod/vk/cmdgen.comp` + `lod/vk/merged_prefix.comp` (merged
entries/prefix), `lod/vk/quad_index.glsl`, `lod/vk/index_probe.comp`. See
[repo-map.md](repo-map.md) for full paths.
