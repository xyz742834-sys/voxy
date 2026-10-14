# R32 narrow review of bb47958f (CLEAR_FOR_BOUNDED_VALIDATION) — the reviewer's probes and logs

Verbatim copies from the frozen review checkout `native-prelaunch-bb47958f` (its `.agent-run/prelaunch/`),
imported 2026-10-14 without modification; the review checkout itself was left untouched. The verdict of
record is [../native-prelaunch-review-r32-bb47958f.md](../native-prelaunch-review-r32-bb47958f.md) /
[.json](../native-prelaunch-review-r32-bb47958f.json); the reviewer's hashes of its artifacts are in
`artifact-hashes.json`, the source files it bound in `source-binding.json`, reproduction notes in the
reviewer's `README.md`. Scope: the one `reset_lock_split` survivor only (`DomainResetProbe.java`,
`domain-reset.json`: same Domain object retained across observation switches; new read and removal
parked behind the old holder; 50 checks under the held read). Shutdown CPU closure carried from
`dd230016` without re-run. JUnit 11/11 over two classes (`junit/`, `junit-counts.json`); gate controls in
`gate-results.json` (stale v4 refused, fresh v5 accepted, empty terminal v8 accepted; the historical
dd230016 contradictory record is still structurally accepted by the unchanged verifier — the repair
prevents the producer schedule, it does not make the verifier detect false producer metadata). CLEAR
permits seeking ONE coordinated bounded validation; it is not launch, delivery, default-on or normal-play
authority. Not copied: `tmp/`, `runtime-classpath.txt`.
