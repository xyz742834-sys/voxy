# GPU contracts

Java ↔ shader layouts, descriptor binding rules, push constants, synchronization
expectations, and GPU-object lifetime assumptions for the Vulkan path
(`client/core/vk/`). This is the ABI surface: a change to any of these has to be
mirrored on both the Java-writer side and every shader that reads it, and often on the
GL-side equivalent too (see [constraints.md](constraints.md)).

Verified against current source at `vulkan-macos` @ `ad54dd8d`, 2026-09-22. When in
doubt, the source anchors listed under each section are the ground truth — this doc
summarizes them.

## Java ↔ shader buffer layouts

| Structure | Layout | Source |
|---|---|---|
| Terrain scene UBO | `mat4 MVP` @0 (64B, column-major) · `ivec3 baseSectionPos` @64 · `uint frameId` @76 · `vec3 cameraSubPos` @80. Written span (`VkSceneUniform.SIZE`) = **92 bytes**; a conventional std140-rounded footprint would be 96 — the buffer is allocated large enough, but don't assume the extra 4 bytes are meaningful. | `VkSceneUniform.write`, `bindings.glsl` |
| Traversal UBO | 208 bytes total: MVP @0, section origin @64, packed HiZ dimensions @76, camera @80, minimum screen size @92, six `vec4` frustum planes @96..191, render-queue limit @192, frame @196, request limit @200, distance @204. | `VkTraversal.writeUniform`/`reset` |
| Packed quad | 8 bytes/quad. Shared format between GL and Vulkan. Geometry offsets are **element indices** — multiply by 8 to get byte offsets for uploads. | `quad_format.glsl`, mesher |
| Section metadata | 32 bytes = two `uvec4`. First: packed position, AABB, geometry start. Second: eight packed 16-bit run counts, in this order: translucent, double-sided, down, up, north, south, west, east. | `BasicAsyncGeometryManager.writeMetadata`, `section.glsl` |
| Block model | 64 bytes: six face words, flags, tint, custom ID, seven padding words. Model color table entries are 4 bytes each. | `block_model.glsl` |
| Node | 16-byte `uvec4`. Packed position in `xy`; 24-bit mesh pointer and 24-bit child pointer in `z`/`w`, with flags packed into the high byte of each. Sentinel values `0xFFFFFF` (null geometry) and `0xFFFFFE` (empty geometry) must stay consistent between `NodeStore` (Java) and `node.glsl`/`VkNodeTree` (GPU-side packing). | `NodeStore.writeNode` (`SENTINEL_NULL_GEOMETRY_ID`/`SENTINEL_EMPTY_GEOMETRY_ID`), `VkNodeTree`, `node.glsl` |
| Queue metadata | Five 16-byte records: `xyz` = indirect dispatch dimensions, `w` = count. Top-level/source/sink node IDs are 4 bytes each. | `queue.glsl` |
| Requests / render queue | Requests: 8-byte header + 8-byte packed position. Render queue / indirect lookup: 4-byte count followed by 4-byte section IDs. **World-key word order and GPU-position word order differ** — don't assume they're interchangeable. | `queue.glsl` |
| Merged entries / prefix | Entry = 8 bytes `(quadStart, drawId)`; dense, face-major, `7 * sectionCount` slots. Prefix buffer = 4-byte entry count + 4-byte exclusive offsets + terminal sentinel. Empty runs legitimately produce equal adjacent prefix values — **do not** re-add a strict-monotonicity check; that was an earlier design, deliberately replaced (see [current-state.md](current-state.md) superseded-decisions note in the audit). | `VkGeometryFlush.flush`, `merged_prefix.comp` |
| Indirect commands | Indexed draw = 20 bytes: index count, instance count, first index, signed vertex offset, first instance. Compute dispatch = 12 bytes. Shared quad-index buffer capacity defaults to `1<<20`; opaque/temporal draws split beyond that. | `quad_index.glsl`, `cmdgen.comp` |
| Visibility / translucent | Visibility = 4 bytes/section: low 31 bits = frame, high bit = previous visibility. 1024 translucent buckets; slot list has a count header; stats track max bucket-quad count and clamped-bucket count. | `VkTerrainResources`, `translucent_gen.comp` |

Texture atlas: real atlas is 12288×8192, 4 allocated mip levels, 256×256 model tiles, six
16×16 face cells/model (~534MB decimal RGBA mip storage). Synthetic test atlas is
768×512. `ModelAtlasLayout` (Java) centralizes these numbers, but shader-side constants
are a **separate ABI surface** — a dimension change must be checked in both places.

**Verify by reflection, not by trusting the Java constant.** `VkShader`/`SpirvReflect`
reflect descriptor types/counts/stage visibility from the compiled SPIR-V, but do
**not** compare Java struct offsets against reflected UBO/SSBO member layouts. A
passing build does not prove a Java writer and a GLSL reader agree on byte layout —
only a passing runtime test with actual data does.

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

**Known contract violation**: `VkDepthVisualise` binds its source via
`VkAutoBindingShader.texture()`, which advertises `SHADER_READ_ONLY_OPTIMAL`, but
`record()` keeps the actual image in `GENERAL` for interop reasons — this is defect D2
(see [current-state.md](current-state.md#known-defects)). Image descriptors generally
advertise a fixed read-only layout; **actual image state is caller-managed**, and this
is the one place that contract is currently broken.

## Push constants

| Shader path | Bytes / layout |
|---|---|
| Traversal queue index | 4 bytes @0, flushed once per traversal iteration. |
| Merged opaque/temporal prefix | 12 bytes: section count @0, quad capacity @4, max draw slots @8. |
| Translucent prefix | 4 bytes: quad capacity @0. |
| Depth reprojection variant | 128 bytes: inverse source MVP @0, destination MVP @64. The non-reprojecting variant needs neither. |
| Index probe (test-only) | Small dedicated push block; not a rendering dependency. |

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

**Confirmed gaps** (re-verified this session, 281 pass / 1 skip / **7 unexpected
diagnostics** with `-PvkValidation=true -PvkSyncEnv=true` — see
[testing.md](testing.md)):

- **D2** — `VkDepthVisualise` layout mismatch above → `VUID-vkCmdDraw-imageLayout-00344`
  ×4.
- **D3** — `VkRenderTarget.beginRendering(cmd, null, clearDepth)` treats a null color
  attachment as LOAD but only transitions for `COLOR_ATTACHMENT_WRITE`, not the
  `_READ` access LOAD actually requires → `SYNC-HAZARD-READ-AFTER-WRITE` ×3 in
  `VkHiZDepthSourceTest`.
- **Descriptor-SSBO negative control** currently produces **zero** hazard messages —
  it is not proving what it's meant to. Don't treat that silence as a positive result.
- A deliberately-missing fill-buffer barrier **is** correctly caught by sync
  validation (`VkBarriersTest.missingBarrierIsDetected`) — this is the one working
  negative control confirming validation is actually active, not silently disabled.

Additional source-level risks not yet runtime-verified (see
[current-state.md](current-state.md) and [testing.md](testing.md) for what test
coverage would close each):

- `VkHiZ.record()` transitions intermediate mip levels for fragment reads, but only
  the *final* mip explicitly includes the compute consumer — visibility of every mip
  to traversal needs checking, especially without a surrounding broad barrier.
- The terrain interop path waits Vulkan→GL, but `GlVkSync.waitForGl()` (explicit
  GL-completion wait) is only used in DEPTH mode — no confirmed wait exists for a
  previous GL composite's read completing before the next Vulkan write/reallocation of
  the same shared image. Driver serialization may currently be masking this.
- Fence completion protects execution lifetime but not **logical** references — next
  frame's opaque pass draws from the *previous* frame's table, which can reference
  geometry that CPU reclamation has since changed. This needs multi-frame eviction
  testing, not just fence-wait correctness.
- `free()` methods and raw mapped addresses are not universally guarded against
  in-flight or after-free use. The single-frame-in-flight convention (below) is part
  of correctness here, not a removable performance detail.

## GPU lifetime assumptions

- **Single command buffer, single fence, single frame in flight.** `VkFrameTracker`
  owns exactly one command buffer + fence and explicitly waits after submission before
  reusing resources. Code that assumes multiple frames can be in flight simultaneously
  is assuming something the current implementation does not provide — see
  `phase6-device-sharing-survey.md` for the (still-unimplemented) analysis of what
  multi-frame-in-flight would actually require (mainly resource-ownership/host-write
  ordering, not per-mip layout-tracking replacement).
- **Previous-frame draw table + current-frame reclamation is a live hazard, not
  handled by fences.** See the "logical references" point above.
- **`VkInteropProbe` is a singleton with no confirmed `shutdown()` caller.** Engine
  liveness is checked via `worldIsLive()` only, not engine identity — a stale engine
  can outlive a world/dimension switch (defect D5). Don't assume world-switch or
  disconnect cleanly tears down Vulkan scene state.
- **Buffers are individually allocated and persistently mapped**, requiring
  `DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT` memory (`VkBuffer`). This is a
  unified-memory assumption; optimally-tiled images still need staging/copy. Don't
  assume a suballocator or a staging-buffer path exists where one doesn't.
- **Host-side section counts can go stale relative to GPU-side counts within a single
  frame** — this is exactly defect D1 (`VkHierarchicalScene.prepare()` reads
  `lastDrawnSections` before resetting traversal; downstream prefix/dispatch stages
  consume that stale value while `prep.comp`/`cmdgen.comp` use the current GPU count).
  Any new consumer of "how many sections are selected this frame" must get that number
  from the same place `prep.comp`/`cmdgen.comp` do, not from a host-tracked field that
  may lag by one stage.

## Source anchors

`VkSceneUniform.write`, `VkTraversal.writeUniform`/`reset`, `NodeStore.writeNode`,
`BasicAsyncGeometryManager.writeMetadata`, `VkGeometryFlush.flush`,
`VkTerrainResources`, `VkMergedTableBuilder`, `VkHierarchicalScene`; shader files
`bindings.glsl`, `section.glsl`, `block_model.glsl`, `node.glsl`, `queue.glsl`,
`quad_index.glsl`. See [repo-map.md](repo-map.md) for full paths.
