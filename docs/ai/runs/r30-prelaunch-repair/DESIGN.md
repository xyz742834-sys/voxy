# Repair design for the two surviving 5bdc067e blockers (CPU only; sent to root before implementation)

Verdict of record: [native-prelaunch-review-r30-5bdc067e.md](../native-prelaunch-review-r30-5bdc067e.md)
(HOLD). The reviewer's bounded design advisory (Orca msg_996b573da225) is applied below. The removal
premise/no-op/empty-terminal repairs of 5bdc067e stay as they are. GL keeps its existing default path.

## 1. CAUSALITY — one synchronization boundary, identity carried on the result

**Counterexample to close.** Unrelated chunk section (3,7,4) of leaf (1,3,2) changes and publishes
generation 2; a mesh read captures generation 2 with the target (2,7,4) still glass; the target removal
writes air and pauses *before* publishing; the old mesh is accepted and the held content reads air:
the whole gate accepts, crediting the mesh with the unrelated change.

**Why it survives today.** The write and its publication are not one atomic step with respect to the
reader: the mesher samples a global counter before an unlocked read, `READING` is keyed by position,
`generation++` is non-atomic, and the updater's callback runs after the unlocked write.

**Design.**
- `McNativeIngestTrace.observe(leafId, probeX, probeY, probeZ, engine)` creates one immutable
  **Observation**: `epoch` (monotonic id), the engine identity it is bound to, the leaf id, the target
  chunk section (probe >> 4) and the probe's index in the leaf, a `ReentrantReadWriteLock`, and the
  leaf's **content version** (kept from the previous observation only for the same leaf *and* engine).
  Every event records the epoch it belongs to. The harness emits `epoch` in before/after; the gate
  requires one epoch per stage, so a reset, a scene rebuild or a later start cannot supply identity.
- **Write boundary** (`WorldUpdater`): the observer gains `beginLeafWrite(engine, leaf, source)` /
  `end(changed)`; production observer is null → unchanged code path (the trace installs its observer
  only when enabled, so "null in production" becomes literally true). When the leaf is the observed one
  and the engine is the bound one, `begin` takes the observation's **write lock**; the level-0 content
  write (`insertSectionLvlIntoWorld`), the non-empty count update and the publication
  (`version++`, the `Update` record) all happen inside it; `end` releases before `markDirty`
  (no callbacks under the lock). The `Update` record is immutable: `seq, epoch, engine, version,
  changed, nonEmptyBlocks, section=[sx,sy,sz] (from the updater's own VoxelizedSection), wrote` (the
  content the updater wrote at the probe, decoded through the engine's mapper). The trace keeps the
  last update **of the target chunk section** (`update`/`lastUpdate`) and counts other sections'
  updates of the leaf separately (`otherSectionUpdates`, informational).
- **Read boundary** (`VkRealMesher`): `McNativeIngestTrace.beginMesh(pos, engine, section)` takes the
  observation's **read lock** (only for the observed leaf; null and no lock otherwise or when off) and
  captures an immutable `MeshRead(seq, epoch, engine, version, probeHeld)`; the lock is held through
  `RenderDataFactory.generateMesh` (its `prepareSectionData` copy of the raw array) and released in
  `finally` by `endMesh(read)`. The `MeshRead` travels **on the result**: a new field of
  `VkRealMesher.AsyncResult`; the synchronous path returns `SyncMesh(built, read)`. The scene passes
  that exact object to `McNativeIngestTrace.meshed(read, engine)`; no position map. Because every
  read lock is serialized with the write boundary, `read.version >= update.version` *implies* the
  snapshot includes the target's write, and `read.probeHeld` is consistent with `read.version`.
- **Held content** (`McNativeHierarchicalLoad.heldBlockAt`): read under the same read lock when the
  position is observed, so the paused-before-publication state is unobservable: the harness sees
  glass with the old version, or air with the new one.
- **Empty-leaf terminal**: `unwatched(pos, engine)` records, under the read lock,
  `Unwatch(seq, epoch, engine, version, nonEmptyBlocks)` (the leaf's count read back; -1 if the leaf
  is not obtainable → refused). The gate requires `version >= update.version` and `nonEmptyBlocks == 0`.
- **Gate (`native_update_problem`)**, in addition to every existing refusal: all events and both marks
  carry the same epoch; `update.section == target chunk section`; `update.wrote == <expected block>`;
  `leafMeshed.probeHeld == <expected block>`; `leafMeshed.generation >= update.generation` (kept —
  now sufficient by serialization); the unwatch binding above. Nothing is relaxed.

**Proof obligations (CPU).** JUnit against the real `WorldUpdater`/`WorldEngine`/trace: the
reviewer's race replayed (unrelated change, old mesh read under the read boundary, target thread
paused *inside* its write boundary): the held-content read blocks until publication; the old mesh
accepted during the pause records version 2 with probe glass; after release the target update is
version 3, section (2,7,4), wrote air, and the gate-shaped snapshot refuses the old mesh and accepts
only a mesh read begun after publication (version 3, probe air). A variant whose only change is the
unrelated section cannot produce a target update. Epoch mismatch (re-observe between read and
acceptance) is refused. `WorldUpdaterOutcomeTest` keeps its real no-op/empty outcomes under the new
observer. Python: fixtures carry the new fields; the reviewer's `gate_probe.py` shape with the new
fields is refused; mutations of each new predicate detected.

## 2. SHUTDOWN — one retained operation, caller waits only on its handle

**Counterexample to close.** Real instance + real world with a slow storage `flush`, 1200 ms bound:
the caller stays inside `world.free()` until the flush ends (2524 ms), interrupt included;
`shutdownIncomplete` stays empty. Other synchronous reachable waits: `cleanIdle`, untimed
`activeWorldLock`, `ImportManager.cancelImport` (importer joins), `SectionSavingService.processJob`,
`WorldEngine.free` (mapper close, storage flush/close), executor cleanup callbacks.

**Design.**
- The complete cleanup chain (cleaner join, `cleanIdle`, locks, import cancellation, ingest and saving
  drains and saves, per-world wait and `free` = flush/close, pool shutdown) becomes the body of one
  **`ShutdownOperation`** that runs **to completion, waiting as long as each step takes** (every stage
  at `Deadline.NONE`: nothing is left half-freed, no fallback cleanup anywhere else).
- **GL / default (`shutdownDeadlineMillis < 0`)**: the body runs inline on the caller exactly as today.
- **Native (`boundShutdown(10_000)`)**: the caller takes one absolute deadline, starts the operation on
  its own non-daemon thread (strong references to the instance, its services, worlds and storages are
  the operation's own fields; `VoxyCommon.INSTANCE = null` releases nothing), and waits **only on the
  operation's completion latch**. At the deadline, or when interrupted (flag restored,
  `shutdownInterrupted`), the caller records the stage the operation is in (`shutdownIncomplete +=
  stage`), counts the operation as **outstanding**, and returns: a bounded refusal, never completion.
  The operation keeps running; it publishes exactly once in `finally`: outstanding → completed
  (`shutdownCompletedLate++` if its caller had already returned) or failed (`shutdownFailed++`, the
  throwable logged). Stage exceptions inside the body are counted (`shutdownStageFailures`), not only
  logged. `awaitOutstandingShutdowns(millis)` lets tests and the harness reap operations.
- **Gate (`native_render_result`)**: in addition to the existing checks, `shutdownOutstanding == 0`,
  `shutdownCompletedLate == 0`, `shutdownFailed == 0`, `shutdownStageFailures == 0`. A shutdown that
  outlives its 10 s bound is refused even though it eventually completes. Stated limits: a stuck
  flush/import/lock retains the operation (and its thread) indefinitely, visibly; a JVM exit before
  completion loses what the operation had not flushed, as a killed process would; none of this is
  default-on acceptance.

**Proof obligations (CPU, real `VoxyInstance.shutdown`, worlds registered through `getOrCreate` with a
controllable storage).** Slow `flush` (the reviewer's probe): the caller returns inside its 1200 ms
bound with `shutdownIncomplete == ["world-free"]`, `shutdownOutstanding == 1`, the storage not closed,
the world not freed twice; after explicit release, `awaitOutstandingShutdowns` joins, the storage was
flushed and closed exactly once, `shutdownCompletedLate == 1`, `shutdownOutstanding == 0`. The same
with the caller interrupted mid-wait (returns at once, flag restored, operation unaffected). Slow
`saveSection` (saving stage), blocking importer `shutdown` (import cancellation), a reader still
holding a section (the operation waits; nothing is freed under the reader; release → completion), the
stuck pool job (stage `thread-pool`, worker counted alive while outstanding, zero after release), and
the default: inline, no operation, nothing counted. Every test thread is retained until explicit
release and positive join.
