package me.cortex.voxy.client.core.vk.mcnative;
import static me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTraceTest.*;
import me.cortex.voxy.common.world.*;import me.cortex.voxy.common.world.other.Mapper;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.nio.file.*;
public class DomainResetProbe {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static void waitFor(CountDownLatch l)throws InterruptedException{check(l.await(8,TimeUnit.SECONDS),"latch timeout");}
 static void pause(CountDownLatch l){try{waitFor(l);}catch(Exception e){throw new RuntimeException(e);}}
 static void parked(Thread t,String method)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){if(Arrays.stream(t.getStackTrace()).anyMatch(f->f.getClassName().equals("java.util.concurrent.locks.StampedLock")&&f.getMethodName().equals(method)))return;Thread.sleep(1);}throw new AssertionError("not parked "+t.getName());}
 static Object domain()throws Exception{var f=McNativeIngestTrace.class.getDeclaredField("current");f.setAccessible(true);var o=f.get(null);var d=o.getClass().getDeclaredField("domain");d.setAccessible(true);return d.get(o);}
 static String raw(WorldEngine e,WorldSection s){return McNativeIngestTrace.blockName(e,s._unsafeGetRawDataArray()[WorldSection.getIndex(PX&31,PY&31,PZ&31)]);}
 static Map<String,Object> snap(WorldEngine e,WorldSection s,long before,long bg){var m=new LinkedHashMap<String,Object>();m.put("before",before);m.put("beforeGeneration",bg);m.put("after",McNativeIngestTrace.now());m.put("afterGeneration",McNativeIngestTrace.generation());m.put("epoch",McNativeIngestTrace.epoch());m.put("engine",System.identityHashCode(e));m.put("heldBefore","minecraft:glass");var h=McNativeIngestTrace.readHold(LEAF,e);try{m.put("heldAfter",raw(e,s));}finally{if(h!=null)h.release();}m.put("ingest",McNativeIngestTrace.lastIngest(2,7,4));m.put("update",McNativeIngestTrace.lastChange());m.put("lastUpdate",McNativeIngestTrace.lastUpdate());m.put("leafDirty",McNativeIngestTrace.leafDirty());m.put("leafMeshed",McNativeIngestTrace.leafMeshed());m.put("leafUnwatched",McNativeIngestTrace.leafUnwatched());return m;}
 public static void main(String[]args)throws Exception{
  me.cortex.voxy.testsupport.MinecraftBootstrap.ensure();var e=new WorldEngine(new WorldUpdaterOutcomeTest.MemoryStorage());var foreign=new WorldEngine(new WorldUpdaterOutcomeTest.MemoryStorage());e.setSaveCallback((w,s,n,a)->{s.setNotDirty();if(a)s.release();return true;});
  glass=Mapper.composeMappingId((byte)15,e.getMapper().getIdForBlockState(net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState()),0);
  McNativeIngestTrace.setEnabled(true);McNativeIngestTrace.observe(LEAF,PX,PY,PZ,e);var pin=e.acquire(LEAF);WorldUpdater.insertUpdate(e,section(2,glass));WorldUpdater.insertUpdate(e,section(3,glass));
  Object originalDomain=domain();var trace=McNativeIngestTrace.observer();var errors=new ConcurrentLinkedQueue<Throwable>();
  var entered=new CountDownLatch[]{new CountDownLatch(1),new CountDownLatch(1)};var allowWrite=new CountDownLatch[]{new CountDownLatch(1),new CountDownLatch(1)};
  var written=new CountDownLatch[]{new CountDownLatch(1),new CountDownLatch(1)};var oldPublished=new AtomicReferenceArray<McNativeIngestTrace.Update>(2);
  WorldUpdater.setObserver((w,s,src)->{var end=trace.beginLeafWrite(w,s,src);String name=Thread.currentThread().getName();if(end==null||!name.startsWith("review-old-"))return end;int n=Integer.parseInt(name.substring(name.length()-1));entered[n].countDown();pause(allowWrite[n]);return changed->{end.end(changed);written[n].countDown();};});
  var writers=new Thread[2];for(int n=0;n<2;n++){final int k=n;writers[n]=new Thread(()->{try{WorldUpdater.insertUpdate(e,section(2,k==0?glass:AIR));}catch(Throwable t){errors.add(t);}},"review-old-"+n);}
  var readRef=new AtomicReference<McNativeIngestTrace.MeshRead>();var readGot=new CountDownLatch(1);var releaseRead=new CountDownLatch(1);var targetDone=new CountDownLatch(1);
  var reader=new Thread(()->{McNativeIngestTrace.MeshRead read=null;try{read=McNativeIngestTrace.beginMesh(LEAF,e,pin);readRef.set(read);readGot.countDown();pause(releaseRead);}catch(Throwable t){errors.add(t);}finally{McNativeIngestTrace.endMesh(read);}},"review-new-read");
  var target=new Thread(()->{try{WorldUpdater.insertUpdate(e,section(2,AIR));}catch(Throwable t){errors.add(t);}finally{targetDone.countDown();}},"review-new-target");var out=new LinkedHashMap<String,Object>();
  try{
   writers[0].start();waitFor(entered[0]);writers[1].start();parked(writers[1],"acquireWrite");out.put("oldWaiterStack",Arrays.stream(writers[1].getStackTrace()).map(Object::toString).toList());
   long oldEpoch=McNativeIngestTrace.epoch();McNativeIngestTrace.observe(LEAF,PX,PY,PZ,e);check(domain()==originalDomain,"same leaf reset keeps exact domain object");
   // Captured operations remain live while observations switch away and back; no retirement of their domain.
   McNativeIngestTrace.observe(OTHER,PX,PY,PZ+32,e);check(domain()!=originalDomain,"other physical leaf distinct");
   McNativeIngestTrace.observe(LEAF,PX,PY,PZ,foreign);check(domain()!=originalDomain,"other live engine distinct");
   McNativeIngestTrace.observe(LEAF,PX,PY,PZ,e);check(domain()==originalDomain,"return to original engine/leaf reuses domain with pending old writer");
   out.put("oldEpoch",oldEpoch);out.put("newEpoch",McNativeIngestTrace.epoch());out.put("domainRetainedAcrossResets",true);
   McNativeIngestTrace.ingestQueued(2,7,4,e);reader.start();parked(reader,"acquireRead");target.start();parked(target,"acquireWrite");
   out.put("newReadStack",Arrays.stream(reader.getStackTrace()).map(Object::toString).toList());out.put("newTargetStack",Arrays.stream(target.getStackTrace()).map(Object::toString).toList());check(readGot.getCount()==1&&targetDone.getCount()==1,"both blocked by old domain holder");
   allowWrite[0].countDown();allowWrite[1].countDown();waitFor(readGot);var read=readRef.get();check(read!=null,"read captured");
   boolean oldPending=written[1].getCount()==1,targetPending=targetDone.getCount()==1;
   for(int i=0;i<50;i++){check(raw(e,pin).equals(read.probeHeld),"raw content equals exact result identity during read");check(McNativeIngestTrace.generation()==read.version,"no version mutation under read");if(oldPending)check(written[1].getCount()==1,"old waiter cannot publish under read");if(targetPending)check(targetDone.getCount()==1,"new writer cannot publish under read");Thread.sleep(2);}
   out.put("contentDuringHeldRead",raw(e,pin));out.put("readVersion",read.version);out.put("readProbeHeld",read.probeHeld);out.put("consistentSamples",50);out.put("oldWriterPendingDuringRead",oldPending);out.put("targetPendingDuringRead",targetPending);
   releaseRead.countDown();reader.join(5000);for(var t:writers)t.join(5000);target.join(5000);check(!reader.isAlive()&&!target.isAlive()&&Arrays.stream(writers).noneMatch(Thread::isAlive),"all original race children joined");check(errors.isEmpty(),"child errors "+errors);
   var update=McNativeIngestTrace.lastUpdate();check(update!=null&&update.epoch()==McNativeIngestTrace.epoch()&&update.sx()==2&&update.wrote().equals("minecraft:air"),"only new target event credited to current epoch");out.put("serializedTargetOutcome",update);out.put("serializedCurrentChange",McNativeIngestTrace.lastChange());
   // Deterministic stale-result and fresh-result controls, with actual target content and exact MeshRead.
   WorldUpdater.setObserver(trace);WorldUpdater.insertUpdate(e,section(2,glass));McNativeIngestTrace.observe(LEAF,PX,PY,PZ,e);long before=McNativeIngestTrace.now(),bg=McNativeIngestTrace.generation();var oldRead=meshRead(e);check(oldRead.probeHeld.equals("minecraft:glass"),"old result holds glass");
   McNativeIngestTrace.ingestQueued(2,7,4,e);WorldUpdater.insertUpdate(e,section(2,AIR));McNativeIngestTrace.dirtyConsumed(LEAF,e);McNativeIngestTrace.meshed(oldRead,LEAF,e);out.put("staleResult",snap(e,pin,before,bg));
   McNativeIngestTrace.meshed(meshRead(e),LEAF,e);out.put("freshResult",snap(e,pin,before,bg));
   // No-op remains no changed update. Empty-leaf unwatch records the actual shared-domain version/count.
   WorldUpdater.insertUpdate(e,section(3,AIR));WorldUpdater.insertUpdate(e,section(2,glass));McNativeIngestTrace.observe(LEAF,PX,PY,PZ,e);before=McNativeIngestTrace.now();bg=McNativeIngestTrace.generation();McNativeIngestTrace.ingestQueued(2,7,4,e);WorldUpdater.insertUpdate(e,section(2,AIR));McNativeIngestTrace.dirtyConsumed(LEAF,e);McNativeIngestTrace.unwatched(LEAF,e);out.put("emptyTerminal",snap(e,pin,before,bg));
   McNativeIngestTrace.observe(LEAF,PX,PY,PZ,e);WorldUpdater.insertUpdate(e,section(2,AIR));check(McNativeIngestTrace.lastChange()==null&&!McNativeIngestTrace.lastUpdate().changed(),"real no-op not promoted");out.put("noOpOutcome",McNativeIngestTrace.lastUpdate());
  }finally{
   releaseRead.countDown();for(var l:allowWrite)l.countDown();for(var t:new Thread[]{writers[0],writers[1],reader,target}){if(t.getState()!=Thread.State.NEW)t.join(10000);check(!t.isAlive(),"owned child joined "+t.getName());}WorldUpdater.setObserver(trace);pin.release();e.free();foreign.free();McNativeIngestTrace.setEnabled(false);
  }
  check(errors.isEmpty(),"child failures");out.put("ownedChildrenJoined",true);out.put("childErrors",errors.size());Files.writeString(Path.of(args[0]),new com.google.gson.GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(out));System.out.println("DOMAIN_RESET_PROBE_COMPLETE all owned children joined");
 }
}
