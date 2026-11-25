package ppc.sharding;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything that defines an experiment grid. Task sets are seeded by (skew, rep) only, so every policy,
 * every worker count and every estimate-error level sees the same tasks: a paired design.
 */
public record GridConfig(
        int n,
        double medianMs,
        double floorMs,
        int reps,
        List<Double> skews,
        List<Double> errors,
        List<Integer> workers,
        String variant,
        long seed,
        double itersPerMs,
        int warmupRuns,
        Path out) {

    public static final Set<String> KEYS = Set.of(
            "n", "median-ms", "floor-ms", "reps", "skews", "errors", "workers",
            "variant", "seed", "iters-per-ms", "warmup", "out");

    /** CPU-spin rate from the 25 Nov 2026 calibration (results/calibration-raw.csv), the rate the spin grid was run with; used by the spin variant only. */
    public static final double CALIBRATED_ITERS_PER_MS = 749_596;

    /**
     * Defaults match the frozen study design (see docs/DESIGN_DECISIONS_QA.md). The timed variant is the main
     * grid: 30 reps, N in {2,4,8,16}. The spin variant is the optional robustness check: 10 reps, N in
     * {2,4,8} (no N = 16, which oversubscribes the 12 hardware threads), at the calibrated spin rate.
     */
    public static GridConfig from(Map<String, String> kv) {
        String variant = kv.getOrDefault("variant", "timed");
        if (!variant.equals("timed") && !variant.equals("spin")) {
            throw new IllegalArgumentException("variant must be timed or spin, got " + variant);
        }
        boolean spin = variant.equals("spin");
        return new GridConfig(
                Integer.parseInt(kv.getOrDefault("n", "200")),
                Double.parseDouble(kv.getOrDefault("median-ms", "40")),
                Double.parseDouble(kv.getOrDefault("floor-ms", "10")),
                Integer.parseInt(kv.getOrDefault("reps", spin ? "10" : "30")),
                doubles(kv.getOrDefault("skews", "0.5,1,1.5")),
                doubles(kv.getOrDefault("errors", "0,0.05,0.1,0.15,0.25,0.35,0.5")),
                ints(kv.getOrDefault("workers", spin ? "2,4,8" : "2,4,8,16")),
                variant,
                Long.parseLong(kv.getOrDefault("seed", "20261117")),
                Double.parseDouble(kv.getOrDefault("iters-per-ms", spin ? String.valueOf(CALIBRATED_ITERS_PER_MS) : "0")),
                Integer.parseInt(kv.getOrDefault("warmup", "3")),
                Path.of(kv.getOrDefault("out", "results/grid-" + variant + ".csv")));
    }

    /**
     * All runs of the grid in a fixed, unshuffled order. Policies that read estimates run once per error
     * level; estimate-free policies run once per (skew, workers, rep) as a baseline (error = NaN).
     */
    public List<RunSpec> enumerate(List<Policy> policies) {
        List<RunSpec> specs = new ArrayList<>();
        for (Policy p : policies) {
            List<Double> levels = p.usesEstimates() ? errors : List.of(Double.NaN);
            for (double skew : skews) {
                for (double err : levels) {
                    for (int w : workers) {
                        for (int rep = 0; rep < reps; rep++) {
                            specs.add(new RunSpec(p.name(), skew, err, w, variant, rep));
                        }
                    }
                }
            }
        }
        return specs;
    }

    public long taskSeed(double skew, int rep) {
        return mix(seed ^ mix(Double.doubleToLongBits(skew)) ^ mix(rep + 1L));
    }

    public long noiseSeed(double skew, double err, int rep) {
        return mix(taskSeed(skew, rep) ^ mix(Double.doubleToLongBits(err) + 17L));
    }

    /** SplitMix64 finaliser: turns structured inputs into well-spread seeds. */
    static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static List<Double> doubles(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).map(Double::parseDouble).toList();
    }

    private static List<Integer> ints(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).map(Integer::parseInt).toList();
    }
}
