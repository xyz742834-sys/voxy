import pathlib, re, shutil, subprocess, sys, tempfile
root = pathlib.Path('.')
src = (root / 'scripts/verify.py').read_text()
a = src.index('def native_update_problem(checkpoints):'); b = src.index('def hier_stream_problem(report):')
body = src[a:b]
conds = [m for m in re.finditer(r'\n(\s+)if (.+?):\n\s+return ', body, re.S)]
results = []
for i, m in enumerate(conds):
    mutated = body[:m.start()] + f"\n{m.group(1)}if False:\n{m.group(1)}    return " + body[m.end():]
    with tempfile.TemporaryDirectory() as tmp:
        t = pathlib.Path(tmp) / 'scripts'
        shutil.copytree(root / 'scripts', t, ignore=shutil.ignore_patterns('__pycache__'))
        (t / 'verify.py').write_text(src[:a] + mutated + src[b:])
        r = subprocess.run([sys.executable, '-m', 'unittest', 'tests.test_render_gate'], cwd=t, capture_output=True, text=True)
    print(i, 'DETECTED' if r.returncode else 'SURVIVED', '|', ' '.join(m.group(2).split())[:90])
