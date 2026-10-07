# Handoff — Voxy native Vulkan / macOS work

Written 2026-10-07 for a **fresh session with no conversation context**. Branch
`vulkan-macos`, HEAD **bb91527e**.

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

Dispatch procedure (works; GPT-6.1-Sol, not Astra):

```
orca worktree create --agent codex --base-branch <full SHA> --name native-review-rN --json
# dismiss codex's update prompt first:
orca terminal send --terminal <handle> --text $'\x1b'
orca terminal send --terminal <handle> --enter --wait-submit 120 --text "$(cat prompt.txt)"
```

The round-7 prompt is kept as `docs/ai/runs/native-review-prompt-template.txt` — start from it,
replace the SHA, the round number, the findings table and the evidence directory.
The reviewer writes `docs/ai/runs/native-integration-review.md` + `.agent-run/*.json` in its own
worktree; **copy both into `docs/ai/runs/native-integration-review-rN.*`** — I forgot this for
r7 and the commit message referenced a file that wasn't in the repo.

### Findings status after round 7

| Finding | State |
| --- | --- |
| B2 inertness | closed (r3) |
| B5 destruction after unconfirmed completion | closed (r4) |
| R5-LIFETIME retired draw reachable | closed (r6) |
| R4-L1 bounded abandonment | closed (r7) |
| R6-TERRAIN-WAIT free without observed wait | closed (r7) |
| **B1** authoritative/replayable evidence | **open, 7 rounds** |
| **B3** fail-closed proof consistency | **open, 7 rounds** |
| **B4** failure persistence / orientation / sample association | **open, 7 rounds** |
| **R6-TERRAIN-GATE** | **open** (replay/manifest residuals) |
| **R6-TERRAIN-DEVICE** | **open** (stale on-disk report on divergence — fixed in tree, unverified) |
| **R7-DEPTH-GATE** | addressed in `bb91527e`, unreviewed |

B1/B3/B4 are all **evidence/gate** findings, not implementation findings. The recurring error
has one shape: **the gate trusted something the thing under test controls.** Repairs have
removed that progressively — raw pixels instead of aggregates, source constants instead of
published geometry/colours/offsets, the adopted identity instead of any well-formed string,
both orientations instead of the selected one, shared helpers so replay cannot drift from the
stage gate. Counterexamples have narrowed from "three unrelated 500-pixel patches pass" (r2) to
"an offset outside the struct" and "a 1x1 black image" (r7).

## What is measured (survives independent reading)

- MC 26.2 ships a usable Vulkan backend, forced by `--graphicsBackend vulkan`
  (`options.txt preferredGraphicsBackend` is overridden — the launch arg is what works).
- Its device/queues/colour+depth images are reachable without GL.
- MC's device is **Vulkan 1.2 + `VK_KHR_synchronization2` + `VK_KHR_dynamic_rendering`**; core
  1.3 entry points are null, hence the `VkCmd` dispatch layer.
- MC enables only 4 of Voxy's 8 required device features; `MixinVulkanBackend` injects
  `drawIndirectFirstInstance`, `shaderInt64`, `fragmentStoresAndAtomics`,
  `vertexPipelineStoresAndAtomics`. Offsets are **resolved by read-back experiment, never
  arithmetic** (an arithmetic guess produced `VK_ERROR_FEATURE_NOT_PRESENT` and MC refused to
  start). The real offsets are 40 / 160 / 104 / 100 and the gate now pins them.
- A bounded depth-tested draw reaches MC's colour image (`McNativeMarkerDraw`).
- Voxy's real shader stack runs on the adopted device and agrees with a CPU reference
  (189 ordinals, 0 mismatches).
- **Voxy's real terrain pipeline records into a pass MC opens over its own images and comes out
  RGB-identical** to the same scene on Voxy's own target (26116 non-background px, 0
  mismatches). Rounds 6 and 7 both CONFIRMED the measurement; both REFUTED the gate's and the
  probe's lifetime soundness at the time.

## What is NOT established — do not claim these

- Native Voxy terrain is `BLOCKED_UNIMPLEMENTED`. The terrain experiment uses **synthetic**
  input and **clears MC's frame** to make comparison possible, i.e. it does not coexist.
- The **Z convention is unmeasured.** The old band heuristic ("screen bottom is nearby ground")
  was deleted in `bb91527e` because band separation cannot identify which region is nearer, and
  the gate had been *rejecting* "unknown" — forcing certainty from an unproven premise. Do not
  reintroduce it; `zConventionMeasuredHere: false` is asserted by the gate.
- No round has accepted the diagnostic layer as a foundation. Round 4's wording still governs:
  terrain investigation may be **experimental**, not "continuation from an accepted layer".

## THE IMPORTANT NEW FINDING (uncommitted work)

`McNativeDepthLadder` (new, flag `voxy.native.depthladder`, default off) tests a ladder of
8 known NDC depths against **MC's own LOADed scene depth**, compare LESS, **`depthWriteEnable =
false`** (it never writes MC's depth), with a co-located ALWAYS control stripe per column.

Measured twice in isolation:

```
inline ALWAYS control (per column)  0.921 .. 0.929   filled
separate control band               0.978            filled
LESS rungs z = 0.0625 .. 0.9375     ALL FAILED, fill 0.0
=> MC's depth in that band is <= 0.0625
```

Per column the ALWAYS stripe is filled and the LESS rung is empty, so **the only difference is
the depth comparison** — "nothing was drawn" and "something covered it" are excluded. An
independent recount from the retained crop agrees.

Two consequences:

1. **The buffer-copy readback was not broken.** `McNativeDepthProbe` reported all 1,639,680
   pixels as 0.0 and round 7 correctly said the cause was unestablished. The ladder uses a
   completely different mechanism (the GPU's own depth test, no transfer) and agrees.
2. **The `LevelRenderer.render` TAIL hook cannot support coexistence.** MC's depth attachment
   holds the cleared value there, not scene depth. Voxy is reverse-Z with 0.0 = FAR, so testing
   against it occludes nothing — Voxy's terrain would draw over MC's world at any distance.
   **Every existing probe (marker, terrain, depth) sits on this hook.** Coexistence needs an
   earlier hook, before whatever discards the depth.

⚠ The ladder and the terrain probe **cannot both be enabled in one run** — the terrain probe
clears MC's depth, which is the thing the ladder measures.

## Exact state of the tree (half-finished — read this before anything)

Uncommitted:

```
 M build.gradle                       # -PharnessNativeDepthLadder flag
 M scripts/verify.py                  # +185 lines: ladder_report_checks, recount_ladder_sample
 M .../mcnative/McNativeVkContext.java # ladder shutdownImmediate in releaseAdopted
 M .../mixin/minecraft/MixinLevelRenderer.java  # ladder before terrain probe
?? .../mcnative/McNativeDepthLadder.java        # the probe itself
```

Plus `docs/ai/runs/native-integration-review-r7.{md,json}` which I have just copied in.

**The gate is written but NOT wired.** `ladder_report_checks` and `recount_ladder_sample` exist
in `verify.py` and pass when called by hand against the real sample, but:

- nothing calls them from the native stage
- nothing calls them from `replay_evidence`
- there are **zero** tests for them (`grep -c ladder scripts/tests/test_marker_gate.py` = 0)

This is the same defect class the reviews keep finding (R6-TERRAIN-GATE was round 5's marker
replay hole reintroduced in new code). **Do not commit it in this state.**

### Next steps, in order

1. Wire `ladder_report_checks` into the native stage **and** `replay_evidence`; add tests
   (counterexamples: inline control below 0.8, survivors not a prefix, bounds disagreeing with
   the surviving set, sample name vs `sampleAtDraw`, `depthWritesEnabled: true`,
   `zConventionMeasuredHere: true`).
2. Publish `terrainProbeEnabled` from the ladder and make the gate refuse to treat a
   terrain-contaminated run as a measurement.
3. **Retain the isolated run's evidence.** Round 7 recorded
   `isolated_run_independently_confirmed: false` against an isolation claim I made from a
   scratchpad run I never committed. Either retain it or do not claim isolation.
4. Run `python3 scripts/verify.py --only native --seconds 8 --timeout 1500`, commit, push to
   `myfork`, dispatch round 8.
5. Then the goal work: find which point in MC's rendering **does** hold scene depth, using the
   same ladder at candidate hook points. Measure per position before moving the hook.

## Commands

```
./gradlew test --offline --no-daemon -PvkLibname=/opt/homebrew/lib/libvulkan.dylib \
  -PvkValidation=true -PvkSyncEnv=true          # 326 JUnit, 1 skipped, 0 failures
python3 -m unittest discover -s scripts/tests   # 107 cases, ~63s
python3 scripts/verify.py --only native --seconds 8 --timeout 1500   # launches MC, ~2 min
python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/<run>   # no launch
```

Isolated ladder run (terrain OFF — required, see above):

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
- `docs/ai/current-state.md` — what works/doesn't; has rounds 6-7 and the depth finding.
- `docs/ai/vulkan-native-integration-survey.md` — the living claims/evidence document, with a
  per-round repairs section. **Note its "A note on how runs are cited"**: a figure from a run
  whose evidence directory is absent is *narrative, not proof*, and a retained directory stops
  replaying when the gate gets stricter (only the newest run replays; the others are tabled).
- `docs/ai/harness.md` — the review rule.
- `docs/ai/runs/native-evidence/<run>/` — retained evidence, 7 runs, ~12 MB total.
