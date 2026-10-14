from pathlib import Path
import json,sys,ast,io,unittest,copy
sys.dont_write_bytecode=True;r=Path.cwd();o=r/'.agent-run/prelaunch';sys.path[:0]=[str(r/'scripts'),str(r/'scripts/tests')]
import verify,test_render_gate as t
stream=io.StringIO();res=unittest.TextTestRunner(stream=stream,verbosity=2).run(unittest.TestSuite([t.RenderGateTest('test_native_updates_must_reach_voxys_mesh'),t.RenderGateTest('test_mesher_workers_that_outlive_their_stop_are_accounted')]))
(o/'focused-gate.log').write_text(stream.getvalue());assert res.wasSuccessful()
trace=json.loads((o/'trace.log').read_text());tc=t.RenderGateTest()
def counterfeit(e):
 for c in e['checkpoints']:
  p=c.get('updateProbe')
  if not p:continue
  for side in ['before','after']:p[side]['sceneEngine']=trace['engine']
  if c['stage']=='remove':
   # Offset all actual monotonic sequence values so the existing fixture edit marks precede them.
   # This is a constant offset only, preserving the real trace order and lack of ingest execution.
   p['before'].update(seq=trace['before']+1000,leafMeshVersion=10)
   p['after'].update(seq=trace['after']+1000,leafMeshVersion=11)
   for k in ['ingest','leafDirty','leafMeshed']:p[k]=dict(seq=trace[k]['seq']+1000,engine=trace[k]['engine'])
checks={'control':tc.run_gate(),'pending_ingest_prequeued_dirty_same_leaf':tc.run_gate(env=counterfeit)}
def edit_both(e,k,v):
 for c in e['checkpoints']:
  if c['stage'] in verify.UPDATE_PROBE_STAGES:
   for side in ['before','after']:c['updateProbe'][side][k]=v
checks['build_minus5']=tc.run_gate(env=lambda e:edit_both(e,'builds',-5))
checks['build999999']=tc.run_gate(env=lambda e:edit_both(e,'builds',999999))
checks['negative_seq']=tc.run_gate(env=lambda e:edit_both(e,'seq',-1))
def change(e,field,val):
 p=next(c['updateProbe'] for c in e['checkpoints'] if c['stage']=='remove')
 if field=='leafMeshVersion':p['before'][field]=10;p['after'][field]=10
 else:p[field]=val
checks['ancestor20to22_leaf10']=tc.run_gate(env=lambda e:change(e,'leafMeshVersion',10))
checks['wrong_engine']=tc.run_gate(env=lambda e:change(e,'ingest',{'seq':203,'engine':78}))
checks['pre_mark_ingest']=tc.run_gate(env=lambda e:change(e,'ingest',{'seq':199,'engine':77}))
checks['no_empty_leaf_mesh_event']=tc.run_gate(env=lambda e:change(e,'leafMeshed',None))
source=(r/'scripts/verify.py').read_text();f=next(n for n in ast.parse(source).body if isinstance(n,ast.FunctionDef) and n.name=='native_update_problem');sites=sorted(n.lineno for n in ast.walk(f) if isinstance(n,ast.If) and isinstance(n.body[0],ast.Return));mut=[]
for line in sites:
 tree=ast.parse(source);n=next(n for n in ast.walk(tree) if isinstance(n,ast.If) and n.lineno==line);n.test=ast.copy_location(ast.Constant(False),n.test)
 exec(compile(ast.fix_missing_locations(tree),str(r/'scripts/verify.py'),'exec'),verify.__dict__)
 s=io.StringIO();v=unittest.TextTestRunner(stream=s).run(unittest.TestSuite([t.RenderGateTest('test_native_updates_must_reach_voxys_mesh')]))
 mut.append(dict(line=line,detected=not v.wasSuccessful(),failures=len(v.failures),errors=len(v.errors)));(o/f'mutation-{line}.log').write_text(s.getvalue())
exec(compile(source,str(r/'scripts/verify.py'),'exec'),verify.__dict__)
(o/'gate-results.json').write_text(json.dumps({'focused_tests':res.testsRun,'trace_scope':'Actual production trace events plus constant1000 sequence offset; queue/mesh source-control model, no Minecraft/world execution','counterexamples':checks,'mutations':mut},indent=2));print(json.dumps({'checks':{k:v['success'] for k,v in checks.items()},'mutations_detected':sum(v['detected'] for v in mut),'mutations_total':len(mut)},indent=2))
