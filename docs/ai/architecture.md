# Architecture

Voxy is a level-of-detail (LoD) terrain renderer for Minecraft (Fabric mod, package
`me.cortex.voxy`). It maintains its own voxelized/mipped world representation outside
Minecraft's own chunk rendering and renders it with one of two independent GPU
backends. This doc describes the major paths as implemented; see
[gpu-contracts.md](gpu-contracts.md) for exact binary layouts and binding numbers, and
[current-state.md](current-state.md) for what's actually wired up end to end today.

## Layering

```
common/            world ingestion, persistence, compression, storage backends,
                    threading/allocators — GPU-API-agnostic
commonImpl/         mod/world lifecycle, world identifiers, importers
client/core/model/   shared CPU model bakery, atlas layout, upload boundary
                     (used by BOTH backends)
client/core/gl/      OpenGL renderer (established path)
client/core/vk/      Vulkan renderer (Apple/macOS-oriented, diagnostic-scope)
client/mixin/        Fabric Mixins into Minecraft/Sodium/Iris/Flashback/Nvidium
```

`common/` and `commonImpl/` know nothing about GL or Vulkan. `WorldEngine`/
`WorldSection` plus the mippers/mappers/storage backends produce LoD section data
independent of how it's eventually drawn.

## CPU boundary shared by both backends

`RenderDataFactory` turns CPU-side model data into packed quads (see
[gpu-contracts.md](gpu-contracts.md) for the 8-byte quad format). `NodeManager`
manages the hierarchy/geometry association. `ModelUploadTarget` and
`NodeUploadTarget` are the abstract upload boundaries each backend implements;
`BasicAsyncGeometryManager` does CPU-side allocation independent of the target GPU
API. This is the seam that lets Vulkan reuse the same real model baking, real meshing,
and real texture atlas (12288×8192, 4 mip levels) as the GL path, rather than needing
its own synthetic content.

## Backend selection

`VoxyClient.initVoxyClient()` (called from Minecraft's render-init):

1. If GL capabilities suffice (compute shaders, indirect-parameters, no broken AMD
   depth sampler) → **OpenGL**. Apple's GL 4.1 always fails this (no
   `glDispatchComputeIndirect`, no indirect-count), so macOS never takes this branch.
2. Else, if Minecraft itself is running on OpenGL (Voxy's Vulkan path composites through
   GL texture IDs, so it needs a live GL context) **and** `VkContext.init()` actually
   succeeds (capability *and* real device-creation check) → **Vulkan**.
3. Else → Voxy disables itself for this session.

If Minecraft is running its own native Vulkan backend, Voxy's Vulkan path is *not*
selected — it does not borrow Minecraft's device, and previously crashed trying to cast
a Vulkan texture view to a GL one before this was guarded.

## OpenGL path

`VoxyRenderSystem` / `NormalRenderPipeline` / `MDICSectionRenderer` /
`AsyncNodeManager` / `NodeCleaner` / bounds renderer (`BoundRenderer` and friends).
`RenderPipelineFactory` constructs this pipeline. This is the established,
feature-complete renderer; it is not exercised further in these docs beyond noting it
exists and is what real players get on non-macOS/Vulkan-required systems.

## Vulkan path

### Entry point

`MixinLevelRenderer.voxy$createRenderer()` deliberately does **not** build a Vulkan
`VoxyRenderSystem` — Vulkan is not wired into Minecraft's normal render-pipeline
construction. The actual entry point is `MixinDefaultChunkRenderer.doRender()`, which
calls `VkInteropProbe.composite()` during Sodium's CUTOUT terrain pass. `VkInteropProbe`
is a singleton diagnostic controller with several scene modes
(`VkInteropProbe.Mode`); `resolveMode()` defaults to `PAIR` (synthetic two-object
scene) and only switches to `HIERARCHICAL` (real traversal) when the `voxy.5c4` system
property is present at all (its value is not parsed as on/off).

### Frame sequence (HIERARCHICAL mode)

```
shared world data
  -> synchronous model bake/mesh (CPU, shared boundary)
  -> NodeManager / geometry allocation
  -> mapped geometry, metadata, nodes, uniforms uploaded
  -> opaque render using PREVIOUS frame's draw table
  -> HiZ built from this frame's own Voxy D32 depth
  -> GPU hierarchy traversal against HiZ -> current-frame section lookup
  -> prep.comp -> raster cull -> cmdgen.comp / prefix -> translucent table build
  -> temporal opaque render -> translucent render
  -> depth resolve/reprojection into Minecraft's projection space
  -> Vulkan submit (VkFrameTracker.endFrame) -> explicit fence wait (waitForFrame)
  -> diagnostics + CPU services newly-requested meshes (scene.serviceRequests)
  -> GL color/depth composite via IOSurface
```

Uniform/visibility setup for a frame happens **before** `beginFrame()`; CPU request
servicing happens after the fence wait and **before** the GL composite (source order in
`VkInteropProbe.composite()`). See
[gpu-contracts.md](gpu-contracts.md#gpu-lifetime-assumptions) for why that ordering,
plus the one-time `primeLayout()` of the imported depth image, are correctness
preconditions rather than incidental details.

This is the *intended* stage ordering, not a certified guarantee that every
cross-stage value agrees on frame N — see D1 in
[current-state.md](current-state.md#known-defects) for a concrete case where it
doesn't (a stale host-side section count reaches the prefix/dispatch stage).

### Context and resource model

`VkContext` creates its own Vulkan instance and logical device (does not share with
Minecraft), picks the first physical device, and opens one queue and command pool from
the first queue family advertising `VK_QUEUE_GRAPHICS_BIT`. That queue is also used for
compute, but no explicit combined graphics+compute capability check is implemented.

Initialization policy (source: `VkContext` constructor and its feature chain) — keep
this separate from what was *observed* on the development host:

- Instance API version = `min(queryInstanceVersion(), VK_MAKE_VERSION(1, 4, 0))`. This
  is a fallback expression, **not** an enforced "Vulkan 1.4 required"; equally, it does
  not establish that lower-version devices work — that is untested.
- Enabled features: Vulkan 1.1 `shaderDrawParameters`; Vulkan 1.3 `dynamicRendering`
  and `synchronization2`; base `multiDrawIndirect`, `drawIndirectFirstInstance`,
  `shaderInt64`, `fragmentStoresAndAtomics`, `vertexPipelineStoresAndAtomics`.
  `VK_KHR_portability_subset` is enabled when present (MoltenVK).
- **Timeline semaphores are not enabled** and not used — source comments naming them
  are aspirational; `VkFrameTracker` submits with a fence.
- Observed host (2026-09-22): device API 1.4.357, driver 0.2.2210, subgroup size 32,
  push-constant limit 4096.

`SpirvCompiler` targets SPIR-V for Vulkan **1.2** — the shader target version is
independent of, and lower than, the instance version actually negotiated.

`VkFrameTracker` owns one command buffer, one fence, and submission-generation
bookkeeping with deferred-free hooks. `endFrame()` submits without waiting;
`beginFrame()` waits before reusing the command buffer; the interop path additionally
calls `waitForFrame()` right after submission (single-frame-in-flight design — the
exact preconditions are in
[gpu-contracts.md](gpu-contracts.md#gpu-lifetime-assumptions)).

`VkBuffer` allocates each buffer individually and maps it persistently, requiring
`DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT` memory — this is an Apple/unified-memory
design, not a generic discrete-GPU allocator; optimally-tiled images still need
staging/copy. Color interop is BGRA8; depth interop is R32F (resolved from Voxy's own
D32). Projection convention across the whole Vulkan path is **reverse-Z, depth range
0..1, GL-oriented Y with no path-level Y flip**; reprojection composes the inverse Voxy
MVP with Minecraft's MVP.

### Hierarchy and traversal

`NodeManager`/`NodeStore` (shared with GL) track the tree; `VkNodeTree` packs it into
16-byte GPU node records. `VkTraversal` runs GPU-side hierarchical occlusion traversal
against the HiZ pyramid, producing a bounded work queue consumed by `prep.comp` and
`cmdgen.comp`. `VkMergedTableBuilder` turns per-section quad ranges into dense,
face-major indirect-draw tables (`merged_prefix.comp` / `translucent_prefix.comp` /
`cmdgen.comp`) — see [gpu-contracts.md](gpu-contracts.md) for the exact buffer layouts.

### GL/Vulkan interop

`client/core/vk/interop/`: `IOSurf` + `Cgl` bridge macOS IOSurfaces into both APIs;
`VkInteropImage` wraps a Vulkan image backed by shared memory; `GlInteropCompositor` /
`GlScratchFramebuffer` do the GL-side compositing with state preservation;
`GlVkSync`/`VkGpuTimer` handle cross-API fencing and GPU timing. `VkInteropProbe` is the
top-level controller tying scene selection, the frame sequence above, and the composite
step together — and is currently the *only* wired path from Minecraft into Vulkan
rendering (see Entry point, above).

## Shader organization

`src/main/resources/assets/voxy/shaders/lod/`:

- `gl46/` — OpenGL compute/raster shaders (prep, cmdgen, cull raster, quad
  vertex/fragment). `hierarchical/` — GL hierarchy traversal, node/queue definitions,
  cleaner passes, debug visualization; guarded `#ifdef VULKAN` blocks add Vulkan-only
  bounds checks that the GL path does not get (see
  [current-state.md](current-state.md#known-defects)).
- `vk/` — Vulkan compute/raster equivalents: `prep.comp`, `cmdgen.comp`,
  `merged_prefix.comp`, `translucent_prefix.comp`, `translucent_gen.comp`,
  `cull_raster.vert/frag`, `quads3.vert`, `depth_resolve.*`, `depth_visualise.*`,
  `hiz_blit.vert`, `quad_index.glsl`.
- Shared includes at the `lod/` root (`quad_format.glsl`, `section.glsl`,
  `block_model.glsl`, `frustum.glsl`, `pos_util.glsl`, `lighting.glsl`,
  `quad_util.glsl`) are `#include`d by both GL and Vulkan shaders — this is where a
  Java-side struct-layout change has to be mirrored on both backends at once.
- `VkShaderLoader` rewrites `gl_VertexID` to `gl_VertexIndex` on import; it does
  **not** blindly rewrite `gl_InstanceID`, since the two backends' instancing
  semantics diverge in places.

## Where to look for more detail

- Exact GPU struct layouts, descriptor bindings, push constants, barrier chains and
  GPU-lifetime assumptions: [gpu-contracts.md](gpu-contracts.md).
- Subsystem → source-path → historical-report mapping: [repo-map.md](repo-map.md).
- What's actually working vs. aspirational, and the current defect list:
  [current-state.md](current-state.md).
