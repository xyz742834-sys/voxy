"""Remove, one at a time, every refusing `if` in native_update_problem (its condition replaced by
False; multi-line conditions included) in a copy of scripts/, and run test_render_gate against it.
Run from the repository root."""
import pathlib, shutil, subprocess, sys, tempfile
root = pathlib.Path('.')
src = (root / 'scripts/verify.py').read_text()
a = src.index('def native_update_problem(')
b = src.index('def hier_stream_problem(report):')
lines = src[a:b].split('\n')
sites = []
for i, line in enumerate(lines):
    if not line.lstrip().startswith('if '):
        continue
    j = i
    while lines[j].rstrip().endswith('\\'):
        j += 1
    if lines[j + 1].lstrip().startswith('return '):
        sites.append((i, j))
for i, j in sites:
    indent = lines[i][:len(lines[i]) - len(lines[i].lstrip())]
    cond = ' '.join(l.strip().rstrip('\\') for l in lines[i:j + 1])
    mutated = lines[:i] + [indent + 'if False:'] + lines[j + 1:]
    with tempfile.TemporaryDirectory() as tmp:
        t = pathlib.Path(tmp) / 'scripts'
        shutil.copytree(root / 'scripts', t, ignore=shutil.ignore_patterns('__pycache__'))
        (t / 'verify.py').write_text(src[:a] + '\n'.join(mutated) + src[b:])
        r = subprocess.run([sys.executable, '-m', 'unittest', 'tests.test_render_gate'],
                           cwd=t, capture_output=True, text=True)
    print('DETECTED' if r.returncode else 'SURVIVED', '|', ' '.join(cond.split())[:110])
