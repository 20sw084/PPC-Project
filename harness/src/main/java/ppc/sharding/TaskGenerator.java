package ppc.sharding;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Builds a seeded, skewed task set. Same seed gives the same set for every policy. */
public final class TaskGenerator {

    private TaskGenerator() {
    }

    /**
     * Durations are lognormal: {@code median * exp(sigma * Z)}, Z ~ N(0,1), clamped to {@code floorMs}.
     * Estimates start equal to the true durations; apply {@link EstimateNoise} to degrade them.
     */
    public static List<Task> generate(int n, double medianMs, double sigmaTask, double floorMs, long seed) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive");
        }
        Random rng = new Random(seed);
        List<Task> tasks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double ms = Math.max(floorMs, medianMs * Math.exp(sigmaTask * rng.nextGaussian()));
            tasks.add(new Task(i, ms, ms));
        }
        return tasks;
    }
}
