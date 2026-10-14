# Independent narrow dd230016 prelaunch evidence

Frozen source: dd23001649bda8e05e2e02911c6556f1d665a3ee. CPU ONLY. No Minecraft, Vulkan, offscreen or native launch. Run all commands from `/Users/xyz/orca/workspaces/voxy/native-prelaunch-dd230016`.

The report is `docs/ai/runs/native-prelaunch-review.md`; machine verdict is `.agent-run/prelaunch-review.json`. `source-binding.json` binds every changed src/scripts file against 5bdc067e. The entry and pre-report tracked trees were clean. `cleanup-status.json` records reviewer ownership, not a clearance of other terminals' processes.

`cpu-junit-exit.json` contains the exact focused Gradle argv and real parent exit. `cpu.init.gradle` writes the selected test runtime classpath. The 4 selected test classes initialize CPU registry/test collaborators, not Vulkan. The independent result is 21 tests, not the author's broader 37-test snapshot. XML and counts are retained here.

Reproduce the Java probes after that focused Gradle command (requires the same CPU-only test classpath; no GPU initialization):

```python
from pathlib import Path
import subprocess
p = Path('.agent-run/prelaunch')
jdk = '/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/'
cp = str(p/'tmp') + ':' + (p/'runtime-classpath.txt').read_text().strip()
for name in ['ResetProbe', 'OriginalProbe', 'ShutdownProbe']:
    subprocess.run([jdk+'javac', '-cp', cp, '-d', str(p/'tmp'), str(p/(name+'.java'))], check=True, timeout=45)
    result = 'reset-probe.json' if name == 'ResetProbe' else name+'.json'
    subprocess.run([jdk+'java', '-cp', cp, 'me.cortex.voxy.client.core.vk.mcnative.'+name, str(p/result)], check=True, timeout=45)
```

ResetProbe pauses real writes at observer boundaries, positively samples the second writer blocked on the original lock, and demonstrates writes crossing the replacement observation's read lock. Raw target data models the mesh factory's read; the exact MeshRead is passed to the actual trace acceptance. It does not execute the full factory, geometry acceptance or native scene. Both old dirty callbacks are held until modeled acceptance, then all writers are released and joined. The stored result identity says air/v3 while the modeled mesh read glass. No fake updater outcome is injected.

OriginalProbe repeats the old target-publication pause and unrelated same-leaf update. Held read blocks until release; old mesh identity remains glass/v2 and fresh identity becomes air/v3. It also records enqueue-only without an executed update. ShutdownProbe uses actual VoxyInstance/getOrCreate/WorldEngine/free with controllably slow storage; caller/cleanup threads are explicitly joined after release. Its two cases demonstrate bounded refusal and late completion, not successful in-bound cleanup.

`python3 .agent-run/prelaunch/gate_probe.py` runs the unchanged WHOLE render gate on these trace/counter substitutions, two focused Python tests and targeted in-memory refusal-guard mutations. It maps Java `version` to harness `generation`, `(sx,sy,sz)` to `section`, and offsets sequences by a constant 1000. Scenario/revision facts otherwise remain fixture values. 37 AST predicates were removed separately and all detected; the author's line-based 36-site sweep omits line2660 because a comment precedes its return. No production Python was edited.

Positive whole-gate control/fresh result and negative original/queued-only/shutdown results are retained alongside the reset false acceptance. `gate-probe-final.log` preserves an initial reviewer-script failure from the wrong fixture keyword; `gate-exits.json` describes the correction and final exit0. All other compile/run exit records are retained. Expected synthetic timeout/interrupt logs are not erased or reclassified as passing hardware evidence.

The owner's full Python suite is PENDING_EXTERNAL; no final receipt available at report time. Old hardware evidence is UNBOUND. No artifact here establishes delivery, default-on, normal-play, pixels or launch authority.
