VERDICT: CLEAR_FOR_BOUNDED_VALIDATION

Independent CPU-only review of **bb47958f48be60fef44c9a864c8218294f42b0d1**, base **dd23001649bda8e05e2e02911c6556f1d665a3ee**, solely for the surviving `reset_lock_split` counterexample and directly relevant regression. HEAD and clean tracked tree were confirmed. The imported prior report/JSON, original probes and r31 repair README were read; previously read required context is unchanged against the base. No prior checkout/report or production source was edited.

1. **Reset judgment: CLOSED within this narrow source/CPU scope.**

   The source delta is exactly `McNativeIngestTrace.java` and its `McNativeCausalityRaceTest`. The lock and content version now belong to `Domain`, outside `Observation` (`McNativeIngestTrace.java:76–89`). `observe()` selects the engine/leaf domain rather than making a new content lock (`:221–222`). Captured writers retain that domain and advance its version inside its write lock (`:149–170`); mesher reads and unwatch use its read lock (`:269–276`, `:299–315`). A result keeps the epoch/content identity of its own read; replacing an observation does not rewrite it.

   The weak map is synchronized, and each engine's leaf map uses `computeIfAbsent`; no reset path removes or replaces a live domain. `WorldEngine` is final and inherits Object equality/hashCode, so the WeakHashMap's equality semantics are identity semantics for the actual production keys. Its Domain values do not strongly reference the engine. A live updater/reader retains the actual engine and/or captured domain while using it; the old writer's completion closure retains `into`. Thus observation replacement or weak-key lifetime does not retire the synchronization object beneath the captured operations reviewed here. This is source reasoning, not an exhaustive GC stress test or proof covering arbitrary observer enable/disable misuse.

   Independent `.agent-run/prelaunch/DomainResetProbe.java` uses the real mapper, WorldEngine, WorldUpdater and trace. It reproduces the old holder plus positively waiting old writer, then resets the same engine/leaf. It also switches away to another leaf and engine and back while those captured operations remain pending: reflection confirms the original engine/leaf retains the **same Domain object**, while different engine/leaf pairs have distinct domains. The new read is positively sampled in `StampedLock.acquireRead` and the new target removal in `acquireWrite`; both wait behind the old holder. The old split-lock interleaving cannot proceed.

   After explicit release, the new read held air/version3. Fifty checks while its read lock remained held found the actual target array content and shared version equal to its immutable MeshRead; the pending new removal could not publish during that read. Old writers had serialized before it. The eventual new-epoch target removal truthfully reported **changed=false/version3** because the old writer had already restored air; `lastChange` stayed null in the new epoch. An old-epoch outcome was not promoted into a current causal update. Every controlled thread was released and joined; compile and JVM exit0, child errors0.

   Deterministic follow-on controls used actual writes and exact result identities: stale glass/version4 accepted after the dirty event for removal/version5 was **refused by the unchanged WHOLE gate**; fresh air/version5 passed. A genuinely empty leaf produced update/unwatch version8, nonEmptyBlocks0 and passed. A subsequent actual no-op stayed changed=false. These are CPU trace/terminal controls; full geometry acceptance/removal is modeled.

2. **Gate identity and the limit of the closure.**

   All **13 tracked files under scripts/** have identical git blobs to dd230016, including the complete verifier and fixtures. No guard was weakened or changed. `gate-results.json` retains the whole-gate control, stale refusal, fresh acceptance and empty-terminal acceptance. It also deliberately replays the historical dd230016 contradictory JSON: that record still passes the unchanged verifier. The repair prevents the reviewed source schedule from generating the contradictory record; it does **not** make the verifier detect a producer that falsely claims to have read air. The truthful stale result emitted by the current protocol is rejected. This distinction is required to avoid claiming an impossible script change.

   Gate substitutions map Java record fields to harness names and add a constant1000 sequence offset. Scenario facts/build count/mesh revision remain the existing fixture's values. The raw array read models the mesh factory's read; actual mesh generation, scene acceptance, geometry and pixels were NOT_RUN. No native UAF or deadlock is claimed.

3. **Verification and carried closure.**

   Independent commands, with retained logs/exits under `.agent-run/prelaunch/`:

   - `./gradlew test --offline --no-daemon -I .agent-run/prelaunch/cpu.init.gradle --tests me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTraceTest --tests me.cortex.voxy.client.core.vk.mcnative.McNativeCausalityRaceTest`: exit0; **11 tests, 2 classes, 0 failures/errors/skips**. Selected classes use CPU world/registry collaborators, without Vulkan/game-client initialization. The author's 38-test snapshot is not represented as our rerun.
   - `javac` and `java` for `DomainResetProbe` with that test runtime classpath: compile0/run0, all controlled children joined. Exact reproduction is in `.agent-run/prelaunch/README.md`.
   - `python3 .agent-run/prelaunch/gate_probe.py`: exit0; the one relevant native-update gate test passed, plus the whole-gate controls above. No historical mutation sweep or shutdown suite was rerun.

   **R29-PRELAUNCH-SHUTDOWN CPU closure is carried unchanged from dd230016.** Shutdown source/tests are byte-identical; their prior actual slow-flush/interruption/retained cleanup evidence was not repeated or upgraded to native acceptance. A stuck retained operation can keep resources and a non-daemon JVM indefinitely; a bounded caller return is refusal, not successful cleanup/persistence.

   The previously pending full Python run on **dd230016 did finish:351 tests, OK, real exit0**, retained in `docs/ai/runs/r30-prelaunch-repair/python-suite.log` and its README. It was **not rerun on bb47958f**. Identical scripts do not certify the changed Java or hardware. No external full suite is claimed to be running or pending now.

4. **What remains unrun and what CLEAR permits.**

   This finite one-survivor source/CPU review is complete. CLEAR permits seeking **one future coordinated bounded validation**, subject to resource clearance; it is not launch authority, delivery/default-on/normal-play approval, or a whole-product PASS.

   Offscreen Vulkan and Minecraft/native/live execution remain NOT_RUN here; old hardware evidence is UNBOUND to this Java source. Existing live preconditions remain: warmup scene readiness, ordinary unload-ingest arrival establishing held glass, level0 watching, genuine scene mesh/empty-unwatch terminals and native lifecycle cleanup. Real load and render-thread waiting on the shared read/write lock need the proposed validation. Other placement ingestion, sustained updates and pixels remain wider missing acceptance, not new scope imposed by this review. The 10-second caller stall policy, indefinite retention and unflushed-data failure limits remain unaccepted for default-on.

   No GPU/native/offscreen work was launched or reserved. Reviewer queue: active0, queued0, planned0, reservations[], accepted holds[]. All review-owned CPU check parents exited and controlled children joined; no review-owned daemon remains. Unrelated processes were untouched. Production/script hashes were checked again after testing; only new-checkout review artifacts were written.
