# Native acceptance matrix (after the round-28 review of record)

Living. Scope: Voxy on Minecraft's own Vulkan backend (macOS/MoltenVK) under `voxy.native.render`.
The round-28 Codex review of record ([runs/native-integration-review-r28-record.md](runs/native-integration-review-r28-record.md),
frozen `6e8a35a0`, REDESIGN) is the source for every open row. DELIVERY-BOUNDARY (normally enabled
delivery) is separate from default-on readiness; neither is accepted. `safe_for_normal_play=true`
in its JSON is the flags-unset dormant scope only.

Status: **E** = existing retained evidence covers it (within its stated scope); **R** = repaired
in source, needs one fresh bound native run; **M** = missing check, implementable; **O** = owner
decision implementation cannot settle.

| # | Area | Status | Existing evidence / repair | Missing (finite) |
|---|------|--------|----------------------------|------------------|
| 1 | Clear-pixel composition, depth write, cutout point | E | Ladder bands (12 judged hierarchy samples, zero violations), reprojection | Downstream depth users (entities, weather) — visual reference |
| 2 | Far / fade / night | E | Run `20261013T013657-855541Z`: 3553 far, 69 faded, 2357 night pixels judged | Floors are 1 pixel (row 13) |
| 3 | Water / translucency | E (partial) | Horizon band is ocean; judged against Voxy's GL rule | Mixed opaque/translucent ordering reference |
| 4 | Native updates (placement held → removal → updater change → exact leaf terminal) | R (source, CPU; R29 prelaunch HOLD findings repaired, not run) | The R29 prelaunch review refuted the previous derivation (ingest/dirty/mesh order on one engine is not a cause) and its removal premise (Voxy never held the placement; correct removal is a no-op). Now: the probe glass is placed at `warmup` (chunk loaded, chunk section all air); `return` must show Voxy holds it, read back from its world (`heldBlockAt`), delivered by the ordinary chunk-unload ingest during `travel`, with the real updater's content change dated between the marks; `remove` requires Voxy holding the glass before, then on the scene's engine and strictly after the action: the section queued for ingest, `WorldUpdater` changing the leaf (content generation advanced, Voxy now holds air), the scene consuming the leaf's dirty update, and one truthful terminal — a mesh whose mesher read content at or after that generation (revision advanced), or, the leaf emptied, its geometry removed (unwatched). Stale-content meshes, no-change outcomes and bare orderings are refused. CPU: 6 trace tests (the reviewer's prequeued-dirty replay yields no update), 3 real-`WorldUpdater` tests on a real `WorldEngine` (unchanged air: no dirty, `changed=false`; removal: `changed=true`, 0 non-air blocks), 29/29 gate predicates detected when removed | Not yet run on hardware (the chunk-unload arrival, the leaf's LOD level and the empty-leaf terminal are source claims until then); pixels not observable this close; first-lifecycle checkpoints only (stated coverage limit); sustained edits / rapid travel; placement via any path other than chunk unload is not observed |
| 5 | Worker and whole-shutdown bound | R (source, CPU; R29 prelaunch HOLD findings repaired, not run) | Inner: `VkMeshWorkers` 250 ms stop, per-task world reference, deferred mesher/bakery free, leaked count (gate: 0). Outer (R29 prelaunch): one `Deadline` for the whole `VoxyInstance.shutdown` — cleaner join, ingest and saving services (`PerThreadContextExecutor`/`Service`/`ServiceManager`/`UnifiedServiceThreadPool` gained bounded `shutdownUntil`; the default `shutdown()` is `Deadline.NONE`, so GL waits as before), each used world, the thread pool; set to 10 s by the native path. At the deadline nothing is freed under a running reader: the world stays unfreed and counted, unsaved entries counted, alive workers counted, the executor's contexts and the pool's semaphore left to them. An interrupt ends the waiting, the remaining steps still run and count, the flag is restored (no exception escapes). Report: `worldsLeftUnfreed`, `shutdownIncomplete[]`, `shutdownInterrupted`, `saveEntriesUnflushed`, `poolThreadsLeft` (gate: all zero/empty/false). CPU: 4 tests on a real instance (stuck pre-deadline service job → only `thread-pool` incomplete, within the bound; unbounded + interrupt → finishes, counted, flag restored; default completes; saving service counts what the deadline leaves) + 4 world-wait tests + 7 worker tests | Not yet run on hardware; a world left unfreed is not flushed (data since its last save is lost, as with a killed process) and an unflushed save entry keeps its section acquired — explicit product limits, not accepted for default-on; a reader stuck inside `ActiveSectionTracker.acquire`'s loader spin is bounded only by this deadline (its own wait is unbounded) |
| 6 | Gate guard coverage | R | streaming=False and within-size-run growth: focused tests, each predicate's removal detected | — |
| 7 | Living context | R | current-state / project-goal / three code comments corrected | — |
| 8 | Lifecycle soak, pressure reclaim, injected submit failure | E | 4-cycle product + pressure soaks, one injected failed submit | Long session, rapid travel; real device loss is not testable here |
| 9 | Kill switch / config off-on | E (CPU) | `render_disable_probe`, JUnit | Live menu toggle and startup kill launch |
| 10 | Lightmap latency across dimension changes | E (partial) | Per-scene first frame uses the cached lightmap | First-ever scene synthetic until first copy — measure or accept |
| 11 | SSAO BETTER/BEST | O | BASIC ported; BETTER/BEST run as BASIC with one note | **Owner:** accept the BASIC fallback, or require BETTER/BEST (they read Minecraft's depth: new work) |
| 12 | Performance / memory budget | O | Product p95 CPU 2.0 ms, scene GPU 3.5 ms; 730 MB buffers + 599 MB textures at 1708×960 (option B capacities) | **Owner:** set budgets, or accept "measured, not budgeted" for default-on |
| 13 | Allocation tolerances 5 % / 1 %, far/fade/night floors of one pixel | O | Measured runs are flat per size; samples exceed floors by 69–3553× | **Owner:** keep, or tighten (e.g. floors from measured minima); not changed without evidence/decision |
| 14 | GL path regression with the new mixins | M | Mixins inert flags-unset (source review) | One ordinary GL launch on the candidate |

Before the next native run: an explicit narrow pre-launch judgment by the independent R29 reviewer on
rows 4–5 (the run of `6a29306c` was aborted: its edit oracle could not pass —
`runs/native-aborted/20261013T083748-060746Z-abort.json`; the judgment on `5c976db8` was HOLD —
`runs/native-prelaunch-review-r29-5c976db8.{md,json}`, three blockers repaired here, see
`runs/r29-prelaunch-repair/README.md`). The run then binds rows 4–7 and must pass the update
observation in the product, pressure and inject launches. Until that run, every native evidence claim
is UNBOUND to this source.
