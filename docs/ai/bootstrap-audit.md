# Repository bootstrap audit

**Audit date:** 2026-09-22 (Asia/Tokyo)  
**Baseline:** branch `vulkan-macos`, commit `4d955b0a4b6f4cfe6ce0b352e75076e4004b919c`  
**Scope:** baseline inspection and verification before a Claude Code + Codex + OrcaADE workflow migration. No fixes, production-source changes, historical-report edits, or workflow migration were performed.

> **Corrections applied 2026-09-22** after the independent [context-review.md](context-review.md) (reviewed at `d476f559`). The original audit text is preserved; superseding notes are marked **Correction (context review)** inline at the affected statements (validated interop result, device-feature/version wording, frame-order diagram, atlas dimensions). No finding was removed or weakened.

## Current repository state

The repository builds and executes Vulkan rendering tests on this Apple Silicon host. It contains a substantial Vulkan implementation, but **the Minecraft Vulkan integration remains a diagnostic/prototype path, not a feature-complete replacement for the established OpenGL renderer**. Default Vulkan fallback displays a synthetic pair scene; real hierarchical rendering requires an explicit launch property.

The fresh validation run is **not clean**, despite Gradle reporting success. Seven unexpected Vulkan diagnostics occur in passing tests. A separate temporary GPU probe also confirmed that supplying a stale section count can suppress valid generated draw commands. These are more actionable baseline facts than historical “all PASS / zero validation errors” statements.

Evidence labels used below:

- **Observed:** reproduced during this audit using the current checkout.
- **Source-confirmed:** established by current control flow or data declarations; not necessarily exercised in Minecraft.
- **Likely/risk:** a concrete suspicious path requiring a focused reproduction.
- **Historical:** reported previously, not independently reproduced here.

The initial tracked working tree was clean. The only intended new repository file is this report. Builds regenerated ignored outputs; temporary audit logs/probe files were placed in `/tmp`. No Minecraft world was opened or changed. This was a whole-repository inventory and targeted implementation audit, not a claim that every line or every runtime path has been verified.

### Repository inventory

There are **442 tracked files**, **257 main Java files**, **52 test Java files**, **55 shader files**, and **45 historical `docs/*.md` reports** at this baseline.

| Area | Current contents and role |
|---|---|
| Root | Gradle build/settings/properties, Unix/Windows wrappers, `init.gradle`, mod metadata helper, minimal README, `LICENSE.md`, two tracked JVM crash logs. |
| `.github/workflows/` | Three Ubuntu workflows: push build, PR build, manual artifact build/upload. No Apple GPU or interop lane. |
| `src/main/java/.../common/` | World sections, voxel ingestion/mipping, persistence, mapping, compression, storage backends, threading, allocators and native memory utilities. |
| `src/main/java/.../commonImpl/` | Mod/world lifecycle, world identifiers, importers, common mixins. |
| `src/main/java/.../client/` | Client lifecycle/configuration/commands, Minecraft/Sodium/other compatibility mixins, debug utilities. |
| `client/core/model/` | Shared software model bakery, model metadata and atlas layout, upload boundary. |
| `client/core/rendering/`, `client/core/gl/` | Existing GL renderer, hierarchy, async geometry, bounds, streams, shaders, framebuffer and pipeline infrastructure. The spelling `hierachical` is part of the current package paths. |
| `client/core/vk/` | Context/resources, shader reflection/binding, offscreen rendering, traversal, table generation, real-model/mesh adapters, hierarchical scene, timing, reclamation. |
| `client/core/vk/interop/` | macOS IOSurface/CGL bridge, GL compositor/depth import, synchronization, and the large Minecraft probe controller. |
| `src/main/resources/` | Fabric metadata, access widener, mixin configs, localization/icon; common, GL and Vulkan shader sources. |
| `src/test/java/me/cortex/voxy/vk/` | 49 JUnit-bearing classes, Vulkan test support and two standalone GL/Vulkan bench/check entry points. |
| `docs/` | 45 historical Markdown reports; eight reference images and two Java capability probes. These are evidence and rationale, not authoritative specifications. |
| Local/generated directories | `.gradle`, `build`, `.idea`, `run`, `logs`; local `.claude/settings.local.json`. Runtime artifacts include crash reports and previous measurements. No checked-in `AGENTS.md` or agent-loop orchestration configuration was found. |

### Build and dependencies

[build.gradle](../../build.gradle), [gradle.properties](../../gradle.properties), and [the wrapper configuration](../../gradle/wrapper/gradle-wrapper.properties) currently specify:

| Component | Baseline |
|---|---|
| Java / Gradle | Java release 25; Gradle 9.6.0 wrapper. Audit JVM: Temurin 25.0.4+7, arm64. |
| Minecraft / Fabric | Minecraft 26.2, loader 0.19.3, Fabric API 0.152.2+26.2. |
| Loom | `1.16-SNAPSHOT`; cached build succeeded, but this is a moving dependency. |
| Sodium | `mc26.2-0.9.2-alpha.3-fabric`; generated dependency metadata also permits 0.9.1. |
| LWJGL | 3.4.1 BOM; Vulkan/shaderc plus LMDB/Zstd bindings and native artifacts. |
| Other mods | Mod Menu 20.0.0 and Lithium mc26.2-0.25.1-fabric; compile dependencies for Chunky, Vivecraft, Flashback and Nvidium. |
| Persistence/utilities | RocksDB JNI 10.2.1, Jedis 5.1.0, commons-pool2 2.12.0, LZ4 1.8.0, XZ 1.10; SQLite JDBC supplied for local Minecraft runtime. |
| Tests | JUnit Jupiter 5.10.2 with native access enabled. |

macOS arm64 native inclusion defaults to enabled. Other architecture coverage is uneven: Windows/Linux x64 artifacts exist, optional Linux arm64 inclusion does not establish a tested portable Vulkan implementation. RocksDB repackaging removes `.jnilib` and several native variants; LMDB and RocksDB packaging must not be conflated. The produced jar includes a dummy Sodium provider; loader behavior and supported real Sodium versions deserve packaged-client testing.

The build uses multiple external repositories and has no checked-in dependency lock/verification metadata. `processResources` embeds build time and commit, so byte-for-byte reproducible jars are not established. The offline run proves that the **current local cache** can rebuild; it is not a fresh-machine dependency-resolution test. Gradle emitted Gradle-10 incompatibility/deprecation notices, and native/Unsafe warnings remain.

**Observed packaging discrepancy:** the jar task uses `from("LICENSE")`, but the tracked file is `LICENSE.md`. The generated jar has no root license file. This is a packaging finding, not a legal assessment.

## Architecture overview

### Shared engine and backend selection

World ingestion and persistence feed `WorldEngine`/`WorldSection` and LoD data. `RenderDataFactory` generates packed quads using CPU model data. `NodeManager` manages hierarchy and geometry associations. `ModelUploadTarget` and `NodeUploadTarget` provide existing boundaries for backend-specific uploads; `BasicAsyncGeometryManager` performs CPU allocation independently of the GPU API.

The OpenGL path uses `VoxyRenderSystem`, `NormalRenderPipeline`, `MDICSectionRenderer`, `AsyncNodeManager`, `NodeCleaner`, and the bounds renderer. `RenderPipelineFactory` still constructs the GL pipeline.

`VoxyClient.initVoxyClient()` chooses GL when its capabilities suffice, otherwise attempts the Voxy Vulkan backend **while Minecraft itself still uses OpenGL**. If Minecraft uses its native Vulkan backend, Voxy disables itself. The code does not borrow Minecraft's Vulkan device.

`MixinLevelRenderer.voxy$createRenderer()` deliberately does not create `VoxyRenderSystem` for Vulkan. Its log says the render path is “not wired yet”; that text is stale in a broader sense: `MixinDefaultChunkRenderer.doRender()` separately calls `VkInteropProbe.composite()` at Sodium's CUTOUT pass. This is the real Vulkan integration entry point.

`VkInteropProbe.resolveMode()` defaults to `PAIR`. Presence of `voxy.5c4` selects `HIERARCHICAL`; it does not parse an on/off boolean, so `-Pvoxy5c4=off` also selects that mode. Scene configuration remains phase-specific development properties rather than normal rendering configuration.

### Current Vulkan frame

The implemented hierarchical path is:

```text
Shared world data -> synchronous model bake/mesh -> NodeManager/geometry allocation
    -> mapped geometry, metadata, nodes, uniforms
    -> opaque render using previous table
    -> HiZ from this frame's Voxy D32 depth
    -> GPU hierarchy traversal / current section lookup
    -> prep -> raster cull -> cmdgen / prefix / translucent table construction
    -> temporal opaque render -> translucent render
    -> depth resolve/reprojection to Minecraft space
    -> Vulkan fence wait -> GL color/depth composite through IOSurface
    -> CPU services requested meshes after Vulkan completion
```

This describes intended/current ordering, not a certification that all cross-stage values agree; see D1 below.

**Correction (context review):** the last two diagram lines are in the wrong order. Source order in `VkInteropProbe.composite()` is Vulkan submit (`endFrame`) → explicit `waitForFrame()` → diagnostics and `serviceRequests()` → GL color/depth composite. CPU request servicing precedes the GL composite. Uniform/visibility preparation happens before `beginFrame()`, relying on the previous frame's explicit wait; deferred frees drain at the next `beginFrame()`/`waitIdle()`; the imported depth image is primed `UNDEFINED → GENERAL` once at allocation via `primeLayout()`, which submits and waits and rejects calls during recording. See [gpu-contracts.md](gpu-contracts.md#gpu-lifetime-assumptions).

`VkContext` creates its own instance/device, chooses the first physical device, and creates a graphics/compute queue and command pool. It requests Vulkan 1.4 plus the needed rendering/synchronization and shader/indirect/fragment-storage features. `SpirvCompiler` targets Vulkan 1.2 shader output; that lower shader target is separate from the runtime device requirement.

**Correction (context review):** "requests Vulkan 1.4" is imprecise. The instance requests `min(queryInstanceVersion(), 1.4)` — a fallback, not an enforced device requirement, and not evidence of portability to lower-version devices. Enabled features are Vulkan 1.1 `shaderDrawParameters`, Vulkan 1.3 `dynamicRendering`/`synchronization2`, and the base indirect/int64/fragment-storage features. Timeline semaphores are **not** enabled (only mentioned in comments); submission uses a fence. Queue selection checks `VK_QUEUE_GRAPHICS_BIT` only. The observed host is API 1.4.357.

`VkFrameTracker` owns one command buffer and fence, submission generations and deferred-free/hooks. The interop path explicitly waits after submission. `VkBuffer` allocates individually, maps persistently, and requires DEVICE_LOCAL + HOST_VISIBLE + HOST_COHERENT memory. This is an Apple/unified-memory-oriented implementation, not a generic discrete-GPU allocator. Optimal-tiled images still require staging/copy operations.

Color sharing is BGRA8; depth sharing is R32F after resolving from D32. Current projection conventions are **reverse Z, depth 0..1, GL-oriented Y with no path-level Y flip**. Reprojection uses inverse Voxy MVP followed by Minecraft MVP. PNG display orientation is a separate operation.

### Java ↔ shader interfaces and descriptor bindings

`VkShaderLoader` expands imports from classpath resources and adds `gl_VertexID -> gl_VertexIndex`. It deliberately does not blindly translate `gl_InstanceID`. `VkShader` compiles stages, reflects SPIR-V, merges descriptor types/counts/stage visibility, rejects nonzero descriptor sets and type/count collisions, and checks push-constant size against the device limit.

**Binding numbers are pipeline-local, not global resource IDs.** All implemented layouts use set 0. The important current maps are:

| Pipeline | Bindings |
|---|---|
| Terrain vertex/fragment | 0 scene UBO; 1 quads; 2 depth-bound sampler; 3 models; 4 model colors; 5 position scratch; 6 light sampler; 7 atlas sampler. Merged draws additionally use 10 entries and 11 prefix. |
| Table generation | 0 scene UBO where reflected; 3 section metadata; 4 visibility; 5 section lookup; 6 position scratch; 10 merged entries; 11 merged prefix; 12 dispatch command; 13 opaque draws. |
| Translucent/temporal generation | 14 buckets; 15 translucent section list; 16 translucent entries; 17 translucent prefix; 18 translucent draws; 19 diagnostics; 20 temporal prefix; 21 temporal draws; 22 cull indirect command. Only the resources needed by each stage are declared/bound. |
| Cull | 0 scene UBO; 1 metadata; 2 visibility; 3 section lookup. |
| Traversal | 0 HiZ sampler; 1 traversal UBO; 2 requests; 3 render queue; 4 nodes; 6 queue metadata; 7 source queue; 8 sink queue; 9 render tracker; 10 overflow diagnostics. Binding 5 is no longer a Vulkan descriptor. |
| HiZ / depth resolve / depth visualizer | Source sampler at 0 in independent pipeline layouts. |

`VkAutoBindingShader` supports typed buffers/images, explicit ranges, dynamic SSBO offsets and a push shadow buffer. `VkDescriptorSetGroup` supplies three prebuilt traversal queue variants and per-mip HiZ variants, avoiding descriptor mutation between recorded dispatches. Submission-generation checks reject many in-flight descriptor updates.

**Limits of these guards:** descriptor reflection does not compare Java offsets with complete UBO/SSBO member layouts. Image descriptors advertise a fixed read-only layout; actual image state is caller-managed, and the visualizer violates that contract (D2). `unboundBindings()` is available but is not a universal automatic assertion in `bind()`. Descriptor arrays, same-type aliases across stages, malformed reflection input, negative/overflowing ranges and full dynamic-range bounds are not comprehensively guarded/tested. Reflection handles the current shader subset, not arbitrary SPIR-V.

### GPU buffer layouts

| Structure | Current contract / producer-consumer pair |
|---|---|
| Terrain scene UBO | MVP floats at 0..63; section origin at 64/68/72; frame ID 76; camera sub-position 80/84/88. `VkSceneUniform.SIZE=92` is the written member span; a conventional std140 rounded struct footprint is 96. Current devices/tests accept the bound range; add reflected-member/span checks rather than assuming Java object layout. |
| Traversal UBO | 208 bytes: MVP 0; section origin 64; packed HiZ dimensions 76; camera 80; minimum screen size 92; six vec4 planes 96..191; render queue limit 192; frame 196; request limit 200; distance 204. |
| Packed quad | 8 bytes per quad; common `quad_format.glsl`/mesher contract. Geometry offsets are element indices, multiplied by 8 for byte uploads. |
| Section metadata | 32 bytes, two `uvec4`: packed position, AABB, geometry start in the first; eight packed 16-bit run counts in the second. Runs: translucent, double-sided, down/up/north/south/west/east. |
| Block model | 64 bytes: six face words, flags, tint, custom ID, seven padding words. Model color table elements are 4 bytes. |
| Node | 16-byte `uvec4`; packed position in xy; 24-bit mesh and child pointers in z/w; flags in their high bytes. Sentinel values 0xFFFFFF and 0xFFFFFE must remain consistent with Java. |
| Queue metadata | Five 16-byte records; xyz are indirect dispatch dimensions and w is count. Top-level/source/sink node IDs are 4 bytes each. |
| Requests / render queue | Requests have an 8-byte header and 8-byte packed positions. Render queue/indirect lookup share a 4-byte count followed by 4-byte section IDs. World-key and GPU-position word order differ. |
| Merged entries / prefix | Entry is 8-byte `(quadStart, drawId)`; dense face-major slots, `7 * sectionCount`. Prefix buffer has 4-byte entry count then 4-byte exclusive offsets plus terminal sentinel. Empty runs create equal adjacent prefix values. |
| Indirect commands | Indexed draw stride 20 bytes: index count, instance count, first index, signed vertex offset, first instance. Compute dispatch is 12 bytes. Shared quad index capacity defaults to 1<<20; opaque/temporal draws split beyond it. |
| Visibility / translucent | Visibility is 4 bytes/section: low 31 bits frame, high bit previous visibility. 1024 translucent buckets; slot list has count header; stats contain maximum bucket quads and clamped-bucket count. |

Source anchors: `VkSceneUniform.write`, `VkTraversal.writeUniform/reset`, `NodeStore.writeNode`, `BasicAsyncGeometryManager.writeMetadata`, `VkGeometryFlush.flush`, `VkTerrainResources`, and shader files `bindings.glsl`, `section.glsl`, `block_model.glsl`, `node.glsl`, `queue.glsl`, `quad_index.glsl`.

The real atlas is 12288×8192 with four allocated mip levels, 256×256 model tiles, six 16×16 face cells per model. It consumes roughly 534 MB decimal for RGBA mip storage. Synthetic atlas dimensions are 768×512. `ModelAtlasLayout` centralizes Java calculations, but shader constants still constitute another ABI surface.

**Correction (context review):** read "256×256 model tiles" as *a 256×256 grid of model tiles, each tile 48×32 texels at mip 0* (3×2 face cells of 16×16). The dimensions and mip count above are correct.

### Push constants

| Implemented shader path | Bytes / Java writes |
|---|---|
| Traversal queue index | 4; offset 0, flushed for each traversal iteration. |
| Merged opaque/temporal prefix | 12; section count at 0, quad capacity at 4, maximum draw slots at 8. |
| Translucent prefix | 4; quad capacity at 0. |
| Depth reprojection variant | 128; inverse source MVP at 0 and destination MVP at 64. Non-reprojecting variant does not require these. |
| Index probe | Test shader has its own small push block; not a game-rendering dependency. |

Cleaner/scatter/old GL postprocess shaders still have default-block uniforms. Some are bypassed by CPU reclamation/direct mapped uploads or dedicated Vulkan depth resolve; this is not simply “six missing translations needed before Vulkan can render.” SSAO remains unported. Its historical six-matrix/384-byte requirement is hypothetical for the Vulkan path. The GL `BETTER_SSAO` declarations also contain duplicate location 4 and need separate compile verification.

The audit observed **4096-byte push-constant limits on both bundled and loader paths**. The historical loader-path 256-byte failure did not reproduce. This does not justify hardcoding 4096: `VkContextTest` still asserts the exact device value and subgroup size 32 rather than only actual pipeline requirements.

### Synchronization and barriers

Implemented mechanisms include compute→compute and compute→indirect helpers, consumer-specific indirect-draw barriers, conservative ALL_COMMANDS barriers, per-mip image transitions, upload/copy visibility, mapped readback barriers, and submission/fence tracking. Hierarchical table/cull/terrain objects explicitly use `CONSERVATIVE`; traversal uses compute→indirect barriers. Narrow barrier variants remain test/comparison paths.

The relevant dependency chain includes host writes→shader reads, previous opaque indirect/vertex reads→table overwrite (WAR), prep→indirect dispatch/cull reads, fragment visibility writes→compute, prefix writes→indirect/vertex reads, depth attachment→HiZ, each HiZ mip→next mip, HiZ→traversal, and resolve→interop consumption. A mechanical translation of GL barrier bits cannot prove these chains.

Fresh negative controls establish that synchronization validation catches a deliberately missing fill-buffer barrier. The descriptor-SSBO negative control still produces no hazard and skips. **A zero-message SSBO test is therefore weak evidence here.** Other validation classes are demonstrably active, including the unexpected failures in D2/D3.

Additional source-review risks requiring runtime tests:

- `VkHiZ.record()` transitions intermediate levels for fragment reads, but only the final mip explicitly includes the compute consumer; validate visibility of every mip to traversal, especially without surrounding broad barriers.
- The terrain interop path waits Vulkan→GL, but explicit `GlVkSync.waitForGl()` is only used in DEPTH mode. No explicit completion wait was found for a previous GL composite's read before the next Vulkan write/reallocation of the shared image. Driver serialization may mask this; offscreen single-operation pixel comparisons do not prove the cross-frame handoff.
- Fence completion protects execution lifetime, but not logical references from the next frame's **previous-table draw** to geometry that CPU reclamation/reuse has changed. Multi-frame eviction tests are needed.
- Per-resource `free()` methods and raw mapped addresses are not universally guarded against all in-flight/after-free use. The single-frame integration convention is part of correctness, not a removable performance detail.

## Completed work

“Completed” here means present and supported by current code/tests within its tested scope:

- Vulkan context, mapped buffers, textures/views/samplers, dynamic rendering, shaderc compilation/reflection and typed descriptor infrastructure.
- Independent synthetic terrain fixtures and CPU references; merged quad lookup and face-major tables; GPU table construction, bounded scans/searches and indirect draw splitting.
- Opaque, temporal and distance-bucket translucent rendering; cull raster pass, HiZ generation and hierarchical traversal with Vulkan queue bounds diagnostics.
- Reverse-Z/Y/projection tests, depth resolve/reprojection, IOSurface color/depth interop and GL state-preserving composition.
- Shared model/node upload boundaries, real model baking/biome replay, real meshing, top-level node queue callbacks, synchronous request servicing.
- CPU geometry reclamation, protection for top-level/requesting nodes, corrected partial `unwatch` result, fragmentation-aware `canFit`/`canAlloc` checks. Focused tests pass; real-world post-fix reclamation is not certified.
- GPU timing, visibility-mode controls and diagnostic readbacks.
- GL-less capabilities initialization and intentional Iris stubs. These are implemented scope decisions, not evidence of Iris compatibility.

## Incomplete work

- **Production integration:** no MC-native-Vulkan device borrowing, normal Vulkan `VoxyRenderSystem`, complete backend lifecycle, or ordinary configuration-driven real scene by default.
- **Streaming/world updates:** Vulkan uses a fixed initial top-level region, synchronous meshing and a local watcher map. It does not connect the full async render-generation/update-router/visible-chunk system. Traveling, edits, new chunks and world changes need explicit behavior tests.
- **Recovery:** permanent `pendingMesh` membership and the exhaustion latch prevent several retries (D4). Reclamation is not equivalent to a complete streaming cache.
- **Rendering parity:** actual lightmap input is absent (`fillSyntheticLightmap` writes white); generated depth bounds are absent; no Vulkan SSAO or equivalent full GL fog/postprocess integration; Iris functionality is disabled. Real atlas/model support does not imply full visual parity.
- **Translucency:** in-bucket ordering is nondeterministic; oversize buckets clamp rather than split, deliberately omitting excess quads and recording diagnostics.
- **Portability:** first-device selection, unified-memory assumptions, Metal/IOSurface integration and subgroup assumptions are Apple-oriented. Linux CI success does not certify the Vulkan path.
- **Diagnostics/lifecycle:** buffer/texture debug naming remains TODO; shutdown/reload/session integration and error recovery are incomplete or unproven.

## Known/likely defects

### D1 — Current GPU selection mixed with previous-frame count (high; source-confirmed, bounded GPU reproduction)

`VkHierarchicalScene.prepare()` reads `lastDrawnSections` **before** resetting traversal. `record()` then runs current traversal/prep/cmdgen, but passes that old count to `VkMergedTableBuilder.recordAfterPrep()`. The builder pushes it to both prefix shaders and uses it to size `translucent_gen` dispatch.

`prep.comp` and `cmdgen.comp` use the **current** GPU `sectionCount`; `merged_prefix.comp` uses the host count to index face boundaries. Thus the values disagree whenever selection size changes. Growing from zero is decisive: the prefix contains real geometry while all face ranges use zero boundaries. Increasing translucent counts can also exceed the work scheduled from the old count. Decreasing counts can read stale prefix entries beyond the newly written logical range.

A temporary `/tmp` Java probe used the existing `SyntheticTerrain.boundaryCases()` fixture and production `VkMergedTableBuilder`, with current lookup count 9:

```text
currentCount=9 suppliedCount=9 entries=63 totalQuads=189 emittedIndices=1134
currentCount=9 suppliedCount=0 entries=63 totalQuads=189 emittedIndices=0
```

This proves the stale-count consequence in the table builder. It does not reproduce all visible Minecraft symptoms. Existing table tests supply the matching count and do not exercise the complete changing-count hierarchy sequence. The historical indirect-cull-count fix correctly moved cull count generation to the GPU, but did not remove this analogous host count from later stages.

### D2 — Depth visualizer descriptor/layout mismatch (high; observed)

All four `VkDepthVisualiseTest` cases pass while emitting `VUID-vkCmdDraw-imageLayout-00344`. `VkDepthVisualise` binds with `VkAutoBindingShader.texture()`, which advertises SHADER_READ_ONLY_OPTIMAL, then `record()` keeps the source in GENERAL for interop. The validation message names `srcDepth`, set 0 binding 0. This is a current API contract violation, not one of the explicitly suppressed IOSurface metadata VUIDs.

### D3 — LOAD attachment transition omits read access (high; observed)

`VkHiZDepthSourceTest` emits three `SYNC-HAZARD-READ-AFTER-WRITE` diagnostics across its two passing cases. Its helper calls `VkRenderTarget.beginRendering(cmd, null, clearDepth)`. Null color means LOAD, although the test comment says color is untouched. The transition only enables COLOR_ATTACHMENT_WRITE; validation specifically requires COLOR_ATTACHMENT_READ for LOAD. The helper also uses a fresh color attachment, so loading its undefined contents is not useful to this test.

This is rooted in the shared `beginRendering` helper, not the HiZ reduction algorithm. Audit depth LOAD/read-write and early/late stage handling as well; the observed diagnostic specifically concerns color.

### D4 — Mesh requests can become permanently unserviceable (high; source-confirmed control flow)

`serviceRequests()` adds a position to `pendingMesh` **before** `meshOne()` succeeds. A missing section returns null, but that position is never removed for retry. The same set survives geometry eviction; a position requested again can be skipped as “already meshed.” If `acceptGeometry()` cannot fit after bounded reclamation, it sets `geometryExhausted`; later `serviceRequests()` exits before calling `acceptGeometry()` again. The documented suggestion that it may resume does not provide an actual retry trigger in this loop.

Expected risks are persistent holes, one-shot failures for not-yet-ingested chunks and inability to restore evicted detail. Existing tests isolate reclamation/allocator behavior and do not exercise a real scene's request→evict→request lifecycle. Minecraft impact/extent needs reproduction.

### D5 — Vulkan scene lifetime is not tied to active world identity (likely; source inspection)

`VkInteropProbe` is a singleton; repository search found its `shutdown()` declaration but no caller. The hierarchy only checks `worldIsLive()`, not whether its engine matches the active level. An old engine can remain live after switching worlds/dimensions. A fixed initial region and missing session teardown can retain stale scene data/resources. Verify disconnect/reconnect, dimensions, resource reload and idle-engine reclamation; do not infer safety from resize support.

### D6 — Passing tests do not enforce a clean validation log (high; observed test gap)

Validation suite: 281 passed / 1 skipped, yet seven unexpected diagnostics. Tests clear/check global validation messages selectively, so unrelated cases can print errors without failing. The deliberate fill-buffer hazard is expected and must be scoped separately; blanket log rejection would incorrectly reject that negative control.

### Other established limitations and risks

| Finding | Evidence / status |
|---|---|
| Translucent bucket truncation | `translucent_prefix.comp` clamps at index capacity. Source-confirmed loss of geometry for oversize buckets; counter exists. |
| Shader write race candidate | `translucent_gen.comp` has every active invocation write `translucentSlotCount` non-atomically. Same-value writes are not a substitute for a defined single-writer contract; investigate with a focused shader test. No corruption reproduced here. |
| Reclamation vs previous draw table | CPU may reuse geometry before next frame consumes the previous table. Source-level lifetime concern; not exercised by current isolated reclamation tests. |
| GL debug shader unavailable | `node_outline.vert` defines `NODE_DATA_INDEX`, while imported `node.glsl` requires `NODE_DATA_BINDING`; `DebugRenderer` supplies no correction. Source-confirmed latent compile defect; no instantiation found and no GL compile run here. |
| GL node queue overflow | `queue.glsl` bounds checks are under `#ifdef VULKAN`; GL still writes/reads against an increasing counter without equivalent capacity guards. Existing historical finding remains relevant. |
| SSAO declarations | Default-block uniforms are unported and `BETTER_SSAO` includes duplicate explicit location 4. Verify compilation separately; Vulkan SSAO is not currently executed. |
| Packaging | License input filename mismatch reproduced in jar inspection. RocksDB/native deployment matrix and dummy-provider behavior remain unverified in installed clients. |
| Hardcoded device expectations | `VkContextTest` expects push constants exactly 4096 and subgroup exactly 32. Capability changes can fail tests even when current render pipelines fit. |

### TODO/FIXME/stub inventory

A search found TODO/FIXME markers in **98 source files**. Most are upstream maintenance/performance notes; a marker alone is not proof of a live defect. Material unfinished areas outside the new Vulkan implementation include:

- `RenderDataFactory`: translucent chunk-border culling, self-occlusion and lighting questions.
- `SoftwareModelTextureBakery` / `BakedBlockEntityModel`: block entity model support and an unfinished branch.
- `WorldUpdater`: neighbor dirty/update propagation; `VoxelIngestService`: explicitly noted unsafe concurrent chunk-section reads without copying.
- `Mapper`: unsynchronized mapping access notes and missing Minecraft data-version handling.
- `ZSTDCompressor`: native compression/decompression return values used without error checks; no corrupt-input tests found.
- `SaveLoadSystem3`: missing hash/checking TODO; storage recovery/version migration are untested.
- `ServiceManager`: failure propagation/release TODO; `ConfigBuildCtx`: unfinished path resolution handling.
- `AllocationArena`: documented large-free-block size limitation; new fragmentation tests do not establish all size/overflow behavior.
- GL `NodeCleaner`, streams, geometry/model work: batching and ownership/performance TODOs. `IrisUtil` is an intentional no-op compatibility stub.

No broad cleanup is recommended as part of bootstrap. These need separately scoped work with relevant tests.

## Historical report index

All 45 root Markdown reports are indexed below. “Retain” means the rationale still corresponds to current code, **not** that old measurements have been independently validated. Reports often append corrections without updating their initial status.

| Report | Current interpretation |
|---|---|
| [vulkan-port-feasibility.md](../vulkan-port-feasibility.md) | Original pre-port inventory at 337b919d. Useful motivations/platform constraints; counts, implementation estimates and many blockers superseded by code. |
| [phase0-b1-mdi-measurement.md](../phase0-b1-mdi-measurement.md) | Retain merged-draw rationale and bounded-GPU-loop lesson. Bench source was outside this repo; performance figures are historical. |
| [phase1-completion.md](../phase1-completion.md) | Retain shared CPU boundary and intentional Iris removal. Future-work list and universal 4096-byte assumption are stale. |
| [phase2-binding-audit.md](../phase2-binding-audit.md) | Retain unified Vulkan descriptor namespace and atlas/light sampler collision rationale. Read final corrections; current maps include later bindings. |
| [phase2-glsl-compat.md](../phase2-glsl-compat.md) | Retain safe vertex-ID shim and refusal to blindly alias instance IDs. Opening “GL shaders unchanged” is superseded by shared VULKAN branches. |
| [phase2-pushconstant-todo.md](../phase2-pushconstant-todo.md) | Header “unstarted” is obsolete. Traversal is implemented; other old paths are bypassed, deferred or still GL-only. New Vulkan push blocks are missing from this original list. |
| [phase3-barrier-survey.md](../phase3-barrier-survey.md) | Historical inventory; its own correction defers to access-series reasoning. Blanket sync-validation failure statement superseded. |
| [phase3-completion.md](../phase3-completion.md) | Foundation completion record. Missing image upload/rendering/pipeline gaps are now implemented; debug naming remains absent. |
| [phase3-descriptor-survey.md](../phase3-descriptor-survey.md) | Retain static/prebuilt/dynamic-offset strategy. Texture API unsupported and image-descriptor gaps no longer describe code. |
| [phase3-image-abstraction.md](../phase3-image-abstraction.md) | Retain explicit per-mip state management. Upload/renderpass gaps superseded; external IOSurface images now exist. |
| [phase3-uploadstream-proposal.md](../phase3-uploadstream-proposal.md) | Proposal is implemented in stream/frame classes; original undecided status is stale. Direct mapped ownership still matters. |
| [phase4-proposal.md](../phase4-proposal.md) | Staged test strategy retained; unstarted status superseded by stage completion reports and current code. |
| [phase4-buffer-hazards.md](../phase4-buffer-hazards.md) | Retain RAW/WAR/access-series rationale. It is an earlier graph, not proof that later hierarchy/HiZ/reclamation edges are correct. |
| [phase4-stage1-completion.md](../phase4-stage1-completion.md) | Retain independent synthetic color/geometry fixtures and their limitations. Current scope includes more passes. |
| [phase4-stage2-completion.md](../phase4-stage2-completion.md) | Merged lookup rationale retained. Compact, strictly increasing prefix design was replaced by dense tables with empty runs in Stage 3. |
| [phase4-stage3-completion.md](../phase4-stage3-completion.md) | Retain deterministic dense table/CPU comparison design. “Cull/translucent absent” is superseded. |
| [phase4-stage4-completion.md](../phase4-stage4-completion.md) | Retain cull/translucent/temporal and index splitting rationale. Seven draws is a minimum grouping, not an unconditional upper bound. |
| [phase4-completion.md](../phase4-completion.md) | Offscreen implementation milestone, not live-client completeness. Counts/clean-validation claims are historical snapshots. |
| [phase5-proposal.md](../phase5-proposal.md) | Interop/model-boundary design background; unanswered implementation choices mostly superseded by phase5b/5c code. |
| [phase5a-gl-to-vk-sync.md](../phase5a-gl-to-vk-sync.md) | Retain separate API synchronization rationale and measurement limits. Timing is historical; does not prove all cross-frame reuse edges. |
| [phase5b-stencil.md](../phase5b-stencil.md) | Retain depth-only equivalence assumptions and near/far edge cases. Does not certify unported SSAO. |
| [phase5b-composite.md](../phase5b-composite.md) | Interop implementation and narrowly scoped suppression rationale remain relevant. Initial orientation conclusions corrected later. 45 current checks pass. |
| [phase5c-mc-vulkan-survey.md](../phase5c-mc-vulkan-survey.md) | Native MC Vulkan/device-access feasibility research. Later appendix supersedes initial Sodium uncertainty; actual borrowing remains absent. Layout-tracking cost corrected by phase6 survey. |
| [phase5c-plan.md](../phase5c-plan.md) | Historical staged plan; “5c-1 unstarted” obsolete. Useful separation of offscreen, GL and Minecraft validation. |
| [phase5c-reverse-z.md](../phase5c-reverse-z.md) | Reverse-Z decision implemented. Y warning superseded by no-flip decision in next report. |
| [phase5c-y-orientation.md](../phase5c-y-orientation.md) | Retain GL-oriented Y/no path-level flips and independent orientation controls. Current source agrees. |
| [phase5c1a-completion.md](../phase5c1a-completion.md) | Backend gating/GL static-init caution retained. “Nothing drawn” describes that stage only; probe now draws separately. |
| [phase5c1b-completion.md](../phase5c1b-completion.md) | Retain GL state/FBO/sampler restoration lessons and diagnostic modes. Pixel/visual claims are prior observations. |
| [phase5c1c-completion.md](../phase5c1c-completion.md) | Depth-import/orientation design retained; fresh visualizer validation errors weaken prior clean-validation conclusions. |
| [phase5c1d-completion.md](../phase5c1d-completion.md) | Retain non-destructive depth composite/discard and projection distinctions. Synthetic scene is not real-world parity. |
| [phase5c1e-completion.md](../phase5c1e-completion.md) | Retain both-sided occlusion controls and resize diagnostic ownership lesson. Current default PAIR originates here. |
| [phase5c2a-completion.md](../phase5c2a-completion.md) | Real atlas dimensions and synthetic-scale equivalence implemented. Later boundary choice is no longer pending. |
| [phase5c2b-boundary.md](../phase5c2b-boundary.md) | Retain ModelUploadTarget/shared CPU bakery, biome replay and ID-space caution. Real Minecraft lightmap/parity not established. |
| [phase5c3-plan.md](../phase5c3-plan.md) | Header unstarted superseded by its completion appendices. Own projection, reprojection and real meshing exist; earlier fixed-LoD limitations superseded by hierarchy. |
| [phase5c4-plan.md](../phase5c4-plan.md) | HiZ/traversal implementation and bounded-queue rationale retained. Initial root wiring, depth source and reported timings superseded by 5c5 corrections. |
| [phase5c5-plan.md](../phase5c5-plan.md) | Later sections implement top-level wiring, correct HiZ source, pass order, cull and translucent. Earlier missing-path conclusions obsolete; stale host count still remains (D1). |
| [phase5-status.md](../phase5-status.md) | Milestone summary with later corrections mixed in. Not feature-complete shipping status; some opening/remaining-issue text conflicts with phase6 results. |
| [phase6-device-sharing-survey.md](../phase6-device-sharing-survey.md) | Still a proposal. Retain host-write ownership/multiple-in-flight/queue concerns; no shared-device implementation found. |
| [phase6-geometry-reclaim.md](../phase6-geometry-reclaim.md) | Current safety/fragmentation fixes exist and unit tests pass. Explicit post-fix Minecraft verification remains outstanding; completion label has limited scope. |
| [phase6-selection-churn.md](../phase6-selection-churn.md) | Later fixed-tree results supersede opening “measurement waiting” and blanket selection-churn explanation. Remaining translucency explanation is a hypothesis. |
| [phase6-opaque-cost.md](../phase6-opaque-cost.md) | Later results reject the original discard-sweep experiment as confounded. Do not use its proposed sweep as a vertex/fragment cost decomposition. |
| [phase6-sync-validation.md](../phase6-sync-validation.md) | Message-ID correction and SSBO blind spot confirmed. Loader 256-byte push limit/fixed one-test failure is not current on this host. Old zero-error summaries do not cover D2/D3. |
| [optimization-policy.md](../optimization-policy.md) | Retain measurement-first reasoning. Historical timings and unimplemented optimization lists need fresh qualification. |
| [upstream-issue-candidates.md](../upstream-issue-candidates.md) | GL-less capability guard present; debug binding defect and GL queue overflow remain. These reports are not instructions to contact upstream. |
| [vulkan-validation-setup.md](../vulkan-validation-setup.md) | Loader/layer setup works here. Read its final correction, not initial “sync validation dead”/old test-count claims. |

`docs/probes/ExtProbe.java` and `FeatProbe.java` are standalone survey programs, not Gradle verification gates. Reference PNGs are historical copies, not independently versioned automated goldens. External `~/dev/mdi-bench` measurements cannot be reproduced from this repository alone.

## Superseded decisions and report/code inconsistencies

1. **Per-section indirect draws → merged face-major draws.** Implemented; retain performance motivation, not fixed historical cost estimates.
2. **Compact/no-empty prefix → dense deterministic table.** `cmdgen.comp` emits every face/section slot; equal prefixes are valid. Do not restore strict monotonicity as a new invariant.
3. **GL barrier-bit translation → actual producer/consumer graph.** Still necessary; current validation catches hazards outside the old scope.
4. **“Sync validation is dead” → functioning controls with a descriptor-SSBO blind spot.** Message IDs are included now; one negative control still skips.
5. **“Loader always reports 256-byte push constants” → environment-dependent.** Both current runs report 4096; historical failure is not today's baseline.
6. **Conventional Vulkan Y flip / forward-Z examples → GL-oriented Y and reverse Z.** Current tests/code agree on no path-level flips.
7. **Previous resolved interop depth as HiZ source → current Voxy depth attachment after opaque.** Implemented.
8. **All-visible host writes / missing cull → cull-generated visibility by default in hierarchical probe.** The class's fallback default alone is not the probe's selected mode.
9. **No reclamation / byte-total budget guard → CPU bounded reclamation and contiguous-fit query.** Old no-reclaim comments in `VkHierarchicalScene` and `build.gradle` are stale; re-request/recovery remains incomplete.
10. **“Selection churn causes most flicker” → earlier measurement included tree growth.** Later fixed-tree observations refute that broad claim. Do not promote the replacement translucency hypothesis to a fact.
11. **“Discard sweep measures fragment-cost floor” → confounded by loss of depth occlusion.** Its measured timing change does not isolate a single cost.
12. **“Multiple in-flight frames require replacing per-mip layout tracking” → not inherently.** The later device-sharing survey identifies resource ownership/host writes and queue ordering as the main work. It is still unimplemented.
13. **“Vulkan rendering is not wired” → separate probe hook is wired.** `MixinLevelRenderer` message is stale; conversely “Phase 5 complete” does not mean default real LoD/product parity.
14. **“Unchanged GL shader sources” / “all remaining uniform ports are blockers” → no longer accurate.** Shared VULKAN branches and dedicated Vulkan replacements exist.
15. `ModelAtlasLayout` comments name `ModelAtlasLayoutTest`, but no such test class exists; related atlas/resource tests are present. Do not treat a comment's test name as proof of coverage.

## Testing status

### Commands executed during this audit

All runs used the baseline source. The initial sandboxed build could not write the Gradle wrapper cache lock; rerunning with approved cache access succeeded. A temporary probe likewise needed GPU access outside the sandbox. Those failures were environment restrictions, not repository build defects.

| Command | Fresh result |
|---|---|
| `./gradlew build --offline --rerun-tasks` | **SUCCESS**, 9 tasks executed; 282 tests: **278 pass, 4 skip, 0 failures/errors**. Includes Java compilation, JUnit and access-widener validation; jar produced. |
| `./gradlew test --offline --rerun-tasks -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true` | **SUCCESS**, 282 tests: **281 pass, 1 skip**, but **7 unexpected validation diagnostics** described in D2/D3. |
| `./gradlew interopCompositeCheck --offline` | **45 checks passed**. GL/Vulkan offscreen integration available. |
| `./gradlew interopCompositeCheck --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true` | **45 checks passed**, 28 narrowly suppressed interop metadata messages reported; no unsuppressed validation error lines observed. **Correction (context review):** this is a prior observation, superseded — the same command run twice during the 2026-09-22 context review emitted **one unsuppressed** `UNASSIGNED-VkDescriptorImageInfo-BoundResourceFreedMemoryAccess` diagnostic (C11 depth-visualization path) while still printing `ALL CHECKS PASSED`. The checker's result depends only on `failures`. Treat the validated interop run as **not clean**; see [testing.md](testing.md). |
| Temporary count probe | Matching 9→9 control emits 1134 indices; 0→9 stale-count case emits 0 for the same 189 generated quads. |

Bundled-path runtime: Apple M4 Pro, Vulkan 1.4, subgroup 32, 4096-byte push constants. Loader-path log: Apple M4 Pro, API 1.4.357, driver 0.2.2210, `metalObjects=true`, `validation=true`, `syncValidation=true`. Thus the validation suite actually ran GPU code; it did not succeed by skipping the GPU tests.

The four ordinary-run skips are `VkBarriersTest.computeToComputeSilencesTheHazard`, `missingBarrierIsDetected`, `conservativeBarrierAlsoCovers`, and `plainBufferHazardIsNowDetected`. With validation enabled only `missingBarrierIsDetected` skips. The fill-buffer hazard message is deliberately generated by the negative control and is **not** counted among the seven unexpected diagnostics.

Local evidence retained for this session:

- `/tmp/voxy-bootstrap-build.log`
- `/tmp/voxy-bootstrap-validation.log`
- `/tmp/voxy-bootstrap-default-tests/` and `/tmp/voxy-bootstrap-validation-tests/` (JUnit XML copies)
- `/tmp/voxy-bootstrap-interop.log` and `/tmp/voxy-bootstrap-interop-validation.log`
- `/tmp/BootstrapCountProbe.java`, `/tmp/voxy-bootstrap-count-probe.log`
- `build/reports/tests/test/`, `build/vk-test-output/`, and `build/libs/voxy-0.2.19-beta-4d955b0.jar`

These are local/generated evidence, not permanent repository artifacts. Test reports/build outputs can be overwritten by subsequent runs; the counts and diagnostic identities above are captured in this report.

### Existing coverage

The suite covers capabilities without GL; buffer/texture/context/frame/stream behavior; shader guards, bindings and push reflection; model/node uploads and atlas scaling; packed geometry/position invariants; synthetic CPU/GPU merged-table equivalence; index splitting; temporal visibility; translucent buckets; cull; HiZ/depth/orientation/projection; traversal queues and limits; timing; allocator budgets/fragmentation/reclamation.

The synthetic fixtures and deliberate defective alternatives provide useful independent controls. However, many suites validate isolated passes, stable input counts or output pixels, not the complete Minecraft hierarchy loop or a clean validation stream.

### Missing automated coverage

- Full multi-frame hierarchy→table→draw with changing counts, especially 0→nonzero, growth/shrink across 128-thread dispatch boundaries, and rapid camera turns.
- Requests while data is absent, later ingestion, edits/remeshing, eviction→re-request, exhaustion→recovery, and geometry reuse while old table references remain.
- Active-world identity, dimensions, disconnect/reconnect, resource reload, resize/minimize/Retina changes and GPU object count/leak checks.
- Automatic per-test validation assertions with tightly scoped expected-error controls and suppression audits.
- Reflected Java↔shader member offsets/strides/array lengths, all live compile-time variants, device-limit/capacity edges, subgroup variants.
- Cross-API multi-frame image reuse under queued GL load, beyond offscreen endpoint equivalence.
- Real-world color/lighting/fog/SSAO and GL/Vulkan output parity on a machine where the full GL renderer works.
- Persistence, malformed/compressed data, importers, mapper migration, concurrent ingestion/save/service failure paths. All JUnit-bearing files are under the Vulkan test package, although some test shared utilities.
- Fresh-cache builds, installed mod/loader/dependency matrix and native packaging; current Ubuntu workflows can skip Vulkan initialization failures.

`VulkanTestSupport.requireVulkan()` catches **any Throwable** from initialization and turns it into an assumption skip. That includes genuine regressions, not only missing hardware. A supported GPU lane must fail when expected GPU tests skip. `TestNodeManager` lives in main source with manual `main*` methods; it is not an automatically run JUnit suite.

## Recommended automated verification gates

These are recommendations only; no gates or fixes were installed.

| Gate | Required evidence / failure criterion |
|---|---|
| 1. Change boundary | Record base commit and initial status; restrict edits to task scope. An audit-only run must leave source/build/workflow files unchanged. |
| 2. Compile/package | JDK 25 wrapper build; access-widener validation; inspect produced mod metadata, required native artifacts and license inclusion. Keep a fresh-cache lane distinct from cached/offline builds. |
| 3. CPU/ABI | Run device-independent data/layout/allocator/model tests; compare emitted bytes and reflected member offsets, not just Java constants. Add corrupted/boundary input cases. |
| 4. Required Apple GPU | Full JUnit on a known supported Apple stack; report device/driver/library path and test counts. Unexpected GPU skips fail. Separate device-specific benchmark assertions from required capability checks. |
| 5. Validation | Loader + validation + sync control. Assert layer activation and that the intentional missing-barrier control is detected. Fail unexpected VUIDs/hazards per test; allow only exact expected negative controls. Preserve the explicit SSBO blind-spot result. |
| 6. Frame integration | Deterministic multi-frame synthetic hierarchy exercising count changes, temporal composition, eviction/reuse, capacity overflow and recovery. Require nonempty positive controls and independent expected totals/positions. |
| 7. Interop | Run the existing 45 checks on macOS, both ordinary and validated. Record suppression counts/identities; add repeated-frame synchronization and resize/reallocation checks. |
| 8. Minecraft smoke/soak | Controlled disposable world and known backend/properties; validate real hierarchy, travel/edits, world switches, resize, water/translucency, occlusion and low-budget reclamation. Save logs/screenshots with hardware and settings. |
| 9. Performance | Only after correctness: repeatable scene/camera, fixed resolution and stable tree; distinguish CPU wait, GPU pass timing and readback diagnostics. No performance claim from a confounded discard sweep or unmatched workloads. |

For the agent loop, use this audit as a baseline handoff: one scoped defect/feature, a concrete expected failure, an isolated patch, independent review against source, and gate evidence attached to the resulting change. Historical completion labels must not become machine-readable “done” assertions. Agents sharing a GPU/world or Gradle output directory should serialize destructive/output-overwriting runs or use explicit isolated workspaces. Do not remove conservative barriers or relax failing controls merely to make the loop green.

Suggested first follow-up work: capture D1 in a persistent regression test; make D2/D3 fail through validation assertions; address request/lifecycle integration with focused tests. Fixes require a separate task—none were attempted here.

## Uncertainties requiring runtime verification

1. **Latest reclamation fixes:** the existing September 22 crash log records Geometry OOM with 497920/500000 elements used and a request requiring a larger contiguous block. Current HEAD has the `canFit` fix and the tests pass, but a post-fix Minecraft run at `-Pvoxy5c4Quads=500000` was not performed. Verify repeated eviction, requests-in-progress and eventual recovery, not just absence of the original exception.
2. **Current-count defect in real scenes:** bounded synthetic consequence reproduced; measure actual changing selected counts, draw totals and visible holes in Minecraft without attributing all historical flicker to it.
3. **True Vulkan/GL ordering:** test shared-image reuse and destruction with multiple queued frames and minimal diagnostic readbacks. Current fence waits and single-shot pixel checks do not settle all cross-API edges.
4. **World lifecycle:** engine identity, new chunks, block edits, dimensions, reconnects, resource reload and prolonged travel with fixed roots and local watchers.
5. **Shader synchronization:** descriptor-SSBO blind spot, intermediate HiZ mip visibility, fragment visibility→compute and logical old-table lifetime across geometry reuse.
6. **Rendering parity:** daylight/night/weather lightmap, fog, transparency, cutout overlaps, fluids, block entities, camera-inside-geometry and beyond-far-plane depth. SSAO/depth bounds/Iris are incomplete features, not merely untested toggles.
7. **Translucent saturation/order:** count clamped buckets and compare overlapping translucent data across repeated stable frames. Historical flicker measurements do not identify a single proven cause.
8. **Supported platform:** current hardware passes core tests. Lavapipe is installed but not rerun in this audit; historical subgroup/translucent failures remain historical. Native MC Vulkan and shared-device integration are absent, not verified capabilities.
9. **Performance:** old MDI/interop timings, HiZ bimodality and opaque-cost claims require matched workloads and unbiased instrumentation. No fresh performance claim is made here.

Available runtime tools include `runClient`, `glToVkSyncBench`, `interopCompositeCheck`, Vulkan JUnit, the installed Khronos loader/validation layer and a Lavapipe ICD. Full Minecraft testing is possible on this host but was deliberately not used to modify existing saves during this baseline audit. No runtime launch is needed to conclude that the current implementation and test gates have the concrete gaps listed above.

## Reproduction appendix: stale-count probe

The standard build, validation and interop commands above are sufficient to reproduce their reported results. The additional count probe was intentionally kept outside repository source. To recreate it without changing production/test files, save the following Gradle init script as `/tmp/voxy-bootstrap-classpath.gradle`:

```groovy
gradle.projectsEvaluated {
    rootProject.tasks.register("auditClasspath") {
        doLast {
            new File("/tmp/voxy-bootstrap-classpath.txt").text =
                rootProject.sourceSets.test.runtimeClasspath.asPath
        }
    }
}
```

Run `./gradlew -I /tmp/voxy-bootstrap-classpath.gradle auditClasspath --offline` after building. Save this source as `/tmp/BootstrapCountProbe.java`:

```java
import me.cortex.voxy.client.core.vk.*;
import org.lwjgl.system.MemoryUtil;
public class BootstrapCountProbe {
 public static void main(String[] args) {
  VkContext.init(); VkFrameTracker.init();
  var terrain = SyntheticTerrain.boundaryCases();
  var res = new VkTerrainResources(terrain.sectionCount(), terrain.totalQuads(), 4096, terrain.maxStateId()+1);
  int[] starts=terrain.writeGeometry(res.geometry);
  terrain.writeMetadata(res.sectionMetadata,starts); res.fillModels(VkTerrainResources.MODEL_FLAG_SHADED);
  terrain.writeIndirectLookup(res.indirectLookup); terrain.writeVisibility(res.visibility,1,null);
  VkSceneUniform.write(res.uniform,VkSceneUniform.perspective(1f,1f,0.1f,2000f),SyntheticTerrain.ORIGIN,1,new float[]{0,0,0});
  var builder=new VkMergedTableBuilder(res,VkTerrainRenderer.Barriers.CONSERVATIVE);
  int draws=SyntheticTerrain.maxFaceDrawCount(terrain.totalQuads(),res.indexQuadCapacity);
  for(int supplied : new int[]{terrain.sectionCount(),0,terrain.sectionCount()-1}) {
   var tracker=VkFrameTracker.get();var cmd=tracker.beginFrame();builder.record(cmd,supplied,draws);tracker.endFrame();tracker.waitForFrame();
   int n=MemoryUtil.memGetInt(res.mergedPrefix.addr());
   long indices=0;for(int d=0;d<draws;d++)indices+=Integer.toUnsignedLong(MemoryUtil.memGetInt(res.mergedDraw.addr()+d*20L));
   System.out.println("AUDIT currentCount="+terrain.sectionCount()+" suppliedCount="+supplied+" entries="+n+" totalQuads="+MemoryUtil.memGetInt(res.mergedPrefix.addr()+4L+n*4L)+" emittedIndices="+indices);
  }
  builder.free();res.free();VkSampler.shutdown();VkQuadIndexBuffer.shutdown();VkFrameTracker.shutdown();VkContext.shutdown();
 }
}
```

Run with the resolved classpath (Python is only used to pass the classpath without shell escaping):

```python
from pathlib import Path
import subprocess
subprocess.run([
    "java", "--enable-native-access=ALL-UNNAMED", "-cp",
    Path("/tmp/voxy-bootstrap-classpath.txt").read_text(),
    "/tmp/BootstrapCountProbe.java",
], check=True)
```

This requires normal GPU access, just as the Vulkan tests do. It holds the GPU lookup at nine sections and varies the host count to isolate D1; it does not simulate a Minecraft world or claim to reproduce every changing-count symptom.

### Primary source entry points

- [Backend selection](../../src/main/java/me/cortex/voxy/client/VoxyClient.java), [Minecraft renderer gate](../../src/main/java/me/cortex/voxy/client/mixin/minecraft/MixinLevelRenderer.java), [Sodium integration hook](../../src/main/java/me/cortex/voxy/client/mixin/sodium/MixinDefaultChunkRenderer.java).
- [Hierarchical scene and request/reclaim loop](../../src/main/java/me/cortex/voxy/client/core/vk/VkHierarchicalScene.java), [table builder](../../src/main/java/me/cortex/voxy/client/core/vk/VkMergedTableBuilder.java), [prefix shader](../../src/main/resources/assets/voxy/shaders/lod/vk/merged_prefix.comp).
- [Depth visualizer](../../src/main/java/me/cortex/voxy/client/core/vk/VkDepthVisualise.java), [render target](../../src/main/java/me/cortex/voxy/client/core/vk/VkRenderTarget.java), [HiZ](../../src/main/java/me/cortex/voxy/client/core/vk/VkHiZ.java).
- [Context/validation collection](../../src/main/java/me/cortex/voxy/client/core/vk/VkContext.java), [frame tracker](../../src/main/java/me/cortex/voxy/client/core/vk/VkFrameTracker.java), [descriptor binding](../../src/main/java/me/cortex/voxy/client/core/vk/shader/VkAutoBindingShader.java).
- [Interop/lifecycle controller](../../src/main/java/me/cortex/voxy/client/core/vk/interop/VkInteropProbe.java), [test availability guard](../../src/test/java/me/cortex/voxy/vk/VulkanTestSupport.java).
