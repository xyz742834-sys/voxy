# bb47958f one-survivor independent CPU review

Run only in `/Users/xyz/orca/workspaces/voxy/native-prelaunch-bb47958f`. Frozen HEAD bb47958f48be60fef44c9a864c8218294f42b0d1, base dd23001649bda8e05e2e02911c6556f1d665a3ee. No GPU/game/offscreen initialization, launch or reservation.

`cpu-junit-exit.json` records the exact focused Gradle argv, parent PID, elapsed time and real exit. Selected classes: McNativeIngestTraceTest and McNativeCausalityRaceTest only. XML copied into `junit/`; 11 tests, not the author's 38-test set. Shutdown tests and historical mutation audits were not rerun. The init script writes the runtime classpath for these CPU probes.

`DomainResetProbe.java` adapts the previous ResetProbe's real updater/world/trace schedule: old holder paused before its write, another old writer positively waiting in StampedLock.acquireWrite, reset, then new read and target removal. These latter operations run on probe threads so the test can positively observe both blocked acquisitions and then explicitly release the holder. It also switches observations away and back while captured operations remain pending and checks exact Domain object identity through reflection. No event or lock state is fabricated by that reflection. A held new read is checked against actual raw leaf data and shared version 50 times. All controlled writers/readers are released and joined. The first serialized target removal became a real no-op after the old writer already wrote air: this remains changed=false, and no old-epoch change is credited to the new epoch. Separate deterministic real updates establish stale/fresh result and empty-unwatch gate controls.

Reproduce after the focused Gradle command in `cpu-junit-exit.json`:

```python
from pathlib import Path
import subprocess
p = Path('.agent-run/prelaunch')
jdk = '/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/'
cp = str(p/'tmp') + ':' + (p/'runtime-classpath.txt').read_text().strip()
subprocess.run([jdk+'javac', '-cp', cp, '-d', str(p/'tmp'), str(p/'DomainResetProbe.java')], check=True, timeout=45)
subprocess.run([jdk+'java', '-cp', cp, 'me.cortex.voxy.client.core.vk.mcnative.DomainResetProbe', str(p/'domain-reset.json')], check=True, timeout=45)
subprocess.run(['python3', str(p/'gate_probe.py')], check=True, timeout=30)
```

The gate probe uses unchanged WHOLE native_render_result via its existing fixture. Java event names are mapped to harness names and sequences offset by1000. Other scenario facts and mesh revision remain fixture values. It runs one focused native-update Python test, no mutations or shutdown tests. Historical dd230016 false-record acceptance is deliberately retained and disclosed; source prevention of that contradictory producer schedule is the repair. Full mesh factory, scene acceptance, geometry removal and pixels were not run.

`source-binding.json` verifies the two-file src/test delta, unchanged git blobs for all13 tracked scripts files plus shutdown source/test, and clean tracked tree. `cleanup-status.json` concerns reviewer-owned work only. The whole Python351/exit0 log belongs to dd230016 and was not rerun on this candidate. No external full suite remains pending.
