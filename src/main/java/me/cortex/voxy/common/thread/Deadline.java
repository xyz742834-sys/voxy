package me.cortex.voxy.common.thread;

/**
 * Absolute deadlines ({@code System.nanoTime()} values) for the bounded shutdown waits
 * (R29-PRELAUNCH-SHUTDOWN). {@link #NONE} waits as long as it takes, as Voxy always has: the GL
 * default. A wait that is interrupted stops waiting and keeps the interrupt flag set, so its caller
 * can finish its remaining steps without waiting and then report the interruption.
 */
public final class Deadline {
    /** No deadline: wait as long as it takes. */
    public static final long NONE = Long.MAX_VALUE;

    private Deadline() {}

    /** The deadline {@code millis} from now; negative: {@link #NONE}. */
    public static long in(long millis) {
        return millis < 0 ? NONE : System.nanoTime() + millis * 1_000_000L;
    }

    public static boolean passed(long deadlineNanos) {
        return deadlineNanos != NONE && System.nanoTime() - deadlineNanos >= 0;
    }

    /** Milliseconds left, at least 1 while the deadline has not passed; {@link Long#MAX_VALUE} for {@link #NONE}. */
    public static long remainingMillis(long deadlineNanos) {
        if (deadlineNanos == NONE) return Long.MAX_VALUE;
        long left = deadlineNanos - System.nanoTime();
        return left <= 0 ? 0 : Math.max(1, left / 1_000_000L);
    }

    /**
     * Sleep up to {@code millis} but not past the deadline. @return false when the caller should stop
     * waiting: the deadline has passed or the thread was interrupted (the flag is left set)
     */
    public static boolean sleep(long millis, long deadlineNanos) {
        if (passed(deadlineNanos)) return false;
        try {
            Thread.sleep(Math.min(millis, remainingMillis(deadlineNanos)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !passed(deadlineNanos);
    }

    /** Spin (yielding) until {@code done}, the deadline, or an interrupt (flag left set). @return done */
    public static boolean spinUntil(java.util.function.BooleanSupplier done, long deadlineNanos) {
        while (!done.getAsBoolean()) {
            if (passed(deadlineNanos) || Thread.currentThread().isInterrupted()) return done.getAsBoolean();
            Thread.onSpinWait();
            Thread.yield();
        }
        return true;
    }
}
