VERDICT: REDESIGN

Independent review, round 28, candidate `1085615492e502b52778c8a5199d4039c5624f1d`.
Initial worktree was clean. No production code, fixtures, thresholds or historical
reports were edited. No Minecraft/native/live launch was performed. Review scripts,
logs and isolated mutation results are retained under `.agent-run/r28/`.

The original R27 empty-rebuild and rendering-disable defects are closed in the
source/CPU-contract scope. All seven original full-replay attacks and five negative
direct-gate probes now fail. The named ladder-coverage residual closes. Context drift
and the hierarchy-only coverage residual remain. A new blocking replay defect accepts
an empty or wrong-sized required render thumbnail. DELIVERY-BOUNDARY remains blocking;
default-on is not ready. The original evidence's bounded measurements are confirmed.

## 1. R27 closure judgments

| Finding | Judgment against HEAD | Closed? |
| --- | --- | --- |
| DELIVERY-BOUNDARY | CONFIRMED: normally enabled native LoD with complete normal-play delivery acceptance remains absent | no |
| R27-EMPTY-REBUILD | REFUTED as the old permanent same-section suppression | yes, source/CPU contract |
| R27-RENDER-DISABLE | REFUTED as the old ineffective rendering-off control | yes, source/CPU contract |
| R27-RENDER-GATE | REFUTED for its seven original replay attacks and negative direct probes | yes for those exact attacks; new thumbnail-validity finding below |
| R26-LADDER-GUARD-COVERAGE | REFUTED as the two remaining named untested guards | yes, 22/22 named removals detected |
| R24-HIER-GUARD-COVERAGE | CONFIRMED: hierarchy-only coverage still detects 10/37 shared-helper removals | no |
| R27-CONTEXT-DRIFT | CONFIRMED residual: the architecture introduction still says the native connection is unimplemented | no, partly repaired |

### Empty builds and recovery

**Repair — CONFIRMED.** `McNativeHierarchicalLoad.java:227–229` implements the time
limit; `:348–371` keys suppression on engine, camera section, extent and atlas
generation and updates the key after each build. Ordinary calls stop suppressing
after 600 calls since the empty build. The separate 60-call build limit remains.
Under the ladder, a matching empty key remains suppressed to preserve its budget.

`python3 .agent-run/r28/empty_build_probe.py` extracts and compiles the candidate's
actual suppression predicate and build block with a CPU build stub. Results in
`empty-build.json`: with data available after call 1, stationary recovery occurs
at call **601**; resize and atlas replacement recover at call **61**, with earlier
calls correctly reporting `rebuild-wait`. A ladder key survives 1,000 additional
calls, but a ladder resize releases it. A still-empty ordinary region attempts
four builds in 1,801 calls. The old permanent-suppression reproduction fails.
These are executable control-flow contracts, not hardware recovery measurements.
The retained run does not demonstrate waiting stationary through an empty retry.

### Rendering off, back on, and the kill switch

**Rendering setting — CONFIRMED repaired.** `VoxyConfig.java:117–119` requires
availability, `enabled` and `enableRendering`. The new hierarchy branch
(`McNativeHierarchicalLoad.java:264–268`) retires the current scene and skips before
atlas work, building or drawing. Ordinary calls increment `renderCalls` once
(`:243`), and `skip` increments `FRAME_SKIPS["rendering-disabled"]` once (`:645–649`).
Retirement clears `instance` before queueing (`:839–848`), so later disabled calls
do not queue the same scene again. The setter changes `enableRendering` directly
(`VoxyConfigMenu.java:84–97`); its nullable GL renderer does not bypass this check.

Turning rendering back on permits the next eligible build, subject to the existing
60-call limit, empty key, engine/atlas/camera prerequisites and failure budget.
Disabling rendering leaves ingest/context alive; it is a rendering control, not
whole-engine teardown. Master `enabled=false` also stops rendering and its menu
action shuts down the Voxy instance (`VoxyConfigMenu.java:45–61`).

**Kill switch — CONFIRMED in product scope.** `McNativeRender.java:20–26` makes
`voxy.native.disable=true` override the product switch. At startup, with no explicit
diagnostic flags, it prevents injected product features (`McNativeDeviceFeatures.java:78`),
adoption (`McNativeVkContext.java:96–98`) and native instance mode
(`VoxyClient.java:146–153`). Clearing a startup kill switch at runtime cannot supply
missing device features or rerun the already-attempted adoption/initialization.

At runtime after successful startup, it makes hierarchy `enabled()` false, so
`renderIfEnabled()` returns before counters, retirement or drawing (`hierarchy
:208–212`, `:241–244`). It retains the scene, context and sticky native instance
mode; clearing the kill switch permits ordinary validity checks and drawing again.
Explicit diagnostic flags remain independent and can bypass this **product** kill
switch. It is not a global diagnostic emergency teardown.

`python3 .agent-run/r28/render_disable_probe.py` compiles the extracted entry,
enabled predicates, disabled branch, skip accounting, retirement and switch logic
with configuration/queue stubs. Five disabled calls cause five skips and one
retirement; reenabling builds again; a runtime kill stops calls and retains the
scene, and unkill resumes. `render-disable.json` records these results. Startup
features/adoption and actual GPU/menu transitions are source reasoning only.

### Every original render-gate counterexample

`python3 .agent-run/r28/render_review.py` repeats the round-27 attacks on isolated
copies of **this** committed run. Only changed/deleted member hashes are refreshed;
source fingerprints and judged image/depth bytes stay unchanged. All seven full
replays now return **1**, with no hash or source mismatches:

| Attack | Refusal |
| --- | --- |
| Diagnostic-enabling product command | `native_render_result`, `verify.py:2861–2863` |
| False off-report with draws/results/problems | `render_off_report_problems`, `:2825–2843`, invoked at `:2873–2878` |
| Delete all 14 render thumbnails and their member hashes | required manifest references, `:4758–4761`, `:4781–4785` |
| Validation error and unexpected Voxy ERROR in render log | `native_log_problems`, `:4065–4079`, invoked at `:2908–2910` |
| Own environment incomplete/failed | own environment gate, `:2866–2868`, `:290` |
| Reduce own and summary checkpoints to one | lifecycle coverage, `:292–293` |
| Native edit snapshot before harness edit | current-stage matching, `:2920–2929` |

`python3 .agent-run/r28/render_probes.py` also repeats the direct probes. Baseline
passes; contradictory off-report, omitted `attempted`, early edit snapshot,
999,999,999 skip snapshot and shared `atlasCloseFailures=1` all fail. No original
counterexample survives. Details are in `replay-mutations.json` and `render-probes.json`.
The final composited/skipped/build totals bound their snapshots (`:2935–2939`).

## 2. New repair defects and limits

**R28-RENDER-THUMBNAIL-VALIDITY — CONFIRMED, blocking for sound replay acceptance.**
The new required-thumbnail rule checks manifest membership only. The product
environment deliberately skips screenshots (`verify.py:2866`), and replay never
decodes the replacement visual records. `retain_render_thumbnails` explicitly
says the gate does not read them (`:4334–4336`); replay merely appends their names
to `ladder_refs` (`:4758–4761`).

`/Users/xyz/.nix-profile/bin/python3 .agent-run/r28/render_extra_probes.py` demonstrates
two full replay exits of **0**: replace only `render/frame-horizon.png` with an
**empty file**, or with a valid **1×1 black PNG**, and refresh only that member's
hash. Manifest and source mismatches remain empty; replay still credits 5,301 frames
and every required stage. No judged ladder pixel/depth or command/report/log is
changed. Thus the requirement for retained checkpoint thumbnails can be satisfied
without the required visual record. This does not allege corruption in the original
run: all 14 original thumbnails independently decode at the expected quarter-size
checkpoint extents (`render-thumbnail-checks.json`). Closure of the seven exact old
attacks does not establish general render-gate soundness.

**Environment without screenshots — CONFIRMED narrowly.** It still checks scenario
completion, all 14 distinct lifecycle stages, actual Vulkan backend class, nonzero
device/instance/VMA and image/view handles, positive attachment extents, valid queue
families, empty notes and one device (`verify.py:282–315`). The removed screenshot
check is appropriate only when the retained replacement images are verified; that
replacement validation is the defect above. No new bypass of the unchanged device
or lifecycle guards was demonstrated.

**Print-then-set stage order — REFUTED as a demonstrated race.** The harness prints
its stage at `LiveWorldHarness.java:182`, then sets `voxy.harness.stage` at `:185`.
Its `enter` runs synchronously in the END_CLIENT_TICK callback (`:91–94`), while the
hierarchy snapshot runs at level-render tail. There is no render call or yield
between those two statements. Source control flow and the original ordered logs
support the new in-stage rule; a separate-thread hypothetical is not runtime proof.
Growth remains delimited by native snapshots, with entry-to-snapshot delay, rather
than a per-frame stage trace. No false refusal of this committed run occurred.

**Disabled-path retirement/accounting — CONFIRMED in inspected successful paths.**
The added branch uses the existing Minecraft destroy queue, not immediate free.
`destroy` is idempotent, and adopted-context release waits for the owner device idle
before draining queued/live scenes (`hierarchy :839–879`, `McNativeVkContext.java:291–314`).
Same-queue ordering/fence reasoning from the prior review still applies. No new
successful-path premature free or double accounting was reproduced. Real repeated
toggle/queue/failure/pressure behavior is unmeasured in the retained run.

**Empty-retry cadence — CONFIRMED bounded by calls, not cheap or time-bounded.**
Every 600 eligible render calls a still-empty region constructs a full target,
hierarchical scene, REAL atlas, buffers and pipelines before finding zero meshes
(`hierarchy :604–615`), then frees them (`:635–641`). `VkHierarchicalScene.java:451–457`
and `VkTerrainResources.java:214`, `:259–271` allocate the 32,000,000-byte geometry
buffer, about 534.8 MB atlas mip payload and a similarly sized atlas staging buffer
at 65,536 model slots, plus other resources. Those are requested sizes, not observed
resident peaks. At 60 calls/second, 600 calls is ten seconds; there is no wall-clock
bound at low FPS or while rendering is stopped. This trades permanent suppression
for repeated expensive work. No latency budget, native allocation plateau or
long-session empty-region measurement is retained; those remain readiness gaps,
not a reproduced new leak or latency failure.

## 3. Guard coverage and context drift

**Named ladder residual — CONFIRMED closed.** The two new tests use the reachable
64×32 extent and coincident high-ground camera cases (`test_ladder_coverage.py:116–145`).
`python3 .agent-run/r28/guards.py` rebinds the prior named inventory to the candidate's
source locations, runs unchanged tests and disables one whole direct-raise `if` in
memory at a time. All **22/22** named ladder and **18/18** render-gate removals are
detected (`guard-summary.json`, `guard-details.json`). This does not claim complete
coverage of every ladder guard, OR clause, shader instruction or Vulkan argument.

`helper_guards.py` additionally disables 14 `if` branches in
`render_off_report_problems`, `native_log_problems` and `native_environment_result`.
RenderGateTest detects 10; unchanged NativeGateTest detects all four environmental
survivors when its imported function reference is rebound to the mutant. All 14
are detected across those classes; structural-branch changes and failures/errors
are distinguished in `helper-guard-summary.json`.

**R24 hierarchy-only residual — CONFIRMED remains.** A fresh candidate-bound
`r24_guards.py` removes the same 37 shared-helper direct-raise predicates:
hierarchy-only tests detect **10**, and **27 survive**; real-LOAD detects **37/37**.
Detection by another experiment does not establish hierarchy-only coverage.

**R27-CONTEXT-DRIFT — CONFIRMED partly repaired, not closed.** `project-goal.md:56–61`,
`current-state.md:15–18`, `:144–147` and `architecture.md:203–207` now distinguish
implemented default-off experiments from unaccepted production delivery. However,
`architecture.md:17` still states **"That connection is not implemented"**, and
`:69–71` still says Minecraft-native Vulkan does not borrow Minecraft's device.
The latter could describe only the legacy selector, but it is unqualified; the
introduction directly contradicts the later native-path paragraph. Supporting
context also remains stale at `gpu-contracts.md`'s GPU lifetime introduction and
`testing.md`'s native introduction. Production delivery remains explicitly unaccepted.

## 4. Retained evidence, independent pixels and visuals

**Candidate binding and bounded records — CONFIRMED.** Required replay returns 0.
The run was built/finalized at `8d241b40d9650b955ff232c1bc9a70eb89aa45db`; all **431**
selected source fingerprints match this HEAD with equal inventory. `git diff
8d241b40 HEAD -- scripts src build.gradle` is empty. The independent recount also
hashes all **395** manifest members and checks complete disk membership. These are
editable-record/selected-source consistency checks, not independently authenticated
compiled-JAR or native-call provenance.

The product report reconciles **5,301 composites + 6 atlas-pending + 290
nothing-meshed = 5,597 calls**, six build attempts and three atlas reads. Required
stage composites: warmup 524, turn 653, travel 587, return 589, horizon 593, edit 361,
remove 297, resize 291, reload 253, overworld 290, descend 296, ascend 298, reconnect
268; nether 1, create 0. The launch's command has the product switch alone among
native path switches. Counters confirm recordings, not geometry or pixel correctness
on every counted frame.

**Independent hierarchical recount — CONFIRMED.** `python3 .agent-run/r28/recount.py`
imports no verifier or fixture module. It independently decodes P6/VXF32 bytes,
classifies the fixed palette, compares before/actual/reference RGB, derives depth
reprojection and checks integer 4×4 block-mean anchors. All 23 ladder/coexistence
samples match, as do all eight judged hierarchy samples: **396,521 geometry**,
**294,935 no geometry**, **21,204 must-show shown**, **375,317 must-hide unchanged**,
zero undecided/ambiguous/other/violations. Maximum reprojection relative error is
**4.1177335e-7**; no far clamp is exercised. Ladder frame accounting reconciles
5,181 ordinary composites + 285 skips + 8 handed samples = 5,474 calls.

At **horizon draw 2642**, the 1024×77 crop contains **6,567 geometry**, **72,281 no
geometry**, **2,605 must-show shown** and **3,962 must-hide unchanged**, with zero
violations, **4,845** anchored actual-image blocks and maximum relative depth error
**3.6332082e-7**. This verifies the judged clear-only eligibility rule against the
same-renderer reference in that band, not whole-frame GL parity, ordinary-frame
pixels, downstream depth behavior or production lighting/translucency.

I visually inspected `render/frame-horizon.png`, `frame-travel.png`, `frame-reload.png`,
`frame-nether.png` and `frame-reconnect.png`. Horizon shows the stone wall; travel
shows ocean with disconnected dark horizon strips; reload/reconnect show forests,
mountains, gaps and floating terrain; Nether is nearly black. No diagnostic ladder
band appears. These quarter-size images record displayed checkpoints. They do not
independently identify each visible piece as Voxy or diagnose gaps without a reference.

## 5. Updated default-on readiness

**Default-on readiness — REFUTED; safe to flip now is false.** Concrete remaining work:

1. Retain actual native stationary initial-load/empty-retry recovery and live
   rendering-off/on controls, including repeated toggles, startup/runtime kill
   behavior and safe resource retirement. Source repairs close the old control-flow
   defects but the current run never exercises these acceptance cases.
2. Use configured coverage/subdivision/budgets instead of fixed radius 1, depth 2,
   subdivision 128, 8,192 sections and 4,000,000 quads (`hierarchy :82–100`;
   `VoxyConfig.java:30–35`). Establish sustainable streaming and measured rebuild
   latency, including repeated empty retries and fast travel.
3. Establish correct world/block updates throughout normal play. In particular,
   native instance mode keeps `VoxyClient.backend()` null; the coalesced block-update
   path requires `Backend.VULKAN` (`MixinClientLevel.java:55–58`,
   `VulkanWorldUpdates.java:35–39`) and the Sodium mesh-update ingest path requires a
   nonnull GL render system (`MixinRenderSectionManager.java:97–99`). A non-air
   placement falls through to `MixinClientLevel.java:62`'s return in native mode.
   Chunk-load/unload ingestion exists, but does not prove stationary edit propagation.
   Check sustained placements/removals with actual native mesh-version evidence.
4. Establish the production composite point before Minecraft translucents, native
   vanilla-section bounds/near cut, and downstream depth-consumer behavior. Current
   level-tail clear-mask/color-only composition is a narrower accepted experiment.
5. Provide real Minecraft lightmap/lighting, accepted fog/fade/SSAO and translucency
   ordering, with GL/native visual references for water, sky/clouds, night, Nether,
   mixed opaque/translucent scenes and projection/far-world variants.
6. Demonstrate native arena exhaustion/reclaim/retry/recovery, long-session allocation
   plateau, many builds/retirements, sustained edits and travel. Exercise repeated
   dimensions/reconnect/resize/reload, app close without disconnect, failed submits/
   waits and device changes. Short lifecycle counters and standalone tests are insufficient.
7. Close required-thumbnail decoding/extent validation and hierarchy-only shared-helper
   coverage; finish the context corrections. The two named ladder omissions and
   original seven render attacks are removed from this remaining list.
8. Retain normally enabled, exact-candidate native validation, visual and performance
   acceptance. Product adoption logs still say `syncValidation=false`; validation-layer
   insertion alone does not independently establish native synchronization coverage.

## 6. Dormant safety and required commands

**Every `voxy.native.*` flag unset on GL and Minecraft Vulkan — CONFIRMED in
source/offline scope.** `McNativeRender.on()` is false, features return the original
set, adoption returns without a context, native instance mode stays false, and
marker/terrain/depth/ladder/real/hierarchy/instance sampling remain guarded. Native
camera capture requires native instance mode (`MixinDefaultChunkRenderer.java:53`).
Evidence writes require a harness output directory. The legacy GL selector and GL
renderer remain unchanged; on Minecraft Vulkan the default still leaves Voxy
rendering off. `safe_for_normal_play=true` in the machine verdict means only this
flags-unset dormant scope. Enabled native product normal-play safety remains false.

| Required command | Fresh result |
| --- | --- |
| `./gradlew test --offline -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true` | Exit 0; 354 tests, 353 passed, one documented descriptor-SSBO skip, zero failures/errors. Offscreen context validation/syncValidation=true. Deliberate fill-buffer WAW control and deprecated validation-settings warning are retained. |
| `python3 -m unittest discover -s scripts/tests` | Exit 0; 317 tests in 850.466 s, Python 3.14.4, all passed. Log: `.agent-run/r28/python-tests.log`. |
| `python3 scripts/verify.py --replay-evidence docs/ai/runs/native-evidence/20261009T224733-249406Z` | Exit 0; candidate source inventory and bounded retained measurements pass, subject to the new replay defect above. |

The Python suite uses installed Python 3.14.4 through PATH; the slower system-3.9
attempt was interrupted and its partial log retained. Mutation scripts alter only
in-memory gate predicates or isolated evidence copies. They leave fixtures,
assertions, production source and committed evidence unchanged.

## What could not be checked and why

No fresh Minecraft/native/live, normal-play toggle, GL parity, long-session,
pressure/failure-injection or far-world run was performed because the user prohibited
native/live launches and requested committed-evidence review. The permitted Gradle
tests execute offscreen Vulkan and do not supply these proofs. The product launch
has counters and checkpoint thumbnails, not ordinary-frame pixel/depth readbacks,
allocation/retirement histories or an accepted performance budget. CPU extraction
probes establish only their control-flow contract, not driver/device behavior or
real stationary ingestion timing. Startup kill clearing, repeated actual GPU toggles,
near 8/0.1, extra projection transforms and beyond-Minecraft-far rendering lack hardware
samples here. Editable fingerprints and records do not authenticate compiled-JAR/
native-call provenance. Final delivery/default-on/whole normal-play acceptance remains
unproven; no production fixes were made.
