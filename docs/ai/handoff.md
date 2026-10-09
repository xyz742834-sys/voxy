# Handoff — Voxy native Vulkan / macOS work

Written 2026-10-07 for a **fresh session with no conversation context**. Branch
`vulkan-macos`, round-17 candidate **19b2444a** (`19b2444a5a5c40dca47160ece609970caf6a4c07`, dispatched 2026-10-09 in Orca worktree `native-review-r17`, terminal `term_c1c0e855-b94a-4e1e-ab2b-326bf8efdd2e`; round 16 judged
`a49b926f`: coexistence CONFIRMED again, R16-COEXIST-LAUNCH-SEMANTICS refuted and repaired here).

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
until a fresh independent review of a frozen commit records a verdict. Sixteen rounds have
run, **all REDESIGN** — the standing delivery boundary (native LoD is not implemented) is
always blocking; round 16 confirmed the coexistence measurement again and added
R16-COEXIST-LAUNCH-SEMANTICS (replay read only the literal `=true` launch token while Gradle
enables on `hasProperty`), repaired in this HEAD.
Reports are in `docs/ai/runs/native-integration-review-r1..r16.{md,json}`.

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
-9 … -17 prompts as sent are `docs/ai/runs/native-review-prompt-r8.txt` … `-r17`. Start
from the newest, replace the SHA, the round number, the findings table and the evidence
directory.
The reviewer writes `docs/ai/runs/native-integration-review.md` + `.agent-run/*.json` in its own
worktree; **copy both into `docs/ai/runs/native-integration-review-rN.*`** and commit the
report separately from any repair.

### Findings status after round 16, and what this HEAD claims

| Finding | State |
| --- | --- |
| every evidence/gate finding B1 … R13-Z-BINDING, R14-TEST-BINDINGS | closed |
| DELIVERY-BOUNDARY | standing: diagnostics are not an accepted foundation; native LoD is unimplemented |
| **Z direction** | confirmed (r13, r14): reverse-Z |
| **Coexistence, known-depth quad** | **confirmed by r15 and r16** as a bounded measurement (24 samples, zero exact-RGB violations, four mixed) |
| R15-COEXIST-PRESENCE | closed by r16 (the original attack is refused); its residual became R16-COEXIST-LAUNCH-SEMANTICS |
| **R16-COEXIST-LAUNCH-SEMANTICS** | repaired in this HEAD (`9c6cf401`) — `launch_enables()` reads the retained command with Gradle's `hasProperty` semantics in stage and replay; replay tests for `=false`, `=`, bare, and a non-enabling neighbour; **unreviewed** |
| non-blocking R15-TEST-COEXIST residual (five guards whose removal passed the suite) | each has a failing test now, checked by mutating each guard to `False` |
| non-blocking R14-DOC-DRIFT residual (gate date, "replays in this checkout" for old runs, this file's launch-2 flags) | corrected in the survey, `current-state.md` and this file |
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
- The depth **scale** beyond one value is not measured: the coexistence quad shows that a
  depth of 2⁻⁸ in Voxy's convention lands where Minecraft's 2⁻⁸ does, nothing more. Voxy's
  real terrain pipeline against MC's LOADed depth is not yet attempted.
- No round has accepted the diagnostic layer as a foundation. Round 4's wording still governs:
  terrain investigation may be **experimental**, not "continuation from an accepted layer".

## Exact state of the tree

Clean at HEAD. The native stage (`--only native`) launches Minecraft **twice**: launch 1 as
before (marker, features, adopt, probe, terrain, depth copy), launch 2 with
`-PharnessNativeDepthLadder=true -PharnessNativeCoexist=true` (+ native/adopt/features/probe)
and nothing that writes or clears MC's depth. Both are gated; the second's
device and frame extents are tied to its **own** checkpoints. Evidence for the ladder launch is
retained under `<run>/ladder/` (report, every sample's two crops, band crops from every
screenshot, log, own checkpoints) and `--replay-evidence` runs `ladder_report_checks` on it
(same function as the stage), saying explicitly "not replayed" for runs that retained none and
refusing when the summary says a ladder ran but none is retained.

Gate/test counts at this HEAD: JUnit 333 (1 documented skip, 0 failures); Python 199 cases;
native stage green as `docs/ai/runs/native-evidence/20261009T063708-667962Z` (replay 0 **in this
checkout** — replay requires the retained source fingerprint to equal the tree's source
inventory, so only a run built from HEAD's sources replays; `20261009T060453-272917Z`, which
rounds 15–16 judged, replayed 0 in the checkout it was built from). The lifecycle now has thirteen
checkpointed stages (`descend`, `ascend` added). Older retained runs do not replay under this
gate, which is expected and tabled in the survey.

## Round 17

Dispatched against `19b2444a` with `docs/ai/runs/native-review-prompt-r17.txt` (worktree
`native-review-r17`, terminal `term_c1c0e855-b94a-4e1e-ab2b-326bf8efdd2e`). When its report
lands: copy it to `docs/ai/runs/native-integration-review-r17.{md,json}`, commit the report
alone, then repair blocking findings in a separate commit.

### Next steps, in order

1. Import the round-17 report; repair its blocking findings; re-run `--only native`, commit,
   push to `myfork`, dispatch round 18. (Round 17 was dispatched alongside the terrain-LOAD
   work; do both without waiting for an overall PASS, per the owner directive.)
2. The goal work, in progress: **Voxy's real terrain pipeline in a LOADed pass**, as a
   **separate probe with its own flag** (`McNativeTerrainLoad`, `voxy.native.terrainload`,
   `-PharnessNativeTerrainLoad`), because rule 3 below forbids the ladder itself from writing
   MC's depth. Design, settled 2026-10-09 (not yet in the tree): on each frame the ladder
   samples, after the ladder's two readbacks are requested, the probe opens a third pass that
   LOADs MC's colour and depth and records `VkTerrainRenderer.recordDrawsInRenderPass`
   (Voxy's own depth state: `GREATER_OR_EQUAL`, writes ON) for a synthetic scene whose
   footprint is fitted into the ladder band and whose depths straddle MC's brackets; then a
   third readback. Expectation per band pixel from two independent measurements of the same
   frame — the ladder's bracket (lo, hi] for MC's depth and Voxy's own reference render of the
   same scene with the same MVP into its own target (colour + D32 depth, read back once per
   extent): Voxy's colour must appear exactly where its depth ≥ hi, the pixel must stay
   byte-identical to the previous readback where depth ≤ lo, either is allowed in between
   (undetermined, counted), and pixels without Voxy geometry must not change. Zero violations;
   at least one sample with both determinate kinds. Evidence: the third crop, its thumbnail,
   the reference colour and depth crops, a `native-terrain-load.json` report and a log line
   per sample, retained and recounted by the gate like the quad's. MC's depth format must be
   `D32_SFLOAT` (126, measured) to match the pipeline's declared attachment. The reference
   build reuses the terrain probe's fence-waited build (to be factored into a shared scene
   builder), the leak/retire discipline from rounds 5–6, and `recordBeforeRenderPass` is not
   needed in MC's pass because uploads and the depth bound are fence-complete at build time.
   Integration question it answers: can Voxy's real terrain pipeline, with its own depth
   state, composite per pixel against MC's own depth on PreferVulkan/MoltenVK — the dependency
   every real-world LoD draw rests on. Next seam after it: real section data (WorldEngine /
   NodeManager meshes) into `VkTerrainResources` in place of `SyntheticTerrain`.
   Open and not needed for that: why the buffer copy reads 0.0.
3. Nothing in the ladder may ever write MC's depth. If a future variant needs to, it is a
   different probe with a different flag (which is what item 2 does).

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
