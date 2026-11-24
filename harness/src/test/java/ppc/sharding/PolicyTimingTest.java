package ppc.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Sanity checks with real sleeps and deliberately wide margins (Windows sleep granularity, shared CPU).
 * These check the runner and the policies, not performance; precise timing is the calibration step.
 */
class PolicyTimingTest {

    private static List<Task> of(double... durations) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < durations.length; i++) {
            tasks.add(new Task(i, durations[i], durations[i]));
        }
        return tasks;
    }

    private static double ms(RunResult r) {
        return r.makespanNanos() / 1e6;
    }

    @Test
    void makespanNeverBeatsTheLowerBound() throws InterruptedException {
        List<Task> tasks = TaskGenerator.generate(40, 20, 1.0, 10, 5L);
        for (Policy p : Policies.all()) {
            double lb = LowerBound.ms(tasks, 4);
            // sleeping never returns early, so this is a hard physical bound (1 ms slack for the clock)
            assertTrue(ms(p.run(tasks, 4, TaskBodies.sleep())) >= lb - 1.0, p.name());
        }
    }

    @Test
    void equalTasksGiveSimilarMakespanUnderEveryPolicy() throws InterruptedException {
        List<Task> tasks = of(40, 40, 40, 40, 40, 40, 40, 40); // 8 x 40 ms on 4 workers -> about 80 ms
        for (Policy p : Policies.all()) {
            double makespan = ms(p.run(tasks, 4, TaskBodies.sleep()));
            assertTrue(makespan >= 79 && makespan < 250, p.name() + " took " + makespan + " ms");
        }
    }

    @Test
    void oneWorkerGivesTheSumForEveryPolicy() throws InterruptedException {
        List<Task> tasks = of(30, 20, 10, 10); // 70 ms in total
        for (Policy p : Policies.all()) {
            double makespan = ms(p.run(tasks, 1, TaskBodies.sleep()));
            assertTrue(makespan >= 69 && makespan < 200, p.name() + " took " + makespan + " ms");
        }
    }

    @Test
    void dynamicPoliciesRescueACollisionThatStaticRoundRobinSuffers() throws InterruptedException {
        // long tasks at even indices: round-robin runs both on worker 0 (about 400 ms); the ideal is about 210
        List<Task> tasks = of(200, 10, 200, 10);
        double rr = ms(new StaticRoundRobin().run(tasks, 2, TaskBodies.sleep()));
        double queue = ms(new DynamicQueue().run(tasks, 2, TaskBodies.sleep()));
        double steal = ms(new WorkStealing().run(tasks, 2, TaskBodies.sleep()));
        assertTrue(rr >= 399, "round-robin " + rr);
        assertTrue(queue < 350, "dynamic queue " + queue);
        assertTrue(steal < 350, "work-stealing " + steal);
    }

    @Test
    void lptWithExactEstimatesAvoidsTheSameCollision() throws InterruptedException {
        List<Task> tasks = of(200, 10, 200, 10);
        double lpt = ms(new Lpt().run(tasks, 2, TaskBodies.sleep()));
        assertTrue(lpt < 350, "lpt " + lpt);
    }

    @Test
    void idleShareIsAFractionAndHighWhenLoadIsUnbalanced() throws InterruptedException {
        List<Task> tasks = of(200, 10, 200, 10);
        RunResult rr = new StaticRoundRobin().run(tasks, 2, TaskBodies.sleep());
        // worker 1 works about 20 of 400 ms: idle share is about 0.45
        assertTrue(rr.idleShare() > 0.35 && rr.idleShare() < 0.6, "idle share " + rr.idleShare());
        assertEquals(2, rr.finishNanos().length);
    }
}
