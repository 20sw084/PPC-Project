package ppc.sharding;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Runs the experiment grid: every run once, in a seeded random order (so thermal drift is not tied to one
 * policy), one CSV row per run, resumable. Runs are strictly sequential: two runs at once would disturb
 * each other's timing.
 *
 * <pre>
 *   java -cp target/classes ppc.sharding.ExperimentDriver --dry-run
 *   java -cp target/classes ppc.sharding.ExperimentDriver --reps=30 --out=results/grid-timed.csv
 * </pre>
 */
public final class ExperimentDriver {

    private static final double PER_RUN_OVERHEAD_SECONDS = 0.05; // thread start, bookkeeping, file append

    private ExperimentDriver() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> kv = parseArgs(args);
        Set<String> allowed = new HashSet<>(GridConfig.KEYS);
        allowed.add("dry-run");
        allowed.add("limit");
        for (String key : kv.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("unknown option --" + key + "; allowed: " + allowed);
            }
        }
        GridConfig cfg = GridConfig.from(kv);
        List<Policy> policies = Policies.all();

        if (kv.containsKey("dry-run")) {
            printPlan(cfg, policies, System.out);
            return;
        }
        TaskBody body = switch (cfg.variant()) {
            case "timed" -> TaskBodies.sleep();
            case "spin" -> {
                if (cfg.itersPerMs() <= 0) {
                    throw new IllegalArgumentException("--variant=spin needs --iters-per-ms from Calibration");
                }
                yield TaskBodies.spin(cfg.itersPerMs());
            }
            default -> throw new IllegalArgumentException("variant must be timed or spin");
        };
        int limit = kv.containsKey("limit") ? Integer.parseInt(kv.get("limit")) : Integer.MAX_VALUE;
        int executed = run(cfg, policies, body, limit, System.out);
        System.out.println("done: " + executed + " runs executed this session -> " + cfg.out());
    }

    /** The full run list in the seeded random execution order. */
    static List<RunSpec> plan(GridConfig cfg, List<Policy> policies) {
        List<RunSpec> specs = cfg.enumerate(policies);
        Collections.shuffle(specs, new Random(cfg.seed()));
        return specs;
    }

    /** Executes the runs not yet in the results file (at most {@code limit}); returns how many ran. */
    static int run(GridConfig cfg, List<Policy> policies, TaskBody body, int limit, PrintStream log)
            throws IOException, InterruptedException {
        ResultsFile file = new ResultsFile(cfg.out());
        Set<String> done = file.openAndLoadDoneKeys(cfg.n(), cfg.medianMs());
        Map<String, Policy> byName = new HashMap<>();
        policies.forEach(p -> byName.put(p.name(), p));

        List<RunSpec> todo = new ArrayList<>();
        for (RunSpec spec : plan(cfg, policies)) {
            if (!done.contains(spec.key())) {
                todo.add(spec);
            }
        }
        if (todo.size() > limit) {
            todo = new ArrayList<>(todo.subList(0, limit));
        }
        log.printf("%d runs already done, %d to execute%n", done.size(), todo.size());
        if (todo.isEmpty()) {
            return 0;
        }

        warmUp(cfg, policies, body);

        long begin = System.nanoTime();
        int count = 0;
        for (RunSpec spec : todo) {
            file.append(execute(cfg, byName.get(spec.policy()), body, spec));
            count++;
            if (count % 50 == 0 || count == todo.size()) {
                double elapsed = (System.nanoTime() - begin) / 1e9;
                double eta = elapsed / count * (todo.size() - count);
                log.printf("%d/%d runs, %.0f s elapsed, ~%.0f s left%n", count, todo.size(), elapsed, eta);
            }
        }
        return count;
    }

    private static String execute(GridConfig cfg, Policy policy, TaskBody body, RunSpec spec)
            throws InterruptedException {
        long taskSeed = cfg.taskSeed(spec.skew(), spec.rep());
        List<Task> tasks = TaskGenerator.generate(cfg.n(), cfg.medianMs(), spec.skew(), cfg.floorMs(), taskSeed);
        String noiseSeed = RunSpec.NOT_APPLICABLE; // baseline rows: estimates are never read, so no noise
        if (!spec.isBaseline()) {
            long seed = cfg.noiseSeed(spec.skew(), spec.estError(), spec.rep());
            tasks = EstimateNoise.apply(tasks, spec.estError(), seed);
            noiseSeed = String.valueOf(seed);
        }
        double lowerBound = LowerBound.ms(tasks, spec.workers());

        RunResult result = policy.run(tasks, spec.workers(), body);

        double makespan = result.makespanNanos() / 1e6;
        return String.format(Locale.ROOT, "%s,%s,%s,%s,%d,%d,%d,%s,%d,%s,%.4f,%.4f,%.6f,%.6f,%s",
                spec.variant(), spec.policy(), spec.skew(), spec.errorLabel(), spec.workers(), spec.rep(),
                cfg.n(), cfg.medianMs(), taskSeed, noiseSeed, makespan, lowerBound,
                makespan / lowerBound - 1.0, result.idleShare(), Instant.now());
    }

    /** Short discarded runs so class loading and JIT compilation happen before the first measured run. */
    private static void warmUp(GridConfig cfg, List<Policy> policies, TaskBody body) throws InterruptedException {
        List<Task> tasks = TaskGenerator.generate(40, 2, 1.0, 1, cfg.seed());
        for (int i = 0; i < cfg.warmupRuns(); i++) {
            for (Policy p : policies) {
                p.run(tasks, 4, body);
            }
        }
    }

    /** Expected duration: the lower bound approximates each makespan; real runs come out somewhat longer. */
    static double estimateSeconds(GridConfig cfg, List<Policy> policies) {
        double seconds = 0;
        for (double skew : cfg.skews()) {
            seconds += estimateSecondsForSkew(cfg, policies, skew);
        }
        return seconds;
    }

    private static double estimateSecondsForSkew(GridConfig cfg, List<Policy> policies, double skew) {
        int runsPerCell = 0; // runs of every policy for one (skew, workers, rep)
        for (Policy p : policies) {
            runsPerCell += p.usesEstimates() ? cfg.errors().size() : 1;
        }
        double seconds = 0;
        for (int rep = 0; rep < cfg.reps(); rep++) {
            List<Task> tasks = TaskGenerator.generate(cfg.n(), cfg.medianMs(), skew, cfg.floorMs(),
                    cfg.taskSeed(skew, rep));
            for (int w : cfg.workers()) {
                double perRun = LowerBound.ms(tasks, w) / 1000.0 + PER_RUN_OVERHEAD_SECONDS;
                seconds += perRun * runsPerCell;
            }
        }
        return seconds;
    }

    private static void printPlan(GridConfig cfg, List<Policy> policies, PrintStream out) {
        long baseline = policies.stream().filter(p -> !p.usesEstimates()).count();
        long estimating = policies.size() - baseline;
        long cells = (long) cfg.skews().size() * cfg.workers().size() * cfg.reps(); // (skew, workers, rep)
        out.printf("variant=%s n=%d median=%.0f ms floor=%.0f ms reps=%d%n",
                cfg.variant(), cfg.n(), cfg.medianMs(), cfg.floorMs(), cfg.reps());
        out.printf("runs: %d = %d baseline (%d estimate-free policies x %d skews x %d worker counts x %d reps)"
                        + " + %d estimating (%d policies x %d error levels x same)%n",
                cfg.enumerate(policies).size(), baseline * cells, baseline, cfg.skews().size(),
                cfg.workers().size(), cfg.reps(), estimating * cfg.errors().size() * cells, estimating,
                cfg.errors().size());
        for (double skew : cfg.skews()) {
            out.printf("  skew %.2f: ~%.2f h%n", skew, estimateSecondsForSkew(cfg, policies, skew) / 3600.0);
        }
        out.printf("total: ~%.2f h (lower-bound estimate; plan for 10-20%% more)%n",
                estimateSeconds(cfg, policies) / 3600.0);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> kv = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("expected --key[=value], got " + arg);
            }
            int eq = arg.indexOf('=');
            if (eq < 0) {
                kv.put(arg.substring(2), "");
            } else {
                kv.put(arg.substring(2, eq), arg.substring(eq + 1));
            }
        }
        return kv;
    }
}
