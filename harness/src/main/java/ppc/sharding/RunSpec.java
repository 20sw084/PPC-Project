package ppc.sharding;

/**
 * One run of the grid: one policy in one cell, repetition {@code rep}.
 *
 * @param estError estimate-error level, or {@link Double#NaN} for an estimate-free policy (the single baseline
 *                 run per skew, workers and rep, which is compared against every error level of LPT)
 */
public record RunSpec(String policy, double skew, double estError, int workers, String variant, int rep) {

    /** Value of the est_error column for estimate-free (baseline) rows. */
    public static final String NOT_APPLICABLE = "NA";

    public boolean isBaseline() {
        return Double.isNaN(estError);
    }

    public String errorLabel() {
        return isBaseline() ? NOT_APPLICABLE : String.valueOf(estError);
    }

    /** Identity of the run inside a results file; used to skip finished runs when resuming. */
    public String key() {
        return variant + "," + policy + "," + skew + "," + errorLabel() + "," + workers + "," + rep;
    }
}
