# Repo map

Subsystem → source paths → relevant historical reports. Paths are verified against
current source; historical reports are evidence/rationale only (see note at the end).

## Build / project

| Path | Role |
|---|---|
| `build.gradle`, `settings.gradle`, `gradle.properties` | Fabric/Loom build for Minecraft 26.2, Loader 0.19.3, Java 25. See [testing.md](testing.md) for task list and known packaging discrepancy (`from("LICENSE")` vs tracked `LICENSE.md`). |
| `init.gradle` | Used only by the `manual-artifact` CI workflow. |
| `.github/workflows/` | `check-does-build.yml` (push), `check-does-build-pr.yml` (PR), `manual-artifact.yml` (dispatch) — all `ubuntu-latest`, plain `./gradlew build`. No Vulkan/GPU runner. |
| `dummyprovider.fabric.mod.json` | Dummy Sodium provider metadata bundled into the jar. |

## World / persistence (`common/`, `commonImpl/`) — GPU-agnostic

| Subsystem | Path |
|---|---|
| World engine, sections, active-section tracking | `src/main/java/me/cortex/voxy/common/world/` (`WorldEngine`, `WorldSection`, `WorldUpdater`, `ActiveSectionTracker`) |
| Voxelization / mipping | `src/main/java/me/cortex/voxy/common/voxelization/` |
| Mapping (block-state ↔ id) | `src/main/java/me/cortex/voxy/common/world/other/Mapper.java` |
| Persistence / storage backends | `src/main/java/me/cortex/voxy/common/config/storage/` (LMDB, RocksDB, Redis, in-memory, compression adaptors) |
| Compression | `src/main/java/me/cortex/voxy/common/config/compressors/` (LZ4, LZMA, ZSTD) |
| Threading / allocators | `src/main/java/me/cortex/voxy/common/thread/`, `src/main/java/me/cortex/voxy/common/util/` (`AllocationArena`, `MemoryBuffer`, `UnsafeUtil`) |
| Mod/world lifecycle, importers | `src/main/java/me/cortex/voxy/commonImpl/` (`VoxyCommon`, `VoxyInstance`, `WorldIdentifier`, `importers/DHImporter.java`) |

## Shared client boundary (used by both GL and Vulkan)

| Subsystem | Path |
|---|---|
| CPU model bakery, atlas layout, upload boundary | `src/main/java/me/cortex/voxy/client/core/model/` (`ModelBakerySubsystem`, `ModelAtlasLayout`, `ModelUploadTarget`, `SoftwareRasterizer`) |
| Quad/geometry generation | `src/main/java/me/cortex/voxy/client/core/rendering/building/` (`RenderDataFactory`, `RenderGenerationService`) |
| Hierarchy management (shared) | `src/main/java/me/cortex/voxy/client/core/rendering/hierachical/` (`NodeManager`, `NodeStore`, `AsyncNodeManager`, `NodeCleaner`) — note the `hierachical` spelling is load-bearing in package paths |
| Backend selection | `src/main/java/me/cortex/voxy/client/VoxyClient.java` |

## OpenGL renderer

| Subsystem | Path |
|---|---|
| Pipeline / render system | `src/main/java/me/cortex/voxy/client/core/NormalRenderPipeline.java`, `VoxyRenderSystem.java`, `RenderPipelineFactory.java` |
| Section rendering (MDIC) | `src/main/java/me/cortex/voxy/client/core/rendering/section/backend/mdic/` |
| GL object wrappers | `src/main/java/me/cortex/voxy/client/core/gl/` (`GlBuffer`, `GlTexture`, `GlFramebuffer`, `GlPersistentMappedBuffer`, shader loader) |
| GL shaders | `src/main/resources/assets/voxy/shaders/lod/gl46/`, `.../hierarchical/` (shared GL+VK via `#ifdef VULKAN`), root `lod/*.glsl` includes |

## Vulkan renderer

| Subsystem | Path | Historical reports |
|---|---|---|
| Context, device, queue | `src/main/java/me/cortex/voxy/client/core/vk/VkContext.java` | phase1-completion, vulkan-validation-setup |
| Frame/submission tracking | `VkFrameTracker.java` | phase3-uploadstream-proposal |
| Buffers / textures / samplers | `VkBuffer.java`, `VkTexture.java`, `VkSampler.java` | phase3-image-abstraction |
| Shader compile/reflect/bind | `src/main/java/me/cortex/voxy/client/core/vk/shader/` (`SpirvCompiler`, `SpirvReflect`, `VkShader`, `VkAutoBindingShader`, `VkDescriptorSetGroup`) | phase2-binding-audit, phase2-glsl-compat, phase3-descriptor-survey |
| Push constants | see [gpu-contracts.md](gpu-contracts.md) | phase2-pushconstant-todo |
| Hierarchy / node tree | `VkNodeTree.java`, `VkHierarchicalScene.java` | phase5c4-plan, phase5c5-plan |
| Traversal | `VkTraversal.java` + `shaders/lod/hierarchical/traversal_dev.comp`, `queue.glsl`, `node.glsl` | phase5c4-plan, phase6-device-sharing-survey |
| HiZ | `VkHiZ.java` + `shaders/lod/vk/hiz_blit.vert` | phase5c4-plan |
| Command/table generation | `VkMergedTableBuilder.java` + `shaders/lod/vk/{prep,cmdgen,merged_prefix,translucent_prefix,translucent_gen}.comp` | phase4-stage2/3/4-completion |
| Cull pass | `VkCullPass.java` + `shaders/lod/vk/cull_raster.{vert,frag}` | phase4-stage4-completion |
| Terrain rendering / resources | `VkTerrainRenderer.java`, `VkTerrainResources.java` + `shaders/lod/vk/quads3.vert` | phase4-completion |
| Depth resolve / reprojection / reverse-Z | `VkDepthResolve.java`, `VkDepth.java` + `shaders/lod/vk/depth_resolve.*` | phase5c-reverse-z, phase5c-y-orientation |
| Depth visualizer (has D2 defect) | `VkDepthVisualise.java` + `shaders/lod/vk/depth_visualise.*` | phase5c1c-completion, phase6-sync-validation |
| Barriers | `VkBarriers.java` | phase3-barrier-survey, phase4-buffer-hazards |
| Geometry upload / reclamation | `VkGeometryFlush.java`, `VkModelUploadTarget.java`, `VkNodeUploadTarget.java` | phase6-geometry-reclaim |
| Real meshing / model bakery adapter | `VkRealMesher.java`, `VkRealModelBakery.java`, `VkRealSectionUpload.java` | phase5c2a-completion, phase5c2b-boundary |
| Scene uniform | `VkSceneUniform.java` | see [gpu-contracts.md](gpu-contracts.md) |
| Synthetic test scene | `SyntheticTerrain.java` | phase4-stage1-completion |
| GPU timing | `VkGpuTimer.java` | — |
| Host viewport | `VkHostViewport.java` | phase5c1b-completion |

## GL/Vulkan interop (macOS)

| Subsystem | Path | Historical reports |
|---|---|---|
| IOSurface/CGL bridge | `src/main/java/me/cortex/voxy/client/core/vk/interop/` (`IOSurf.java`, `Cgl.java`, `VkInteropImage.java`) | phase5b-composite |
| GL-side compositor | `GlInteropCompositor.java`, `GlScratchFramebuffer.java` | phase5b-stencil |
| Cross-API sync | `GlVkSync.java` | phase5a-gl-to-vk-sync, phase6-sync-validation |
| Depth import | `GlDepthImport.java` | phase5c1c-completion |
| Top-level controller / scene modes | `VkInteropProbe.java` | phase5c-plan, phase5c1a..1e-completion, phase5-status |
| Capability probe | `VkInteropProbe` internals + `docs/probes/{ExtProbe,FeatProbe}.java` | vulkan-port-feasibility, phase0-b1-mdi-measurement |

## Mixins (Minecraft/Sodium/Iris integration points)

| Subsystem | Path |
|---|---|
| Minecraft hooks | `src/main/java/me/cortex/voxy/client/mixin/minecraft/` (`MixinLevelRenderer` — Vulkan `VoxyRenderSystem` deliberately not constructed here; `MixinRenderSystem`, `MixinFogRenderer`, `MixinClientLevel`, `MixinDebugScreenEntryList`) |
| Sodium hooks | `src/main/java/me/cortex/voxy/client/mixin/sodium/` (`MixinDefaultChunkRenderer` — **actual Vulkan entry point**, calls `VkInteropProbe.composite()` at CUTOUT; `MixinSodiumWorldRenderer`, `MixinRenderSectionManager`, `MixinVisibleChunkCollector`) |
| Iris compat | `src/main/java/me/cortex/voxy/client/core/util/IrisUtil.java` (intentional no-op stub) |
| Flashback / Nvidium compat | `src/main/java/me/cortex/voxy/client/mixin/flashback/`, `src/main/java/me/cortex/voxy/client/mixin/nvidium/` |

## Tests

All Vulkan JUnit tests live under `src/test/java/me/cortex/voxy/vk/` (52 files, one
package — not split by subsystem). `VulkanTestSupport.requireVulkan()` is the shared
skip-if-no-GPU guard; note it catches **any** `Throwable`, not just missing-hardware
errors (see [testing.md](testing.md)). Two bench/manual entry points live under
`vk/bench/`: `GlToVkSyncBench.java`, `InteropCompositeCheck.java`.

## Historical reports (`docs/*.md`, 45 files)

These are **frozen evidence and rationale**, not current specs — several explicitly
contain later corrections appended below an earlier, now-superseded conclusion. Read
`docs/ai/bootstrap-audit.md`'s "Historical report index" and "Superseded decisions"
sections before trusting any single historical report's *opening* claim. Two Java
capability probes (`docs/probes/ExtProbe.java`, `FeatProbe.java`) are standalone survey
programs, not build-verified code. Reference PNGs under `docs/images/` are historical
screenshots, not automated visual-regression goldens.

Do not edit historical reports to "fix" them — file a new report or update
[current-state.md](current-state.md) instead.
