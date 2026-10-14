package me.cortex.voxy.commonImpl;
import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public class ShutdownProbe {
 public static void main(String[]args)throws Exception{
  var field=VoxyInstance.class.getDeclaredField("worldReleaseDeadlineMillis");field.setAccessible(true);
  if(field.getLong(null)!=-1)throw new AssertionError("default changed");
  VoxyInstance.boundWorldRelease(30);
  var v=new VoxyInstance();var w=new VoxyInstance.World();v.activeWorlds.put("held",w);
  var entered=new CountDownLatch(1);var release=new AtomicBoolean(false);var error=new AtomicReference<Throwable>();
  // Model the real PerThreadContextExecutor.shutdown wait, prior to awaitWorldRelease.
  v.ingestService.action=()->{entered.countDown();while(!release.get())Thread.onSpinWait();};
  var t=new Thread(()->{try{v.shutdown();}catch(Throwable e){error.set(e);}},"review-owned-shutdown");
  long start=System.nanoTime();t.start();if(!entered.await(1,TimeUnit.SECONDS))throw new AssertionError("not entered");
  Thread.sleep(180);boolean exceeded=t.isAlive()&&VoxyInstance.worldsLeftUnfreed()==0&&!w.freed;
  release.set(true);t.join(1000);if(t.isAlive()||error.get()!=null||!exceeded||w.freed||VoxyInstance.worldsLeftUnfreed()!=1||!v.threadPool.stopped)throw new AssertionError("bounded model failed "+error);
  long elapsed=(System.nanoTime()-start)/1_000_000;
  var x=new VoxyInstance();var y=new VoxyInstance.World();y.interrupt=true;x.activeWorlds.put("held",y);
  int before=VoxyInstance.worldsLeftUnfreed();boolean threw=false;try{x.shutdown();}catch(RuntimeException e){threw=e.getCause() instanceof InterruptedException;}
  boolean interruptPreserved=Thread.currentThread().isInterrupted();Thread.interrupted();
  if(!threw||y.freed||x.threadPool.stopped||x.activeWorlds.isEmpty()||VoxyInstance.worldsLeftUnfreed()!=before)throw new AssertionError("interruption model failed");
  System.out.println("{\"default_unbounded\":true,\"model_deadline_ms\":30,\"total_elapsed_ms\":"+elapsed+",\"blocked_before_deadline_loop\":"+exceeded+",\"timeout_world_freed\":"+w.freed+",\"timeout_count_delta\":1,\"normal_pool_stopped\":"+v.threadPool.stopped+",\"interrupt_throws\":"+threw+",\"interrupt_preserved\":"+interruptPreserved+",\"interrupt_count_delta\":0,\"interrupt_pool_stopped\":"+x.threadPool.stopped+",\"interrupt_world_retained_in_map\":"+!x.activeWorlds.isEmpty()+",\"owned_child_joined\":"+!t.isAlive()+"}");
 }
}
