# Handoff — Voxy native Vulkan / macOS work

Written 2026-10-07 for a **fresh session with no conversation context**. Branch
`vulkan-macos`, round-18 candidate **a6e1c3a3** (`a6e1c3a39fab2a4d00848a133387e7c009c3b06e`, dispatched
2026-10-09 in Orca worktree `native-review-r18`, terminal `term_bf2731e6-fa41-4296-9fa9-ef05db2f53c6`;
round 17 judged `19b2444a`: coexistence CONFIRMED a third time,
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
until a fresh independent review of a frozen commit records a verdict. Seventeen rounds have
run, **all REDESIGN** — the standing delivery boundary (native LoD is not implemented) is
always blocking; rounds 16 and 17 confirmed the coexistence measurement and kept
R16-COEXIST-LAUNCH-SEMANTICS open (replay modelled the launch token; Gradle has more spellings),
repaired in this HEAD by making the stage's literal tokens the authority.
Reports are in `docs/ai/runs/native-integration-review-r1..r17.{md,json}`.

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
-9 … -18 prompts as sent are `docs/ai/runs/native-review-prompt-r8.txt` … `-r18`. Start
from the newest, replace the SHA, the round number, the findings table and the evidence
directory.
The reviewer writes `docs/ai/runs/native-integration-review.md` + `.agent-run/*.json` in its own
worktree; **copy both into `docs/ai/runs/native-integration-review-rN.*`** and commit the
report separately from any repair.

### Findings status after round 17, and what this HEAD claims

| Finding | State |
| --- | --- |
| every evidence/gate finding B1 … R13-Z-BINDING, R14-TEST-BINDINGS | closed |
| DELIVERY-BOUNDARY | standing: diagnostics are not an accepted foundation; native LoD is unimplemented |
| **Z direction** | confirmed (r13, r14): reverse-Z |
| **Coexistence, known-depth quad** | **confirmed by r15, r16 and r17** as a bounded measurement (24 samples, zero exact-RGB violations, four mixed) |
| R15-COEXIST-PRESENCE | closed by r16 (the original attack is refused); its residual became R16-COEXIST-LAUNCH-SEMANTICS |
| **R16-COEXIST-LAUNCH-SEMANTICS** | r17: attached `-P` forms repaired, but `-P x`, `--project-prop`, `org.gradle.project.*` still escaped. Repaired in this HEAD: `launch_enables()` reads every CLI form r17 measured, AND replay requires the retained ladder command to carry the stage's literal tokens (`LADDER_LAUNCH_FLAGS`) and no depth-writing experiment — any other command is not the stage's launch; **unreviewed** |
| R15-TEST-COEXIST | closed by r17 (all 23 refusal guards have a failing test) |
| non-blocking R14-DOC-DRIFT residuals (manual ladder command without coexist, run count) | fixed in this file; **unreviewed** |
| **Terrain-LOAD experiment** (Voxy's real terrain pipeline, depth writes on, into a pass that LOADs MC colour+depth) | **in the tree and measured in this HEAD** (`McNativeTerrainLoad`, gate `terrain_load_checks`); **unreviewed** — see "What is measured" |
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
  bottom, undetermined between, unchanged without geometry. **Measured** (run `20261009T073850-255416Z`, replay 0 in this checkout): 20 samples, 495 376 geometry pixels judged, **zero violations** (no pixel shown where Voxy's depth was at or below MC's bracket, none hidden where it was at or above, none of another colour, none changed without geometry); 439 452 pixels expected visible and 22 973 expected hidden, 32 951 undetermined; 14 samples hold both determinate kinds. Survey section
  "Voxy's terrain pipeline in a LOADed pass, judged per pixel". Limits: the depth state is
  declared (`[6, 1, 1]`), not read back; synthetic scene; bracket-grain verdicts. The first run
  failed on a log-chronology race (sample line logged after the next stage began), repaired by
  a request-time log line the gate now anchors to; see the survey.
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
  bracket nothing is decided. Real-world geometry (Voxy's LoD meshes against MC's chunks at the
  same world scale) is not yet drawn; the sweep is synthetic and its camera is not MC's.
- The terrain-LOAD pipeline's depth state is **declared**, not read back from pipeline
  creation (the ladder's is). `VkTerrainRenderer` builds it with
  `depthTest(true).depthWrite(true).depthCompare(VkDepth.COMPARE_OP)`; the probe publishes
  `declaredDepthState: [6, 1, 1]`, `depthStateReadBack: false`, and the gate pins both.
- No round has accepted the diagnostic layer as a foundation. Round 4's wording still governs:
  terrain investigation may be **experimental**, not "continuation from an accepted layer".

## Exact state of the tree

Clean at HEAD. The native stage (`--only native`) launches Minecraft **twice**: launch 1 as
before (marker, features, adopt, probe, terrain, depth copy), launch 2 with
`-PharnessNativeDepthLadder=true -PharnessNativeCoexist=true -PharnessNativeTerrainLoad=true`
(+ native/adopt/features/probe; `verify.LADDER_LAUNCH_FLAGS`) and nothing that writes or clears
MC's depth before the ladder's readbacks. Both are gated; the second's
device and frame extents are tied to its **own** checkpoints. Evidence for the ladder launch is
retained under `<run>/ladder/` (report, every sample's two crops, band crops from every
screenshot, log, own checkpoints) and `--replay-evidence` runs `ladder_report_checks` on it
(same function as the stage), saying explicitly "not replayed" for runs that retained none and
refusing when the summary says a ladder ran but none is retained.

Gate/test counts at this HEAD: JUnit 341 (1 documented skip, 0 failures; 8 new for the
terrain-LOAD probe, one of them a GPU render of the reference scene); Python 220 cases;
native stage green as `docs/ai/runs/native-evidence/20261009T073850-255416Z` (replay 0 **in this
checkout** — replay requires the retained source fingerprint to equal the tree's source
inventory, so only a run built from HEAD's sources replays; `20261009T063708-667962Z`, which
round 17 judged, replayed 0 in the checkout it was built from). The lifecycle now has thirteen
checkpointed stages (`descend`, `ascend` added). Older retained runs do not replay under this
gate, which is expected and tabled in the survey.

## Round 18

Dispatched against `a6e1c3a3` with `docs/ai/runs/native-review-prompt-r18.txt` (worktree
`native-review-r18`, terminal `term_bf2731e6-fa41-4296-9fa9-ef05db2f53c6`). When its report
lands: copy it to `docs/ai/runs/native-integration-review-r18.{md,json}`, commit the report
alone, then repair blocking findings in a separate commit.

### Next steps, in order

1. Import the round-18 report; repair its blocking findings; re-run `--only native`, commit,
   push to `myfork`, dispatch round 19. Do the goal work alongside; do not wait for an overall
   PASS (owner directive).
2. The goal work, done in this HEAD: **Voxy's real terrain pipeline in a LOADed pass** as a
   separate probe (`McNativeTerrainLoad`, `voxy.native.terrainload`,
   `-PharnessNativeTerrainLoad`; shared fence-waited scene builder `McNativeTerrainScene`, which
   the terrain probe now also uses). It answers the integration question "can Voxy's real
   terrain pipeline, with its own depth state and writes on, composite per pixel against MC's
   own depth on PreferVulkan/MoltenVK" — the dependency every real-world LoD draw rests on —
   at bracket grain, for a synthetic scene. Survey section "Voxy's terrain pipeline in a LOADed
   pass, judged per pixel".
3. **Next seam into real data:** real section meshes into `VkTerrainResources` in place of
   `SyntheticTerrain` — the native path has Voxy's `WorldEngine`/meshing/`NodeManager` producing
   sections, and the Vulkan traversal/`VkTerrainRenderer` consuming `VkTerrainResources`; the
   missing piece is the upload of real section geometry/metadata/positions/models and MC's
   actual camera matrix (`viewport.MVP`, GL convention, which Voxy's matrices already follow)
   into the uniform. Approach it as another bounded, gated experiment: a LOAD pass of real
   sections around the harness camera, judged against the ladder's brackets the same way
   (reference depth from Voxy's own render of the same sections). Not a general framework.
   Existing seams to reuse, not recreate (coordinator's cross-check, 2026-10-09):
   `VkRealMesher:73` already constructs the shared `RenderDataFactory`;
   `VkHierarchicalScene:453-477` wires the real world mapper/bakery, the shared
   `BasicAsyncGeometryManager`/`NodeManager`, HiZ and traversal; `VkHierarchicalScene.record`
   (`:737-784`) owns the opaque/HiZ/traversal/cull/table/temporal/translucent sequence over a
   Voxy target — its projection/depth relationship to MC's pass and its upload retirement are
   the concrete integration questions, not missing terrain algorithms.
   Open and not needed for that: why the buffer copy reads 0.0.
4. Nothing in the ladder may ever write MC's depth. A variant that writes is a different probe
   with a different flag (which is what `McNativeTerrainLoad` is).

## Commands

```
./gradlew test --offline --no-daemon -PvkLibname=/opt/homebrew/lib/libvulkan.dylib \
  -PvkValidation=true -PvkSyncEnv=true          # JUnit
python3 -m unittest discover -s scripts/tests   # 199 cases, ~6 min
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
