package ppc.sharding;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** One shared FIFO queue in original order; an idle worker takes the next task. Needs no estimates. */
public final class DynamicQueue implements Policy {

    @Override
    public String name() {
        return "dynamic-queue";
    }

    @Override
    public RunResult run(List<Task> tasks, int workers, TaskBody body) throws InterruptedException {
        AtomicInteger next = new AtomicInteger();
        return WorkerRunner.execute(workers, body, worker -> {
            int i = next.getAndIncrement();
            return i < tasks.size() ? tasks.get(i) : null;
        });
    }
}
