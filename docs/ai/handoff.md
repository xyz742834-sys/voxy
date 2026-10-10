# Handoff — Voxy native Vulkan / macOS work

Written 2026-10-07 for a **fresh session with no conversation context**. Branch
`vulkan-macos`, round-28 candidate **10856154** (dispatched 2026-10-10, worktree `native-review-r28`): the
round-27 repairs. Round 27 judged `9cfd9c01`: REDESIGN (DELIVERY-BOUNDARY + three defects, repaired; default-on not ready). Round 26 judged `460602ab`: REDESIGN on DELIVERY-BOUNDARY only. Round 25 judged `043aa11f`: REDESIGN on DELIVERY-BOUNDARY only.
Round 24 judged `03461cea`: REDESIGN (DELIVERY-BOUNDARY + four defects, repaired). Round 23 judged `14743bf4`: REDESIGN on DELIVERY-BOUNDARY
only; no open code or gate finding; its wording residuals fixed in `32de53c2`. Round 22 judged `8ba67fc5`: REDESIGN on DELIVERY-BOUNDARY only; occlusion CONFIRMED;
four R21 items closed; three non-blocking residuals addressed in `5476545d`. Round 21 judged `03ee3485`: REDESIGN on DELIVERY-BOUNDARY only; two R20 items closed;
four residual and two new non-blocking items addressed in `d0a08faf`; occlusion measured. Round 20 judged `39584eba`: REDESIGN on DELIVERY-BOUNDARY only; R19 items closed;
real-LOAD and the Blaze3D atlas read confirmed; six non-blocking items repaired in `da91d70e`. Round 19 judged `9a6ca051`: REDESIGN on DELIVERY-BOUNDARY only; R18 items closed;
instance mode and matrix capture confirmed. Round 18 judged `a6e1c3a3`: REDESIGN on DELIVERY-BOUNDARY only;
R16-COEXIST-LAUNCH-SEMANTICS and R14-DOC-DRIFT closed; terrain-LOAD measurement confirmed; four
non-blocking items repaired in `bb55f53b`. (Round 17 judged `19b2444a`: coexistence CONFIRMED a third time,
R16-COEXIST-LAUNCH-SEMANTICS left open on other Gradle CLI spellings and repaired here with the
stage's literal tokens as the authority; the terrain-LOAD experiment is now in the tree and measured).

> Read `docs/ai/project-goal.md` and `docs/ai/current-state.md` first, then this. This file is
> task state, not source of truth; when the work moves on, update it or delete it.

## Absolute constraints (owner's words, non-negotiable)

- **Never push to `origin`** (MCRcortex/voxy upstream). Push only to `myfork`
  (xyz742834-sys/voxy). Both remotes exist in this checkout.
- **Never weaken verification to obtain a PASS.** If a gate threshold is inconvenient, the
  fixture is wrong, not the threshold. (Measured example: shrinking test fixtures to 640x360
  put the pass cell at 448 pixels, under the gate's 500 floor — the correct action was to
  revert the fixture, not lower the floor.)
- **Never self-declare production-ready.** Only a fresh independent Codex review of a frozen
  commit may judge readiness. This is written into `docs/ai/harness.md` (commit `8400c0d1`).
- Do not merge review candidates into `vulkan-macos` via the harness.
- Owner's goal statement: ignore licensing, reverse-engineer/decompile as needed, **do not
  deviate from Voxy's behaviour**, run on **PreferVulkan native**, keep **macOS compatible**.
- Owner asked not to stop autonomously unless there is a serious mistake or a decision only
  they can make.

## The review discipline (this is the spine)

`scripts/verify.py` is the verification spine. Native work is **"measured", never "verified"**
until a fresh independent review of a frozen commit records a verdict. Twenty-two rounds have
run, **all REDESIGN** — the standing delivery boundary (native LoD is not implemented) is
always blocking. Rounds 18–22 had **no blocking finding besides the boundary**.
Reports are in `docs/ai/runs/native-integration-review-r1..r22.{md,json}`.

**Owner directive (2026-10-09, via the coordination mail):** do not spend effort on waste; the
parallel agents watch each other for it. Take the shortest safe route to real-world native
Voxy LoD, reusing WorldEngine/meshing/NodeManager, the Vulkan traversal and
`VkTerrainRenderer`; keep the terrain-LOAD experiment bounded as a dependency check and name
the integration question it answers and the next seam into real data; do not grow another
diagnostic framework; do not wait for an overall PASS before the next experimental milestone;
keep the independent-review requirement, no GL dependency, GPU lifetime correctness; fix
required failing gates, never weaken them. One implementation editor (this session); an
efficiency coordinator and the reviewer are read-only.

Dispatch procedure (works; GPT-6.1-Sol, not Astra):

```
orca worktree create --agent codex --base-branch <full SHA> --name native-review-rN --json
# dismiss codex's update prompt first:
orca terminal send --terminal <handle> --text $'\x1b'
orca terminal send --terminal <handle> --enter --wait-submit 120 --text "$(cat prompt.txt)"
```

The round-7 prompt is kept as `docs/ai/runs/native-review-prompt-template.txt`; the round-8,
-9 … -28 prompts as sent are `docs/ai/runs/native-review-prompt-r8.txt` … `-r28`. Start
from the newest, replace the SHA, the round number, the findings table and the evidence
directory.
The reviewer writes `docs/ai/runs/native-integration-review.md` + `.agent-run/*.json` in its own
worktree; **copy both into `docs/ai/runs/native-integration-review-rN.*`** and commit the
report separately from any repair.

### Findings status after round 27, and what this HEAD claims

| Finding | State |
| --- | --- |
| every evidence/gate finding B1 … R13-Z-BINDING, R14-TEST-BINDINGS | closed |
| DELIVERY-BOUNDARY | standing: diagnostics are not an accepted foundation; native LoD is unimplemented |
| **Z direction** | confirmed (r13, r14): reverse-Z |
| **Coexistence, known-depth quad** | **confirmed by r15, r16 and r17** as a bounded measurement (24 samples, zero exact-RGB violations, four mixed) |
| R15-COEXIST-PRESENCE | closed by r16 (the original attack is refused); its residual became R16-COEXIST-LAUNCH-SEMANTICS |
| R16-COEXIST-LAUNCH-SEMANTICS | closed by r18 |
| non-blocking R18-TERRAIN-METADATA / R18-TEST-TERRAINLOAD / R18-DOC-SEAM / depth-copy wording | repaired in `bb55f53b` (view, matrix, extent, counters pinned; a test per guard; seam sentence and "near-zero" wording corrected); **unreviewed** |
| R15-TEST-COEXIST | closed by r17 (all 23 refusal guards have a failing test) |
| R14-DOC-DRIFT | closed by r18 |
| **Terrain-LOAD experiment** (Voxy's real terrain pipeline, depth writes on, into a pass that LOADs MC colour+depth) | **confirmed by r18** as a bounded measurement |
| **Native instance mode** + MC matrix capture + `horizon` stage | confirmed by r19 (bounded); R19-INSTANCE-INVENTORY / R19-TEST-INSTANCE / R19-DOC-DRIFT repaired in `9caf152d`; **unreviewed** |
| **Real-section LOAD** + Blaze3D atlas read | **confirmed by r20** (bounded: visible half only); R20 items addressed in `da91d70e`; r21 closed two and found four incomplete, those and two new r21 items addressed in `d0a08faf`; **unreviewed** |
| **Hierarchical-LOAD** (`McNativeHierarchicalLoad` + `McNativeComposite`, three-way hand-off) | measured (6 judged, zero violations, 82 073 shown / 3 962 hidden); judged by r24 (four defects, repaired) |
| **Hierarchical-LOAD every frame** (`voxy.native.hierframes`) | **confirmed by r25** within source/specification and retained-run scope (own fence + same queue + target barriers); ordinary-frame pixels not judged; R25-FRAME-ACCOUNTING (totals unbounded) addressed after it — the gate reconciles composited + skipped + handed with the ladder's frames; **unreviewed** |
| **Product switch `voxy.native.render`** + third gated launch (`345ebde7`, `c4ddb119`) | measured (5 235 frames composited with the switch alone, every required stage, clean); **unreviewed** |
| **R26 findings** | PROJECTION-EQUIVALENCE, COMPOSITE-DOC-SCOPE closed by r27; LADDER-GUARD-COVERAGE completed after r27 |
| **R27 findings** (EMPTY-REBUILD, RENDER-DISABLE, RENDER-GATE, CONTEXT-DRIFT) | repaired in `8d241b40`; **round 28 reviews `10856154`** |
| **Allocation plateau** (`f5c1cfa6`, `92001fdc`, `a568e664`, `990d68e7`) | measured, run `20261010T043121-449461Z` (`487fb4d8`): 40 buffers / 10 textures across five builds; 81 MB buffers (product), 51 MB (pressure) after the ~400 MB synthetic atlas staging became lazy (`f037e152`); judged per live-scene size (a rebuild at a new size may add 2 RGBA8 readbacks of the pixel difference); **unreviewed** |
| **Soak** (`f06a9fe4`) | measured, run `20261010T050124-126449Z`: 4 lifecycles in the pressure launch, 21 builds, per-size bytes exactly flat; **unreviewed** |
| **Geometry pressure launch** (`24a84f6e`, `8202e26b`) | measured (4 MB scene: 219 789 reclaimed, every stage composited, clean); **unreviewed** |
| **GL's fog and fade** (`2fb1737a`) | measured (FOG_AND_FADE, 8 judged zero violations, nether skipped as GL); **unreviewed** |
| **Minecraft's own lightmap** (`9be59d81`, `40e38c9c`) | measured (about 2 800 applied per launch, validation clean); **unreviewed** |
| **GL's composite point + depth writes** (`24d3f23b`, `e748a2f0`) | measured (7 judged by the strict clear-only rule, zero violations, with depth writes); **unreviewed** |
| **Near cut: vanilla depth bound** (`afc61e91`) | measured (up to 1 926 sections; Voxy geometry no longer inside vanilla terrain; 8 judged zero violations); **unreviewed** |
| **Streaming like GL** (`155673fb`, `6a15b446`) | measured (1 802 top-level nodes, 5 609 product frames incl. nether, 8 judged zero violations); **unreviewed** |
| **Voxy's GL composition rule + ladder CLEAR class** (`6dffc1b4`) | measured (8 judged, every Voxy pixel decided: 23 359 shown, 350 597 kept Minecraft's, zero violations); **unreviewed** |
| **Voxy's own projection + reprojecting resolve** (`429ce46a`) | measured (8 judged, zero violations, every reference depth re-derived); **unreviewed** (after the round-25 candidate) |
| **R24 findings** | HIER-CULL, DIRTY-CALLBACK, RETIRED-CONTEXT, INSTANCE-STORED, EMPTY-BUILD **closed by r25**; HIER-GUARD-COVERAGE **partly**: the seven hierarchy-specific predicates are detected, the 27 shared-helper predicates are detected only by the real-LOAD tests (stated, not repaired) |
| **Occlusion** (render-distance cut, `horizon` wall, hidden pixels required) | **confirmed by r22** (one look, bracket grain); R22 residuals (budget-guard test, draw-time cut, wording) addressed after it; **unreviewed** |
| non-blocking limits (anchor grain, consistent forgery, creator restore, geometry scope, depth-copy scope, recorder binding untested in JUnit) | **stated as limits** |

B1 has one recurring shape: **the gate trusted something the thing under test controls.**
Repairs have removed that progressively — raw pixels instead of aggregates, source constants
instead of published geometry/colours/offsets/bands/palette, the adopted identity instead of
any well-formed string, both orientations instead of the selected one, shared helpers so replay
cannot drift from the stage gate, the launch's own checkpoints for the ladder's identity and
frame extent, every retained file a manifest member.

## What is measured (survives independent reading)

- MC 26.2 ships a usable Vulkan backend, forced by `--graphicsBackend vulkan`
  (`options.txt preferredGraphicsBackend` is overridden — the launch arg is what works).
- Its device/queues/colour+depth images are reachable without GL.
- MC's device is **Vulkan 1.2 + `VK_KHR_synchronization2` + `VK_KHR_dynamic_rendering`**; core
  1.3 entry points are null, hence the `VkCmd` dispatch layer.
- MC enables only 4 of Voxy's 8 required device features; `MixinVulkanBackend` injects
  `drawIndirectFirstInstance`, `shaderInt64`, `fragmentStoresAndAtomics`,
  `vertexPipelineStoresAndAtomics`. Offsets are **resolved by read-back experiment, never
  arithmetic**. The real offsets are 40 / 160 / 104 / 100 and the gate pins them.
- A bounded depth-tested draw reaches MC's colour image (`McNativeMarkerDraw`).
- Voxy's real shader stack runs on the adopted device and agrees with a CPU reference
  (189 ordinals, 0 mismatches).
- **Voxy's real terrain pipeline records into a pass MC opens over its own images and comes out
  RGB-identical** to the same scene on Voxy's own target (26116 non-background px, 0
  mismatches). Rounds 6 and 7 both CONFIRMED the measurement.
- **MC's depth attachment at the `LevelRenderer.render` tail holds non-zero depth** where the
  buffer copy reported 0.0: the re-rung ladder (2⁻¹⁶…2⁻²) saw 76 % of its first column pass
  over clouds in a frame from before the world loaded (run `20261007T011026-211934Z`, retained).
  The per-pixel ladder's numbers are in the survey's "Round-8 review repairs, and the
  per-pixel ladder" section and the newest run's `ladder/` directory; do not quote older
  runs' counts as this HEAD's.
- **A known-depth quad drawn with Voxy's compare op (`GREATER_OR_EQUAL`) against MC's LOADed
  depth composes per pixel** (flag `voxy.native.coexist`, in the ladder launch): on each
  sampled frame a second pass draws the quad at z* = 2⁻⁸ over the band, a second readback is
  compared pixel by pixel with the ladder's brackets from the same frame; zero violations
  required and measured. Survey section "Coexistence, first measurement".
- **Voxy's real terrain pipeline composes per pixel against MC's LOADed depth, with depth
  writes on** (flag `voxy.native.terrainload`, separate probe `McNativeTerrainLoad`, in the
  ladder launch): on each sampled frame, after the ladder's two readbacks, a third pass LOADs
  MC's colour and depth and records `VkTerrainRenderer.recordDrawsInRenderPass` for
  `SyntheticTerrain.depthSweep()` (five panels at 32·{1,2,4,8,16} blocks, fitted into the ladder
  band, depths on both sides of MC's terrain bracket); a third readback is judged per pixel from
  the ladder's bracket and Voxy's own reference depth of the same scene: reference colour
  exactly where d_V ≥ bracket top, byte-identical to the previous readback where d_V ≤ bracket
  bottom, undetermined between, unchanged without geometry. **Measured** (run `20261009T073850-255416Z`, which replayed 0 in the checkout it was built from; round 18 confirmed it): 20 samples, 495 376 geometry pixels judged, **zero violations** (no pixel shown where Voxy's depth was at or below MC's bracket, none hidden where it was at or above, none of another colour, none changed without geometry); 439 452 pixels expected visible and 22 973 expected hidden, 32 951 undetermined; 14 samples hold both determinate kinds. Survey section
  "Voxy's terrain pipeline in a LOADed pass, judged per pixel". Limits: the depth state is
  declared (`[6, 1, 1]`), not read back; synthetic scene; bracket-grain verdicts. The first run
  failed on a log-chronology race (sample line logged after the next stage began), repaired by
  a request-time log line the gate now anchors to; see the survey.
- **Native instance mode: Voxy's world engine, storage and ingest run on MC's Vulkan backend
  without any render path** (flag `voxy.native.instance`, `-PharnessNativeInstance`, in the
  first native launch; `McNativeInstanceProbe`, gate `native_instance_result`). Measured (run
  `20261009T080812-119439Z`): a world engine for the level, no `VoxyRenderSystem` ever, up to
  16 active sections across 73 samples, ingest enabled, log reconciled. Says
  nothing about the sections' content or drawing. Survey section "Native instance mode".
  Since 2026-10-10 (repaired after round 24) the gate requires level-0 sections holding a block near the camera instead of a
  non-zero active-section count (cache occupancy, which read 0 in a run whose engine ingested).
- **Voxy's hierarchical pipeline natively** (flag `voxy.native.hierload`; run `20261009T175351-583841Z`):
  `VkHierarchicalScene` driven with MC's matrix into Voxy's own target, then `McNativeComposite`
  writes Voxy's colour and depth into MC's LOADed frame with Voxy's depth test — the GL resolve's
  native analogue. 6 judged samples, zero violations, 82 073 must-show shown, 3 962 must-hide
  hidden at `horizon`. Limits: MC's projection (far 2048, no reprojection), no native near cut,
  a device-idle wait per hand-off. Survey section "Voxy's hierarchical pipeline natively".
- **Every frame** (flag `voxy.native.hierframes`; run `20261009T182330-214639Z`): the same scene rendered and
  composited on every frame, waiting only for Voxy's own previous submission; 5 145 frames
  composited, the 8 handed samples judged with zero violations through `reconnect`. Survey
  section "Every frame".
- **Occlusion: MC's nearer geometry hides Voxy's real terrain** (run `20261009T120055-798091Z`): at `horizon`, with
  sections inside MC's 128-block render distance cut and a stone wall 20 blocks ahead, 2 003
  pixels had to show Voxy over MC's sky and did, 3 306 had to be hidden behind the wall and
  were, zero violations; the gate now requires hidden pixels. One look, one wall; the near cut
  approximates Voxy's real mechanism. The harness waits in a stage until the ladder has
  requested a sample after the stage became ready, while the ladder is sampling (a slower frame
  rate once dropped `descend`); it guarantees a request, not a measurement.
- **Real sections, drawn with MC's own matrix through Voxy's terrain pipeline into MC's LOADed
  colour and depth, compose per pixel** (flag `voxy.native.realload`, `McNativeRealLoad`, gate
  `real_load_checks`; run `20261009T100957-766731Z`): 10 judged samples, zero violations, 143 425 pixels that had to
  show Voxy did (8 samples, incl. `horizon` over MC's sky); **0 pixels had to be hidden**, so
  occlusion by nearer MC geometry is untested. MC's Vulkan projection is already 0..1 depth;
  far plane 2048. On the way: Voxy's model bakery read MC's atlas with raw GL (JVM abort on MC
  Vulkan); `McNativeAtlas` reads it through Blaze3D instead.
- **The Z direction is measured: larger depth value = nearer (reverse-Z).** Two harness stages
  look straight down at the ground under (0, 0) (y = 67, from the heightmap) from 12 and 108
  blocks above it; the ladder labels samples with stage and camera; the gate takes the last
  sample of each look (camera at ground + offset + 1.62 within a block, pitch 90) and requires
  every bracket of the nearer look to lie strictly on one side of the farther look's. Measured:
  near brackets 3–4, far bracket 2. Retained in `20261009T045515-905630Z/ladder/`. Rounds 13–14 confirmed the direction.

## What is NOT established — do not claim these

- Native Voxy terrain is `BLOCKED_UNIMPLEMENTED`. The terrain experiment uses **synthetic**
  input and **clears MC's frame** to make comparison possible, i.e. it does not coexist.
- The probe itself asserts no convention (`zConventionMeasuredHere: false`, required by both
  depth gates); the **direction is the gate's reading of the two straight-down looks**
  (reverse-Z, confirmed r13/r14). The band heuristic deleted in `bb91527e` must not return.
- ⚠ **"The tail hook cannot support coexistence" was claimed in `3421640f` and RETRACTED the
  same day** (`fcd5ce18`). The linear rungs 0.0625..0.9375 sat inside the first block in front
  of the camera. Round 8 refuted the same claim independently. **Why the buffer copy read 0.0
  everywhere** while the depth test sees non-zero values is not established.
- The depth **scale** is measured only at bracket grain: the quad at 2⁻⁸ and the sweep's
  panels (≈ 2⁻⁸·¹ … 2⁻¹²·⁷) land on the side of Minecraft's brackets their values say; inside a
  bracket nothing is decided. The terrain-LOAD sweep is synthetic and its camera is not MC's;
  real-world geometry with MC's matrix is the real-LOAD experiment (one coarse level around the
  camera), not Voxy's LoD selection.
- The terrain-LOAD pipeline's depth state is **declared**, not read back from pipeline
  creation (the ladder's is). `VkTerrainRenderer` builds it with
  `depthTest(true).depthWrite(true).depthCompare(VkDepth.COMPARE_OP)`; the probe publishes
  `declaredDepthState: [6, 1, 1]`, `depthStateReadBack: false`, and the gate pins both.
- No round has accepted the diagnostic layer as a foundation. Round 4's wording still governs:
  terrain investigation may be **experimental**, not "continuation from an accepted layer".

## Exact state of the tree

Clean at HEAD. The native stage (`--only native`) launches Minecraft **twice**: launch 1 as
before (marker, features, adopt, probe, terrain, depth copy, and since this HEAD native
instance mode `-PharnessNativeInstance=true`), launch 2 with
`-PharnessNativeDepthLadder=true -PharnessNativeCoexist=true -PharnessNativeTerrainLoad=true`
(+ native/adopt/features/probe; `verify.LADDER_LAUNCH_FLAGS`) and nothing that writes or clears
MC's depth before the ladder's readbacks. Both are gated; the second's
device and frame extents are tied to its **own** checkpoints. Evidence for the ladder launch is
retained under `<run>/ladder/` (report, every sample's two crops, band crops from every
screenshot, log, own checkpoints) and `--replay-evidence` runs `ladder_report_checks` on it
(same function as the stage), saying explicitly "not replayed" for runs that retained none and
refusing when the summary says a ladder ran but none is retained.

Gate/test counts at this HEAD: JUnit 354 (1 documented skip, 0 failures; 8 for the
terrain-LOAD probe, one of them a GPU render of the reference scene, 2 for instance mode,
4 for real-LOAD);
Python 318 cases;
native stage green as `docs/ai/runs/native-evidence/20261010T015936-831549Z` (replay 0 **in this
checkout** — replay requires the retained source fingerprint to equal the tree's source
inventory, so only a run built from HEAD's sources replays; `20261009T063708-667962Z`, which
round 17 judged, replayed 0 in the checkout it was built from). The lifecycle now has fourteen
checkpointed stages (`descend`, `ascend` added 2026-10-07; `horizon` — facing the Voxy-only
terrain at x ≈ 768 from spawn at y 160, yaw -90, pitch 18, with a stone wall 20 blocks ahead —
added 2026-10-09 for the real-section
experiment). Older retained runs do not replay under this
gate, which is expected and tabled in the survey.

## Round 28

Round 27 (`9cfd9c01`) returned REDESIGN: DELIVERY-BOUNDARY plus R27-EMPTY-REBUILD,
R27-RENDER-DISABLE, R27-RENDER-GATE, and judged default-on not ready (report
`docs/ai/runs/native-integration-review-r27.md`, its readiness list in the JSON); repaired in
`8d241b40` (survey "Round-27 repairs"). Round 28 was dispatched 2026-10-10 against `10856154`
(`1085615492e502b52778c8a5199d4039c5624f1d`) with `docs/ai/runs/native-review-prompt-r28.txt`
(worktree `native-review-r28`, terminal `term_771f42df-7ce0-4d08-97ea-56c453082d7d`). **It did not run:** the
reviewer hit its usage limit right after the prompt was accepted ("You've hit your usage limit …
try again at Oct 14th, 2026 12:30 PM"; it offers a cheaper model, which is the owner's call, not
this session's). No round-28 report exists. Re-send the same prompt to that terminal (or a fresh
worktree on `10856154` — or on the latest HEAD, adding the streaming milestone) once the limit
resets or the owner chooses otherwise. When its report lands: copy it to `docs/ai/runs/native-integration-review-r28.{md,json}`,
commit the report alone, then repair.

### Next steps, in order

1. Import the round-28 report; repair its blocking findings; re-run `--only native`, commit,
   push to `myfork`, dispatch round 29. Do the goal work alongside; do not wait for an overall
   PASS (owner directive).
2. The goal work, done in this HEAD: **Voxy's real terrain pipeline in a LOADed pass** as a
   separate probe (`McNativeTerrainLoad`, `voxy.native.terrainload`,
   `-PharnessNativeTerrainLoad`; shared fence-waited scene builder `McNativeTerrainScene`, which
   the terrain probe now also uses). It answers the integration question "can Voxy's real
   terrain pipeline, with its own depth state and writes on, composite per pixel against MC's
   own depth on PreferVulkan/MoltenVK" — the dependency every real-world LoD draw rests on —
   at bracket grain, for a synthetic scene. Survey section "Voxy's terrain pipeline in a LOADed
   pass, judged per pixel".
3. **Done (measured): the real-section LOAD experiment, confirmed by r20 for the visible half;
   occlusion measured in this HEAD (unreviewed)** — see "What is measured". **Next, in order:**
   **Review state (2026-10-09):** rounds 18–23 had no blocking finding besides the delivery
   boundary, and after round 23 no open code or gate finding remains (its residuals were wording,
   fixed in `32de53c2`). Round 24 is NOT dispatched for wording alone; it goes with the next
   implementation milestone below (owner directive: no evidence-only loops).
   **Done 2026-10-10 (measured, unreviewed): `McNativeHierarchicalLoad`** and its every-frame
   path (`voxy.native.hierframes`), and Voxy's own projection with the reprojecting resolve
   (`429ce46a`), Voxy's GL composition rule (`6dffc1b4`) and the product switch (`345ebde7`) — see
   the survey. Done in round 27's readiness order: the scene streamed at Voxy's configured radius
   (`155673fb`), the native near cut (`afc61e91`), the composite at GL's point writing Voxy's depth
   (`24d3f23b`, `e748a2f0`), Minecraft's lightmap (`9be59d81`), GL's fog and fade (`2fb1737a`).
   Geometry pressure (`8202e26b`), an allocation plateau within a lifecycle (`92001fdc`), judged per
   live-scene size (`990d68e7`); buffers down from ~616 MB to 81 MB by a lazy synthetic atlas staging
   (`f037e152`). Sodium's zero-size `vkCmdCopyBuffer` (VUID-VkBufferCopy-size-01988, run
   `20261010T023920-410812Z`) appeared once and did not recur; the gate still refuses it. The soak
   (`f06a9fe4`, run `20261010T050124-126449Z`): the pressure launch repeats travel..reconnect for 4
   lifecycles — 21 builds, 4 resizes/reloads/nether trips/reconnects, 552707 sections reclaimed,
   bytes back exactly to the same value at each scene size; the gate holds bytes per size within 1 %
   across lifecycles. Failure injection (`860ad7dd`): one failed submit used to leave Voxy's frame
   tracker unusable for the session (recording window left open, a reset fence waited on forever);
   fixed, with real-Vulkan injection tests (`VkFrameTrackerTest`) for a failed submit and a failed
   wait; run `20261010T052418-163141Z` passes on it. Live injection (`8f59c3ab`): a fifth launch
   fails one every-frame submission after 1000 frames; Voxy composites 4350 more through every
   stage (run `20261010T055934-778167Z`). On Vulkan with the native path Voxy no longer logs that it
   is disabled/unsupported (`545d2b2a`, `5fcaaf97`); the product gates refuse those lines (run
   `20261010T065741-230625Z`). Still open from round 27 item 6: device changes — Minecraft 26.2 sets its
   device once per process (`RenderSystem.initRenderer` throws "RenderSystem.DEVICE already
   initialized" and is the only writer of `DEVICE`, javap of the 26.2 jar), so a change is a new
   launch, which every gated launch already is; the in-process divergence check
   (`deviceDiverged`) stays. Synchronization validation inside Minecraft (`87578942`, round-27 item
   7): a positive control in the injection launch records two unsynchronised fills; Minecraft logs
   the hazard as bare text with neither SYNC-HAZARD- nor a VUID, which the native gates did not see
   before (so earlier native passes did not cover synchronization hazards). The gates now treat
   Minecraft-logged `vkXxx():` messages as validation output; run `20261010T073717-488727Z` shows
   the control's hazard and no other. Scene budgets (round-27 item 3): subdivision follows
   `VoxyConfig.subDivisionSize` every frame as GL (`6a671f65`); draw buffers and traversal queues are
   sized by the render queue, min(sections, GL's MAX_QUEUE_SIZE), nodes twice the sections
   (`d9ef4af8`, run `20261010T084558-353771Z`). **Owner decision pending — default geometry size on
   macOS:** GL's formula on this M4 Pro means ~4 GB of unified memory (zeroed); measured buffers:
   81 MB now (8192 sections, 32 MB geometry), 730 MB at GL's capacities with its 512 MB minimum,
   ~4.2 GB literal. The default stays 81 MB until decided; recommended: GL's capacities with 512 MB,
   plus GL's `voxy.geometryBufferSizeOverrideMB`. Gate coverage: R26's two ladder predicates are
   covered (`test_ladder_coverage.py`); R24 re-audited 2026-10-10 with the round-27 tool
   (`native-review-r27/.agent-run/r27/r24_guards.py`, ROOT pointed at this checkout): 37/37
   detected, 27 still only by real-LOAD tests (shared helpers `real_load_skip_provenance`,
   `load_judged_entry_checks`, `judge_load_sample`) — deferred, non-blocking. Far-world: the gate
   already counts Voxy pixels beyond Minecraft's far plane (`beyondMinecraftFar`, 2048 blocks
   here) and was 0. Now (`1376d11f`): `travel` goes on to x 2560 and a new `far` stage looks at that
   terrain from (0, 185, 0), pitch 16, below the clouds; far samples go to hierarchical-LOAD and the
   gate requires judged far pixels beyond the far plane. Run `20261010T100424-805829Z`: 3325 such
   pixels in the far look (5820 in all), judged per pixel. Next: failure injection, gate-coverage residuals and far-world evidence, then
   default-on. Default-on (the kill switch `voxy.native.disable` exists) waits for those and for
   an independent review of everything since `10856154`. The original design notes follow.
   **Designed 2026-10-09: `McNativeHierarchicalLoad`** — Voxy's
   whole hierarchical pipeline natively, the step from "real sections at one level" to Voxy's LoD:
   `VkHierarchicalScene` (real world mapper/bakery, `NodeManager`, HiZ, traversal, prep/cull,
   table, opaque/temporal/translucent — `VkHierarchicalScene.record`, `:737`) driven on the ladder's
   hand-off frames with MC's matrix (`McNativeCamera`, the same `prepare(mvp, camSection, sub, …)`
   the interop probe used), rendering into Voxy's own `VkRenderTarget`; then a **composite pass in
   MC's LOADed colour+depth** that writes Voxy's colour and depth where Voxy's depth ≥ MC's
   (GREATER_OR_EQUAL, writes on) — the native analogue of the GL path's resolve, without GL. The
   per-pixel judge is the real-LOAD one unchanged: the ladder's bracket against Voxy's own depth of
   the same frame. Questions it answers: does Voxy's own LoD selection/culling produce terrain that
   composes with MC's depth natively, and what must be submitted outside MC's pass (traversal
   compute, HiZ) and how its lifetime ties to MC's submissions — which is item (c). Reuse:
   `VkHierarchicalScene`, `VkDepthResolve` (shape of the composite), the ladder/real-LOAD gate.
   Then: (a) Voxy's real near-terrain cut: the vanilla visible-section stream feeding the depth
   bound, natively, instead of the level-3 render-distance approximation; (b) LoD beyond one level: mesh the
   levels the hierarchical scene selects (`VkHierarchicalScene` traversal) instead of one fixed
   level; (c) tie the scene's lifetime to MC's submissions without the per-sample
   `vkDeviceWaitIdle` (today's safe-but-blocking uniform write). The historical design notes
   follow.
   **Earlier next-seam notes (kept for the record):** real section meshes into `VkTerrainResources` in place of
   `SyntheticTerrain`. Round 18 (R18-DOC-SEAM) corrected an earlier sentence here: on MC's
   Vulkan backend nothing produced sections until native instance mode (this HEAD) — Voxy's
   `WorldEngine`/meshing/`NodeManager` and the Vulkan traversal/`VkTerrainRenderer` are
   **reusable components**, not an already-running native producer. What exists now: the
   instance (world engine, ingest) runs natively and holds sections; MC's matrices are copied
   each frame (`McNativeCamera`). What is missing: meshing those sections, uploading them, and
   drawing them with that matrix in a LOAD pass, with the lifetime of those resources tied to
   MC's submissions. Approach it as another bounded, gated experiment: a LOAD pass of real
   sections around the harness camera, judged against the ladder's brackets the same way
   (reference depth from Voxy's own render of the same sections). Not a general framework.
   Existing seams to reuse, not recreate (coordinator's cross-check, 2026-10-09):
   `VkRealMesher:73` already constructs the shared `RenderDataFactory`;
   `VkHierarchicalScene:453-477` wires the real world mapper/bakery, the shared
   `BasicAsyncGeometryManager`/`NodeManager`, HiZ and traversal; `VkHierarchicalScene.record`
   (`:737-784`) owns the opaque/HiZ/traversal/cull/table/temporal/translucent sequence over a
   Voxy target — its projection/depth relationship to MC's pass and its upload retirement are
   the concrete integration questions, not missing terrain algorithms.
   **Measured in run `20261009T085529-037358Z`:** the Sodium CUTOUT hook runs on MC Vulkan and
   copied MC's matrices 4 860 times; the engine held up to 92 active sections. ⚠ During
   `horizon` the engine's *active* (in-memory) section count was 0 — active sections are the
   cache, not what is stored. The real-section mesher must therefore `acquire` sections (which
   loads them from storage), not rely on the active set; check that sections around x = 768
   were saved before `horizon`, or the experiment will mesh nothing there.
   **Obstacle, measured and removed (2026-10-09):** on MC's Vulkan backend `VoxyClient` set
   `BACKEND = null` and never registered the instance factory, so no `WorldEngine` existed.
   **Native instance mode** (`voxy.native.instance`, in this HEAD) registers the factory only;
   `MixinLevelRenderer` creates the level's engine and skips `VoxyRenderSystem` (the GL/interop
   renderer). Measured: engine present, sections ingested, no renderer (see "What is measured").
   **Next:** `VkRealMesher.meshAround` + `VkRealSectionUpload.upload` into
   `VkTerrainResources(REAL)` (the interop probe's template, `VkInteropProbe.ensureRealScene`)
   and `VkTerrainRenderer.recordDrawsInRenderPass` in a LOAD pass with MC's own matrix
   (GL convention, as Voxy's), gated like terrain-LOAD: the ladder's brackets against Voxy's own
   reference depth of the same sections. Keep it flagged and experimental; no acceptance claim.
   **Design settled 2026-10-09 (not yet in the tree), `McNativeRealLoad`, flag
   `voxy.native.realload`, in the ladder launch together with instance mode:**
   - Matrix: `McNativeCamera` (in the tree, `df4cbce5`) copies Sodium's `ChunkRenderMatrices`
     and camera from the CUTOUT hook each frame in instance mode; the instance gate requires
     captures. Use `VkHostViewport.projectionForVulkan` (0..1 depth; it returns MC's matrix
     unchanged when MC already produces 0..1, else halves the range — which of the two happened
     must be published, because the whole point is to draw in MC's own depth space) and
     `VkHostViewport.mvp(projection, modelView, cameraSubPos)` with anchor = camera section.
   - Scene: `VkTerrainResources(REAL)` + `useExternalAtlasContent()`, `VkModelUploadTarget`,
     `VkRealModelBakery(target, world.getMapper())`, `VkRealMesher.meshAround(cx, cy, cz, r,
     0)`, `bakery.replayBiomes()`, `VkRealSectionUpload.upload(built, res)` (the interop probe's
     `ensureRealScene`); `bakery.recordUploads(cmd)` must be recorded once before the first draw.
     Re-mesh when the camera leaves the meshed radius; cap quads.
   - Per sample frame (the ladder's handoff): write the uniform with THIS frame's matrix, record
     (outside any pass, into MC's command buffer) `recordBeforeRenderPass`, then a reference
     pass into Voxy's own `VkRenderTarget` (CLEAR) with colour + depth readback into Voxy's
     host-visible buffers, then the LOAD pass with `recordDrawsInRenderPass` into MC's colour
     and depth, then MC's readback; judge in MC's callback (the frame is complete, so Voxy's
     reference buffers are too). No fence wait on the render thread.
   - Samples: the ladder hands each sampled frame to ONE consumer, alternating terrain-LOAD and
     real-LOAD, and publishes the consumer per sample; each gate requires exactly its samples
     and at least one sample with both determinate kinds. Both experiments stay in one launch.
   - What real sections at LoD 0 around the camera can decide: over sky, Voxy must appear; where
     Voxy's mesh is farther than MC's nearer terrain, it must be hidden; where the surfaces
     coincide, Voxy's reference depth must fall in (or at the edge of) MC's bracket — the
     projection/depth agreement the coordinator named. Inside a bracket nothing is decided.
   - Evidence like terrain-LOAD's (third crop, thumbnail, per-sample reference colour and depth
     crops — per sample here, because the matrix changes — report, log line), gated by a
     `real_load_checks` mirroring `terrain_load_checks` plus the published projection choice.
   - **Where determinate pixels come from (worked out 2026-10-09):** MC's near plane is 0.05, so
     its terrain bracket (2⁻¹², 2⁻¹⁰] is 51–205 blocks and sky is beyond. Voxy only holds what
     the client ingested: around spawn (x = 0) and around the `travel` teleport (x = 768), each
     ±128 blocks. Sections at x ≈ 640–896 are **Voxy-only** after `return` — the LoD case
     itself. The existing looks face +z (yaw 0) and never see them, so add a stage, e.g.
     `horizon` after `return` (superseded: the look in the tree is y 160, pitch 18, plus a wall —
     see the survey's occlusion section): `tp @s 0 120 0 -90 15` (face +x, pitch 15° down: the band's rays
     run 1–9° below horizontal, so MC shows sky there and Voxy's far terrain at 640–896 blocks,
     3.5–4.9° below, falls inside the band → expected VISIBLE). Mesh at a coarse level
     (`meshAround(cx, cy, cz, r, level)` with level 2–3 so radius 4 covers ±1024 blocks; the
     engine propagates ingest to `MAX_LOD_LAYER` = 4). Expected HIDDEN needs Voxy geometry whose
     nearest fragment is farther than MC's nearer surface by a bracket; with the same world
     ingested that is rare (coincident surfaces are undetermined by construction), so the gate
     should require zero violations and at least one sample with expected-visible pixels, and
     report hidden/undetermined counts without demanding them. Adding a stage means
     `LIFECYCLE_STAGES` (13 → 14) and the shared test fixtures' stage lists change, as for
     `descend`/`ascend`. Both launches would then carry instance mode.
   Open and not needed for that: why the buffer copy reads 0.0.
4. Nothing in the ladder may ever write MC's depth. A variant that writes is a different probe
   with a different flag (which is what `McNativeTerrainLoad` is).

## Commands

```
./gradlew test --offline --no-daemon -PvkLibname=/opt/homebrew/lib/libvulkan.dylib \
  -PvkValidation=true -PvkSyncEnv=true          # JUnit
python3 -m unittest discover -s scripts/tests   # count in "Exact state of the tree", ~9 min
python3 scripts/verify.py --only native --seconds 8 --timeout 1500   # launches MC twice, ~5 min
python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/<run>   # no launch
```

Manual isolated ladder run (what launch 2 does, including its two experiments; terrain
probe and marker OFF are required):

```
O=$(mktemp -d)/ladder; mkdir -p $O/game; echo ladder > $O/game/.voxy-harness
printf 'preferredGraphicsBackend:"vulkan"\nonboardAccessibility:false\ntutorialStep:none\npauseOnLostFocus:false\nrenderDistance:8\nsimulationDistance:5\nmaxFps:60\nenableVsync:false\n' > $O/game/options.txt
./gradlew --console=plain runHarnessClient --offline --no-daemon \
  -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true \
  -PharnessOutput=$O -PharnessRunDir=$O/game -PharnessSeconds=6 -PharnessNative=true \
  -PharnessGraphicsBackend=vulkan -PharnessNativeAdopt=true -PharnessNativeFeatures=true \
  -PharnessNativeProbe=true -PharnessNativeDepthLadder=true -PharnessNativeCoexist=true \
  -PharnessNativeTerrainLoad=true
```

⚠ `verify.py` fails the run if any file under `src/` or `scripts/` changes mid-run
(`changed_sources_during_run`). Do not edit those while a native run is in flight. `docs/` is
not fingerprinted.

⚠ macOS has no `timeout(1)`. Use `run_in_background` instead.

## Where the documentation lives

- `docs/ai/project-goal.md` — scope, next priority, completion evidence. Read first.
- `docs/ai/current-state.md` — what works/doesn't; has rounds 6-7, the depth-copy result and
  the ladder result.
- `docs/ai/vulkan-native-integration-survey.md` — the living claims/evidence document, with a
  per-round repairs section and the ladder section. **Note its "A note on how runs are
  cited"**: a figure from a run whose evidence directory is absent is *narrative, not proof*,
  and a retained directory stops replaying when the gate gets stricter (only the newest run
  replays; the others are tabled).
- `docs/ai/harness.md` — the review rule.
- `docs/ai/testing.md` — the stages and what each proves.
- `docs/ai/runs/native-evidence/<run>/` — retained evidence, one directory per run (count them; the survey's replay table lists each).
