# R30 prelaunch HOLD on 5bdc067e — repair of the two surviving blockers (CPU only)

Review of record: [native-prelaunch-review-r30-5bdc067e.md](../native-prelaunch-review-r30-5bdc067e.md)
/ [.json](../native-prelaunch-review-r30-5bdc067e.json) (HOLD; probes in
[r30-prelaunch-5bdc067e-probes/](../r30-prelaunch-5bdc067e-probes/)). The design sent to root before
implementation, with the reviewer's bounded advisory applied: [DESIGN.md](DESIGN.md). Everything below
is a source claim verified on the CPU only; **no native run has executed it**, and all retained native
evidence is UNBOUND to this source until one does. Default-on stays off. The 5bdc067e repairs of the
removal premise (held content read back, no-op refused, empty-leaf terminal) are preserved.

## What changed

**CAUSALITY (R29-PRELAUNCH-CAUSALITY, surviving in 5bdc067e).** One synchronization boundary, identity
on each result (`McNativeIngestTrace`, `WorldUpdater`, `VkRealMesher`, `VkHierarchicalScene`,
`McNativeHierarchicalLoad.heldBlockAt`, `LiveWorldHarness`, `scripts/verify.py`):
- The trace holds one immutable **observation**: epoch, the bound engine's identity, the leaf, the
  target chunk section and the probe's index, a `StampedLock`, and the leaf's content version (kept
  across observations only for the same leaf and engine). Every event carries the epoch.
- The real `WorldUpdater.insertUpdate` calls `Observer.beginLeafWrite(engine, leaf, source)` before the
  leaf's level-0 content write and `LeafWrite.end(changed)` after the content and the non-air count are
  final, before the dirty mark. The trace's observer takes the observation's write lock in `begin` and
  publishes in `end`, inside it: the `Update` (version, changed, non-air count, the chunk section
  written, and what the write put at the probe, decoded through the engine's mapper). Only the target
  chunk section's updates are the observation's `update`; other sections' are counted separately. The
  observer is installed only while the trace is enabled — production literally runs with none.
- The mesher (`beginMesh` … `endMesh`) holds the read lock through `RenderDataFactory.generateMesh`
  and captures the version and the probe content under it as an immutable `MeshRead`, carried on its own
  result (`AsyncResult.read`, `SyncMesh.read`) to the scene's acceptance (`meshed(read, pos, engine)`);
  the position-keyed `READING` map is gone. `heldBlockAt` reads under the same lock (`readHold`); the
  removed-geometry terminal (`unwatched`) records the version and the leaf's non-air count under it.
- The gate requires, in addition to everything from R29: one epoch per stage and on every event;
  `update.section` equal to the probe's chunk section and `update.wrote` equal to the expected block;
  `leafMeshed.probeHeld` equal to the expected block and `leafMeshed.generation >= update.generation`
  (now sufficient: a read at version v is serialized after every publication at or before v); the unwatch
  terminal at a version at or past the update's with `nonEmptyBlocks == 0`. Nothing is relaxed.

**SHUTDOWN (R29-PRELAUNCH-SHUTDOWN, surviving in 5bdc067e).** One retained operation
(`VoxyInstance`, `McNativeHierarchicalLoad` report, `scripts/verify.py`):
- The complete cleanup — cleaner join, `cleanIdle`, import cancellation, the ingest and saving services,
  each world's wait and `free` (mapper close, storage flush and close), the thread pool — runs as one
  `ShutdownOperation` **to completion** (every step waits as long as it takes) on its own non-daemon
  thread that holds the instance. The caller sets `isRunning=false`, starts it, and waits **only on its
  completion latch** until the bound (`boundShutdown(10_000)` on the native path) or an interrupt; then
  it records the stage the operation is in (`shutdownIncomplete`), counts it outstanding
  (`shutdownOutstanding`) and returns, restoring the interrupt flag. No cleanup runs on the caller after
  that; nothing is freed under a running reader; the operation publishes once in `finally`:
  outstanding → completed (`shutdownCompletedLate` if its caller had already returned) or failed
  (`shutdownFailed`); cleanup stages that throw are counted (`shutdownStageFailures`).
  `awaitOutstandingShutdowns(ms)` reaps. The default (no bound) runs the same body inline — GL unchanged.
- The gate requires the four new counters to be 0 besides the R29 fields. A bounded return is a
  refusal with accounting, never a completed cleanup.

## Counterexample closures (CPU)

| Reviewer's counterexample | Where it is replayed | Result |
|---|---|---|
| `unrelated_change_delayed_target_publication`: (3,7,4) published generation 2; old mesh read 2 with the target glass; target wrote air, paused before publishing; whole gate accepted | `McNativeCausalityRaceTest.thePausedTargetPublicationCannotLendTheUnrelatedChangeToTheOldMesh` (real updater/engine/trace; the pause is inside the write boundary — the only place the write is unpublished) | the held-content read-back **blocks** until the publication (it cannot see the unpublished air); the old mesh is recorded as version 2 / glass; the target's update, once published, is version 3 / section (2,7,4) / wrote air; `mesh.version < update.version` and `mesh.probeHeld != update.wrote` — the gate refuses on both (`test_render_gate`: "update event is chunk section [3, 7, 4]'s, not the probe's [2, 7, 4]" when the unrelated update is offered as the update; "mesh was made from content holding 'minecraft:glass'" for the old mesh); only a read begun after the publication is version 3 / air |
| a variant with only the unrelated section's change | `…theUnrelatedSectionsChangeAloneProducesNoTargetUpdate` | version 2, mesh version 2, **no target update** (gate: "records no update event"); `otherSectionUpdates == 1` |
| "a callback after an unlocked write leaves my pause-before-observer race intact" (advisory) | `…aMeshReadBlocksAWriteUntilItEndsAndTheWriteCannotBeHalfSeen`; `WorldUpdaterOutcomeTest.theObserverSeesTheOutcomeBeforeTheDirtyMark` (`begin`, outcome, dirty) | the write waits for a mesh read to end; the content a mesher reads does not change while it reads; the boundary opens before the write and publishes before the dirty mark |
| reset / rebuild / later start supplying identity; wrong engine | `McNativeIngestTraceTest.observingAgainStartsANewEpochAndKeepsTheContentVersionOfTheSameLeafAndEngine`, `…anotherEnginesReadOrWriteIsNotObserved`; `test_render_gate` epoch and engine refusals | a read captured in an earlier epoch keeps it (refused by the gate); another engine's read takes no lock and its write is not the observation's |
| the actual instance's `world.free()` flush held a 1200 ms shutdown to 2524 ms, interrupt included, `shutdownIncomplete=[]` | `VoxyInstanceShutdownBoundTest.aSlowStorageFlushDoesNotHoldTheBoundedCallerAndIsFinishedOnceByTheRetainedOperation` (real instance, real world via `getOrCreate`, controllable storage) | the caller returns inside its bound (≥ 1100 ms, < 2700 ms) with `shutdownIncomplete += ["world-free"]`, `shutdownOutstanding == 1`, the storage flushed once and not closed; after the explicit release the operation completes: closed once, `shutdownCompletedLate == 1`, outstanding 0, no failure |
| interrupt did not bound the flush | `…anInterruptedCallerStopsWaitingAtOnceAndTheOperationIsUnaffected` | the caller returns at the interrupt (30 s bound untouched), flag restored, `shutdownInterrupted`; the operation's thread is not interrupted and finishes the flush and close once after the release |
| other synchronous waits (saves, import cancellation, untimed locks) | `…aSlowSectionSaveIsTheSavingStageAndIsFinishedAfterRelease`, `…aBlockingImportCancellationIsTheCancelImportsStageAndIsFinishedAfterRelease`, `…aReaderStillHoldingASectionKeepsTheWorldAliveUntilItReleases`, `…aStuckPoolJobIsTheThreadPoolStageAndItsWorkerIsNeitherKilledNorLeaked` | each returns inside the bound at its stage with the operation outstanding; the world is not freed while its save/import/reader is live; after release each completes once |
| GL default | `…theDefaultShutdownRunsInlineCompletesEverythingAndCountsNothing` | inline, no operation, everything freed, nothing counted |
| exceptions logged without counters | `shutdownStageFailures`, `shutdownFailed` (gate: 0) | — |
| matrix rows 4–5 overstated | rewritten: "R29 and R30 prelaunch HOLD findings repaired, not run" | — |
| observer installed while off | installed only by `setEnabled(true)` / the harness flag at class load | production runs with `observer == null` |

## CPU results on this source

- JUnit (`./gradlew test --offline --no-daemon --tests …` for the seven CPU classes): **37 tests,
  0 failures** — `McNativeIngestTraceTest` 7, `McNativeCausalityRaceTest` 3, `WorldUpdaterOutcomeTest` 3,
  `VoxyInstanceShutdownBoundTest` 8, `WorldReleaseBoundTest` 4, `VkMeshWorkersTest` 7,
  `McNativeHierarchicalLoadTest` 5. No GPU/offscreen test was run.
- Gate guard mutations: [update_mutations.py](update_mutations.py) →
  [update-mutations.log](update-mutations.log): **36/36** refusing predicates of
  `native_update_problem` detected when removed (guard coverage; not semantic sufficiency).
- Python suite (`python3 -m unittest discover -s scripts/tests`): **PENDING** at the freeze of this candidate (started on this exact source before the freeze and left running; its exit and count are appended here, docs only, when it ends).
- Snapshot taken at the freeze: [junit/](junit/) (the seven classes' XML), [junit-counts.json](junit-counts.json), [focused-gate.log](focused-gate.log).

## Not checked (and why)

Candidate-bound offscreen Vulkan, the native launches, the real chunk-unload arrival at `return`, the
leaf's LOD level and the live mesh/unwatch terminals in a full scene, the lock's cost and the
`unwatched` read-back's availability under native load, a real 10 s stall or interrupt in the client,
rendering pixels, sustained edits/travel, ordinary GL launch: **NOT_RUN** (CPU-only instruction). The
harness's `warmup` still waits for the chunk, not an explicitly ready native scene (the reviewer's
non-blocking REMOVAL-LIVE-PRECONDITIONS). The judgment on this repair belongs to the independent narrow
prelaunch review.
