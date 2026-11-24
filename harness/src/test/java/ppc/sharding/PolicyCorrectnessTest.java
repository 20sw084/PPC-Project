package ppc.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Structural checks that do not depend on timing: every task runs exactly once under every policy. */
class PolicyCorrectnessTest {

    static Stream<Arguments> policiesAndWorkerCounts() {
        return Policies.all().stream()
                .flatMap(p -> Stream.of(1, 2, 4, 16).map(n -> Arguments.of(p, n)));
    }

    @ParameterizedTest(name = "{0} / {1} workers")
    @MethodSource("policiesAndWorkerCounts")
    void everyTaskRunsExactlyOnce(Policy policy, int workers) throws InterruptedException {
        List<Task> tasks = TaskGenerator.generate(200, 100, 1.5, 50, 1L);
        AtomicIntegerArray runs = new AtomicIntegerArray(tasks.size());

        RunResult result = policy.run(tasks, workers, t -> runs.incrementAndGet(t.id()));

        for (int i = 0; i < tasks.size(); i++) {
            assertEquals(1, runs.get(i), policy.name() + " ran task " + i + " " + runs.get(i) + " times");
        }
        assertEquals(workers, result.finishNanos().length);
        assertEquals(workers, result.busyNanos().length);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ppc.sharding.PolicyCorrectnessTest#allPolicies")
    void fewerTasksThanWorkersStillCompletes(Policy policy) throws InterruptedException {
        List<Task> tasks = TaskGenerator.generate(3, 100, 1.0, 50, 2L);
        AtomicIntegerArray runs = new AtomicIntegerArray(tasks.size());
        policy.run(tasks, 16, t -> runs.incrementAndGet(t.id()));
        for (int i = 0; i < 3; i++) {
            assertEquals(1, runs.get(i));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ppc.sharding.PolicyCorrectnessTest#allPolicies")
    void emptyTaskListFinishesImmediately(Policy policy) throws InterruptedException {
        RunResult result = policy.run(List.of(), 4, t -> {
            throw new AssertionError("no task should run");
        });
        assertTrue(result.makespanNanos() >= 0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ppc.sharding.PolicyCorrectnessTest#allPolicies")
    void aFailingTaskBodySurfacesAsAnException(Policy policy) {
        List<Task> tasks = TaskGenerator.generate(5, 100, 1.0, 50, 3L);
        assertThrows(IllegalStateException.class, () -> policy.run(tasks, 2, t -> {
            throw new RuntimeException("boom");
        }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ppc.sharding.PolicyCorrectnessTest#allPolicies")
    void rejectsNonPositiveWorkerCount(Policy policy) {
        assertThrows(IllegalArgumentException.class, () -> policy.run(List.of(), 0, t -> { }));
    }

    static Stream<Policy> allPolicies() {
        return Policies.all().stream();
    }
}
