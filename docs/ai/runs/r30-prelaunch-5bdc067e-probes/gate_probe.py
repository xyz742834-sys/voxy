from pathlib import Path
import ast,io,json,sys,unittest,copy
sys.dont_write_bytecode=True;r=Path.cwd();p=r/'.agent-run/prelaunch';sys.path[:0]=[str(r/'scripts'),str(r/'scripts/tests')]
import verify,test_render_gate as t
s=io.StringIO();res=unittest.TextTestRunner(stream=s,verbosity=2).run(unittest.TestSuite([t.RenderGateTest('test_native_updates_must_reach_voxys_mesh'),t.RenderGateTest('test_mesher_workers_that_outlive_their_stop_are_accounted')]))
(p/'focused-gate.log').write_text(s.getvalue());assert res.wasSuccessful()
raw=json.loads((p/'probe-results.json').read_text());tc=t.RenderGateTest()
def apply_trace(e,data):
 q=next(c['updateProbe'] for c in e['checkpoints'] if c['stage']=='remove')
 for side,mark,gen in [('before','before','beforeGeneration'),('after','after','afterGeneration')]:
  q[side].update(seq=data[mark]+1000,sceneEngine=data['engine'],leafGeneration=data[gen],leafMeshVersion=10 if side=='before' else 11)
 # Reuse the fixed scenario's Minecraft block transition; observe real Voxy content from this probe.
 q['before']['voxyHeld']='minecraft:glass'
 q['after']['voxyHeld']='minecraft:air' if (data['heldAfterRaw'] & (((1 << 20) - 1) << 27)) == 0 else 'minecraft:glass'
 for k in ['ingest','update','lastUpdate','leafDirty','leafMeshed','leafUnwatched']:
  v=data[k];q[k]=None if v is None else dict(v,seq=v['seq']+1000)
checks={'control':tc.run_gate()}
for name,data in raw['trace'].items():checks[name]=tc.run_gate(env=lambda e,d=data:apply_trace(e,d))
# The unchanged-air updater outcome must not turn into observed changed geometry.
def noop(e):
 q=next(c['updateProbe'] for c in e['checkpoints'] if c['stage']=='remove');q['update']['changed']=False
checks['real_noop_shape']=tc.run_gate(env=noop)
def empty(e):
 q=next(c['updateProbe'] for c in e['checkpoints'] if c['stage']=='remove');q['leafMeshed']=None;q['leafUnwatched']={'seq':230,'engine':77}
checks['empty_unwatch_control']=tc.run_gate(env=empty)
# Mutate only predicates within this repaired oracle, in memory; preserve the production script.
source=(r/'scripts/verify.py').read_text();f=next(n for n in ast.parse(source).body if isinstance(n,ast.FunctionDef) and n.name=='native_update_problem');sites=sorted(n.lineno for n in ast.walk(f) if isinstance(n,ast.If) and isinstance(n.body[0],ast.Return));mut=[]
for line in sites:
 tree=ast.parse(source);node=next(n for n in ast.walk(tree) if isinstance(n,ast.If) and n.lineno==line);node.test=ast.copy_location(ast.Constant(False),node.test)
 exec(compile(ast.fix_missing_locations(tree),str(r/'scripts/verify.py'),'exec'),verify.__dict__)
 out=io.StringIO();v=unittest.TextTestRunner(stream=out).run(unittest.TestSuite([t.RenderGateTest('test_native_updates_must_reach_voxys_mesh')]))
 mut.append({'line':line,'detected':not v.wasSuccessful(),'failures':len(v.failures),'errors':len(v.errors)});(p/f'mutation-{line}.log').write_text(out.getvalue())
exec(compile(source,str(r/'scripts/verify.py'),'exec'),verify.__dict__)
result={'focused_tests':res.testsRun,'counterexamples':checks,'mutations':mut,'limits':'Actual updater/world/trace; controlled pre-observer pause. Mesh target read modeled by raw leaf read; full mesher/scene not executed. Constant1000 sequence offset into whole gate fixture.'}
(p/'gate-results.json').write_text(json.dumps(result,indent=2));print(json.dumps({'checks':checks,'mutations_detected':sum(x['detected'] for x in mut),'mutations_total':len(mut)},indent=2))
