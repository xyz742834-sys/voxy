package me.cortex.voxy.client.core.vk.mcnative;
import static me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTraceTest.*;
import me.cortex.voxy.common.world.*;
import me.cortex.voxy.common.world.other.Mapper;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.nio.file.*;
public class ResetProbe {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static void waitFor(CountDownLatch l)throws InterruptedException{check(l.await(5,TimeUnit.SECONDS),"latch timeout");}
 static void pause(CountDownLatch l){try{waitFor(l);}catch(Exception e){throw new RuntimeException(e);}}
 static String held(WorldEngine e,WorldSection s){var hold=McNativeIngestTrace.readHold(LEAF,e);try{return McNativeIngestTrace.blockName(e,s._unsafeGetRawDataArray()[WorldSection.getIndex(PX&31,PY&31,PZ&31)]);}finally{if(hold!=null)hold.release();}}
 public static void main(String[] args)throws Exception {
  me.cortex.voxy.testsupport.MinecraftBootstrap.ensure();
  var e=new WorldEngine(new WorldUpdaterOutcomeTest.MemoryStorage());e.setSaveCallback((w,s,n,a)->{s.setNotDirty();if(a)s.release();return true;});
  glass=Mapper.composeMappingId((byte)15,e.getMapper().getIdForBlockState(net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState()),0);
  McNativeIngestTrace.setEnabled(true);McNativeIngestTrace.observe(LEAF,PX,PY,PZ,e);
  var pin=e.acquire(LEAF);WorldUpdater.insertUpdate(e,section(2,glass));WorldUpdater.insertUpdate(e,section(3,glass));
  var trace=McNativeIngestTrace.observer();var failure=new AtomicReference<Throwable>();
  var entered=new CountDownLatch[]{new CountDownLatch(1),new CountDownLatch(1)};
  var allowWrite=new CountDownLatch[]{new CountDownLatch(1),new CountDownLatch(1)};
  var written=new CountDownLatch[]{new CountDownLatch(1),new CountDownLatch(1)};
  var allowDirty=new CountDownLatch[]{new CountDownLatch(1),new CountDownLatch(1)};
  WorldUpdater.setObserver((into,leaf,source)->{
   var w=trace.beginLeafWrite(into,leaf,source);String name=Thread.currentThread().getName();
   if(w==null || !name.startsWith("review-old-write-"))return w;
   int n=Integer.parseInt(name.substring(name.length()-1));entered[n].countDown();pause(allowWrite[n]);
   return changed->{w.end(changed);written[n].countDown();pause(allowDirty[n]);};
  });
  Thread[] ts=new Thread[2];for(int n=0;n<2;n++){final int k=n;ts[n]=new Thread(()->{try{WorldUpdater.insertUpdate(e,section(2,k==0?glass:AIR));}catch(Throwable t){failure.set(t);}},"review-old-write-"+n);}
  McNativeIngestTrace.MeshRead read=null;var out=new LinkedHashMap<String,Object>();
  try{
   ts[0].start();waitFor(entered[0]);ts[1].start();
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
   while(System.nanoTime()<deadline){waiting=Arrays.stream(ts[1].getStackTrace()).anyMatch(f->f.getClassName().equals("java.util.concurrent.locks.StampedLock")&&f.getMethodName().equals("acquireWrite"));if(waiting)break;Thread.yield();}
   check(waiting,"second writer demonstrably waiting in original trace StampedLock");out.put("second_writer_stack",Arrays.stream(ts[1].getStackTrace()).map(Object::toString).toList());
   long oldEpoch=McNativeIngestTrace.epoch();McNativeIngestTrace.observe(LEAF,PX,PY,PZ,e);
   out.put("oldEpoch",oldEpoch);out.put("epoch",McNativeIngestTrace.epoch());out.put("before",McNativeIngestTrace.now());out.put("beforeGeneration",McNativeIngestTrace.generation());out.put("heldBefore",held(e,pin));out.put("engine",System.identityHashCode(e));
   McNativeIngestTrace.ingestQueued(2,7,4,e);WorldUpdater.insertUpdate(e,section(2,AIR));McNativeIngestTrace.dirtyConsumed(LEAF,e);
   read=McNativeIngestTrace.beginMesh(LEAF,e,pin);check(read.probeHeld.equals("minecraft:air"),"begin mesh air");
   allowWrite[0].countDown();waitFor(written[0]);waitFor(entered[1]);
   String meshRead=McNativeIngestTrace.blockName(e,pin._unsafeGetRawDataArray()[WorldSection.getIndex(PX&31,PY&31,PZ&31)]);
   check(meshRead.equals("minecraft:glass"),"real updater wrote glass while new mesh read lock held");out.put("actualModeledMeshProbeRead",meshRead);out.put("resultProbeHeld",read.probeHeld);out.put("resultVersion",read.version);
   allowWrite[1].countDown();waitFor(written[1]);
   var result=read;McNativeIngestTrace.endMesh(read);read=null;
   McNativeIngestTrace.meshed(result,LEAF,e);
   out.put("heldAfter",held(e,pin));out.put("afterGeneration",McNativeIngestTrace.generation());out.put("after",McNativeIngestTrace.now());
   out.put("ingest",McNativeIngestTrace.lastIngest(2,7,4));out.put("update",McNativeIngestTrace.lastChange());out.put("lastUpdate",McNativeIngestTrace.lastUpdate());out.put("leafDirty",McNativeIngestTrace.leafDirty());out.put("leafMeshed",McNativeIngestTrace.leafMeshed());out.put("leafUnwatched",McNativeIngestTrace.leafUnwatched());
   out.put("oldDirtyMarksStillPausedAtAcceptance",allowDirty[0].getCount()==1&&allowDirty[1].getCount()==1);
  }finally{
   // completion snapshot is emitted by the fixed continuation below
   if(read!=null)McNativeIngestTrace.endMesh(read);
   for(var l:allowWrite)l.countDown();for(var l:allowDirty)l.countDown();for(var t:ts){if(t.getState()!=Thread.State.NEW)t.join(5000);check(!t.isAlive(),"owned child joined");}
   WorldUpdater.setObserver(trace);
   out.put("ownedChildrenJoined",true);out.put("childFailure",failure.get()==null?null:failure.get().toString());
   pin.release();e.free();McNativeIngestTrace.setEnabled(false);
  }
  check(failure.get()==null,"child failure "+failure.get());Files.writeString(Path.of(args[0]),new com.google.gson.GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(out));System.out.println("RESET_PROBE_COMPLETE owned children joined");
 }
}
