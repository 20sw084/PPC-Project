package ppc.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperimentDriverTest {

    private static final PrintStream QUIET = new PrintStream(OutputStream.nullOutputStream());
    private static final TaskBody INSTANT = t -> { };
    private static final List<Policy> POLICIES = Policies.all();

    @TempDir
    Path dir;

    private GridConfig config(int n, Path out) {
        // 3 estimate-free policies x (2 skews x 2 worker counts x 3 reps) = 36 baseline runs
        // + LPT x 2 error levels x (same) = 24 runs -> 60 runs
        return new GridConfig(n, 10, 5, 3, List.of(0.5, 1.0), List.of(0.0, 0.25), List.of(2, 4),
                "timed", 7L, 0, 1, out);
    }

    private List<String> dataLines(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        return lines.subList(1, lines.size());
    }

    @Test
    void enumeratesTheWholeGrid() {
        assertEquals(60, config(20, dir.resolve("a.csv")).enumerate(POLICIES).size());
    }

    @Test
    void estimateFreePoliciesRunOncePerCellWhileLptRunsOncePerErrorLevel() {
        List<RunSpec> specs = config(20, dir.resolve("a.csv")).enumerate(POLICIES);
        for (Policy p : POLICIES) {
            long perCell = specs.stream()
                    .filter(s -> s.policy().equals(p.name()) && s.skew() == 0.5 && s.workers() == 2 && s.rep() == 1)
                    .count();
            assertEquals(p.usesEstimates() ? 2 : 1, perCell, p.name());
        }
        assertTrue(specs.stream().filter(s -> !s.policy().equals("lpt")).allMatch(RunSpec::isBaseline));
        assertTrue(specs.stream().filter(s -> s.policy().equals("lpt")).noneMatch(RunSpec::isBaseline));
    }

    @Test
    void onlyLptReadsEstimates() {
        for (Policy p : POLICIES) {
            assertEquals(p.name().equals("lpt"), p.usesEstimates(), p.name());
        }
    }

    @Test
    void baselineRowsCarryNaInsteadOfAnErrorLevelAndEveryLptRowHasItsBaselines() throws Exception {
        Path out = dir.resolve("grid.csv");
        ExperimentDriver.run(config(20, out), POLICIES, INSTANT, Integer.MAX_VALUE, QUIET);

        Set<String> baselineCells = new HashSet<>(); // skew,workers,rep,policy -> must be unique per cell
        Set<String> baselineCellsWithoutPolicy = new HashSet<>();
        Set<String> lptCells = new HashSet<>();
        for (String row : dataLines(out)) {
            String[] f = row.split(",", -1);
            String cell = f[2] + "," + f[4] + "," + f[5];
            if (f[1].equals("lpt")) {
                assertTrue(!f[3].equals("NA") && !f[9].equals("NA"), "lpt row must have an error and noise seed");
                lptCells.add(cell);
            } else {
                assertEquals("NA", f[3]);
                assertEquals("NA", f[9]);
                assertTrue(baselineCells.add(cell + "," + f[1]), "baseline policy repeated in a cell: " + row);
                baselineCellsWithoutPolicy.add(cell);
            }
        }
        assertEquals(lptCells, baselineCellsWithoutPolicy);
        assertEquals(36, baselineCells.size());
    }

    @Test
    void seedsDependOnSkewAndRepButNotOnPolicyOrWorkers() {
        GridConfig cfg = config(20, dir.resolve("a.csv"));
        assertEquals(cfg.taskSeed(0.5, 1), cfg.taskSeed(0.5, 1));
        assertNotEquals(cfg.taskSeed(0.5, 1), cfg.taskSeed(1.0, 1));
        assertNotEquals(cfg.taskSeed(0.5, 1), cfg.taskSeed(0.5, 2));
        assertNotEquals(cfg.noiseSeed(0.5, 0.1, 1), cfg.noiseSeed(0.5, 0.25, 1));
        assertEquals(cfg.noiseSeed(0.5, 0.1, 1), cfg.noiseSeed(0.5, 0.1, 1));
    }

    @Test
    void planOrderIsShuffledButReproducible() {
        GridConfig cfg = config(20, dir.resolve("a.csv"));
        List<RunSpec> a = ExperimentDriver.plan(cfg, POLICIES);
        assertEquals(a, ExperimentDriver.plan(cfg, POLICIES));
        assertNotEquals(cfg.enumerate(POLICIES), a);
        assertEquals(new HashSet<>(cfg.enumerate(POLICIES)), new HashSet<>(a));
    }

    @Test
    void writesOneUniqueRowPerRunWithAHeader() throws Exception {
        Path out = dir.resolve("grid.csv");
        int executed = ExperimentDriver.run(config(20, out), POLICIES, INSTANT, Integer.MAX_VALUE, QUIET);

        assertEquals(60, executed);
        assertEquals(ResultsFile.HEADER, Files.readAllLines(out).get(0));
        List<String> rows = dataLines(out);
        assertEquals(60, rows.size());
        Set<String> keys = new HashSet<>();
        for (String row : rows) {
            String[] f = row.split(",", -1);
            assertEquals(ResultsFile.HEADER.split(",").length, f.length);
            keys.add(String.join(",", f[0], f[1], f[2], f[3], f[4], f[5]));
        }
        assertEquals(60, keys.size());
    }

    @Test
    void runsOfTheSameCellShareTheSameTaskSeedAcrossPolicies() throws Exception {
        Path out = dir.resolve("grid.csv");
        ExperimentDriver.run(config(20, out), POLICIES, INSTANT, Integer.MAX_VALUE, QUIET);
        // same skew + rep -> same task_seed (column 8), whatever the policy, error or worker count
        Set<String> seedsForSkew05Rep1 = new HashSet<>();
        for (String row : dataLines(out)) {
            String[] f = row.split(",", -1);
            if (f[2].equals("0.5") && f[5].equals("1")) {
                seedsForSkew05Rep1.add(f[8]);
            }
        }
        assertEquals(1, seedsForSkew05Rep1.size());
    }

    @Test
    void secondRunSkipsEverythingAlreadyDone() throws Exception {
        Path out = dir.resolve("grid.csv");
        GridConfig cfg = config(20, out);
        ExperimentDriver.run(cfg, POLICIES, INSTANT, Integer.MAX_VALUE, QUIET);
        assertEquals(0, ExperimentDriver.run(cfg, POLICIES, INSTANT, Integer.MAX_VALUE, QUIET));
        assertEquals(60, dataLines(out).size());
    }

    @Test
    void resumeFillsOnlyTheMissingRuns() throws Exception {
        Path out = dir.resolve("grid.csv");
        GridConfig cfg = config(20, out);
        ExperimentDriver.run(cfg, POLICIES, INSTANT, Integer.MAX_VALUE, QUIET);

        List<String> lines = Files.readAllLines(out);
        Files.write(out, lines.subList(0, lines.size() - 5)); // simulate an interrupted session

        assertEquals(5, ExperimentDriver.run(cfg, POLICIES, INSTANT, Integer.MAX_VALUE, QUIET));
        assertEquals(60, new HashSet<>(dataLines(out).stream()
                .map(r -> r.split(",", -1)).map(f -> String.join(",", f[0], f[1], f[2], f[3], f[4], f[5]))
                .toList()).size());
    }

    @Test
    void aTornLastLineIsRedoneNotTrusted() throws Exception {
        Path out = dir.resolve("grid.csv");
        GridConfig cfg = config(20, out);
        ExperimentDriver.run(cfg, POLICIES, INSTANT, 10, QUIET);
        Files.writeString(out, "timed,lpt,0.5,0.0,2,0,20,10.0,123", java.nio.file.StandardOpenOption.APPEND);

        assertEquals(50, ExperimentDriver.run(cfg, POLICIES, INSTANT, Integer.MAX_VALUE, QUIET));
        // the torn fragment stays as one malformed line; every other row must be intact (none glued to it)
        long intact = dataLines(out).stream()
                .filter(r -> r.split(",", -1).length == ResultsFile.HEADER.split(",").length).count();
        assertEquals(60, intact);
    }

    @Test
    void limitCapsTheSessionAndALaterSessionContinues() throws Exception {
        Path out = dir.resolve("grid.csv");
        GridConfig cfg = config(20, out);
        assertEquals(10, ExperimentDriver.run(cfg, POLICIES, INSTANT, 10, QUIET));
        assertEquals(50, ExperimentDriver.run(cfg, POLICIES, INSTANT, Integer.MAX_VALUE, QUIET));
        assertEquals(60, dataLines(out).size());
    }

    @Test
    void refusesToMixResultsFromADifferentTaskCount() throws Exception {
        Path out = dir.resolve("grid.csv");
        ExperimentDriver.run(config(20, out), POLICIES, INSTANT, 5, QUIET);
        assertThrows(IllegalStateException.class,
                () -> ExperimentDriver.run(config(30, out), POLICIES, INSTANT, 5, QUIET));
    }

    @Test
    void refusesAFileWithAnUnexpectedHeader() throws Exception {
        Path out = dir.resolve("grid.csv");
        Files.writeString(out, "not,our,header\n");
        assertThrows(IllegalStateException.class,
                () -> ExperimentDriver.run(config(20, out), POLICIES, INSTANT, 5, QUIET));
    }

    @Test
    void estimatedDurationGrowsWithRepetitions() {
        GridConfig one = new GridConfig(50, 100, 50, 1, List.of(1.0), List.of(0.0), List.of(4), "timed", 1L, 0, 1,
                dir.resolve("x.csv"));
        GridConfig four = new GridConfig(50, 100, 50, 4, List.of(1.0), List.of(0.0), List.of(4), "timed", 1L, 0, 1,
                dir.resolve("x.csv"));
        assertTrue(ExperimentDriver.estimateSeconds(four, POLICIES) > 3 * ExperimentDriver.estimateSeconds(one, POLICIES) * 0.5);
        assertTrue(ExperimentDriver.estimateSeconds(one, POLICIES) > 0);
    }

    @Test
    void overheadIsMakespanOverLowerBoundMinusOne() throws Exception {
        Path out = dir.resolve("grid.csv");
        ExperimentDriver.run(config(20, out), POLICIES, INSTANT, 3, QUIET);
        for (String row : dataLines(out)) {
            String[] f = row.split(",", -1);
            double makespan = Double.parseDouble(f[10]);
            double lowerBound = Double.parseDouble(f[11]);
            assertEquals(makespan / lowerBound - 1.0, Double.parseDouble(f[12]), 1e-5);
        }
    }
}
