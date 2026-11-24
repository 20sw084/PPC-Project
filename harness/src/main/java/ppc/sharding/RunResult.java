package ppc.sharding;

/**
 * Outcome of one run.
 *
 * @param makespanNanos  wall-clock time from the common start signal until the last worker finished
 * @param busyNanos      per worker: time spent inside task bodies
 * @param finishNanos    per worker: time from start until that worker ran out of work
 */
public record RunResult(long makespanNanos, long[] busyNanos, long[] finishNanos) {

    /** Share of total worker time (workers * makespan) not spent running tasks. */
    public double idleShare() {
        long busy = 0;
        for (long b : busyNanos) {
            busy += b;
        }
        double capacity = (double) makespanNanos * busyNanos.length;
        return capacity == 0 ? 0.0 : 1.0 - busy / capacity;
    }
}
