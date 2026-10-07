# Handoff — Voxy native Vulkan / macOS work

Written 2026-10-07 for a **fresh session with no conversation context**. Branch
`vulkan-macos`, HEAD = the commit introducing `McNativeDepthLadder` (round-8 candidate; exact SHA recorded in the follow-up docs commit).

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
until a fresh independent review of a frozen commit records a verdict. Seven rounds have run,
**all REDESIGN**. Reports are in `docs/ai/runs/native-integration-review-r1..r7.{md,json}`.
Round 8 was dispatched against this HEAD (see "Round 8" below).

Dispatch procedure (works; GPT-6.1-Sol, not Astra):

```
orca worktree create --agent codex --base-branch <full SHA> --name native-review-rN --json
# dismiss codex's update prompt first:
orca terminal send --terminal <handle> --text $'\x1b'
orca terminal send --terminal <handle> --enter --wait-submit 120 --text "$(cat prompt.txt)"
```

The round-7 prompt is kept as `docs/ai/runs/native-review-prompt-template.txt`; the round-8
prompt as sent is `docs/ai/runs/native-review-prompt-r8.txt`. Start from the newest, replace
the SHA, the round number, the findings table and the evidence directory.
The reviewer writes `docs/ai/runs/native-integration-review.md` + `.agent-run/*.json` in its own
worktree; **copy both into `docs/ai/runs/native-integration-review-rN.*`** and commit the
report separately from any repair.

### Findings status after round 7, and what this HEAD claims

| Finding | State |
| --- | --- |
| B2 inertness | closed (r3) |
| B5 destruction after unconfirmed completion | closed (r4) |
| R5-LIFETIME retired draw reachable | closed (r6) |
| R4-L1 bounded abandonment | closed (r7) |
| R6-TERRAIN-WAIT free without observed wait | closed (r7) |
| **B1** authoritative/replayable evidence | open 7 rounds; r7 residuals (colour references, manifest membership) repaired in `bb91527e`, **unreviewed** |
| **B3** fail-closed proof consistency | open 7 rounds; r7 residual (impossible offsets) repaired in `bb91527e` — offsets pinned to 40/160/104/100, **unreviewed** |
| **B4** failure persistence / orientation / sample association | open 7 rounds; r7 residual (rejected-crop clamping, 1x1 image) repaired in `bb91527e`, **unreviewed** |
| **R6-TERRAIN-GATE** | r7 residuals (replay device argument, reference/rejected membership) repaired in `bb91527e`, **unreviewed** |
| **R6-TERRAIN-DEVICE** | r7 residual (stale on-disk report on divergence) repaired in `bb91527e`, **unreviewed** |
| **R7-DEPTH-GATE** | repaired in `bb91527e` (band heuristic deleted, raw depth retained and recounted, replay calls the depth gate), **unreviewed** |
| **Depth ladder** (new) | measured and gated in this HEAD; its isolation claim is retained this time; **unreviewed** |

B1/B3/B4 are all **evidence/gate** findings. The recurring error has one shape: **the gate
trusted something the thing under test controls.** Repairs have removed that progressively —
raw pixels instead of aggregates, source constants instead of published geometry/colours/
offsets/bands, the adopted identity instead of any well-formed string, both orientations
instead of the selected one, shared helpers so replay cannot drift from the stage gate.

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
- **MC's depth attachment at the `LevelRenderer.render` tail holds ≤ 0.0625** across the tested
  band, by MC's own depth test (`McNativeDepthLadder`: all eight LESS rungs with depth writes
  OFF rejected, every co-located ALWAYS stripe filled ~0.93, separate control ~0.98). Retained
  as `docs/ai/runs/native-evidence/20261007T005606-024013Z/ladder/` from the ladder's **own launch**
  with terrain and marker off; the report says so and the gate requires it. Survey section
  "Minecraft's depth at the level-render tail, tested behaviourally".

## What is NOT established — do not claim these

- Native Voxy terrain is `BLOCKED_UNIMPLEMENTED`. The terrain experiment uses **synthetic**
  input and **clears MC's frame** to make comparison possible, i.e. it does not coexist.
- The **Z convention is unmeasured.** A single band of known depths bounds a value, not a
  direction. The band heuristic was deleted in `bb91527e`; `zConventionMeasuredHere: false` is
  asserted by both depth gates. Do not reintroduce it.
- **Why** MC's attachment reads ≤ 0.0625 at the tail (cleared after the scene? a different
  attachment than the scene was drawn into?) is not established. What IS established is that a
  depth test at that hook composes nothing under either convention, so **coexistence needs an
  earlier hook** — and every existing probe sits on the tail hook.
- No round has accepted the diagnostic layer as a foundation. Round 4's wording still governs:
  terrain investigation may be **experimental**, not "continuation from an accepted layer".

## Exact state of the tree

Clean at HEAD. The native stage (`--only native`) now launches Minecraft **twice**: launch 1 as
before (marker, features, adopt, probe, terrain, depth copy), launch 2 with only
`-PharnessNativeDepthLadder=true` (+ native/adopt/features/probe). Both are gated; the second's
device is tied to its **own** checkpoints. Evidence for the ladder launch is retained under
`<run>/ladder/` and `--replay-evidence` runs `ladder_report_checks` on it (same function as the
stage), saying explicitly "not replayed" for runs that retained none.

Gate/test counts at this HEAD: JUnit 329 (1 documented skip, 0 failures); Python 148 cases (41 new in
`scripts/tests/test_ladder_gate.py`); native stage green as
`docs/ai/runs/native-evidence/20261007T005606-024013Z` (replay 0, ladder included). Older retained
runs do not replay under this gate, which is expected and tabled in the survey.

## Round 8

Dispatched against that commit with `docs/ai/runs/native-review-prompt-r8.txt`. When its
report lands: copy it to `docs/ai/runs/native-integration-review-r8.{md,json}`, commit the
report alone, then repair blocking findings in a separate commit.

### Next steps, in order

1. Import the round-8 report; repair its blocking findings; re-run `--only native`, commit,
   push to `myfork`, dispatch round 9.
2. Then the goal work: find which point in MC's rendering **does** hold scene depth. Use the
   same ladder (same flag, same gate) at candidate hook points — e.g. before Sodium's terrain
   pass ends, before MC's post-processing, before whatever discards the depth. **Measure per
   position before moving any hook.** A survived prefix that is not all-or-nothing is the
   signal that the attachment holds scene depth there; only then can a convention experiment
   (move the camera a known amount, watch the bound move) be designed.
3. Nothing in the ladder may ever write MC's depth. If a future variant needs to, it is a
   different probe with a different flag.

## Commands

```
./gradlew test --offline --no-daemon -PvkLibname=/opt/homebrew/lib/libvulkan.dylib \
  -PvkValidation=true -PvkSyncEnv=true          # JUnit
python3 -m unittest discover -s scripts/tests   # 148 cases, ~130s
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
- `docs/ai/runs/native-evidence/<run>/` — retained evidence, 8 runs.
