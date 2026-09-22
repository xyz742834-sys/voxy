# Independent review of repository AI context

**Verdict: PASS_WITH_CORRECTIONS**

Reviewed on 2026-09-22 at `d476f559e2648ad7fbf1c325059f2b9914e80c29`.
The initial working tree was clean. Reviewed `CLAUDE.md`, all six linked context
documents, and `docs/ai/bootstrap-audit.md` against Java, GLSL, Gradle, workflows,
test implementations, and fresh executions. Only this review was added; production
source, tests, build files, and the reviewed documents were not edited.

The context correctly describes a diagnostic Vulkan path, its major GPU contracts,
and substantial integration and validation gaps. It is useful with the corrections
below. The most consequential errors concern validation evidence and device
capabilities. In particular, the interop check also emits an unsuppressed diagnostic
while reporting success. This verdict assesses the documentation, not production
readiness or Vulkan correctness.

## Fresh verification

Tests ran on Apple M4 Pro/macOS arm64. The validation log reports device API
`1.4.357`, driver `0.2.2210`, subgroup size 32, push-constant limit 4096,
`validation=true`, and `syncValidation=true`.

| Command | Independently observed result |
|---|---|
| `./gradlew build --offline --rerun-tasks` | Successful; 9 tasks executed. JUnit: 282 total, 278 passed, 4 skipped, zero failures/errors. Produced jar has no root license file. |
| `./gradlew test --offline --rerun-tasks -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true` | Successful; 281 passed, 1 skipped. Four unexpected `VUID-vkCmdDraw-imageLayout-00344` diagnostics in `VkDepthVisualiseTest`; three unexpected `SYNC-HAZARD-READ-AFTER-WRITE` diagnostics in `VkHiZDepthSourceTest`. |
| `./gradlew interopCompositeCheck --offline` | 45 checks passed. |
| `./gradlew interopCompositeCheck --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true` | 45 checks passed; 28 suppressed messages **and one unsuppressed validation diagnostic**. Repeated once because this contradicted the documentation; the result reproduced. |
| Bootstrap appendix count probe, copied to `/tmp/ContextReviewCountProbe.java` and inspected before execution | Current count 9 / supplied count 9: 63 entries, 189 quads, 1134 indices. Current count 9 / supplied count 0: 63 entries, 189 quads, **0 indices**. Confirms the bounded D1 reproduction. |

The intentional fill-buffer WAW diagnostic is separate from the seven unexpected
JUnit diagnostics. Counts above come from JUnit XML and check output, not from
counting duplicate message text in console logs. The interop result is a separate
execution and must not be folded into the JUnit count.

Local evidence: `/tmp/voxy-context-review-build.log`,
`/tmp/voxy-context-review-validation.log`,
`/tmp/voxy-context-review-validation-tests/`,
`/tmp/voxy-context-review-interop.log`,
`/tmp/voxy-context-review-interop-validation.log`,
`/tmp/voxy-context-review-interop-validation-repeat.log`, and
`/tmp/voxy-context-review-count-probe.log`. These are temporary artifacts; the material
results and diagnostic identity are captured here. No Minecraft smoke/soak, GL
renderer parity test, fresh-cache build, or alternate-GPU run was performed.

## Required corrections

### R1 — Interop validation is not clean (high; observed twice)

**Locations:** `docs/ai/testing.md` → “4. Interop checks”; `docs/ai/bootstrap-audit.md`
→ “Testing status / Commands executed during this audit”, validated interop row.
Also extend `current-state.md` → D6 so its test-gate warning covers the standalone
interop checker as well as JUnit.

Both current validated interop runs emit:

```text
[UNASSIGNED-VkDescriptorImageInfo-BoundResourceFreedMemoryAccess]
vkUpdateDescriptorSets(): pDescriptorWrites[0].pImageInfo[0].image
VkImage 0x750000000075 used with no memory bound.
...
suppressed 28 known interop validation messages
ALL CHECKS PASSED
```

Replace the living document's “no unsuppressed validation errors observed” with the
current result. Treat the bootstrap statement as a prior observation superseded by
this review, not as current clean evidence. This diagnostic appears during the C11
depth-visualization path; it is not the deliberate missing-depth-attachment control.

**Source evidence:** [InteropCompositeCheck.java](../../src/test/java/me/cortex/voxy/vk/bench/InteropCompositeCheck.java#L520)
records the visualization; its final result at lines 279–289 depends on `failures`,
not a clean validation-message collection.
[VkContext.java](../../src/main/java/me/cortex/voxy/client/core/vk/VkContext.java#L436)
suppresses only three specified VUIDs for registered interop images. The reported
`UNASSIGNED` identifier is absent from that list. Its root cause and whether it is
another external-memory metadata limitation remain unproven; this review does not
recommend broadening suppression to hide it.

### R2 — The two synchronization controls are conflated (high; source-confirmed and observed)

**Locations:**

- `current-state.md` → “Current integration status / Test suite”: the claim that
  `missingBarrierIsDetected` runs and passes with validation enabled is false.
- `testing.md` → “3. Validation-layer run”: replace the “counterpart being replaced”
  explanation and the assertion that the descriptor control is “treated as passing”.
- `constraints.md` → “Test coverage gaps are real, not incidental”: replace “passes”.
- `gpu-contracts.md` → “Synchronization / barrier expectations / Confirmed gaps”:
  the fill-buffer control has the wrong method name.

**Correct statement:** `VkBarriersTest.missingBarrierIsDetected()` issues two
descriptor-bound SSBO dispatches, receives zero messages on this stack, and is
**skipped**, including when validation is enabled.
`VkBarriersTest.plainBufferHazardIsNowDetected()` issues two unsynchronized
`vkCmdFillBuffer` calls and **passes by detecting their WAW hazard**.

**Source evidence:** [VkBarriersTest.java](../../src/test/java/me/cortex/voxy/vk/VkBarriersTest.java#L129),
especially `Assumptions.abort` at line 149 and the fill-buffer assertion at lines
175–195. The abort is unconditional after dispatch/readback: the first test cannot
pass even if a future stack starts detecting this hazard. Fresh XML identifies
`missingBarrierIsDetected()` as the sole validated-run skip. The bootstrap audit's
“Testing status” paragraph already states this correctly; the living summaries
introduced the contradiction.

### R3 — Device requirements and enabled features are overstated (medium; source-confirmed)

**Locations:** `architecture.md` → “Context and resource model”; `current-state.md`
→ “What works / Vulkan context”; `bootstrap-audit.md` → “Current Vulkan frame”.

The instance requests `min(queryInstanceVersion(), Vulkan 1.4)`, rather than enforcing
a Vulkan 1.4 device requirement. Device creation explicitly enables Vulkan 1.1
`shaderDrawParameters` and Vulkan 1.3 `dynamicRendering`/`synchronization2`, plus the
listed base features. **It does not enable timeline semaphores.** Submission uses a
fence. State the observed 1.4 host separately from the initialization policy; do not
infer portability to all lower-version devices from the fallback expression.

**Source evidence:** [VkContext.java](../../src/main/java/me/cortex/voxy/client/core/vk/VkContext.java#L134)
and its feature chain at lines 256–284;
[VkFrameTracker.java](../../src/main/java/me/cortex/voxy/client/core/vk/VkFrameTracker.java#L147).
The comment mentioning timeline semaphores is not evidence of an enabled feature.
The Vulkan 1.2 shader target is correctly documented and implemented in
[SpirvCompiler.java](../../src/main/java/me/cortex/voxy/client/core/vk/shader/SpirvCompiler.java#L26).
Queue selection tests only `VK_QUEUE_GRAPHICS_BIT` (line 245); the selected queue is
used for compute too, but an explicit combined-capability check is not implemented.

### R4 — CI workflow attribution is reversed (medium; source-confirmed)

**Locations:** `repo-map.md` → “Build / project”, `init.gradle` and workflow rows;
`current-state.md` → “Current integration status / CI”.

`init.gradle` is used by the **push** workflow, not the manual-artifact workflow.
Replace “all plain `./gradlew build`” with the exact distinction:

- [check-does-build.yml](../../.github/workflows/check-does-build.yml#L32):
  `./gradlew -I init.gradle build`.
- [check-does-build-pr.yml](../../.github/workflows/check-does-build-pr.yml):
  `./gradlew build`.
- [manual-artifact.yml](../../.github/workflows/manual-artifact.yml#L25):
  `./gradlew build`.

All use `ubuntu-latest`; none configures a required GPU/validation lane. That broader
coverage warning remains justified. Prefer “no configured required GPU coverage”
over claiming the workflow files establish that no GPU test can ever execute.

### R5 — Make frame order and lifecycle preconditions explicit (medium; source-confirmed)

**Locations:** `architecture.md` → “Frame sequence (HIERARCHICAL mode)”;
`bootstrap-audit.md` → “Current Vulkan frame”; `gpu-contracts.md` → “GPU lifetime
assumptions” and “Synchronization / barrier expectations”.

The diagrams place CPU request servicing after the GL composite. Actual order is
**Vulkan submit → explicit fence wait → diagnostics/request servicing → GL composite**.
[VkInteropProbe.java](../../src/main/java/me/cortex/voxy/client/core/vk/interop/VkInteropProbe.java#L887)
waits at line 890, services requests at line 909, and composites at line 946.

Document these additional preconditions so “single frame in flight” is not mistaken
for automatic protection of all host writes:

- `VkFrameTracker.endFrame()` submits without waiting. `beginFrame()` waits before
  reusing the command buffer; `waitForFrame()` is a separate caller action. The
  probe's uniform/geometry preparation occurs **before** `beginFrame()`, so its safety
  relies on the preceding explicit completion wait. Deferred frees are drained at
  the next frame begin or `waitIdle()`, not by `endFrame()` or `waitForFrame()`.
  Evidence: [VkFrameTracker.java](../../src/main/java/me/cortex/voxy/client/core/vk/VkFrameTracker.java#L115)
  and [VkInteropProbe.java](../../src/main/java/me/cortex/voxy/client/core/vk/interop/VkInteropProbe.java#L845).
- Imported depth must be primed from UNDEFINED to GENERAL **before GL first writes
  it**. `primeLayout()` submits and waits, and rejects calls during recording.
  Evidence: [VkInteropImage.java](../../src/main/java/me/cortex/voxy/client/core/vk/interop/VkInteropImage.java#L178)
  and the [probe allocation call](../../src/main/java/me/cortex/voxy/client/core/vk/interop/VkInteropProbe.java#L713).
- D3's observed color LOAD error is valid, but the same helper also selects depth
  LOAD for `clearDepth == null` while its depth transition advertises only
  `DEPTH_STENCIL_ATTACHMENT_WRITE`. Include the depth LOAD/read edge in the contract
  and in follow-up verification. Evidence:
  [VkRenderTarget.java](../../src/main/java/me/cortex/voxy/client/core/vk/VkRenderTarget.java#L110).
  This is an additional source-level coverage concern, not an extra reproduced
  diagnostic beyond the three reported color hazards.

The existing warnings about GL-read completion before shared-image reuse, intermediate
HiZ mip visibility to compute, logical previous-table references after reclamation,
and world identity remain appropriate risks. Passing endpoint tests do not settle them.

### R6 — Clarify the atlas dimensions and repair layout source anchors (low; source-confirmed)

**Locations:** `gpu-contracts.md` → “Java ↔ shader buffer layouts”, atlas paragraph,
Requests/render queue row, and Merged entries/prefix row; corresponding atlas paragraph
in `bootstrap-audit.md`.

Write **“a 256×256 grid of model tiles; each tile is 48×32 texels at mip 0”**, rather
than the ambiguous “256×256 model tiles”. The atlas is indeed 12288×8192, with six
16×16 cells per tile and four allocated mip levels. Evidence:
[ModelAtlasLayout.java](../../src/main/java/me/cortex/voxy/client/core/model/ModelAtlasLayout.java#L34).
The approximately 534 MB storage estimate is consistent with those four levels.

The Requests/render queue declarations are in
[traversal_dev.comp](../../src/main/resources/assets/voxy/shaders/lod/hierarchical/traversal_dev.comp#L28),
not `queue.glsl`. Merged entries are generated by
[cmdgen.comp](../../src/main/resources/assets/voxy/shaders/lod/vk/cmdgen.comp), not
`VkGeometryFlush.flush()` (which uploads geometry and section metadata).
Use `lod/gl46/bindings.glsl` as the full scene-UBO include path. These are source-anchor
corrections; the documented byte sizes and binding numbers checked here are correct.

### R7 — Do not imply the bootstrap audit ran Minecraft (medium; evidence-provenance correction)

**Location:** `testing.md` → “5. Minecraft smoke/soak”, “same as the 2026-09-22 audit did”.

Remove that wording. The bootstrap audit explicitly says no Minecraft world was
opened or changed and that post-fix Minecraft verification remained outstanding.
The inspected automated tests are offscreen or isolated fixtures; no test or fresh
result reviewed here supplies the missing live-world evidence. Describe manual
logs/screenshots as the required future evidence, not as completed bootstrap work.

## Contracts and architectural claims that survived checking

| Area | Evidence and result |
|---|---|
| Backend selection and entry | `VoxyClient.initVoxyClient`, `MixinLevelRenderer`, and `MixinDefaultChunkRenderer` confirm GL preference, the Minecraft-GL prerequisite, and the separate Sodium CUTOUT probe hook. `VkInteropProbe.resolveMode` confirms default PAIR and property-presence selection of HIERARCHICAL. No normal Vulkan `VoxyRenderSystem` or borrowed Minecraft device is implemented. |
| CPU boundary and repository map | Shared model/node targets, `NodeManager`, `RenderDataFactory`, and CPU geometry allocation exist. GL pipeline classes reside in `client/core/` and `client/core/rendering/`; `client/core/gl/` supplies wrappers, so the architecture's abbreviated layering box should be read with the fuller repo map. Inventory confirms 257 main Java files, 52 test Java files, 49 JUnit-bearing classes, and 45 root documentation reports. |
| Scene/traversal UBO | `VkSceneUniform.write` and `lod/gl46/bindings.glsl` agree on 0/64/76/80 and a 92-byte written span. `VkTerrainResources` allocates 1024 bytes, but descriptors bind range 92; allocation padding is not evidence of a 96-byte descriptor range. `VkTraversal.writeUniform` and `traversal_dev.comp` agree on all documented offsets through 204 and size 208. |
| Geometry/model/node records | `RenderDataFactory` and `quad_format.glsl`: 8-byte quads. `BasicAsyncGeometryManager.SectionMeta` and `section.glsl`: 32-byte metadata, packed run counts. `ModelFactory`/`VkModelUploadTarget` and `block_model.glsl`: 64-byte model records. `NodeStore.writeNode`, `VkNodeTree`, and `node.glsl`: 16-byte nodes, 24-bit pointers and the documented sentinels. |
| Queues/tables/commands | `VkTraversal`, `queue.glsl`, and `traversal_dev.comp`: five 16-byte queue records, 8-byte request header/entries, 4-byte render-queue header/IDs. `VkTerrainResources`, `cmdgen.comp`, and `merged_prefix.comp`: 8-byte merged entries, dense seven-face ranges, 4-byte prefixes plus sentinel, 20-byte indexed commands and 12-byte dispatch dimensions. Equal adjacent prefixes are valid. |
| Descriptor bindings | Checked Java defines/bind calls against shader declarations for terrain, table generation, temporal/translucent, cull, traversal, HiZ and depth passes. The published numeric maps match. Temporal/translucent terrain draws still bind their selected tables at terrain bindings 10/11; generation bindings 16/17 or 20 are not their terrain binding numbers. `VkShader` rejects nonzero sets and type/count collisions. |
| Push constants | Queue index 4 bytes; merged and temporal prefix 12; translucent prefix 4; depth reprojection 128. Java writes match shader declarations. `index_probe.comp` also has an exact 4-byte `totalQuads` block, which can replace the document's vague “small” description. No implemented Vulkan SSAO push block was found. |
| D1–D6 | D1 reproduced with a positive control. D2/D3/D6 reproduced in the fresh validated suite. D4 follows from permanent `pendingMesh` membership and the exhaustion early return in `serviceRequests`. D5 remains a source-level lifecycle risk: no `VkInteropProbe.shutdown()` caller found, and scene validity checks liveness rather than active-engine identity. |
| Coverage and historical drift | `ModelAtlasLayoutTest` does not exist; only the production comment names it, as the audit warns. Related atlas tests do exist. Reclamation tests exercise isolated managers, not a running-world request/retry/reuse cycle. No automatic changing-count hierarchy or world-switch test was found. Historical clean-validation and completion labels cannot supersede the fresh diagnostics. |
| Build/package | Java 25, Minecraft 26.2, Loader 0.19.3, Gradle 9.6.0 and JUnit 5.10.2 match configuration. `jar { from("LICENSE") }` still disagrees with tracked `LICENSE.md`; fresh jar inspection confirms the omission. |

No wrong numeric descriptor binding, buffer-member offset, record stride, or implemented
push-constant size was found in the reviewed tables. This is targeted source comparison
and runtime coverage, not exhaustive reflected ABI verification. The current reflection
guards do not establish Java-to-GLSL member-offset equivalence. `CLAUDE.md` needs no
direct correction; its linked context needs the changes above before reuse as a trusted
handoff. Existing prototype and lifecycle limitations should remain explicit.
