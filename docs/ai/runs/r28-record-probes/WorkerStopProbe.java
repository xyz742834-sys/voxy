import java.util.*;import java.util.concurrent.*;
public class WorkerStopProbe {
static class Factory {boolean freed;void free(){freed=true;}}
static class Built {void free(){}}
record AsyncResult(Built built){}
static class Logger {static void warn(String s){System.err.println(s);}}
static class Stuck extends AbstractExecutorService {boolean stopped;public void shutdown(){stopped=true;}public List<Runnable> shutdownNow(){stopped=true;return List.of();}public boolean isShutdown(){return stopped;}public boolean isTerminated(){return false;}public boolean awaitTermination(long n,TimeUnit u){return false;}public void execute(Runnable r){}}
ExecutorService workers=new Stuck();Factory factory=new Factory();List<Factory> workerFactories=List.of(new Factory());Queue<AsyncResult> results=new ConcurrentLinkedQueue<>();boolean freed;
    private boolean workersStopped, workersLeaked;

    /**
     * Stop the workers and wait for them; free their factories and any undelivered meshes.
     * Idempotent. If they do not stop in time, their factories are leaked rather than freed under
     * them.
     */
    public void stopWorkers() {
        if (this.workersStopped) return;
        this.workersStopped = true;
        if (this.workers == null) return;
        this.workers.shutdownNow();
        try {
            if (!this.workers.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                this.workersLeaked = true;
                Logger.warn("[native-vk] mesher workers did not stop within 10 s; leaking their factories");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            this.workersLeaked = true;
            return;
        }
        for (AsyncResult r; (r = this.results.poll()) != null; ) {
            if (r.built() != null) r.built().free();
        }
        for (var f : this.workerFactories) f.free();
    }

    public void free() {
        if (this.freed) return;
        this.stopWorkers();
        this.freed = true;
        this.factory.free();
    }

public static void main(String[] a){var p=new WorkerStopProbe();p.stopWorkers();if(p.workers.isTerminated()||!p.workersLeaked)throw new AssertionError();p.free();if(!p.factory.freed||p.workerFactories.get(0).freed)throw new AssertionError();System.out.println("{\"mock_timeout_returns_with_worker_unterminated\":true,\"worker_factory_leaked\":true,\"free_continues_after_timeout\":true}");}}
