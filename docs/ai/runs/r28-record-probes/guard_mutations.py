"""R28-GATE-GUARD-COVERAGE: remove exactly the two named predicates, one at a time, in a copy of
verify.py imported in place of the real one, and run test_render_gate against it."""
import importlib, pathlib, subprocess, sys, shutil, tempfile
root = pathlib.Path(sys.argv[1])
src = (root / 'scripts/verify.py').read_text()
MUTANTS = {
    'streaming predicate': ('if report.get("streaming") is not True or not finite_number(distance):',
                            'if not finite_number(distance):'),
    'within-size-run growth predicate': (
        '''            for run in runs:
                first, last = run[0], run[-1]
                if last[4] > first[4]''',
        '''            for run in []:
                first, last = run[0], run[-1]
                if last[4] > first[4]'''),
}
out = {}
for name, (old, new) in MUTANTS.items():
    assert src.count(old) == 1, name
    with tempfile.TemporaryDirectory() as tmp:
        t = pathlib.Path(tmp) / 'scripts'
        shutil.copytree(root / 'scripts', t, ignore=shutil.ignore_patterns('__pycache__'))
        (t / 'verify.py').write_text(src.replace(old, new))
        r = subprocess.run([sys.executable, '-m', 'unittest', 'tests.test_render_gate'],
                           cwd=t, capture_output=True, text=True)
        failed = [l.split('(')[0][6:].strip() for l in r.stderr.splitlines() if l.startswith('FAIL:') or l.startswith('ERROR:')]
        out[name] = {'exit': r.returncode, 'failing': failed}
        print(name, '->', 'DETECTED' if r.returncode else 'SURVIVED', failed)
r = subprocess.run([sys.executable, '-m', 'unittest', 'tests.test_render_gate'], cwd=root / 'scripts', capture_output=True, text=True)
print('unmutated ->', 'OK' if r.returncode == 0 else 'FAILED')
