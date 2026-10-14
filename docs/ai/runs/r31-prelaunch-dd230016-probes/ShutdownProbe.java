package me.cortex.voxy.client.core.vk.mcnative;
import me.cortex.voxy.common.world.*;import me.cortex.voxy.commonImpl.*;import me.cortex.voxy.common.config.section.SectionStorage;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.nio.*;import java.nio.file.*;import java.util.function.*;
public class ShutdownProbe {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static class Storage extends SectionStorage {
  final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicInteger flushes=new AtomicInteger(),closes=new AtomicInteger();
  public int loadSection(WorldSection s){return 1;}public void saveSection(WorldSection s){}public void putIdMapping(int i,ByteBuffer b){}public Int2ObjectOpenHashMap<byte[]>getIdMappingsData(){return new Int2ObjectOpenHashMap<>();}
  public void flush(){flushes.incrementAndGet();entered.countDown();boolean intr=false;for(;;)try{release.await();break;}catch(InterruptedException x){intr=true;}if(intr)Thread.currentThread().interrupt();}public void close(){closes.incrementAndGet();}public void iteratePositions(int l,LongConsumer c){}
 }
 static Map<String,Object> counts(){return Map.of("shutdownOutstanding",VoxyInstance.shutdownOutstanding(),"shutdownCompletedLate",VoxyInstance.shutdownCompletedLate(),"shutdownFailed",VoxyInstance.shutdownFailed(),"shutdownStageFailures",VoxyInstance.shutdownStageFailures(),"shutdownIncomplete",VoxyInstance.shutdownIncomplete(),"shutdownInterrupted",VoxyInstance.shutdownInterrupted(),"worldsLeftUnfreed",VoxyInstance.worldsLeftUnfreed(),"saveEntriesUnflushed",VoxyInstance.saveEntriesUnflushed(),"poolThreadsLeft",VoxyInstance.poolThreadsLeft());}
 static Map<String,Object> run(boolean interrupt)throws Exception{
  var storage=new Storage();var v=new VoxyInstance(){protected SectionStorage createStorage(WorldIdentifier id){return storage;}};var world=v.getOrCreate(new WorldIdentifier(net.minecraft.world.level.Level.OVERWORLD,0,null));check(world!=null,"registered real world");
  int late=VoxyInstance.shutdownCompletedLate();long bound=interrupt?30000:1200;VoxyInstance.boundShutdown(bound);var error=new AtomicReference<Throwable>();var restored=new AtomicBoolean();var caller=new Thread(()->{try{v.shutdown();}catch(Throwable t){error.set(t);}restored.set(Thread.currentThread().isInterrupted());},"review-bounded-caller");Thread operation=null;var out=new LinkedHashMap<String,Object>();long start=System.nanoTime();
  try{
   caller.start();check(storage.entered.await(5,TimeUnit.SECONDS),"inside actual WorldEngine.free flush");
   operation=Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().equals("Voxy instance shutdown")&&t.isAlive()).findFirst().orElseThrow();
   if(interrupt)caller.interrupt();caller.join(4000);long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
   check(!caller.isAlive()&&error.get()==null,"bounded caller exited cleanly");check(elapsed<3500,"bounded caller time");check(restored.get()==interrupt,"caller interrupt restored");check(VoxyInstance.shutdownOutstanding()==1&&storage.closes.get()==0&&operation.isAlive(),"operation retains still running flush; no close yet");
   out.put("boundMs",bound);out.put("callerElapsedMs",elapsed);out.put("interruptRestored",restored.get());out.put("countsBeforeRelease",counts());out.put("closeBeforeRelease",storage.closes.get());out.put("operationNonDaemon",!operation.isDaemon());out.put("operationStack",Arrays.stream(operation.getStackTrace()).map(Object::toString).toList());
  }finally{
   storage.release.countDown();caller.join(5000);check(VoxyInstance.awaitOutstandingShutdowns(10000),"operation completion");if(operation!=null)operation.join(5000);VoxyInstance.boundShutdown(-1);
  }
  check(operation!=null&&!operation.isAlive()&&!caller.isAlive(),"both actual owned threads joined");check(storage.flushes.get()==1&&storage.closes.get()==1,"flush and close exactly once");check(VoxyInstance.shutdownCompletedLate()==late+1&&VoxyInstance.shutdownFailed()==0&&VoxyInstance.shutdownStageFailures()==0,"truthful late completion counts");out.put("countsAfterRelease",counts());out.put("flushes",storage.flushes.get());out.put("closes",storage.closes.get());out.put("ownedThreadsJoined",true);return out;
 }
 public static void main(String[]args)throws Exception{me.cortex.voxy.testsupport.MinecraftBootstrap.ensure();var out=new LinkedHashMap<String,Object>();out.put("slow_flush",run(false));out.put("interrupted_slow_flush",run(true));Files.writeString(Path.of(args[0]),new com.google.gson.GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(out));System.out.println("SHUTDOWN_PROBE_COMPLETE owned caller and cleanup threads joined");}
}
