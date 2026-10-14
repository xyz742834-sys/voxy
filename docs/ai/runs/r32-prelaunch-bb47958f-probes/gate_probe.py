from pathlib import Path
import json,sys,unittest,io
sys.dont_write_bytecode=True
root=Path.cwd();p=root/'.agent-run/prelaunch';sys.path[:0]=[str(root/'scripts'),str(root/'scripts/tests')]
import test_render_gate as t
raw=json.loads((p/'domain-reset.json').read_text());tc=t.RenderGateTest()
def apply(e,data):
 q=next(c['updateProbe'] for c in e['checkpoints'] if c['stage']=='remove')
 for side,mark,gen in [('before','before','beforeGeneration'),('after','after','afterGeneration')]:
  q[side].update(seq=data[mark]+1000,sceneEngine=data['engine'],epoch=data['epoch'],leafGeneration=data[gen],leafMeshVersion=10 if side=='before' else 11,voxyHeld=data['heldBefore' if side=='before' else 'heldAfter'])
 for k in ['ingest','update','lastUpdate','leafDirty','leafMeshed','leafUnwatched']:
  v=data[k];q[k]=None if v is None else dict(v,seq=v['seq']+1000)
  if v:
   if 'version' in q[k]:q[k]['generation']=q[k].pop('version')
   if 'sx' in q[k]:q[k]['section']=[q[k].pop(c) for c in ['sx','sy','sz']]
checks={'control':tc.run_gate()}
for key in ['staleResult','freshResult','emptyTerminal']:checks[key]=tc.run_gate(env=lambda e,d=raw[key]:apply(e,d))
# Unchanged verifier still trusts a historically false producer record; do not claim a new script fix.
historical=json.loads((root/'docs/ai/runs/r31-prelaunch-dd230016-probes/reset-probe.json').read_text())
checks['historical_dd230016_false_record']=tc.run_gate(env=lambda e:apply(e,historical))
out=io.StringIO();res=unittest.TextTestRunner(stream=out,verbosity=2).run(unittest.TestSuite([t.RenderGateTest('test_native_updates_must_reach_voxys_mesh')]))
(p/'focused-gate.log').write_text(out.getvalue())
result={'whole_gate':checks,'focused_test_count':res.testsRun,'focused_test_success':res.wasSuccessful(),'notes':'Actual trace event substitutions, field names mapped and constant1000 sequence offset; unrelated scenario/build/revision facts are existing fixture values. Historical producer record acceptance is retained, not called rejection: source repair prevents that split-lock schedule from producing contradictory content identity. No gate mutation or shutdown review rerun.'}
(p/'gate-results.json').write_text(json.dumps(result,indent=2))
assert res.wasSuccessful()
assert checks['control']['success'] and not checks['staleResult']['success'] and checks['freshResult']['success'] and checks['emptyTerminal']['success']
print(json.dumps({k:{f:v[f] for f in ['success','failures']} for k,v in checks.items()},indent=2))
