package ppc.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pins the frozen study design, so a changed default cannot silently alter the full run. */
class GridConfigDefaultsTest {

    private static final List<Policy> POLICIES = Policies.all();

    @Test
    void timedDefaultsAreTheMainGrid() {
        GridConfig cfg = GridConfig.from(Map.of());
        assertEquals("timed", cfg.variant());
        assertEquals(200, cfg.n());
        assertEquals(40.0, cfg.medianMs());
        assertEquals(10.0, cfg.floorMs());
        assertEquals(30, cfg.reps());
        assertEquals(List.of(0.5, 1.0, 1.5), cfg.skews());
        assertEquals(List.of(0.0, 0.05, 0.1, 0.15, 0.25, 0.35, 0.5), cfg.errors());
        assertEquals(List.of(2, 4, 8, 16), cfg.workers());
        assertEquals(0.0, cfg.itersPerMs());
        assertEquals(3600, cfg.enumerate(POLICIES).size());
    }

    @Test
    void spinDefaultsAreTheOptionalRobustnessCheck() {
        GridConfig cfg = GridConfig.from(Map.of("variant", "spin"));
        assertEquals(10, cfg.reps());
        assertEquals(List.of(2, 4, 8), cfg.workers());
        assertEquals(690_624.0, cfg.itersPerMs());
        assertEquals(40.0, cfg.medianMs());
        assertEquals(10.0, cfg.floorMs());
        assertEquals(900, cfg.enumerate(POLICIES).size());
    }

    @Test
    void spinRateAppliesOnlyToTheSpinVariantAndFlagsStillOverrideDefaults() {
        assertEquals(0.0, GridConfig.from(Map.of("variant", "timed")).itersPerMs());
        GridConfig overridden = GridConfig.from(Map.of("variant", "spin", "reps", "3", "iters-per-ms", "500000",
                "workers", "4"));
        assertEquals(3, overridden.reps());
        assertEquals(500_000.0, overridden.itersPerMs());
        assertEquals(List.of(4), overridden.workers());
    }

    @Test
    void outputFileDefaultsToTheVariantName() {
        assertEquals("results/grid-timed.csv", GridConfig.from(Map.of()).out().toString().replace('\\', '/'));
        assertEquals("results/grid-spin.csv",
                GridConfig.from(Map.of("variant", "spin")).out().toString().replace('\\', '/'));
    }

    @Test
    void rejectsAnUnknownVariant() {
        assertThrows(IllegalArgumentException.class, () -> GridConfig.from(Map.of("variant", "gpu")));
    }
}
