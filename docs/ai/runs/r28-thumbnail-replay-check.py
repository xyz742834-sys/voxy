"""Focused CPU check of R28-RENDER-THUMBNAIL-VALIDITY: replay retained evidence in-process with the
new gate, exempting ONLY scripts/verify.py and its test file (the gate under change) from the source binding, and
asserting it is the only differing file. Then the reviewer's two attacks."""
import sys, json, shutil, hashlib, pathlib, tempfile, io, contextlib
sys.path.insert(0, 'scripts'); import verify
real = verify.source_binding
def binding(fp):
    b = real(fp)
    assert b['mismatched'] == ['scripts/tests/test_render_gate.py', 'scripts/verify.py'] and not b['absent'], b
    b['mismatched'] = []
    return b
verify.source_binding = binding
src = pathlib.Path(sys.argv[1])
for mode in ('none', 'empty', '1x1', 'pressure-empty'):
    work = pathlib.Path(tempfile.mkdtemp(dir=sys.argv[2])) / src.name
    shutil.copytree(src, work)
    sub = 'pressure' if mode.startswith('pressure') else 'render'
    name = f'{sub}/frame-horizon.png'; target = work / name
    if mode.endswith('empty'): target.write_bytes(b'')
    elif mode == '1x1': verify.write_rgb_png(target, [[(0, 0, 0)]])
    m = json.loads((work / 'MANIFEST.json').read_text())
    m['files'][name] = hashlib.sha256(target.read_bytes()).hexdigest()
    (work / 'MANIFEST.json').write_text(json.dumps(m, indent=2) + '\n')
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        rc = verify.replay_evidence(work)
    o = json.loads(out.getvalue())
    print(mode, 'exit', rc, '|', (o.get('error') or 'replayed: %d' % len(o.get('replayed', [])))[:220])
    shutil.rmtree(work.parent)
