VERDICT: REDESIGN

Independent review, round 20, 2026-10-09. Reviewed exact HEAD `39584eba58731109ee4b0d38e180f173b55104ba`; initial tracked tree clean. No production source, tests, fixtures or committed evidence changed. Review scripts and results are under `.agent-run/r20/`. No Minecraft/native/live launch was performed.

**Blocking DELIVERY-BOUNDARY — CONFIRMED.** The standing rounds 4–19 boundary remains. This is a flagged, default-off experiment, not an accepted native integration foundation or delivered Minecraft-native Voxy LoD. Normal configuration does not render native LoD, and selection/traversal/culling, translucency, lighting and general update/lifecycle acceptance remain absent (`docs/ai/project-goal.md:9`; `docs/ai/handoff.md:31`, `:153`; survey `:1571`). The candidate accepts this boundary. The bounded measurements below are confirmed; REDESIGN does not reject their original pixel data.

## 1. R19 repairs — CONFIRMED

**R19-INSTANCE-INVENTORY closed.** `scripts/verify.py:1586` computes precisely frame 1 and every 60th frame through the frames counted, truncated to the first 256, then compares the complete ordered inventory. The unchanged fixture exercises gaps, trailing omissions and the cap (`scripts/tests/test_instance_gate.py:146`). On a complete copy of the newest run, retaining only frames 1200 and 4680, deleting other instance log lines and updating the aggregate and all manifest hashes, replay exits **1** at the inventory guard. This repeats R19's two-sample attack on the current package; no source-binding failure masks it (`attack-sparse-instance.json`). The original main launch has **79 samples / 4680 frames / max 68 active sections**; the ladder launch has **86 / 5100 / max 45**, and both independently match the complete inventory (`validation.json`). Active sections are cache occupancy, not verified storage contents.

**R19-TEST-INSTANCE closed.** Removed each of the seven named guards separately in memory, in fresh processes, running the entire unchanged `test_instance_gate` module. Lines **1514, 1523, 1555, 1558, 1560, 1562, 1569** all make its suite fail. Removing the new inventory guard at **1592** also fails. The new assertions name each guard's own message (`scripts/tests/test_instance_gate.py:157`). No fixture was shrunk or production file edited. Reproduce with `python3 .agent-run/r20/guards.py`; `guards.json` and `guard-<line>.log` retain every result. These are removal sensitivity, not proof every redundant guard is independently necessary.

**R19-DOC-DRIFT closed.** Historical 073850 and 080812 run prose now attributes replay to the checkout each was built from (survey `:1465`, `:1532`; handoff `:127`). Current evidence is identified as 100957, older runs as predating current source (survey `:470`; handoff `:192`). The handoff command now points to the current 274-case count instead of stating 199. `git diff 9a6ca051 HEAD -- docs/ai/handoff.md docs/ai/vulkan-native-integration-survey.md` verifies the requested repairs. New real-LOAD limits below do not reopen those specific R19 items.

## 2. Real-LOAD — CONFIRMED original bounded pixels; REFUTED complete gate/provenance enforcement

### Independent rule and every judged sample

`python3 .agent-run/r20/recount.py` decodes PPM/VXF32 files directly; it imports no verify.py code. Each ladder colour identifies MC depth: LOW gives d_MC < 2^-16; rung i gives z_i < d_MC <= z_(i+1), with the last upper bound 1. For GEQUAL: d_V at/above the upper bound must show exact reference RGB; d_V at/below the exclusive lower bound must remain exact pre-real-LOAD RGB; inside the bracket either is allowed and counted undetermined. The BASE equality case uses GEQUAL directly. Depth zero means no Voxy geometry, which must leave the pixel unchanged. RGB equal to both before and reference is separately counted ambiguous. All reference depth values must be finite in [0,1]. The previous crop is the coexist crop, whose own per-pixel rule is independently recounted from the ladder crop.

All **22** ladder samples (both orientations), **22** coexist crops, **11** synthetic terrain-LOAD samples and **10** judged real-LOAD samples match their published counts. Every judged real sample uses all five inputs: ladder, coexist, actual real colour, reference colour and reference float depth. Counts in every real/terrain log line agree too. Every whole 4x4 crop block matches its retained full-readback thumbnail at the declared origin: ladder **118620**, rejected **118620**, coexist **118620**, terrain **59310**, real **54465** blocks. These are bounded retained-thumbnail anchors, not authenticated GPU capture provenance.

| draw | stage | geometry | must show | must hide | undetermined |
| --- | --- | ---: | ---: | ---: | ---: |
| 722 | turn | 77802 | 13874 | 0 | 63928 |
| 1202 | travel | 0 | 0 | 0 | 0 |
| 1682 | return | 77625 | 11410 | 0 | 66215 |
| 2162 | horizon | 42317 | 20610 | 0 | 21707 |
| 2642 | remove | 77625 | 11410 | 0 | 66215 |
| 3122 | reload | 97587 | 14032 | 0 | 83555 |
| 3602 | overworld | 97587 | 14032 | 0 | 83555 |
| 4082 | descend | 99072 | 51480 | 0 | 47592 |
| 4562 | ascend | 99072 | 0 | 0 | 99072 |
| 5042 | reconnect | 6577 | 6577 | 0 | 0 |

Total real-LOAD: **889600 band pixels**, **675264 geometry**, **214336 no geometry**, **143425 must show**, **0 must hide**, **531839 undetermined**, **674417 shown**, **847 hidden**, **0 ambiguous / other / violations**. Eight samples decide visible pixels; horizon decides **20610**. Draw 242 is the sole original skip (`atlas-pending`), corroborated by atlas request/completion log lines 492/496. Original pixels therefore confirm the candidate's bracket-grain visible-composition claim. **Occlusion by nearer MC geometry is untested**, and undetermined coincident-surface pixels do not establish a calibrated per-ray depth agreement.

### Reference, uniform and copy ordering — CONFIRMED source/retained scope

`McNativeRealLoad.java:177` requires a newly advanced Sodium camera capture and matching extent; `:207` meshes the engine's real level-3 sections, radius 4, through the existing mapper/bakery/mesher/upload components. `McNativeCamera.java:28` copies both matrices; Sodium CUTOUT capture is `MixinDefaultChunkRenderer.java:53`. The real reference and MC pass use the **same scene resources, uniform buffer, VkTerrainRenderer, descriptors/pipeline and width/height**: `McNativeRealScene.java:155` writes once, `:158` draws the reference, then `McNativeRealLoad.java:234` opens MC's LOAD/LOAD pass and `:242` records the same renderer. `VkTerrainRenderer.java:362`, `:421` sets its own extent/viewport and binds the same pipeline for either call. MC formats are checked against the reference's RGBA8/D32 at `McNativeRealLoad.java:171`.

The uniform is written only after successful `vkDeviceWaitIdle` (`McNativeRealScene.java:150`; `VkFrameTracker.java:216`), and remains unchanged through the fence-waited reference and MC pass. In this single render-thread, one-consumer-per-sampled-frame route there is no earlier pending MC draw using that scene in the same frame; previous sampled-frame submissions have already been made. This is the current explicit wait discipline, not an overlap-safe streaming design. Reference upload/table barriers run before its pass, the reference submission/fence is checked, and MC's copy is requested **after the try-with-resources pass closes** (`McNativeRealLoad.java:245`, `:272`, `:298`). Atlas copy is requested before any real-LOAD pass (`:184`). No copy is requested inside either pass.

Java filenames in this section are under `src/main/java/me/cortex/voxy/client/core/vk/mcnative/`, except VkFrameTracker/VkTerrainRenderer under `client/core/vk/` and the named mixin under `client/mixin/sodium/`.

### Lifetime — CONFIRMED ordering; stronger lifecycle/leak-free claims REFUTED

Travel/return/resize retire a replaced scene through MC `queueForDestroy` (`McNativeRealLoad.java:195`, `:431`, `:441`). Independent `javap -p -c` of the actual cached Minecraft 26.2 VulkanCommandEncoder/DestructionQueue shows copy callbacks use the same destruction queue, submission completion is awaited before rotation, and encoder destruction waits the graphics queue before draining it (`mc-encoder.javap`, `mc-destruction.javap`). Reference readback is fence-waited before CPU access. Real/atlas readback buffers close in callback finally blocks (`McNativeRealLoad.java:382`; `McNativeAtlas.java:89`), and unsuccessful request allocation closes its owned buffer. Device divergence/unobserved completion intentionally leak rather than destroy uncertain in-use resources and count/report the problem. Adopted context shutdown waits and checks owner-device identity before immediate scene destruction (`McNativeVkContext.java:313`; `McNativeRealLoad.java:456`). No original report/log indicates an in-use destruction or a counted leak.

**Non-blocking R20-REAL-ENGINE-IDENTITY — CONFIRMED source gap.** A live current engine is checked but the cached scene is not bound to its identity. Rebuild depends only on poison, coarse camera centre and extent (`McNativeRealLoad.java:180`, `:195`). LevelRenderer.close retires the scene, whereas `voxy$setWorld` only stops the regular renderer (`MixinLevelRenderer.java:34`, `:92`). Disconnect/reconnect at the same centre/extent therefore reuses the pre-disconnect GPU scene; the original log builds its fourth scene at line 639 and never builds another before reconnect's real draw at line 812, despite instance shutdown at 754. This is retained geometry from real sections, not proof of new-engine remeshing/content or lifecycle recovery. No GPU use-after-free was demonstrated: the scene owns its GPU resources and stays alive. Changing worlds at the same centre is not covered by the claimed pixel result.

**Non-blocking R20-DESTROY-ACCOUNTING — CONFIRMED.** `McNativeRealScene.java:204` swallows all six resource-free exceptions; `scene.free()` can return and destroyed=true with an unfreed child without incrementing closeFailures/leakedScenes. Atlas buffer close exceptions are also swallowed (`McNativeAtlas.java:89`). Thus zero counters do not independently prove no leaks. Successful lifetime ordering is source-supported, but failure cleanup and OS/GPU allocation counts were not measured.

### Gate attacks — original gate, refreshed complete manifests

`python3 .agent-run/r20/attacks.py` uses fresh copies of the committed full package. Manifest membership and hashes are regenerated after every mutation, so the refusals below reach semantic gates. Pixel/reference corruption, reference NaN depth, mismatched/nonfinite counts, missing/orphan files, missing/duplicate results, deleted/duplicate/changed judged log lines, and both-consumer/wrong-consumer handoffs replay **1**. An invented skip reason, with its log changed consistently, replays **1** at the fixed-set guard. Sparse instance inventory replays **1**. `attacks.json`, `extra-attacks.json` and `attack-*.json` contain exact exits/errors.

These mutations replay **0**: `real-skip-with-depth-file`, `real-known-skip-substitution`, `real-skip-log-count`, `real-min-nan`, `real-far-nan`, `real-far-10`, `real-capture-0`, `real-centre-empty`, `real-sceneQuads-negative`, `real-projection-flip`, `real-builds-0`, `real-sceneSections-1`, `log-voxy-error`, `real-device-null`, `real-fixed-skip-consistent`.

**Non-blocking R20-REAL-METADATA — CONFIRMED.** Real-LOAD checks counts, file inventory and pixel decisions, but never reconciles reported min/maxDepth, farPlane, cameraCapture, sceneQuads or stage; sceneCentre need only be a list; projectionAdjusted need only be boolean; scene section/draw counts need only be positive (`scripts/verify.py:2217`, `:2223`, `:2261`). NaN minDepth/farPlane, far=10, captures=0, centre=[], quads=-1, a flipped projectionAdjusted value, and an invented positive section count all pass. builds=0 passes despite judged scenes, and device=null bypasses device comparison (`:2166`, `:2192`). The original source/log/report supports its declared 2048 far plane, unadjusted projection and four builds; replay alone does not authenticate these facts. Numerical camera matrices/uniform bytes are not retained per sample.

**Non-blocking R20-REAL-SKIP-PROVENANCE — CONFIRMED.** Replacing judged draw 722 with `atlas-pending`, deleting its four files, reducing both draw counters and replacing its log line passes replay 0, although the earlier log already says atlas READY. Changing original skip 242 to `no-world-engine` with its log changed also passes while the instance inventory shows a live engine. Only the fixed vocabulary is enforced (`scripts/verify.py:2206`); the reason is not corroborated. Skip entries also accept an invented referenceDepthFile and stray count text in the skip log. This can conceal a sample, but the untouched package's ten judged samples were independently recounted and are not concealed. Do not cite replay as authenticating the reason for each skip.

The appended Voxy storage-close ERROR passes replay 0 too. This is the already disclosed exclusion of the native diagnostic log scan, not a new stage-gate omission: `native_log_checks` rejects such lines (`scripts/verify.py:3152`). I independently ran that scanner on both original logs: validation insertion present, no unexpected diagnostics or application ERRORs (`validation.json`).

## 3. Blaze3D atlas — CONFIRMED bounded GL-free route and layout; refresh completeness REFUTED

Native reads mip 0 as RGBA8 bytes and assembles little-endian int[] (`McNativeAtlas.java:43`, `:52`, `:68`), matching GL's mip-0 `GL_RGBA / GL_UNSIGNED_BYTE` int[] read on this little-endian Apple host (`SoftwareModelTextureBakery.java:131`). Both use the identical `useAtlas` software sampler path (`:152`, `:155`). The entire raw GL read/state save/restore substring is byte-identical to R19 (`atlas-comparison.json`, `git diff 9a6ca051 HEAD -- .../SoftwareModelTextureBakery.java`). The native supplied branch validates extent and returns before the first GL query/cast (`:82`). The actual run logs a 2048x2048 copy/receipt; the baked real terrain renders. This verifies code layout and bounded execution, not a retained full GL-vs-Vulkan atlas byte comparison.

The traced native real bake route is VkRealModelBakery -> ModelFactory(VkModelUploadTarget) -> SoftwareModelTextureBakery with supplied pixels; VkRealMesher constructs RenderDataFactory directly. It never constructs the GL ModelStore/ModelBakerySubsystem/VoxyRenderSystem or interop compositor. No further raw GL call was found on this supplied native route. GL imports/constants are not calls. The fallback GL calls remain reachable when supplyAtlas has never run, as intended for the existing GL path.

**Non-blocking R20-ATLAS-RELOAD — CONFIRMED.** `McNativeAtlas.java:39` requests only once; state and `SoftwareModelTextureBakery.suppliedAtlas` have no reload/disconnect reset (`rg -n 'supplyAtlas|suppliedAtlas|McNativeAtlas' src`). A same-size resource-pack replacement continues using old pixels; a different-size atlas causes extent refusal instead of refresh. The 16 MiB supplied 2048x2048 CPU array stays cached for the JVM lifetime. In the original run reload reused an unchanged atlas, so it does not prove content-refresh support. Normal unflagged sessions never populate this cache.

## 4. Tests — CONFIRMED requested execution; guard coverage scoped below

- `./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true`: **346 total, 345 passed, 1 documented skip**, no errors/failures. The test task executed. `junit_result` independently accepts its XML, active validation/sync and intentional control; known gap is VkBarriersTest.missingBarrierIsDetected. Console also contains the known unprefixed deprecated-layer VALIDATION-SETTINGS warning, so this is not a claim every console line is warning-free (`gradle.log`, `validation.json`).
- `python3 -m unittest discover -s scripts/tests`: **274 passed**, unchanged fixtures (`python.log`).
- `python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261009T100957-766731Z`: **exit 0**, 13 named replay checks (`replay.log`). Built/final revision is e72f37a3, with **all 422 source fingerprints identical to this exact HEAD**, and changed_sources_during_run=[] (`validation.json`). This is source equality, not compiled-JAR attestation.

All **38 direct-refusal guard expressions** in real_load_checks were individually disabled in fresh Python processes against the entire unchanged RealLoadGateTest class; **34/38** cause that class to fail. Instance **8/8** tested guards fail on removal, including all seven R19 guards and the new inventory. Removing the handoff check at scripts/verify.py:1008 fails the entire unchanged real-load module. The unchanged Java routing test also executed (`McNativeRealLoadTest.java:37`). No fixture was shrunk to speed the review.

**Non-blocking R20-TEST-REALLOAD — CONFIRMED.** These explicit real-load guards individually leave the complete RealLoadGateTest class green:

| removed guard | condition |
| --- | --- |
| scripts/verify.py:2171 | `not isinstance(entry, dict) or not finite_int(entry.get("at")) \                 or not isinstance(entry.get("status"), str)` |
| scripts/verify.py:2140 | `not isinstance(value, bool)` |
| scripts/verify.py:2143 | `not finite_int(value)` |
| scripts/verify.py:2145 | `not isinstance(value, kind)` |

Some are redundant with later errors. A green suite does not establish removal sensitivity for them. All outputs are retained in guards.json and guard-<line>.log.

The three new real-LOAD JUnit tests check inertness, routing and skip vocabulary. They do not exercise McNativeRealScene's native MC resource/atlas/submission path. The earlier synthetic reference GPU test does execute; it must not be described as a GPU test of this real native scene.

## 5. Survey/handoff and other bounded judgments — CONFIRMED scope

The current measurement paragraphs explicitly state **zero expected-hidden pixels** and no nearer-MC occlusion acceptance (survey `:1563`, `:1575`; handoff `:139`). Next-steps item 3 (`handoff.md:221`) says measured/unreviewed, puts a hidden-pixel experiment first, then multi-level hierarchy and retirement without per-sample device idle. This is a plan, not proof of any of those capabilities. Older nested design notes are explicitly labelled historical (`:230`) and describe a different no-fence/LoD0/both-kinds design; they are not the implemented level-3/fence-waited one. Do not use the older design as a current API or acceptance contract. No new delivery or occlusion overstatement is confirmed in the current summary/item 3.

**Instance mode — CONFIRMED bounded** in both launches: no backend or VoxyRenderSystem, engine/ingest live and captures monotone, complete inventories/log reconciled. MC matrix capture is bounded source-supported execution, not retained numeric matrix authentication.

**Terrain-LOAD — CONFIRMED bounded synthetic**: 11 samples, 968448 pixels, **276652 geometry**, **231642 must show**, **22600 must hide**, **22410 undetermined**, zero violations; **7 mixed samples**. The alternating handoff reduces its original every-sample inventory to its assigned samples. This does not transfer hidden-pixel acceptance to real sections.

**Coexist — CONFIRMED bounded**: 22 samples, **1936896 pixels**, **1813250 expected pass / present**, **123646 expected fail / unchanged**, zero other/violations. Declared known-depth quad, no depth writes; not real-section occlusion.

**Z direction — CONFIRMED bounded reverse-Z**: independent ladder recount at near draw 4322 gives rungs 3/4 = 45734/53338, camera y=80.62; far draw 4562 gives rung 2 = 99072, camera y=176.62, same reported/checkpoint/log ground y=67. Every near bracket lies strictly above the far bracket. This is a same-ground two-look directional observation, not a general metric calibration.

**Ladder/handoff — CONFIRMED bounded**: both selected/rejected crops and all counts/anchors match; MC original depth was measured before its one assigned depth-writing consumer. `McNativeDepthLadder.java:254`, `:263`, `:423` consumes the routing token once and alternates terrain first. Replay enforces the same assignment and consumer result inventory (`scripts/verify.py:1001`, `:2500`). The synthetic CLEAR terrain probe and depth-writing marker are off in that separate launch. Original launch logs and 14 checkpoints were reconciled.

## 6. Normal-play safety — CONFIRMED dormant scope

With every voxy.native.* flag unset from startup, this candidate preserves supported established GL behavior and leaves Voxy disabled on Minecraft Vulkan. RealLoad returns before camera/device/atlas access (`McNativeRealLoad.java:120`); the sole McNativeAtlas caller is beyond that guard. suppliedAtlas remains null, and the byte-identical GL read/state save/restore path is used. Instance factory/camera capture are similarly off (`VoxyClient.java:146`; `MixinDefaultChunkRenderer.java:53`); all other probes have default-off guards. No experimental native scene, atlas buffer or CPU cache is created. I would accept this dormant change for normal supported GL and disabled-Voxy MC Vulkan, subject to existing backend limits; I would not promote the flags or call it a working native LoD renderer. This is source plus offline verification, not a fresh normal-play session.

## What I could not check, and why

The user prohibited native/live launches. No fresh Minecraft world, live GPU-call tracing, disconnect/resource-pack fault injection, allocator/OS-handle counting, multiplayer/mod-compatibility or long-session run was performed. Committed crops/thumbs were independently decoded; full screenshots, full raw atlas and per-sample MC matrices/uniform bytes are absent. Manifest/source binding is consistency, not independent signed capture or compiled-runtime attestation. No original unexpected diagnostic was found, but replay deliberately omits the diagnostic scanner, and swallowed destructor exceptions limit leak counters. Exceptional queue/copy failure cleanup is source-reviewed, not experimentally fault-tested. Occlusion by nearer Minecraft geometry, different-world same-centre reuse, resource-pack content replacement, multi-level selection/traversal/culling/translucency/lighting and general delivery remain unverified. No fix, push or publication was made.
