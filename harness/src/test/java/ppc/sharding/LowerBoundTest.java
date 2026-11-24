package ppc.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LowerBoundTest {

    private static List<Task> of(double... durations) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < durations.length; i++) {
            tasks.add(new Task(i, durations[i], durations[i]));
        }
        return tasks;
    }

    @Test
    void averageLoadDominatesWhenTasksAreBalanced() {
        // 10+9+6*1 = 25, on 2 workers -> 12.5 (longest task is only 10)
        assertEquals(12.5, LowerBound.ms(of(10, 9, 1, 1, 1, 1, 1, 1), 2));
    }

    @Test
    void longestTaskDominatesWhenSkewIsExtreme() {
        // 102/4 = 25.5 but one task alone takes 100
        assertEquals(100.0, LowerBound.ms(of(100, 1, 1), 4));
    }

    @Test
    void equalTasksSplitEvenly() {
        assertEquals(30.0, LowerBound.ms(of(10, 10, 10, 10, 10, 10), 2));
    }

    @Test
    void singleWorkerIsTheSum() {
        assertEquals(6.0, LowerBound.ms(of(1, 2, 3), 1));
    }

    @Test
    void rejectsNonPositiveWorkers() {
        assertThrows(IllegalArgumentException.class, () -> LowerBound.ms(of(1), 0));
    }
}
