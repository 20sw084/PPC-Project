package ppc.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TaskGeneratorTest {

    @Test
    void producesRequestedCountWithSequentialIds() {
        List<Task> tasks = TaskGenerator.generate(200, 100, 1.0, 50, 1L);
        assertEquals(200, tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            assertEquals(i, tasks.get(i).id());
        }
    }

    @Test
    void respectsFloorAndStartsWithExactEstimates() {
        for (Task t : TaskGenerator.generate(500, 100, 1.5, 50, 2L)) {
            assertTrue(t.trueMs() >= 50.0);
            assertEquals(t.trueMs(), t.estimatedMs());
        }
    }

    @Test
    void sameSeedGivesSameTasksDifferentSeedDoesNot() {
        assertEquals(TaskGenerator.generate(50, 100, 1.0, 50, 7L), TaskGenerator.generate(50, 100, 1.0, 50, 7L));
        assertNotEquals(TaskGenerator.generate(50, 100, 1.0, 50, 7L), TaskGenerator.generate(50, 100, 1.0, 50, 8L));
    }

    @Test
    void higherSigmaGivesMoreSkew() {
        // skew proxy: max / median of the generated durations, averaged over seeds to be stable
        assertTrue(skew(1.5) > skew(0.5));
    }

    @Test
    void rejectsNonPositiveCount() {
        assertThrows(IllegalArgumentException.class, () -> TaskGenerator.generate(0, 100, 1.0, 50, 1L));
    }

    private static double skew(double sigma) {
        double total = 0;
        for (long seed = 0; seed < 20; seed++) {
            List<Task> tasks = TaskGenerator.generate(200, 100, sigma, 1, seed);
            double[] d = tasks.stream().mapToDouble(Task::trueMs).sorted().toArray();
            total += d[d.length - 1] / d[d.length / 2];
        }
        return total / 20;
    }
}
