package ppc.sharding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Longest-processing-time-first sharding: sort by estimated duration (descending), give each task to the
 * worker with the smallest estimated load so far, then run the shards as fixed lists. Uses estimates only.
 */
public final class Lpt implements Policy {

    @Override
    public String name() {
        return "lpt";
    }

    @Override
    public boolean usesEstimates() {
        return true;
    }

    static List<List<Task>> partition(List<Task> tasks, int workers) {
        List<Task> sorted = new ArrayList<>(tasks);
        sorted.sort(Comparator.comparingDouble(Task::estimatedMs).reversed().thenComparingInt(Task::id));
        List<List<Task>> parts = StaticRoundRobin.emptyParts(workers);
        double[] load = new double[workers];
        for (Task t : sorted) {
            int target = 0;
            for (int w = 1; w < workers; w++) {
                if (load[w] < load[target]) {
                    target = w; // ties keep the lowest index, so the result is deterministic
                }
            }
            parts.get(target).add(t);
            load[target] += t.estimatedMs();
        }
        return parts;
    }

    @Override
    public RunResult run(List<Task> tasks, int workers, TaskBody body) throws InterruptedException {
        return WorkerRunner.execute(workers, body, WorkerRunner.fixedAssignment(partition(tasks, workers)));
    }
}
