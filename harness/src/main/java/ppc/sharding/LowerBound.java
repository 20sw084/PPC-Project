package ppc.sharding;

import java.util.List;

/** Makespan lower bound for independent tasks on N identical workers: max(sum/N, longest task). */
public final class LowerBound {

    private LowerBound() {
    }

    public static double ms(List<Task> tasks, int workers) {
        if (workers <= 0) {
            throw new IllegalArgumentException("workers must be positive");
        }
        double sum = 0;
        double longest = 0;
        for (Task t : tasks) {
            sum += t.trueMs();
            longest = Math.max(longest, t.trueMs());
        }
        return Math.max(sum / workers, longest);
    }
}
