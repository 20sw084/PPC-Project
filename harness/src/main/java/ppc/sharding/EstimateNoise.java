package ppc.sharding;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Degrades duration estimates with multiplicative lognormal noise. True durations are never changed. */
public final class EstimateNoise {

    private EstimateNoise() {
    }

    /** {@code estimated = true * exp(sigmaEst * Z)}; sigmaEst = 0 gives exact estimates. */
    public static List<Task> apply(List<Task> tasks, double sigmaEst, long seed) {
        Random rng = new Random(seed);
        List<Task> noisy = new ArrayList<>(tasks.size());
        for (Task t : tasks) {
            double factor = sigmaEst == 0.0 ? 1.0 : Math.exp(sigmaEst * rng.nextGaussian());
            noisy.add(t.withEstimate(t.trueMs() * factor));
        }
        return noisy;
    }
}
