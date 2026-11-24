"""Checks for analysis/stats.py. No pytest needed: python analysis/test_stats.py"""
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import stats as S  # noqa: E402  (our module, not scipy.stats)


def close(a, b, tol=1e-9):
    assert abs(a - b) <= tol, f"{a} != {b}"


def test_holm():
    adjusted = S.holm([0.01, 0.04, 0.03])
    assert np.allclose(adjusted, [0.03, 0.06, 0.06]), adjusted
    assert np.allclose(S.holm([0.5, 0.9]), [1.0, 1.0])  # capped at 1 and monotone


def test_rank_biserial_known_value():
    # |d| = 1,2,3,1 -> ranks 1.5,3,4,1.5 ; T+ = 1.5+3+4 = 8.5 ; T- = 1.5 -> (8.5-1.5)/10
    close(S.rank_biserial([1, 2, 3, -1]), 0.7)
    close(S.rank_biserial([-1, -2, -3]), -1.0)
    close(S.rank_biserial([0, 0]), 0.0)


def test_hodges_lehmann_known_value():
    close(S.hodges_lehmann([1, 2, 3]), 2.0)  # Walsh averages 1,1.5,2,2,2.5,3


def test_wilcoxon_all_zero_is_not_significant():
    assert S.wilcoxon_p([0, 0, 0]) == 1.0
    assert S.wilcoxon_p(np.linspace(0.1, 1.0, 30)) < 0.001


def test_crossover_interpolation_and_edge_cases():
    errors = [0.0, 0.1, 0.2]
    close(S.crossover(errors, [-0.2, -0.1, 0.1]), 0.15)
    assert S.crossover(errors, [-0.2, -0.1, -0.05]) == np.inf  # right-censored
    assert S.crossover(errors, [0.01, 0.02, 0.03]) == 0.0  # no advantage even with exact estimates
    close(S.crossover(errors, [-0.1, 0.0, 0.1]), 0.1)  # exact zero counts as the crossing


def test_bootstrap_is_deterministic_and_covers_the_mean():
    d = np.random.default_rng(1).normal(-0.05, 0.02, 30)
    a = S.bootstrap_mean_ci(d, np.random.default_rng(7))
    assert a == S.bootstrap_mean_ci(d, np.random.default_rng(7))
    assert a[0] < d.mean() < a[1]
    lo, hi, censored = S.bootstrap_crossover([0, 0.5], np.tile([-0.1, -0.1], (30, 1)), np.random.default_rng(1), 200)
    assert censored == 1.0 and np.isinf(hi)


def synthetic_grid(reps=30):
    """LPT overhead = baseline + delta(error): -0.05 at error 0, +0.05 at 0.5 -> crossover exactly at 0.25."""
    rng = np.random.default_rng(3)
    rows = []
    for rep in range(reps):
        base = 0.2 + rng.normal(0, 0.03)
        for policy in ["static-rr", "dynamic-queue", "work-stealing"]:
            rows.append(dict(variant="timed", policy=policy, skew=1.0, est_error=np.nan, workers=4, rep=rep,
                             overhead=base + (0.3 if policy == "static-rr" else 0.0), idle_share=0.1,
                             makespan_ms=1000.0, is_baseline=True))
        for err in [0.0, 0.25, 0.5]:
            rows.append(dict(variant="timed", policy="lpt", skew=1.0, est_error=err, workers=4, rep=rep,
                             overhead=base - 0.05 + 0.2 * err + rng.normal(0, 0.005), idle_share=0.1,
                             makespan_ms=1000.0, is_baseline=False))
    return pd.DataFrame(rows)


def test_paired_tests_recover_the_planted_effect():
    df = synthetic_grid()
    tests = S.paired_tests(df, "dynamic-queue", np.random.default_rng(1))
    by_err = tests.set_index("est_error")
    assert len(tests) == 3 and (tests["n_pairs"] == 30).all()
    assert by_err.loc[0.0, "verdict"] == "LPT better" and by_err.loc[0.0, "rank_biserial"] < -0.9
    assert by_err.loc[0.5, "verdict"] == "LPT worse" and by_err.loc[0.5, "rank_biserial"] > 0.9
    close(by_err.loc[0.0, "mean_diff"], -0.05, 0.01)
    assert by_err.loc[0.0, "mean_ci_lo"] < by_err.loc[0.0, "mean_diff"] < by_err.loc[0.0, "mean_ci_hi"]
    assert (tests["holm_p"] >= tests["wilcoxon_p"] - 1e-12).all()


def test_crossover_table_finds_the_planted_crossover():
    df = synthetic_grid()
    cross = S.crossovers(df, "dynamic-queue", np.random.default_rng(2), boots=500)
    close(cross["_point"].iloc[0], 0.25, 0.02)


def test_rq1_tables_shapes_and_ordering():
    summary, friedman, pairs = S.rq1_tables(synthetic_grid())
    assert len(friedman) == 1 and len(pairs) == 6  # 4 policies -> 6 pairs per (skew, N)
    assert friedman["worst_policy"].iloc[0] == "static-rr" and friedman["best_policy"].iloc[0] == "lpt"
    assert friedman["friedman_p"].iloc[0] < 0.001


if __name__ == "__main__":
    tests = [(name, fn) for name, fn in sorted(globals().items()) if name.startswith("test_")]
    for name, fn in tests:
        fn()
        print("ok  ", name)
    print(f"{len(tests)} checks passed")
