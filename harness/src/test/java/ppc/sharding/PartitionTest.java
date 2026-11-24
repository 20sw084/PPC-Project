package ppc.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PartitionTest {

    private static List<Task> of(double... durations) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < durations.length; i++) {
            tasks.add(new Task(i, durations[i], durations[i]));
        }
        return tasks;
    }

    private static List<Integer> ids(List<Task> tasks) {
        return tasks.stream().map(Task::id).toList();
    }

    private static double maxEstimatedLoad(List<List<Task>> parts) {
        return parts.stream().mapToDouble(p -> p.stream().mapToDouble(Task::estimatedMs).sum()).max().orElse(0);
    }

    @Test
    void roundRobinDealsByIndexInOriginalOrder() {
        List<List<Task>> parts = StaticRoundRobin.partition(of(5, 4, 3, 2, 1), 2);
        assertEquals(List.of(0, 2, 4), ids(parts.get(0)));
        assertEquals(List.of(1, 3), ids(parts.get(1)));
    }

    @Test
    void lptBalancesTheClassicExample() {
        // 10, 9 and six 1s on 2 workers: LPT gives loads 13 and 12 (lower bound 12.5)
        List<List<Task>> parts = Lpt.partition(of(10, 9, 1, 1, 1, 1, 1, 1), 2);
        assertEquals(13.0, maxEstimatedLoad(parts));
        assertEquals(List.of(0, 3, 5, 7), ids(parts.get(0)));
        assertEquals(List.of(1, 2, 4, 6), ids(parts.get(1)));
    }

    @Test
    void lptBeatsRoundRobinWhenLongTasksCollide() {
        // long tasks at even indices: round-robin puts both on worker 0
        List<Task> tasks = of(100, 10, 100, 10);
        assertEquals(200.0, maxEstimatedLoad(StaticRoundRobin.partition(tasks, 2)));
        assertEquals(110.0, maxEstimatedLoad(Lpt.partition(tasks, 2)));
    }

    @Test
    void lptReadsEstimatesOnly() {
        // same estimates, wildly different true durations: the partition must not change
        List<Task> a = new ArrayList<>();
        List<Task> b = new ArrayList<>();
        double[] est = {40, 30, 20, 10, 5, 5};
        for (int i = 0; i < est.length; i++) {
            a.add(new Task(i, est[i], est[i]));
            b.add(new Task(i, 1000 - est[i] * 7, est[i]));
        }
        assertEquals(Lpt.partition(a, 3).stream().map(PartitionTest::ids).toList(),
                Lpt.partition(b, 3).stream().map(PartitionTest::ids).toList());
    }

    @Test
    void lptIsDeterministicOnTies() {
        List<Task> tasks = of(5, 5, 5, 5);
        assertEquals(Lpt.partition(tasks, 2).stream().map(PartitionTest::ids).toList(),
                Lpt.partition(tasks, 2).stream().map(PartitionTest::ids).toList());
        assertEquals(List.of(0, 2), ids(Lpt.partition(tasks, 2).get(0)));
    }

    @Test
    void moreWorkersThanTasksLeavesSomeWorkersEmpty() {
        List<List<Task>> parts = Lpt.partition(of(3, 2, 1), 5);
        assertEquals(5, parts.size());
        assertEquals(3, parts.stream().mapToInt(List::size).sum());
    }
}
