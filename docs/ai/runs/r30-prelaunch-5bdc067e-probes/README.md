# R30 narrow prelaunch review of 5bdc067e (HOLD) — the reviewer's probes and logs

Verbatim copies from the frozen review checkout `native-prelaunch-5bdc067e` (its `.agent-run/prelaunch/`),
imported 2026-10-14 without modification; the review checkout itself was left untouched. The verdict of
record is [../native-prelaunch-review-r30-5bdc067e.md](../native-prelaunch-review-r30-5bdc067e.md) /
[.json](../native-prelaunch-review-r30-5bdc067e.json) (sha256 of both and of the probe artifacts:
`review-artifact-hashes.json`; the source files the review bound: `source-binding.json`).

- `RepairProbes.java`, `probe-results.json`, `probes.log`, `probes-compile.log`, `probe-exits.json`: the
  reviewer's real-updater/engine/trace race (unrelated same-leaf change, target write paused before its
  publication) and the real-instance slow-flush shutdown (1200 ms deadline, returned at 2524 ms).
  `RepairProbes-initial-nonair-model.java.txt` + `initial-nonair-model-*`: the earlier probe with a
  representative non-air id, retained by the reviewer.
- `gate_probe.py`, `gate-results.json`, `gate-probe.log`, `focused-gate.log`, `mutation-*.log`: the whole
  gate replay (the race ACCEPTED: `unrelated_change_delayed_target_publication.success == true`), the
  two focused Python tests, and the 30/30 AST predicate-removal sweep.
  `gate-results-initial-wrong-air-decoding.json`: the reviewer's first conversion (packed air as 15), kept.
- `cpu-junit-exit.json`, `cpu-junit.log`, `cpu.init.gradle`, `junit-counts.json`, `junit/`: the five
  CPU classes (24 tests, 0 failures, parent exit 0).

Not copied: `classes/` (compiled probes), `tmp/`, `runtime-classpath.txt`.
