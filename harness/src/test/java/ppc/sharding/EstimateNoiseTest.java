package ppc.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EstimateNoiseTest {

    private final List<Task> base = TaskGenerator.generate(200, 100, 1.0, 50, 3L);

    @Test
    void zeroSigmaKeepsEstimatesExact() {
        assertEquals(base, EstimateNoise.apply(base, 0.0, 99L));
    }

    @Test
    void neverChangesTrueDurationsOrIds() {
        List<Task> noisy = EstimateNoise.apply(base, 0.5, 5L);
        for (int i = 0; i < base.size(); i++) {
            assertEquals(base.get(i).id(), noisy.get(i).id());
            assertEquals(base.get(i).trueMs(), noisy.get(i).trueMs());
        }
    }

    @Test
    void isDeterministicPerSeed() {
        assertEquals(EstimateNoise.apply(base, 0.25, 11L), EstimateNoise.apply(base, 0.25, 11L));
        assertNotEquals(EstimateNoise.apply(base, 0.25, 11L), EstimateNoise.apply(base, 0.25, 12L));
    }

    @Test
    void largerSigmaMeansLargerAverageRelativeError() {
        assertTrue(meanAbsLogError(base, 0.5) > meanAbsLogError(base, 0.1));
    }

    private static double meanAbsLogError(List<Task> tasks, double sigma) {
        double sum = 0;
        for (Task t : EstimateNoise.apply(tasks, sigma, 42L)) {
            sum += Math.abs(Math.log(t.estimatedMs() / t.trueMs()));
        }
        return sum / tasks.size();
    }
}
