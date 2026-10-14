package me.cortex.voxy.commonImpl;
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
 static boolean awaitWorldRelease(BooleanSupplier used, long deadlineMillis) {
        long end = deadlineMillis < 0 ? Long.MAX_VALUE : System.nanoTime() + deadlineMillis * 1_000_000L;
        while (used.getAsBoolean()) {
            if (System.nanoTime() >= end) return false;
            try {
                //noinspection BusyWait
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return true;
    }
 public void shutdown() {
        Logger.info("Shutting down voxy instance");
        this.isRunning = false;
        try {
            this.worldCleaner.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        this.cleanIdle();

        if (!this.activeWorlds.isEmpty()) {
            long stamp = this.activeWorldLock.readLock();
            for (var world : this.activeWorlds.values()) {
                this.importManager.cancelImport(world);
            }
            this.activeWorldLock.unlockRead(stamp);
        }

        try {this.ingestService.shutdown();} catch (Exception e) {Logger.error(e);}
        try {this.savingService.shutdown();} catch (Exception e) {Logger.error(e);}


        long stamp = this.activeWorldLock.writeLock();

        if (!this.activeWorlds.isEmpty()) {
            boolean printedNotice = false;
            for (var world : new ArrayList<>(this.activeWorlds.values())) {
                if (world.isWorldUsed()) {
                    if (!printedNotice) {
                        printedNotice = true;
                        Logger.error("Not all worlds shutdown, force closing worlds");
                    }
                    //Dont lock in the loopy thing, this should basicly never happen if it does something horrific happened
                    this.activeWorldLock.unlockWrite(stamp);
                    boolean released = awaitWorldRelease(world::isWorldUsed, worldReleaseDeadlineMillis);
                    stamp = this.activeWorldLock.writeLock();
                    if (!released) {
                        // still read by someone: freeing it would free it under its reader; leak it
                        worldsLeftUnfreed++;
                        Logger.error("A world was still in use " + worldReleaseDeadlineMillis
                            + " ms into shutdown; left unfreed (not flushed or closed)");
                        continue;
                    }
                }
                //Free the world
                world.free();
            }
            this.activeWorlds.clear();
        }

        try {this.threadPool.shutdown();} catch (Exception e) {Logger.error(e);}

        if (!this.activeWorlds.isEmpty()) {
            throw new IllegalStateException("Not all worlds shutdown");
        }
        Logger.info("Instance shutdown");
        this.activeWorldLock.unlockWrite(stamp);
    }
}