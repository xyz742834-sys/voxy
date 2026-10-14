package me.cortex.voxy.client.core.vk.mcnative;
import static me.cortex.voxy.client.core.vk.mcnative.McNativeIngestTraceTest.*;
import me.cortex.voxy.common.world.*;import me.cortex.voxy.common.world.other.Mapper;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.nio.file.*;
public class OriginalProbe {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static Map<String,Object> snap(WorldEngine e,long before,long bg){var m=new LinkedHashMap<String,Object>();m.put("before",before);m.put("beforeGeneration",bg);m.put("after",McNativeIngestTrace.now());m.put("afterGeneration",McNativeIngestTrace.generation());m.put("epoch",McNativeIngestTrace.epoch());m.put("engine",System.identityHashCode(e));m.put("heldBefore","minecraft:glass");m.put("heldAfter","minecraft:air");m.put("ingest",McNativeIngestTrace.lastIngest(2,7,4));m.put("update",McNativeIngestTrace.lastChange());m.put("lastUpdate",McNativeIngestTrace.lastUpdate());m.put("leafDirty",McNativeIngestTrace.leafDirty());m.put("leafMeshed",McNativeIngestTrace.leafMeshed());m.put("leafUnwatched",McNativeIngestTrace.leafUnwatched());return m;}
 public static void main(String[]args)throws Exception{
  me.cortex.voxy.testsupport.MinecraftBootstrap.ensure();var e=new WorldEngine(new WorldUpdaterOutcomeTest.MemoryStorage());e.setSaveCallback((w,s,n,a)->{s.setNotDirty();if(a)s.release();return true;});
  glass=Mapper.composeMappingId((byte)15,e.getMapper().getIdForBlockState(net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState()),0);
  McNativeIngestTrace.setEnabled(true);McNativeIngestTrace.observe(LEAF,PX,PY,PZ,e);var pin=e.acquire(LEAF);WorldUpdater.insertUpdate(e,section(2,glass));McNativeIngestTrace.observe(LEAF,PX,PY,PZ,e);
  long before=McNativeIngestTrace.now(),bg=McNativeIngestTrace.generation();McNativeIngestTrace.ingestQueued(2,7,4,e);McNativeIngestTrace.dirtyConsumed(LEAF,e);McNativeIngestTrace.meshed(meshRead(e),LEAF,e);
  var queued=snap(e,before,bg);queued.put("heldAfter","minecraft:glass");queued.put("target_ingest_executed",false);
  WorldUpdater.insertUpdate(e,section(3,glass));var old=meshRead(e);check(old.version==2&&old.probeHeld.equals("minecraft:glass"),"old glass/v2");
  var trace=McNativeIngestTrace.observer();var written=new CountDownLatch(1);var release=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();var held=new AtomicReference<String>();
  WorldUpdater.setObserver((w,s,src)->{var write=trace.beginLeafWrite(w,s,src);if(write==null||!Thread.currentThread().getName().equals("review-original-target"))return write;return changed->{written.countDown();ResetProbe.pause(release);write.end(changed);};});
  var target=new Thread(()->{try{WorldUpdater.insertUpdate(e,section(2,AIR));}catch(Throwable t){failure.set(t);}},"review-original-target");
  var reader=new Thread(()->held.set(ResetProbe.held(e,pin)),"review-original-held");var out=new LinkedHashMap<String,Object>();
  try{
   target.start();ResetProbe.waitFor(written);reader.start();reader.join(150);check(reader.isAlive(),"held-content read waits for target publication");McNativeIngestTrace.meshed(old,LEAF,e);check(McNativeIngestTrace.lastChange()==null,"unpublished target cannot be credited");
   release.countDown();target.join(5000);reader.join(5000);check(!target.isAlive()&&!reader.isAlive()&&failure.get()==null,"children joined");check(held.get().equals("minecraft:air"),"held after actual publication");
   McNativeIngestTrace.dirtyConsumed(LEAF,e);McNativeIngestTrace.meshed(old,LEAF,e);out.put("old_after_publication",snap(e,before,bg));
   McNativeIngestTrace.meshed(meshRead(e),LEAF,e);out.put("fresh_after_publication",snap(e,before,bg));out.put("enqueue_only",queued);out.put("heldReaderBlockedUntilPublication",true);out.put("ownedChildrenJoined",true);
  }finally{release.countDown();target.join(5000);if(reader.getState()!=Thread.State.NEW)reader.join(5000);WorldUpdater.setObserver(trace);pin.release();e.free();McNativeIngestTrace.setEnabled(false);}
  Files.writeString(Path.of(args[0]),new com.google.gson.GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(out));System.out.println("ORIGINAL_PROBE_COMPLETE children joined");
 }
}
