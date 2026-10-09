# Handoff — Voxy native Vulkan / macOS work

Written 2026-10-07 for a **fresh session with no conversation context**. Branch
`vulkan-macos`, HEAD = **the commit that adds the coexistence experiment** (round-15 candidate; its SHA is
recorded in the docs commit that follows it; round 14 judged `059df4ca` and closed R13-Z-BINDING).

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
until a fresh independent review of a frozen commit records a verdict. Fourteen rounds have
run, **all REDESIGN** — the standing delivery boundary (native LoD is not implemented) is
always blocking; round 14 closed R13-Z-BINDING and left only non-blocking items, repaired in
this HEAD. Reports are in `docs/ai/runs/native-integration-review-r1..r14.{md,json}`.

Dispatch procedure (works; GPT-6.1-Sol, not Astra):

```
orca worktree create --agent codex --base-branch <full SHA> --name native-review-rN --json
# dismiss codex's update prompt first:
orca terminal send --terminal <handle> --text $'\x1b'
orca terminal send --terminal <handle> --enter --wait-submit 120 --text "$(cat prompt.txt)"
```

The round-7 prompt is kept as `docs/ai/runs/native-review-prompt-template.txt`; the round-8,
-9 … -15 prompts as sent are `docs/ai/runs/native-review-prompt-r8.txt` … `-r15`. Start
from the newest, replace the SHA, the round number, the findings table and the evidence
directory.
The reviewer writes `docs/ai/runs/native-integration-review.md` + `.agent-run/*.json` in its own
worktree; **copy both into `docs/ai/runs/native-integration-review-rN.*`** and commit the
report separately from any repair.

### Findings status after round 14, and what this HEAD claims

| Finding | State |
| --- | --- |
| every evidence/gate finding B1 … R13-Z-BINDING, R13-TEST-XZ | closed (r12, r14) |
| DELIVERY-BOUNDARY | standing: diagnostics are not an accepted foundation; native LoD is unimplemented |
| **Z direction** | confirmed (r13, r14): reverse-Z |
| non-blocking R14-TEST-BINDINGS / R14-DOC-DRIFT | addressed in this HEAD (two guard tests; wording, date, handoff, source comments) |
| **Coexistence, known-depth quad** (new goal work) | measured in this HEAD, gated per pixel, **unreviewed** |
| non-blocking limits R10-ANCHOR-GRAIN / R10-CONSISTENT-FORGERY / R12-CREATOR-RESTORE / R13-GEOMETRY-SCOPE / R13-DEPTH-COPY-SCOPE | **stated as limits** |

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
  The per-pixel ladder in this HEAD measured: 18 samples (draw 2, then every 240 draws to 4082), **every one with `other` = 0 and `anomaly` = 0** — the band was drawn at every pixel and the depth test decided every pixel, in every sample. Draw 2 (world just opened): 42 825 px below 2⁻¹⁶ (sky), 28 131 px in (2⁻¹⁶, 2⁻¹⁴], 7 892 px in (2⁻¹⁴, 2⁻¹²] — the clouds. Draws 242–962 and 1682–2882 (overworld, spawn camera): 11–25 k px below 2⁻¹⁶ and 53–67 k px in (2⁻¹², 2⁻¹⁰], and the retained crop reads as a depth silhouette of the hills against the sky. Draws 1202 and 1442 (just after the travel teleport): every pixel below 2⁻¹⁶ — consistent with the cleared value, not shown to be it. From draw 3122 (after the resize, band 1152×86) the same terrain bracket; draw 3602 (nether): 80 030 px below 2⁻¹⁶, 2 738 px in (2⁻¹⁰, 2⁻⁸] and 16 304 px in (2⁻⁸, 2⁻⁶] — near walls. If the source facts about the projection hold (near 0.05, reverse-Z — direction still unmeasured), (2⁻¹², 2⁻¹⁰] is roughly 50–200 blocks and (2⁻⁸, 2⁻⁶] 3–12 blocks. Replay returns 0 with nine checks; 36 crops (both orientations of 18 samples) and 22 band crops from the screenshots are retained, 7.9 MB.
- **MC clears its main depth to 0.0** at the start of `LevelRenderer.render` (frame-graph
  "clear" pass → `clearColorAndDepthTextures(…, 0.0)`, read from the 26.2 bytecode). That is
  the reverse-Z far value. Source evidence about the clear, not a measurement of the convention.
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
- The **Z convention is unmeasured.** A single band of known depths bounds a value, not a
  direction. The band heuristic was deleted in `bb91527e`; `zConventionMeasuredHere: false` is
  asserted by both depth gates. Do not reintroduce it.
- ⚠ **"The tail hook cannot support coexistence" was claimed in `3421640f` and RETRACTED the
  same day** (`fcd5ce18`). The linear rungs 0.0625..0.9375 sat inside the first block in front
  of the camera. Round 8 refuted the same claim independently. **Why the buffer copy read 0.0
  everywhere** while the depth test sees non-zero values is not established.
- The depth **scale** (near/far mapping) is not measured; the direction is. Coexistence (a
  depth-tested Voxy draw against MC's LOADed depth) is not yet attempted.
- No round has accepted the diagnostic layer as a foundation. Round 4's wording still governs:
  terrain investigation may be **experimental**, not "continuation from an accepted layer".

## Exact state of the tree

Clean at HEAD. The native stage (`--only native`) launches Minecraft **twice**: launch 1 as
before (marker, features, adopt, probe, terrain, depth copy), launch 2 with only
`-PharnessNativeDepthLadder=true` (+ native/adopt/features/probe). Both are gated; the second's
device and frame extents are tied to its **own** checkpoints. Evidence for the ladder launch is
retained under `<run>/ladder/` (report, every sample's two crops, band crops from every
screenshot, log, own checkpoints) and `--replay-evidence` runs `ladder_report_checks` on it
(same function as the stage), saying explicitly "not replayed" for runs that retained none and
refusing when the summary says a ladder ran but none is retained.

Gate/test counts at this HEAD: JUnit 333 (1 documented skip, 0 failures); Python 185 cases;
native stage green as `docs/ai/runs/native-evidence/20261009T045515-905630Z` (replay 0 **in this
checkout** — replay requires the retained source fingerprint to equal the tree's source
inventory, so only a run built from HEAD's sources replays). The lifecycle now has thirteen
checkpointed stages (`descend`, `ascend` added). Older retained runs do not replay under this
gate, which is expected and tabled in the survey.

## Round 15

Dispatch against this HEAD with `docs/ai/runs/native-review-prompt-r15.txt` (if not already
done — check `git log` for a "docs: record the round-15 dispatch" commit). When its report
lands: copy it to `docs/ai/runs/native-integration-review-r15.{md,json}`, commit the report
alone, then repair blocking findings in a separate commit.

### Next steps, in order

1. Import the round-15 report; repair its blocking findings; re-run `--only native`, commit,
   push to `myfork`, dispatch round 16.
2. Then the goal work, next step: **Voxy's real terrain pipeline in a LOADed pass**. The
   known-depth quad now composes per pixel; the terrain probe today CLEARS MC's frame to
   compare against Voxy's own target. The next experiment keeps that comparison but adds a
   LOAD variant: draw the synthetic terrain through `VkTerrainRenderer.recordDrawsInRenderPass`
   into a pass that LOADs MC's colour and depth, with Voxy's own depth state (writes on for
   Voxy's geometry), and show per pixel — against the ladder's brackets of the same frame and
   against the depth the synthetic geometry implies — that Voxy's pixels appear exactly where
   its depth is nearer. Gate it like the quad (two crops of the same band, zero violations,
   a mixed sample required). Open and not needed for that: why the buffer copy reads 0.0.
3. Nothing in the ladder may ever write MC's depth. If a future variant needs to, it is a
   different probe with a different flag.

## Commands

```
./gradlew test --offline --no-daemon -PvkLibname=/opt/homebrew/lib/libvulkan.dylib \
  -PvkValidation=true -PvkSyncEnv=true          # JUnit
python3 -m unittest discover -s scripts/tests   # 185 cases, ~6 min
python3 scripts/verify.py --only native --seconds 8 --timeout 1500   # launches MC twice, ~5 min
python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/<run>   # no launch
```

Manual isolated ladder run (what launch 2 does; terrain OFF is required):

```
O=$(mktemp -d)/ladder; mkdir -p $O/game; echo ladder > $O/game/.voxy-harness
printf 'preferredGraphicsBackend:"vulkan"\nonboardAccessibility:false\ntutorialStep:none\npauseOnLostFocus:false\nrenderDistance:8\nsimulationDistance:5\nmaxFps:60\nenableVsync:false\n' > $O/game/options.txt
./gradlew --console=plain runHarnessClient --offline --no-daemon \
  -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true \
  -PharnessOutput=$O -PharnessRunDir=$O/game -PharnessSeconds=6 -PharnessNative=true \
  -PharnessGraphicsBackend=vulkan -PharnessNativeAdopt=true -PharnessNativeFeatures=true \
  -PharnessNativeDepthLadder=true
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
- `docs/ai/runs/native-evidence/<run>/` — retained evidence, 16 runs.
