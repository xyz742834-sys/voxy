import me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTrace;
public class TraceProbe {
 static String event(McNativeIngestTrace.Event e){return e==null?"null":"{\"seq\":"+e.seq()+",\"engine\":"+e.engine()+"}";}
 public static void main(String[]a){System.setProperty("voxy.harness.native","true");var engine=new Object(); long leaf=(3L<<52)|(2L<<28)|16L;
 // A bare dirty key was queued before mark; the target ingest is then only enqueued, never executed.
 long alreadyQueuedDirty=leaf;McNativeIngestTrace.observeLeaf(leaf);long before=McNativeIngestTrace.now();
 McNativeIngestTrace.visibilityIngested(2,7,4,engine);
 McNativeIngestTrace.dirtyConsumed(alreadyQueuedDirty,engine);McNativeIngestTrace.meshed(leaf,engine);
 long after=McNativeIngestTrace.now();System.out.println("{\"engine\":"+System.identityHashCode(engine)+",\"before\":"+before+",\"after\":"+after+",\"ingest\":"+event(McNativeIngestTrace.lastIngest(2,7,4))+",\"leafDirty\":"+event(McNativeIngestTrace.leafDirty())+",\"leafMeshed\":"+event(McNativeIngestTrace.leafMeshed())+",\"target_ingest_executed\":false}");
 McNativeIngestTrace.observeLeaf(leaf);McNativeIngestTrace.dirtyConsumed(leaf+16,engine);McNativeIngestTrace.meshed(4L<<60,engine);
 if(McNativeIngestTrace.leafDirty()!=null||McNativeIngestTrace.leafMeshed()!=null)throw new AssertionError("other leaf/ancestor accepted");
 }
}