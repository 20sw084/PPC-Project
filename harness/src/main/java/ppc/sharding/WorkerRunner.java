package ppc.sharding;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared execution engine: starts N platform threads, releases them together, lets each pull tasks from a
 * policy-specific {@link TaskSource} until it returns null, and measures makespan and per-worker times.
 * Policies differ only in their TaskSource, so all of them are timed by identical code.
 */
final class WorkerRunner {

    /** Hands a worker its next task, or null when that worker has nothing left to do. */
    interface TaskSource {
        Task next(int worker);
    }

    private WorkerRunner() {
    }

    static RunResult execute(int workers, TaskBody body, TaskSource source) throws InterruptedException {
        if (workers <= 0) {
            throw new IllegalArgumentException("workers must be positive");
        }
        long[] busy = new long[workers];
        long[] finishAbs = new long[workers];
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[workers];

        for (int w = 0; w < workers; w++) {
            final int id = w;
            threads[w] = Thread.ofPlatform().name("worker-" + w).start(() -> {
                ready.countDown();
                try {
                    start.await();
                    Task task;
                    while ((task = source.next(id)) != null) {
                        long begin = System.nanoTime();
                        body.execute(task);
                        busy[id] += System.nanoTime() - begin;
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
                finishAbs[id] = System.nanoTime();
            });
        }

        ready.await();
        long t0 = System.nanoTime();
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
        if (failure.get() != null) {
            throw new IllegalStateException("a worker failed", failure.get());
        }

        long[] finish = new long[workers];
        long makespan = 0;
        for (int w = 0; w < workers; w++) {
            finish[w] = finishAbs[w] - t0;
            makespan = Math.max(makespan, finish[w]);
        }
        return new RunResult(makespan, busy, finish);
    }

    /** Source for policies that fix the assignment up front: each worker walks its own list in order. */
    static TaskSource fixedAssignment(List<List<Task>> partition) {
        int[] cursor = new int[partition.size()]; // each worker only touches its own slot
        return worker -> {
            List<Task> mine = partition.get(worker);
            return cursor[worker] < mine.size() ? mine.get(cursor[worker]++) : null;
        };
    }
}
