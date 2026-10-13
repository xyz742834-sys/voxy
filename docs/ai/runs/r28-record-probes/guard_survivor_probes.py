import ast,copy,json,sys,re,tempfile,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];OUT=Path(__file__).parent;sys.path.insert(0,str(ROOT/'scripts'));import verify
run=ROOT/'docs/ai/runs/native-evidence/20261013T013657-855541Z';command=json.loads((run/'summary.json').read_text())['stages']['native_environment']['render_run']['command']
tree=ast.parse((ROOT/'scripts/verify.py').read_text());nodes={n.name:n for n in tree.body if isinstance(n,ast.FunctionDef)}
rows=[]
for kind,fn,line in [('streaming-false','hier_stream_problem',2567),('within-first-size-run-growth','native_render_result',3344)]:
 with tempfile.TemporaryDirectory() as t:
  p=Path(t);shutil.copytree(run/'render',p,dirs_exist_ok=True);log=(p/'native-render.log').read_text();report=json.loads((p/'native-hier-load.json').read_text())
  if kind=='streaming-false':report['streaming']=False;(p/'native-hier-load.json').write_text(json.dumps(report))
  else:
   ls=log.splitlines(keepends=True)
   for i,l in enumerate(ls):
    if 'hier frames entering stage resize:' in l and ' cycle=' not in l:
     ls[i]=re.sub(r'vkBufferBytes=(\d+)',lambda m:'vkBufferBytes='+str(int(int(m[1])*1.02)),l);break
   log=''.join(ls)
  original=getattr(verify,fn);base=verify.native_product_result(p,log,command=command)
  node=copy.deepcopy(nodes[fn])
  for n in ast.walk(node):
   if isinstance(n,ast.If) and n.lineno==line:n.test=ast.copy_location(ast.Constant(False),n.test)
  exec(compile(ast.fix_missing_locations(ast.Module(body=[node],type_ignores=[])),'review-mutant','exec'),verify.__dict__)
  mutated=verify.native_product_result(p,log,command=command);setattr(verify,fn,original)
  rows.append({'case':kind,'baseline_success':base['success'],'baseline_failures':base['failures'],'guard_removed_success':mutated['success'],'mutant_failures':mutated['failures']})
(OUT/'guard-survivor-probes.json').write_text(json.dumps(rows,indent=2));print(json.dumps(rows,indent=2))
