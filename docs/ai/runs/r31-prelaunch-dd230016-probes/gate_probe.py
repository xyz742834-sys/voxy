from pathlib import Path
import ast,io,json,sys,unittest,copy
sys.dont_write_bytecode=True;r=Path.cwd();p=r/'.agent-run/prelaunch';sys.path[:0]=[str(r/'scripts'),str(r/'scripts/tests')]
import verify,test_render_gate as t
raw=json.loads((p/'reset-probe.json').read_text());tc=t.RenderGateTest()
def apply_trace(e,data):
 q=next(c['updateProbe'] for c in e['checkpoints'] if c['stage']=='remove')
 for side,mark,gen in [('before','before','beforeGeneration'),('after','after','afterGeneration')]:
  q[side].update(seq=data[mark]+1000,sceneEngine=data['engine'],epoch=data['epoch'],leafGeneration=data[gen],leafMeshVersion=10 if side=='before' else 11,voxyHeld=data['heldBefore' if side=='before' else 'heldAfter'])
 for k in ['ingest','update','lastUpdate','leafDirty','leafMeshed','leafUnwatched']:
  v=data[k];q[k]=None if v is None else dict(v,seq=v['seq']+1000)
  if v:
   if 'version' in q[k]:q[k]['generation']=q[k].pop('version')
   if 'sx' in q[k]:q[k]['section']=[q[k].pop(c) for c in ['sx','sy','sz']]
checks={'control':tc.run_gate(),'reset_lock_split':tc.run_gate(env=lambda e:apply_trace(e,raw))}
(p/'gate-results.json').write_text(json.dumps({'counterexamples':checks,'limits':'Actual WorldUpdater, WorldEngine and trace, original result identity. Mesh data read modeled with actual raw target leaf read during beginMesh/endMesh. Scene revision and unrelated scenario facts are fixture values. No actual mesh factory, native scene or pixels executed. Constant 1000 sequence offset and Java record-to-harness field rename only.'},indent=2));print(json.dumps(checks,indent=2))
# Original counterexample and retained-operation refusal checks through the unchanged WHOLE gate.
original=json.loads((p/'OriginalProbe.json').read_text())
for name,data in original.items():
 if isinstance(data,dict):checks[name]=tc.run_gate(env=lambda e,d=data:apply_trace(e,d))
shutdown=json.loads((p/'ShutdownProbe.json').read_text())
for name,data in shutdown.items():
 for side in ['countsBeforeRelease','countsAfterRelease']:
  checks[name+'_'+side]=tc.run_gate(mutate=lambda b,c=data[side]:b.update(c))
out=io.StringIO();res=unittest.TextTestRunner(stream=out,verbosity=2).run(unittest.TestSuite([t.RenderGateTest('test_native_updates_must_reach_voxys_mesh'),t.RenderGateTest('test_mesher_workers_that_outlive_their_stop_are_accounted')]))
(p/'focused-gate.log').write_text(out.getvalue());assert res.wasSuccessful()
source=(r/'scripts/verify.py').read_text();f=next(n for n in ast.parse(source).body if isinstance(n,ast.FunctionDef) and n.name=='native_update_problem');sites=sorted(n.lineno for n in ast.walk(f) if isinstance(n,ast.If) and n.body and isinstance(n.body[0],ast.Return));mut=[]
for line in sites:
 tree=ast.parse(source);node=next(n for n in ast.walk(tree) if isinstance(n,ast.If) and n.lineno==line);node.test=ast.copy_location(ast.Constant(False),node.test)
 exec(compile(ast.fix_missing_locations(tree),str(r/'scripts/verify.py'),'exec'),verify.__dict__)
 out=io.StringIO();v=unittest.TextTestRunner(stream=out).run(unittest.TestSuite([t.RenderGateTest('test_native_updates_must_reach_voxys_mesh')]))
 mut.append({'line':line,'detected':not v.wasSuccessful(),'failures':len(v.failures),'errors':len(v.errors)});(p/f'mutation-{line}.log').write_text(out.getvalue())
exec(compile(source,str(r/'scripts/verify.py'),'exec'),verify.__dict__)
result={'focused_tests':res.testsRun,'counterexamples':checks,'mutations':mut,'limits':'Actual updater/world/trace; controlled pauses at write boundaries and reset. Mesh data read modeled by actual raw leaf read during beginMesh/endMesh; full mesher/scene not executed. Constant 1000 sequence offset into whole gate fixture, Java record-to-harness names mapped. Shutdown counters come from actual VoxyInstance/WorldEngine and controlled slow storage; all callers/cleanup threads joined.'}
(p/'gate-results.json').write_text(json.dumps(result,indent=2));print(json.dumps({'checks':{k:{f:v.get(f) for f in ['success','failures']} for k,v in checks.items()},'mutations_detected':sum(x['detected'] for x in mut),'mutations_total':len(mut)},indent=2))
