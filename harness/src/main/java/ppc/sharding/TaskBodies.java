package ppc.sharding;

import java.util.concurrent.locks.LockSupport;

/** Task bodies used in real runs. Their accuracy is measured by {@link Calibration}. */
public final class TaskBodies {

    private static final long SPIN_TAIL_NANOS = 2_000_000L;

    private static volatile long sink; // keeps the JIT from removing the spin loop

    private TaskBodies() {
    }

    /**
     * Timed task: occupies its worker for the task's true duration, almost without using CPU. Plain
     * {@code Thread.sleep} on Windows rounds up to the ~15.6 ms timer tick (errors up to 16 ms, measured in
     * calibration), so this waits on a deadline in 1 ms sleeps and spins only the last {@link #SPIN_TAIL_NANOS}.
     */
    public static TaskBody sleep() {
        return task -> {
            long deadline = System.nanoTime() + (long) (task.trueMs() * 1_000_000.0);
            while (deadline - System.nanoTime() > SPIN_TAIL_NANOS + 1_000_000L) {
                Thread.sleep(1);
            }
            while (System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        };
    }

    /** Plain {@code Thread.sleep}; kept only so calibration can show how inaccurate it is on Windows. */
    public static TaskBody sleepPlain() {
        return task -> {
            long nanos = (long) (task.trueMs() * 1_000_000.0);
            Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
        };
    }

    /** Like {@link #sleep()} but via parkNanos with a deadline loop; compared against sleep in calibration. */
    public static TaskBody park() {
        return task -> {
            long deadline = System.nanoTime() + (long) (task.trueMs() * 1_000_000.0);
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0) {
                LockSupport.parkNanos(remaining);
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
        };
    }

    /**
     * CPU-bound task: a fixed amount of work ({@code trueMs * itersPerMs} iterations), so under CPU
     * oversubscription it takes longer in wall-clock time, as a real CPU-bound test would.
     */
    public static TaskBody spin(double itersPerMs) {
        return task -> sink = burn((long) (task.trueMs() * itersPerMs));
    }

    /** Dependent xorshift chain: cannot be vectorised or removed, cost is linear in {@code iterations}. */
    public static long burn(long iterations) {
        long x = 0x9E3779B97F4A7C15L;
        for (long i = 0; i < iterations; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
        }
        return x;
    }
}
