"""Loading and pairing for the experiment grid CSVs written by ppc.sharding.ExperimentDriver.

Structure of the grid
---------------------
* Estimate-free policies (static-rr, dynamic-queue, work-stealing) never read duration estimates, so each is
  run ONCE per (variant, skew, workers, rep). Their est_error and noise_seed columns are "NA" (loaded as NaN).
* LPT is run once per estimate-error level for the same (variant, skew, workers, rep).

Because task sets depend only on (skew, rep), every LPT row has a baseline row from each estimate-free
policy on the *identical* tasks. ``paired`` joins them, which gives the paired design the tests rely on
(Wilcoxon signed-rank on the per-rep differences, not Mann-Whitney on independent groups).

Usage: python analysis/grid.py results/grid-timed.csv
"""
import sys

import pandas as pd

CELL = ["variant", "skew", "workers", "rep"]
ESTIMATE_FREE = ["static-rr", "dynamic-queue", "work-stealing"]


def load_grid(path):
    """Read a grid CSV. Rows cut short by an interrupted write are dropped (the driver redoes those runs)."""
    df = pd.read_csv(path, na_values=["NA"])
    complete = df[["makespan_ms", "lower_bound_ms", "overhead", "timestamp_utc"]].notna().all(axis=1)
    if (~complete).any():
        print(f"note: dropping {(~complete).sum()} incomplete row(s) from {path}", file=sys.stderr)
    df = df[complete].copy()
    df["is_baseline"] = df["est_error"].isna()
    keys = df[CELL + ["policy", "est_error"]].fillna({"est_error": -1})
    if keys.duplicated().any():
        raise ValueError(f"{path} has duplicate runs: {keys[keys.duplicated()].head().to_dict('records')}")
    return df


def baselines(df):
    """Estimate-free rows: exactly one per (policy, variant, skew, workers, rep)."""
    return df[df["is_baseline"]]


def lpt(df):
    """LPT rows: one per error level for each (variant, skew, workers, rep)."""
    return df[df["policy"] == "lpt"]


def paired(df, baseline_policy):
    """Each LPT row next to the same-task-set run of ``baseline_policy``.

    Columns: CELL, est_error, overhead_lpt, overhead_base, diff (= lpt - base; negative means LPT wins).
    """
    base = baselines(df)
    base = base[base["policy"] == baseline_policy]
    if base.empty:
        raise ValueError(f"no baseline rows for {baseline_policy!r}; was this file written by the new driver?")
    merged = lpt(df).merge(
        base[CELL + ["overhead", "idle_share", "makespan_ms"]],
        on=CELL, suffixes=("_lpt", "_base"), validate="many_to_one")
    merged["diff"] = merged["overhead_lpt"] - merged["overhead_base"]
    merged["baseline"] = baseline_policy
    return merged


def paired_all(df):
    """LPT against every estimate-free policy, stacked (column ``baseline`` says which)."""
    return pd.concat([paired(df, p) for p in ESTIMATE_FREE if p in set(baselines(df)["policy"])],
                     ignore_index=True)


def summary(df):
    print(f"{len(df)} runs: {int(df['is_baseline'].sum())} baseline, {len(lpt(df))} LPT")
    print("\nbaseline policies: mean overhead by workers x skew")
    print(baselines(df).pivot_table(index=["policy", "workers"], columns="skew", values="overhead",
                                    aggfunc="mean").round(4).to_string())
    print("\nLPT: mean overhead by est_error x workers")
    print(lpt(df).pivot_table(index="est_error", columns="workers", values="overhead",
                              aggfunc="mean").round(4).to_string())
    print("\nLPT minus dynamic-queue (paired mean diff; negative = LPT better) by est_error x workers")
    if "dynamic-queue" in set(baselines(df)["policy"]):
        print(paired(df, "dynamic-queue").pivot_table(index="est_error", columns="workers", values="diff",
                                                      aggfunc="mean").round(4).to_string())


if __name__ == "__main__":
    summary(load_grid(sys.argv[1]))
