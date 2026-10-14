# R29 prelaunch HOLD on 5c976db8 — repair of the three blockers (CPU only)

Review of record: [native-prelaunch-review-r29-5c976db8.md](../native-prelaunch-review-r29-5c976db8.md)
/ [.json](../native-prelaunch-review-r29-5c976db8.json) (HOLD; probes in
[r29-prelaunch-5c976db8-probes/](../r29-prelaunch-5c976db8-probes/)). Everything below is a source
claim verified on the CPU only; **no native run has executed it**, and all retained native evidence
is UNBOUND to this source until one does. Default-on stays off.

## End-to-end design

**Causality (R29-PRELAUNCH-CAUSALITY).** The oracle no longer treats an ordered
ingest → dirty → mesh triple as a cause. The trace (`McNativeIngestTrace`) now has one spine: the
observed leaf's *content generation*, advanced only by the real `WorldUpdater.insertUpdate` at the
moment it has actually changed the leaf's level-0 content (`WorldUpdater.Observer`, called after
`insertSectionLvlIntoWorld` and before the dirty mark; null in production). Every mesher captures the
generation **before** it reads the section (`VkRealMesher.meshOne` / `meshAsync` →
`McNativeIngestTrace.meshStarted`), and the scene's acceptance (`meshed`) attaches that value to the
mesh. So a mesh is never credited with content newer than what it could have read: the gate requires
`leafMeshed.generation >= update.generation`. The visibility ingest's record is now honestly
`ingestQueued` (an enqueue), and an enqueue without an updater outcome is nothing. The reviewer's
replay (`pending_ingest_prequeued_dirty_same_leaf`, `target_ingest_executed=false`) run against the
real trace yields `update == null`, `mesh.generation == 0`: refused ("records no update event").

**Removal premise (R29-PRELAUNCH-REMOVAL).** Voxy's held content is read back from its world before
and after each action (`McNativeHierarchicalLoad.heldBlockAt`: acquire the level-0 section as the
mesher does, map the id through the engine's `Mapper`, release; `"none"` when the engine holds no
section). The harness places the probe glass at `warmup` (once the client holds the chunk; the
chunk section must be all air), so the ordinary **chunk-unload ingest during `travel`** (the spawn
chunks leave the view distance; `MixinRenderSectionManager.onChunkRemoved` → `tryAutoIngestChunk`,
GL's path) delivers the placement: `return` requires `voxyHeld == minecraft:glass` and the updater's
change dated between the placement and the mark. `remove` requires Voxy holding the glass before
(otherwise: "Voxy never held the placement … the removal is a no-op for Voxy and observes nothing"),
then, on the scene's engine and strictly after the action: the section queued for ingest; the
updater's change (generation advanced; Voxy now holds air); the leaf's dirty consumed; and one of
two truthful terminals — a mesh from content at or after the change (revision advanced), or, the
leaf having emptied (`update.nonEmptyBlocks == 0`), its geometry removed (`Watcher.unwatch` of the
BLOCK watch → `unwatched`). A no-change outcome (`changed=false`, or no update) is never promoted;
the real updater's no-op was reproduced on a real `WorldEngine` (in-memory storage, Minecraft
registries bootstrapped), not with stubs.

**Shutdown (R29-PRELAUNCH-SHUTDOWN).** One absolute `Deadline` (`common/thread/Deadline.java`)
covers the whole `VoxyInstance.shutdown`: the cleaner join, `VoxelIngestService.shutdownUntil` →
`Service.shutdownUntil` → `PerThreadContextExecutor.shutdownUntil` (bounded `currentRunning` spin),
`SectionSavingService.shutdownUntil` (bounded drain; the rest saved while time remains, the remainder
counted), each used world (`awaitWorldReleaseUntil`), then `UnifiedServiceThreadPool.shutdownUntil`
→ `ServiceManager.shutdownUntil` (bounded `totalJobs` spin) and the bounded worker join. The default
`shutdown()` of every one of these is `Deadline.NONE`: GL waits exactly as before. At the deadline
nothing is freed under a running reader: the executor's contexts and the pool's semaphore block are
left to the running job, the world is left unfreed, and each is counted. An interrupt turns the
deadline into "now": the remaining steps run without waiting, the accounting completes, the flag is
restored, no exception escapes. The native path sets `boundShutdown(10_000)`; the report carries
`worldsLeftUnfreed`, `shutdownIncomplete[]`, `shutdownInterrupted`, `saveEntriesUnflushed`,
`poolThreadsLeft`, and the product gate requires all zero/empty/false. Product limits stay explicit:
an unfreed world is not flushed (data since its last save is lost, as with a killed process); an
unflushed save entry keeps its section acquired; a reader stuck in `ActiveSectionTracker.acquire`'s
loader spin is bounded only by this deadline.

## Counterexample closures (CPU)

| Reviewer's counterexample | Where it is replayed | Result |
|---|---|---|
| `pending_ingest_prequeued_dirty_same_leaf` with `target_ingest_executed=false` passed the whole gate | `McNativeIngestTraceTest.aQueuedIngestWithAnOlderDirtyKeyAndAnUnchangedRemeshIsNoCause` (real trace); `test_render_gate` (`update=None`, `changed=False`, `leafMeshed.generation < update.generation`) | no updater outcome recorded; gate refuses |
| actual `WorldUpdater` no-op on unchanged air (`noop_probe`) | `WorldUpdaterOutcomeTest.reWritingContentVoxyAlreadyHoldsChangesNothingAndMarksNothingDirty` (real engine) | `changed=false`, zero dirty marks; gate refuses `before.voxyHeld != glass` as a precondition, never as changed geometry |
| emptied leaf legitimately unwatched with no accepted mesh | `WorldUpdaterOutcomeTest.placingThenRemovingTheOnlyBlockChangesAndEmptiesTheLeaf` (`nonEmptyBlocks=0`, BLOCK+CHILD_EXISTENCE); `test_render_gate` accepts `leafUnwatched` after the update when `nonEmptyBlocks == 0`, refuses it otherwise | truthful terminal accepted; no promotion |
| extracted shutdown: 30 ms world deadline did not end a 180 ms pre-deadline service hold | `VoxyInstanceShutdownBoundTest.aServiceJobStuckBeforeTheWorldWaitDoesNotHoldTheShutdownPastItsDeadline` (real instance, stuck pool job, 1500 ms bound) | ends inside the bound; only `thread-pool` incomplete; worker counted, not killed |
| interruption escaped before accounting and pool cleanup | `…anInterruptedShutdownFinishesWithoutWaitingAndKeepsItsAccounting` | no exception; `shutdownInterrupted`; counted; flag restored |
| `R (source, CPU)` overstated | matrix rows 4–5 rewritten; status says repaired, not run | — |

## CPU results on this source

- JUnit (`./gradlew test --offline --no-daemon --tests …` for the six CPU classes): 29 tests,
  0 failures — `McNativeIngestTraceTest` 6, `WorldUpdaterOutcomeTest` 3,
  `VoxyInstanceShutdownBoundTest` 4, `WorldReleaseBoundTest` 4, `VkMeshWorkersTest` 7,
  `McNativeHierarchicalLoadTest` 5. No GPU/offscreen test was run.
- Gate guard mutations: [update_mutations.py](update_mutations.py) →
  [update-mutations.log](update-mutations.log): 29/29 refusing predicates of
  `native_update_problem` detected when removed (guard coverage; not semantic sufficiency).
- Python suite (`python3 -m unittest discover -s scripts/tests`): 351 tests, OK (1285 s).

## Not checked (and why)

Candidate-bound offscreen Vulkan, the native launches, the real chunk-unload arrival at `return`,
the leaf's LOD level in the live scene, the empty-leaf terminal in a full scene, real timeout or
interrupt under native load, rendering pixels, sustained edits/travel, ordinary GL launch:
**NOT_RUN** (CPU-only instruction). A placement reaching Voxy by any path other than chunk unload is
not observed. The judgment on this repair belongs to the independent narrow prelaunch review.
