# R31 narrow prelaunch review of dd230016 (HOLD: one survivor) — the reviewer's probes and logs

Verbatim copies from the frozen review checkout `native-prelaunch-dd230016` (its `.agent-run/prelaunch/`),
imported 2026-10-14 without modification; the review checkout itself was left untouched. The verdict of
record is [../native-prelaunch-review-r31-dd230016.md](../native-prelaunch-review-r31-dd230016.md) /
[.json](../native-prelaunch-review-r31-dd230016.json); the reviewer's own hashes of every artifact are in
`artifact-hashes.json`, the source files it bound in `source-binding.json`, its reproduction notes in
`README.md` (the reviewer's). Shutdown: CLOSED for the bounded-caller counterexample (`ShutdownProbe.*`).
Causality: the original race refused (`OriginalProbe.*`), one survivor `reset_lock_split`
(`ResetProbe.java`, `reset-probe.json` with the second writer's blocked stack, `reset-exits.json`,
`gate_probe.py`, `gate-results.json`: the whole gate accepted the reset schedule). JUnit 21/21 over four
classes (`junit/`, `junit-counts.json`); 37/37 AST guard removals (`mutation-*.log`). Not copied:
`tmp/`, `runtime-classpath.txt`.
