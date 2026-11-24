package ppc.sharding;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-worker deques, initially dealt round-robin. A worker takes from the head of its own deque; when empty
 * it scans the other workers and steals from the tail of the first non-empty deque. No tasks are added
 * after start, so "own deque empty and every other deque empty" means this worker is done. Needs no estimates.
 */
public final class WorkStealing implements Policy {

    @Override
    public String name() {
        return "work-stealing";
    }

    @Override
    public RunResult run(List<Task> tasks, int workers, TaskBody body) throws InterruptedException {
        List<List<Task>> initial = StaticRoundRobin.partition(tasks, workers);
        @SuppressWarnings("unchecked")
        ConcurrentLinkedDeque<Task>[] deques = new ConcurrentLinkedDeque[workers];
        for (int w = 0; w < workers; w++) {
            deques[w] = new ConcurrentLinkedDeque<>(initial.get(w));
        }
        return WorkerRunner.execute(workers, body, worker -> {
            Task own = deques[worker].pollFirst();
            if (own != null) {
                return own;
            }
            for (int k = 1; k < workers; k++) {
                Task stolen = deques[(worker + k) % workers].pollLast();
                if (stolen != null) {
                    return stolen;
                }
            }
            return null;
        });
    }
}
