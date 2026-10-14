from pathlib import Path
import json,subprocess,os
r=Path.cwd();o=r/'.agent-run/prelaunch';j=Path('/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin')
def ex(path,start):
 s=(r/path).read_text();a=s.index(start);b=s.index('{',a);n=1;c=b+1
 while n:n+=(s[c]=='{')-(s[c]=='}');c+=1
 return s[a:c]
vp='src/main/java/me/cortex/voxy/commonImpl/VoxyInstance.java'
world='''package me.cortex.voxy.commonImpl;
import java.util.*;import java.util.function.*;import java.util.concurrent.atomic.*;import java.util.concurrent.locks.*;
public class VoxyInstance {
 private static volatile long worldReleaseDeadlineMillis=-1; private static volatile int worldsLeftUnfreed;
 public static void boundWorldRelease(long n){worldReleaseDeadlineMillis=n;} public static int worldsLeftUnfreed(){return worldsLeftUnfreed;}
 static class Logger {static void info(String s){} static void error(Object s){}}
 boolean isRunning=true; Thread worldCleaner=new Thread();
 static class World {boolean used=true,freed,interrupt;boolean isWorldUsed(){if(interrupt)Thread.currentThread().interrupt();return used;}void free(){if(used)throw new AssertionError("free under reader");freed=true;}}
 static class Service {Runnable action=()->{};boolean stopped;void shutdown(){action.run();stopped=true;}}
 static class ImportManager {void cancelImport(World w){}}
 final Map<String,World> activeWorlds=new LinkedHashMap<>();final StampedLock activeWorldLock=new StampedLock(); final ImportManager importManager=new ImportManager();
 final Service ingestService=new Service(),savingService=new Service(),threadPool=new Service(); void cleanIdle(){}
 AWAIT
 SHUTDOWN
}'''.replace('AWAIT',ex(vp,'static boolean awaitWorldRelease(')).replace('SHUTDOWN',ex(vp,'public void shutdown()'))
(o/'VoxyInstance.java').write_text(world)
(o/'CpuJUnit.java').write_text('''import org.junit.platform.launcher.core.*;import org.junit.platform.launcher.listeners.SummaryGeneratingListener;import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public class CpuJUnit {public static void main(String[]a){var l=new SummaryGeneratingListener();var x=LauncherFactory.create();x.registerTestExecutionListeners(l);x.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass("me.cortex.voxy.client.core.vk.VkMeshWorkersTest"),selectClass("me.cortex.voxy.commonImpl.WorldReleaseBoundTest")).build());l.getSummary().printTo(new java.io.PrintWriter(System.out));l.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));if(l.getSummary().getTestsSucceededCount()!=11)System.exit(1);}}''')
(o/'TraceProbe.java').write_text('''import me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace;
public class TraceProbe {
 static String event(McNativeIngestTrace.Event e){return e==null?"null":"{\\"seq\\":"+e.seq()+",\\"engine\\":"+e.engine()+"}";}
 public static void main(String[]a){System.setProperty("voxy.harness.native","true");var engine=new Object(); long leaf=(3L<<52)|(2L<<28)|16L;
 // A bare dirty key was queued before mark; the target ingest is then only enqueued, never executed.
 long alreadyQueuedDirty=leaf;McNativeIngestTrace.observeLeaf(leaf);long before=McNativeIngestTrace.now();
 McNativeIngestTrace.visibilityIngested(2,7,4,engine);
 McNativeIngestTrace.dirtyConsumed(alreadyQueuedDirty,engine);McNativeIngestTrace.meshed(leaf,engine);
 long after=McNativeIngestTrace.now();System.out.println("{\\"engine\\":"+System.identityHashCode(engine)+",\\"before\\":"+before+",\\"after\\":"+after+",\\"ingest\\":"+event(McNativeIngestTrace.lastIngest(2,7,4))+",\\"leafDirty\\":"+event(McNativeIngestTrace.leafDirty())+",\\"leafMeshed\\":"+event(McNativeIngestTrace.leafMeshed())+",\\"target_ingest_executed\\":false}");
 McNativeIngestTrace.observeLeaf(leaf);McNativeIngestTrace.dirtyConsumed(leaf+16,engine);McNativeIngestTrace.meshed(4L<<60,engine);
 if(McNativeIngestTrace.leafDirty()!=null||McNativeIngestTrace.leafMeshed()!=null)throw new AssertionError("other leaf/ancestor accepted");
 }
}''')
cp=[];cache=Path('/Users/xyz/.gradle/caches/modules-2/files-2.1')
for g,n,v in [('org.junit.jupiter','junit-jupiter-api','5.10.2'),('org.junit.jupiter','junit-jupiter-engine','5.10.2'),('org.junit.platform','junit-platform-engine','1.10.2'),('org.junit.platform','junit-platform-commons','1.10.2'),('org.junit.platform','junit-platform-launcher','1.10.2'),('org.opentest4j','opentest4j','1.3.0'),('org.apiguardian','apiguardian-api','1.1.2')]:cp+=list((cache/g/n/v).glob('*/'+n+'-'+v+'.jar'))
assert len(cp)==7
classes=o/'classes';classes.mkdir(exist_ok=True);classpath=os.pathsep.join(map(str,[classes]+cp));(o/'classpath.txt').write_text(classpath)
cmds=[('compile',[str(j/'javac'),'-cp',classpath,'-d',str(classes),str(o/'VoxyInstance.java'),str(o/'CpuJUnit.java'),str(o/'TraceProbe.java'),'src/main/java/me/cortex/voxy/client/core/vk/VkMeshWorkers.java','src/main/java/me/cortex/voxy/client/core/vk/mcnative/McNativeIngestTrace.java','src/test/java/me/cortex/voxy/client/core/vk/VkMeshWorkersTest.java','src/test/java/me/cortex/voxy/commonImpl/WorldReleaseBoundTest.java']),('focused-junit',[str(j/'java'),'-Xmx256m','-cp',classpath,'CpuJUnit']),('trace',[str(j/'java'),'-Xmx128m','-cp',classpath,'TraceProbe'])]
results=[]
for name,cmd in cmds:
 p=subprocess.run(cmd,cwd=r,capture_output=True,text=True,timeout=45);(o/(name+'.log')).write_text(p.stdout+p.stderr);results.append(dict(name=name,exit=p.returncode,command=cmd));print(name,p.returncode,p.stdout[-1200:],p.stderr)
 (o/'cpu-results.json').write_text(json.dumps(results,indent=2));assert p.returncode==0
