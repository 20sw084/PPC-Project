package ppc.sharding;

import java.util.ArrayList;
import java.util.List;

/** Static partition without duration knowledge: task i goes to worker i mod N, in original order. */
public final class StaticRoundRobin implements Policy {

    @Override
    public String name() {
        return "static-rr";
    }

    static List<List<Task>> partition(List<Task> tasks, int workers) {
        List<List<Task>> parts = emptyParts(workers);
        for (int i = 0; i < tasks.size(); i++) {
            parts.get(i % workers).add(tasks.get(i));
        }
        return parts;
    }

    static List<List<Task>> emptyParts(int workers) {
        List<List<Task>> parts = new ArrayList<>(workers);
        for (int w = 0; w < workers; w++) {
            parts.add(new ArrayList<>());
        }
        return parts;
    }

    @Override
    public RunResult run(List<Task> tasks, int workers, TaskBody body) throws InterruptedException {
        return WorkerRunner.execute(workers, body, WorkerRunner.fixedAssignment(partition(tasks, workers)));
    }
}
