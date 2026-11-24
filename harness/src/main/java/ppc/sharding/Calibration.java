package ppc.sharding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Measures whether the harness measures what it claims to, before any real experiment is run:
 * <ol>
 *   <li>how accurate sleep/park task durations are (Windows timer resolution),</li>
 *   <li>whether N parallel timed tasks really take one task's time even at N = 16 on 12 hardware threads,</li>
 *   <li>how fast the CPU-spin loop is, how stable that is across cores, and what oversubscription does to it,</li>
 *   <li>whether repeated identical runs drift (JIT warm-up, thermal throttling).</li>
 * </ol>
 * Run it plugged in, high-performance power plan, nothing else open:
 * <pre>java -cp target/classes ppc.sharding.Calibration [raw-samples.csv]</pre>
 * The last printed line gives the {@code --iters-per-ms} value for the spin variant of the driver.
 */
public final class Calibration {

    private static final List<String> RAW = new ArrayList<>(List.of("section,name,param,sample,value"));

    private Calibration() {
    }

    public static void main(String[] args) throws Exception {
        System.out.printf("Calibration  java=%s  logical CPUs=%d%n%n", System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors());

        sleepAccuracy("plain Thread.sleep", TaskBodies.sleepPlain());
        sleepAccuracy("parkNanos", TaskBodies.park());
        sleepAccuracy("timed (1 ms sleeps + 2 ms spin tail) <- used by the driver", TaskBodies.sleep());
        parallelTimedTasks();
        double itersPerMs = spinRate();
        spinAccuracy(itersPerMs);
        parallelSpinTasks(itersPerMs);
        drift();

        System.out.printf("%nUse for the spin variant: --iters-per-ms=%.0f%n", itersPerMs);
        if (args.length > 0) {
            Files.write(Path.of(args[0]), RAW);
            System.out.println("raw samples written to " + args[0]);
        }
    }

    // 1. single-task accuracy -------------------------------------------------------------------------------

    private static void sleepAccuracy(String name, TaskBody body) throws InterruptedException {
        System.out.println("== 1. " + name + " accuracy (single task, one thread) ==");
        System.out.println("target ms | samples | median err ms | p95 err ms | max err ms");
        double worstP95At50Plus = 0;
        for (int target : new int[] {1, 5, 10, 20, 50, 100, 200}) {
            int samples = target <= 20 ? 100 : target == 50 ? 40 : 20;
            double[] err = new double[samples];
            body.execute(new Task(0, target, target)); // discarded warm-up
            for (int i = 0; i < samples; i++) {
                long begin = System.nanoTime();
                body.execute(new Task(0, target, target));
                double ms = (System.nanoTime() - begin) / 1e6;
                err[i] = ms - target;
                RAW.add("accuracy," + name + "," + target + "," + i + "," + ms);
            }
            double[] sorted = err.clone();
            Arrays.sort(sorted);
            double p95 = percentile(sorted, 0.95);
            System.out.printf(Locale.ROOT, "%9d | %7d | %13.3f | %10.3f | %10.3f%n", target, samples,
                    percentile(sorted, 0.5), p95, sorted[sorted.length - 1]);
            if (target >= 50) {
                worstP95At50Plus = Math.max(worstP95At50Plus, p95);
            }
        }
        System.out.printf(Locale.ROOT, "-> worst p95 error for tasks >= 50 ms: %.3f ms (%s)%n%n",
                worstP95At50Plus, worstP95At50Plus <= 1.0 ? "accurate enough" : "TOO NOISY for this study");
    }

    // 2. parallel timed tasks -------------------------------------------------------------------------------

    private static void parallelTimedTasks() throws InterruptedException {
        System.out.println("== 2. N parallel 50 ms timed tasks (driver's timed body) on N workers "
                + "(ideal: 50 ms for every N) ==");
        System.out.println("workers | median ms | p95 ms | median excess ms");
        for (int n : new int[] {1, 2, 4, 8, 12, 16}) {
            double[] ms = parallelMakespans("parallel-sleep", n, 50, TaskBodies.sleep(), 30);
            System.out.printf(Locale.ROOT, "%7d | %9.2f | %6.2f | %16.2f%n", n, percentile(ms, 0.5),
                    percentile(ms, 0.95), percentile(ms, 0.5) - 50);
        }
        System.out.println();
    }

    private static double[] parallelMakespans(String section, int workers, double taskMs, TaskBody body, int reps)
            throws InterruptedException {
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            tasks.add(new Task(i, taskMs, taskMs));
        }
        Policy oneEach = new StaticRoundRobin(); // exactly one task per worker
        oneEach.run(tasks, workers, body); // discarded warm-up
        double[] ms = new double[reps];
        for (int r = 0; r < reps; r++) {
            ms[r] = oneEach.run(tasks, workers, body).makespanNanos() / 1e6;
            RAW.add(section + ",makespan," + workers + "," + r + "," + ms[r]);
        }
        Arrays.sort(ms);
        return ms;
    }

    // 3. CPU spin -------------------------------------------------------------------------------------------

    private static double spinRate() {
        System.out.println("== 3. CPU-spin rate (single thread, 15 measurements) ==");
        for (int i = 0; i < 3; i++) {
            TaskBodies.burn(50_000_000L); // JIT warm-up
        }
        long iterations = 50_000_000L;
        double[] rate = new double[15];
        for (int i = 0; i < rate.length; i++) {
            long begin = System.nanoTime();
            TaskBodies.burn(iterations);
            double ms = (System.nanoTime() - begin) / 1e6;
            rate[i] = iterations / ms;
            RAW.add("spin-rate,iters-per-ms,single," + i + "," + rate[i]);
        }
        Arrays.sort(rate);
        double median = percentile(rate, 0.5);
        System.out.printf(Locale.ROOT, "iters/ms: median %.0f, min %.0f, max %.0f (spread %.1f%%)%n", median,
                rate[0], rate[rate.length - 1], (rate[rate.length - 1] - rate[0]) / median * 100);
        System.out.println("-> a large spread means the thread hopped between P and E cores; report it as a threat");
        System.out.println();
        return median;
    }

    private static void spinAccuracy(double itersPerMs) throws InterruptedException {
        System.out.println("== 3b. spin accuracy at the calibrated rate (single thread) ==");
        System.out.println("target ms | median ms | p95 ms");
        TaskBody body = TaskBodies.spin(itersPerMs);
        for (int target : new int[] {10, 50, 100}) {
            double[] ms = new double[20];
            for (int i = 0; i < ms.length; i++) {
                long begin = System.nanoTime();
                body.execute(new Task(0, target, target));
                ms[i] = (System.nanoTime() - begin) / 1e6;
                RAW.add("spin-accuracy,spin," + target + "," + i + "," + ms[i]);
            }
            Arrays.sort(ms);
            System.out.printf(Locale.ROOT, "%9d | %9.2f | %6.2f%n", target, percentile(ms, 0.5), percentile(ms, 0.95));
        }
        System.out.println();
    }

    private static void parallelSpinTasks(double itersPerMs) throws InterruptedException {
        System.out.println("== 3c. N parallel 100 ms CPU tasks on N workers (oversubscription check) ==");
        System.out.println("workers | median ms | p95 ms | median / 100 ms");
        for (int n : new int[] {1, 2, 4, 8, 12, 16}) {
            double[] ms = parallelMakespans("parallel-spin", n, 100, TaskBodies.spin(itersPerMs), 15);
            System.out.printf(Locale.ROOT, "%7d | %9.2f | %6.2f | %15.2f%n", n, percentile(ms, 0.5),
                    percentile(ms, 0.95), percentile(ms, 0.5) / 100);
        }
        System.out.println("-> ratios well above 1.0 for N above the number of fast cores are the P/E-core and "
                + "oversubscription effect; spin results at N = 16 are reported separately");
        System.out.println();
    }

    // 4. drift ----------------------------------------------------------------------------------------------

    private static void drift() throws InterruptedException {
        System.out.println("== 4. drift: the same work-stealing run 30 times (N = 4, 60 timed tasks) ==");
        List<Task> tasks = TaskGenerator.generate(60, 20, 1.0, 10, 1L);
        double lowerBound = LowerBound.ms(tasks, 4);
        Policy policy = new WorkStealing();
        double[] overhead = new double[30];
        for (int i = 0; i < overhead.length; i++) {
            double ms = policy.run(tasks, 4, TaskBodies.sleep()).makespanNanos() / 1e6;
            overhead[i] = ms / lowerBound - 1;
            RAW.add("drift,overhead,4," + i + "," + overhead[i]);
        }
        System.out.printf(Locale.ROOT, "lower bound %.1f ms%n", lowerBound);
        System.out.printf(Locale.ROOT, "mean overhead, runs 1-5:   %.4f%n", mean(overhead, 0, 5));
        System.out.printf(Locale.ROOT, "mean overhead, runs 6-20:  %.4f%n", mean(overhead, 5, 20));
        System.out.printf(Locale.ROOT, "mean overhead, runs 21-30: %.4f%n", mean(overhead, 20, 30));
        System.out.println("-> if runs 1-5 are clearly higher, raise --warmup; if 21-30 climb, suspect throttling");
    }

    // helpers -----------------------------------------------------------------------------------------------

    /** Percentile of an array that is already sorted ascending (nearest-rank). */
    static double percentile(double[] sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private static double mean(double[] values, int from, int toExclusive) {
        double sum = 0;
        for (int i = from; i < toExclusive; i++) {
            sum += values[i];
        }
        return sum / (toExclusive - from);
    }
}
