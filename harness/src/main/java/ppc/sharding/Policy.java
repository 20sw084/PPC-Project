package ppc.sharding;

import java.util.List;

/**
 * A task-assignment policy. Implementations (to come): static round-robin, LPT, shared dynamic queue,
 * work-stealing. A policy may only look at {@link Task#estimatedMs()} before a task runs, never at
 * {@link Task#trueMs()}.
 */
public interface Policy {

    String name();

    /**
     * Whether the policy reads {@link Task#estimatedMs()}. Policies that do not are run once per
     * (skew, workers, rep) as a single baseline, not once per estimate-error level: their timings cannot
     * depend on the error, so repeating them would only be pseudo-replication.
     */
    default boolean usesEstimates() {
        return false;
    }

    /** Runs every task exactly once on {@code workers} threads and reports timings. */
    RunResult run(List<Task> tasks, int workers, TaskBody body) throws InterruptedException;
}
