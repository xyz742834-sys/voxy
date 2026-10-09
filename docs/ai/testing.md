# Testing

Updated 2026-10-04. Use the strict runner for GPU/live verification. The 2026-09-22
results in the audit/context-review are historical baselines, not the current gate.

## Scope relative to the project goal

The target in [project-goal.md](project-goal.md) requires Minecraft itself to use
Vulkan. The commands below verify the current offscreen/GL-hosted diagnostic path;
none currently supplies Minecraft-native-Vulkan integration acceptance.

`scripts/verify.py` writes `preferredGraphicsBackend:"default"` for the live run.
The live harness checks `VoxyClient.Backend.VULKAN` and `VkInteropProbe` metrics;
that enum describes Voxy's renderer, not Minecraft's actual graphics backend.
The existing production selection requires Minecraft GL for this probe route.
Thus even a full runner PASS does not mean the native integration target works.

The development harness also has an environment-only native lifecycle mode:
`python3 scripts/verify.py --only native`. It requests Minecraft Vulkan and verifies
the actual `VulkanDevice`, device/instance/VMA handles and native color/depth image
views at all thirteen lifecycle checkpoints, retaining screenshots. It forces Khronos
validation and synchronization validation through the loader environment and requires
loader evidence that the validation layer was inserted. It never creates Voxy's
private Vulkan context or calls the GL interop probe in this mode. Voxy currently
disables itself; this environment gate cannot certify Voxy native LoD rendering.
The lifecycle has thirteen checkpointed stages since 2026-10-07: the eleven before plus
`descend` and `ascend` (straight down at the ground under (0, 0) from 12 and 108 blocks above
it), which the depth ladder's gate reads the Z direction from.
Since 2026-10-07 the stage launches Minecraft **twice**: the first launch carries the
marker, terrain, depth-copy and proof-file diagnostics; the second carries only the
depth ladder (`-PharnessNativeDepthLadder`), its coexistence experiment
(`-PharnessNativeCoexist`) and the terrain-LOAD experiment (`-PharnessNativeTerrainLoad`,
Voxy's real terrain pipeline into a pass that LOADs MC's colour and depth, after the
ladder's readbacks), because the terrain probe clears the depth attachment the ladder
measures. Any Gradle form naming a harness property enables it (`hasProperty`); the gate
reads the retained command with Gradle's semantics and, on replay, additionally requires
the stage's literal tokens, refusing any ladder command that lacks them. Both launches are gated, logged
(`native.log`, `native-ladder.log`) and retained; the ladder launch lives under
`ladder/` in the evidence directory and `--replay-evidence` re-runs its gate against
the launch's own checkpoints. Replay also requires every retained file to be a
manifest member and refuses to skip a gate the retained summary says ran.

`python3 scripts/verify.py --only required --seconds 30 --timeout 1800` runs the GPU,
interop, existing diagnostic live scenario and native environment scenario in order.
It reports `INCOMPLETE` and exits nonzero while the required native Voxy connection,
native live-world pixel/depth reference and live pressure/lifetime acceptance remain
unimplemented. Passing executed gates is separately recorded as
`executed_gates_success`; these prerequisites cannot be converted to a green result.
`--wait-lock` queues behind an existing runner instead of competing with its build.

A native LoD acceptance run must record Minecraft's actual Vulkan backend/device,
the Voxy connection route and candidate, and reject fallback to Minecraft OpenGL.
Preserve existing evidence for comparison without expanding GL-specific integration
as a delivery milestone. Native integration remains unimplemented.

## One-command verification (current diagnostic path)

```sh
python3 scripts/verify.py
```

Runs all stages sequentially with validation and synchronization validation enabled:

1. JUnit, rerunning tasks and collecting fresh per-test XML.
2. macOS GL↔Vulkan offscreen composite checks.
3. Real Minecraft: create a fresh fixed-seed world → warmup → camera turns →
   travel 768 blocks → return → place/remove glass → resize → resource reload →
   Nether → Overworld → disconnect → reconnect → stop.

Every run uses a unique `build/harness/<UTC timestamp>/` directory. Logs, live
screenshots, JUnit XML, GPU images, source hashes, tracked patch and `summary.json`
are retained, including on failure. Existing `run/` saves/options are never used.
A directory marker and absence of existing saves are checked before live actions.

The runner rejects unexpected validation warnings/errors, unexpected skips, Voxy application ERROR lines, missing
GPU execution, a missing synchronization negative control, missing result/image
files, stage timeouts and nonzero subprocess exit codes. It continues independent
stages after a failed GPU stage, so one invocation captures multiple failure causes.
A checkout lock prevents two runner invocations from racing Gradle/Loom outputs.
Do not concurrently run another Gradle invocation in the same checkout.

Only `VkBarriersTest.plainBufferHazardIsNowDetected` may emit its exact intentional
fill-buffer WAW diagnostic. The descriptor-SSBO `missingBarrierIsDetected` skip
remains visible as `known_gaps`; it cannot pass on this stack and is not evidence.
Setup/teardown diagnostics are checked as well. The existing narrow interop
suppression is not expanded.

## Targeted runs and longer scenarios

```sh
python3 scripts/verify.py --only gpu
python3 scripts/verify.py --only live
python3 scripts/verify.py --seconds 60 --timeout 1800
python3 scripts/verify.py --online
python3 -m unittest discover -s scripts/tests -v
```

Default is offline, 10 seconds per live scenario, 1200 seconds maximum per Gradle
stage. `--seconds` increases dwell time; `--timeout` must cover the whole client
scenario plus startup. `--online` permits dependency downloads. Loader path can be
set with `--vk-lib`; default `/opt/homebrew/lib/libvulkan.dylib`.

Live runs require a macOS graphical login session, a supported Vulkan device,
Khronos loader/layers and the Minecraft asset/dependency cache (or online mode).
No GUI automation app is required. A Voxy backend mismatch or absent validation
fails rather than falling back to a successful GL-only test. The development-only
`src/harness` mod is on the custom run classpath and excluded from the shipped jar.

## Ordinary Gradle commands

```sh
./gradlew build --offline
./gradlew test --offline --rerun-tasks
./gradlew test --offline --rerun-tasks \
  -PvkLibname=/opt/homebrew/lib/libvulkan.dylib \
  -PvkValidation=true -PvkSyncEnv=true
./gradlew interopCompositeCheck --offline \
  -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true
```

These remain useful for development, but only the runner aggregates all validation
output and required execution into a strict exit status. Standard CI has no required
GPU lane, and `VulkanTestSupport` catches any initialization Throwable as a skip.

The normal offline build also passes (302 pass / 4 validation-only skips), and the
produced jar excludes the harness mod.

Fresh required run: `build/harness/20261004T031025-163862Z/summary.json`.
All executed gates passed with no source changes during the run: 305/306 JUnit
cases passed (one documented descriptor-sync blind spot), 45 interop checks, and
eleven checkpoints each in the diagnostic and Minecraft-native environment modes,
with 30 seconds per scenario. Native instance/device validation layer insertion,
Minecraft debugging enabled, native images and clean shutdown diagnostics were
observed. Native Voxy LoD remained disabled. The aggregate correctly reports
`INCOMPLETE`, not native integration acceptance. Its directory also retains the
22 passing Python gate tests and normal build/jar exclusion result.

The latest validation GPU run has 306 cases: 305 pass / 1 known skip, with no
unexpected diagnostics. Offscreen interop has 45 passing checks and clean validation
following explicit IOSurface image memory binding. Consult the run's JSON and logs
for precise revision/source hashes. The live scenario after update-ingestion integration passes all eleven checkpoints,
including newer mesh versions after placement/removal. The generated result is
authoritative runtime evidence; a final run also checks application error output.

## Standalone analytic image and arena recovery gates

`ShaderMemoryLayoutTest` independently decodes actual shaderc-generated SPIR-V
member offsets, matrix stride and runtime array stride. Four tests cover the real
terrain uniform, real traversal uniform/node/request/render queues, shared
DrawCommand/SectionMeta/BlockModel declarations, and an injected member that must
break the contract. Terrain data occupies 92 bytes; that is distinct from its
96-byte std140 rounded struct alignment. This is ABI evidence, not native device
ownership or multi-frame lifetime evidence.

`VkVisualRecoveryTest` adds six required GPU tests. A single known UP quad has a
literal 256×192 reference: framebuffer rectangle x=[64,128), y=[96,144),
RGBA=(192,0,55,65), reverse-Z depth=0.1; all other pixels are black with depth zero.
The reference is independent of the produced image and projection helpers. All
49,152 pixels are compared, including background and encoded alpha. Four deliberate
controls change horizontal placement, omit the draw, change atlas color, or change
only depth; each must be rejected by the reference while its JUnit control passes.

A real 3 KiB `BasicAsyncGeometryManager` arena is filled with two protected roots
and a child. A 2 KiB child demand cannot fit; production `VkGeometryAdmission`
reclaims the child (usage 3072→2048) and rejects the demand. Releasing the other
root gives usage 1024; an actual `NodeManager` re-request then accepts the same
2 KiB demand. Uploads, GPU-generated tables and rendering must recover the exact
analytic image. Three cycles exercise pointer/ID reuse and release all allocations.
This tests arena exhaustion, not OS-wide or Vulkan-device out-of-memory handling.

The strict runner requires all six tests to execute and pass, three complete
pressure cycles, and fresh evidence under `visual-recovery/`. It independently
checks PNG CRCs/scanlines, literal RGB pixels and raw little-endian float depth;
missing, truncated, inconsistent or skipped evidence fails. Each fixture retains
expected/actual/diff PNGs, raw depth and JSON metrics, fingerprinted in the summary.
These reusable Vulkan gates require no Minecraft or OpenGL context. They do not
certify live-world visual parity, real lighting/meshing, historical draw references
under pressure, or Minecraft-native integration. Fifteen Python gate tests also
check diagnostic classification and rejection of corrupted/missing evidence.

## What live checks prove

Each checkpoint requires at least 20 new Vulkan frames, nonzero meshed/selected
geometry and depth coverage, zero invalid render IDs, no stuck geometry exhaustion,
and the current world-engine identity. Position, dimension, block-state, resize and
reload acknowledgements are checked before capture. Placement/removal also waits
for a newer completed mesh version at an ancestor of the edited location.

The harness stabilizes the camera before testing coverage after a turn. Looking away
from terrain during the sweep is valid; only the final known view must contain terrain.
Screenshots are evidence, not a pixel-parity oracle. A checkpoint is not repeated
while asynchronous screenshot saving completes; all eleven PNGs are required.

## Remaining gaps

- Minecraft-native-Vulkan integration and its acceptance gate (the delivery target
  in [project-goal.md](project-goal.md)); current GL-hosted results do not cover it.
- Pixel-level live GL/Vulkan parity, lighting/fog/SSAO comparisons and expected-image
  assertions after edits. A newer mesh version proves regeneration, not pixel accuracy.
- Mandatory low-memory reclaim→re-request and exhaustion→recovery live scenarios.
- Full multi-frame hierarchy/traversal correctness against an independent reference;
  the new changing-count regression checks table generation across dispatch boundaries.
- Shader member offsets/strides against real SPIR-V reflection.
- Long-session resource lifetime, close without disconnect, rapid travel/edit load and
  resource-pack content changes. The current run is a bounded smoke scenario.
- Storage corruption/migration/failure tests and fresh-cache/native-packaging matrices.
- Descriptor-bound SSBO sync-validation blind spot.

Historical manual `TestNodeManager.main()` methods are not JUnit tests. New Vulkan
JUnit tests belong under `src/test/java/me/cortex/voxy/vk/`. Add expected-error rules
only for narrowly scoped negative controls and preserve positive execution checks.

Final combined evidence (2026-10-04):
`build/harness/20261004T021038-528738Z/summary.json` — all three stages passed;
11 live PNGs, no unexpected validation diagnostics, no Voxy application ERROR lines.
This evidence is ignored build output and is not a checked-in baseline image suite.
The captured ocean travel scene shows horizontal bands; the current liveness gate
has no live-world pixel-accuracy oracle to classify that visual artifact or establish its cause.
The new analytic fixture does not establish that the ocean bands are correct.

Additional combined evidence (2026-10-04):
`build/harness/20261004T023424-744890Z/summary.json` — all three stages passed;
289 GPU cases (288 pass, one descriptor-sync skip), six required visual/recovery
cases, four effective negative controls, three recovery cycles, 46 hashed visual
artifacts, 45 interop checks and 11 live checkpoints. No unexpected validation or
live Voxy application errors. The runner's source snapshot/fingerprints bind the
working source candidate; that run certifies its saved source snapshot, not later concurrent source edits.

Latest source-bound combined verdict after concurrent D2/D3/D6 regressions:
`build/harness/20261004T024238-672252Z/combined-verdict.json` — PASS on identical
source fingerprints across the GPU run, complete live rerun and final worktree;
298 cases (297 pass / one known skip), all six visual/recovery tests, three pressure
cycles, 45 interop checks, 11 live checkpoints and normal offline build/JAR checks.
The first live launch from `20261004T024025-344875Z` failed with
`NoClassDefFoundError: FlashbackCompat`; its failed verdict remains retained. A full
live rerun on identical source succeeded. Concurrent editing/builds were observed,
but the exact cause of the transient class-loading failure remains unestablished.
