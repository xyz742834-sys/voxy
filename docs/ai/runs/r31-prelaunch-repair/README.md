# R31 prelaunch HOLD on dd230016 — repair of the one survivor, `reset_lock_split` (CPU only)

Review of record: [native-prelaunch-review-r31-dd230016.md](../native-prelaunch-review-r31-dd230016.md)
/ [.json](../native-prelaunch-review-r31-dd230016.json) (HOLD with one remaining blocker; probes in
[r31-prelaunch-dd230016-probes/](../r31-prelaunch-dd230016-probes/)). **SHUTDOWN is CLOSED** by that
review for the bounded-caller source/CPU counterexample; its source and tests are untouched here. The
whole Python suite result for `dd230016` (351 OK) is recorded in
[../r30-prelaunch-repair/README.md](../r30-prelaunch-repair/README.md) and does not prove this source.
Everything below is a source claim verified on the CPU only; **no native run has executed it**; native
evidence stays UNBOUND; default-on stays off.

## The survivor and the change

In `dd230016` each `Observation` owned its `StampedLock`. The harness re-observes the same engine and
leaf at every stage's before mark; a writer that had captured the old observation (holding or waiting
for the old lock) kept writing the same physical leaf while a new-epoch mesh read held the *new* lock, so
an immutable `MeshRead` could say air/version 3 while the array read under it held glass; the whole
gate accepted (`ResetProbe.java`, `reset-probe.json`, `gate-results.json`).

Now (`McNativeIngestTrace` only; one file): the lock and the content version live in a **`Domain`**, one
per (engine, leaf), held in a weak map keyed by the engine object (identity) for the engine's lifetime
and **never replaced**. An `Observation` (epoch, bound engine, leaf, target chunk section, probe index,
events) references the leaf's domain; `observe()` creates a new observation, new epoch, *same domain*.
The updater's write boundary locks and versions the domain it captured, which is the leaf's one domain
whatever observation is current when it ends; `beginMesh`, `readHold` and `unwatched` take the same
domain's read lock. Evidence identity (epoch) and physical content synchronization are separate: an
old-epoch writer's publication lands in its own observation (not this epoch's update), but its write is
serialized with every current reader. Nothing is retired while a captured operation can still write,
because nothing is retired at all; no new unlocked write path exists. The content version is no longer
copied across observations — it is the domain's, so "same leaf and engine keep their version, another
leaf or engine starts from 0" holds as before.

## Counterexample closure (CPU)

| Reviewer's schedule | Where it is replayed | Result |
|---|---|---|
| `reset_lock_split` (`ResetProbe`): old writer 0 holds the write lock paused before its glass write; old writer 1 positively waiting in `StampedLock.acquireWrite`; `observe()` on the same engine/leaf; the new target removal and a new `beginMesh` proceed on a lock of their own; writer 0 writes glass under a new-epoch read that says air/v3; writer 1 restores air; whole gate accepts | `McNativeCausalityRaceTest.reObservingWhileOldWritersHoldOrWaitForTheLockKeepsOneContentLock` (real updater/engine/trace, the reviewer's wrapping observer, both old writers captured before the reset, writer 1 asserted waiting in `acquireWrite`) | after the reset the new read **waits** and the new removal **waits** (fail closed) while old writer 0 holds the leaf's lock; once the read holds it, for the whole hold the probe content equals `read.probeHeld`, the version equals `read.version`, and neither old writer 1 nor the removal can publish; `read.version >= 3` (writer 0's glass is serialized before it); the result's content is the leaf's content at its version, so a glass result precedes any change of this epoch (refused by the gate: "made from content holding 'minecraft:glass'" / "before the removal's") and only an air result at or past the change contains it; the old writers' publications are not this epoch's update; a fresh post-publication target result (new epoch, removal version v+1, air, section (2,7,4)) is accepted; every writer joined |
| ordinary original race, enqueue-only, no-op, new-epoch identity (regression) | `McNativeCausalityRaceTest` (3 earlier tests), `McNativeIngestTraceTest` (7: epoch replacement keeps the same leaf/engine version and refuses an earlier epoch's read; another leaf or engine starts from 0 — now the domain's), `WorldUpdaterOutcomeTest` (3) | unchanged, passing |
| shutdown (CLOSED by the review) | `VoxyInstanceShutdownBoundTest` 8, `WorldReleaseBoundTest` 4, `VkMeshWorkersTest` 7 | unchanged source, passing |

The gate (`scripts/verify.py`) and its fixture are unchanged from `dd230016`: the trace now supplies
content identity that is consistent with the content actually read; the reviewer's 37/37 AST sweep and
the author's 36/36 line sweep of `dd230016` apply to the identical oracle. The two focused Python tests
were rerun (OK).

## CPU results on this source

- JUnit (seven CPU classes): see [junit-counts.json](junit-counts.json) and [junit/](junit/) — 38 tests,
  0 failures (`McNativeCausalityRaceTest` 4, `McNativeIngestTraceTest` 7, `WorldUpdaterOutcomeTest` 3,
  `VoxyInstanceShutdownBoundTest` 8, `WorldReleaseBoundTest` 4, `VkMeshWorkersTest` 7,
  `McNativeHierarchicalLoadTest` 5). No GPU/offscreen test was run.
- Focused Python: [focused-gate.log](focused-gate.log) (2 OK). Whole Python suite: not rerun for this
  candidate (scripts/ unchanged from `dd230016`, whose run was 351 OK); stated as such, not inherited as
  a PASS for this source.

## Not checked (and why)

Everything listed under "Not checked" for `dd230016`, plus: the lock's behaviour under native load
with real ingest threads re-observed at every stage (a captured old writer makes a new read wait; the
harness's marks are taken on the render thread and may wait for an ingest write to end). **NOT_RUN**
(CPU-only instruction). The judgment belongs to the independent narrow prelaunch review.
