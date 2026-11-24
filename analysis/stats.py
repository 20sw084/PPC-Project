"""Statistical analysis of the experiment grid (RQ1 and RQ2), tables and figures.

    python analysis/stats.py                                    # results/grid-timed.csv -> results/analysis/
    python analysis/stats.py --grid results/grid-spin.csv --out results/analysis-spin

Design (see docs/DESIGN_DECISIONS_QA.md): all runs for one (skew, rep) use the same task set, so every
comparison is PAIRED. The independent unit is the repetition (a fresh random task set).

RQ2  How wrong may the duration estimates be before LPT loses its advantage?
     For every (skew, N, error level): per-rep difference overhead_LPT - overhead_baseline,
     Wilcoxon signed-rank test, matched-pairs rank-biserial effect size, Hodges-Lehmann median difference
     and bootstrap CIs, Holm correction. Primary baseline: dynamic-queue (family of one test per
     skew x N x error); work-stealing and static-rr are secondary families, each Holm-corrected on its own.
     Crossover = interpolated error level where the mean paired difference crosses zero, bootstrap CI.
RQ1  How far above the lower bound is each policy, as skew and N grow?
     Median/IQR tables, Friedman test (Kendall's W) across policies per (skew, N), pairwise paired Wilcoxon
     with Holm.

Negative differences / effect sizes mean LPT has the LOWER overhead (LPT is better).
"""
import argparse
import itertools
import sys
import warnings
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy import stats  # noqa: E402

sys.path.insert(0, str(Path(__file__).parent))
import grid  # noqa: E402

ALPHA = 0.05
PRIMARY = "dynamic-queue"
SECONDARY = ["work-stealing", "static-rr"]
RQ1_POLICIES = ["static-rr", "dynamic-queue", "work-stealing", "lpt"]  # lpt = exact estimates (error 0)
COLORS = {"static-rr": "#d62728", "dynamic-queue": "#1f77b4", "work-stealing": "#2ca02c", "lpt": "#ff7f0e"}


# --------------------------------------------------------------------------------------------------------
# statistics helpers
# --------------------------------------------------------------------------------------------------------

def holm(p):
    """Holm step-down adjusted p-values (same order as the input)."""
    p = np.asarray(p, float)
    m = len(p)
    adjusted = np.empty(m)
    running = 0.0
    for rank, idx in enumerate(np.argsort(p)):
        running = max(running, (m - rank) * p[idx])
        adjusted[idx] = min(1.0, running)
    return adjusted


def wilcoxon_p(d):
    """Two-sided Wilcoxon signed-rank p-value on paired differences (1.0 when every difference is zero)."""
    d = np.asarray(d, float)
    if np.all(d == 0):
        return 1.0
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        return float(stats.wilcoxon(d, zero_method="wilcox").pvalue)


def rank_biserial(d):
    """Matched-pairs rank-biserial correlation (T+ - T-) / (T+ + T-), zeros dropped. Range [-1, 1]."""
    d = np.asarray(d, float)
    d = d[d != 0]
    if d.size == 0:
        return 0.0
    ranks = stats.rankdata(np.abs(d))
    t_plus, t_minus = ranks[d > 0].sum(), ranks[d < 0].sum()
    return float((t_plus - t_minus) / (t_plus + t_minus))


def hodges_lehmann(d):
    """Median of the Walsh averages: the one-sample location estimate that matches the signed-rank test."""
    d = np.asarray(d, float)
    i, j = np.triu_indices(d.size)
    return float(np.median((d[i] + d[j]) / 2.0))


def bootstrap_mean_ci(d, rng, boots=10000):
    d = np.asarray(d, float)
    means = d[rng.integers(0, d.size, (boots, d.size))].mean(axis=1)
    return float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5))


def bootstrap_hl_ci(d, rng, boots=2000):
    d = np.asarray(d, float)
    i, j = np.triu_indices(d.size)
    sample = d[rng.integers(0, d.size, (boots, d.size))]
    hl = np.median((sample[:, i] + sample[:, j]) / 2.0, axis=1)
    return float(np.percentile(hl, 2.5)), float(np.percentile(hl, 97.5))


def crossover(errors, mean_diff):
    """Error level where the mean paired difference (LPT - baseline) first goes from negative to >= 0.

    Linear interpolation between grid levels. Returns 0.0 when LPT has no advantage even with exact
    estimates (difference >= 0 at the first level) and +inf when LPT stays better at every tested level
    (right-censored: the crossover lies beyond the grid).
    """
    errors = np.asarray(errors, float)
    diff = np.asarray(mean_diff, float)
    if diff[0] >= 0:
        return 0.0
    for k in range(1, len(diff)):
        if diff[k] >= 0:
            e0, e1, d0, d1 = errors[k - 1], errors[k], diff[k - 1], diff[k]
            return float(e0 + (e1 - e0) * (-d0) / (d1 - d0))
    return float("inf")


def bootstrap_crossover(errors, rep_by_error, rng, boots=5000):
    """Bootstrap the crossover by resampling repetitions (rows). Returns (lo, hi, share censored), sorted CI."""
    matrix = np.asarray(rep_by_error, float)
    reps = matrix.shape[0]
    means = matrix[rng.integers(0, reps, (boots, reps))].mean(axis=1)  # (boots, levels)
    values = np.sort([crossover(errors, row) for row in means])  # inf sorts last
    lo = values[int(0.025 * boots)]
    hi = values[min(boots - 1, int(0.975 * boots))]
    return float(lo), float(hi), float(np.isinf(values).mean())


def fmt_cross(value, max_error):
    return f">{max_error:g}" if np.isinf(value) else f"{value:.3f}"


# --------------------------------------------------------------------------------------------------------
# RQ2
# --------------------------------------------------------------------------------------------------------

def paired_tests(df, baseline, rng, hl_ci=True):
    """One row per (skew, workers, est_error): LPT vs `baseline` on identical task sets, Holm-corrected."""
    pairs = grid.paired(df, baseline)
    rows = []
    for (skew, workers, err), g in pairs.groupby(["skew", "workers", "est_error"]):
        d = g.sort_values("rep")["diff"].to_numpy()
        lo, hi = bootstrap_mean_ci(d, rng)
        row = {
            "baseline": baseline, "skew": skew, "workers": workers, "est_error": err, "n_pairs": d.size,
            "mean_diff": d.mean(), "mean_ci_lo": lo, "mean_ci_hi": hi,
            "hodges_lehmann": hodges_lehmann(d), "rank_biserial": rank_biserial(d),
            "n_nonzero": int((d != 0).sum()), "wilcoxon_p": wilcoxon_p(d),
            "lpt_median_overhead": g["overhead_lpt"].median(), "base_median_overhead": g["overhead_base"].median(),
        }
        if hl_ci:
            row["hl_ci_lo"], row["hl_ci_hi"] = bootstrap_hl_ci(d, rng)
        rows.append(row)
    out = pd.DataFrame(rows)
    out["holm_p"] = holm(out["wilcoxon_p"].to_numpy())
    out["verdict"] = np.where(out["holm_p"] >= ALPHA, "n.s.",
                              np.where(out["mean_diff"] < 0, "LPT better", "LPT worse"))
    return out


def crossovers(df, baseline, rng, boots=5000):
    pairs = grid.paired(df, baseline)
    rows = []
    for (skew, workers), g in pairs.groupby(["skew", "workers"]):
        matrix = g.pivot_table(index="rep", columns="est_error", values="diff")
        errors = matrix.columns.to_numpy(float)
        point = crossover(errors, matrix.mean(axis=0).to_numpy())
        lo, hi, censored = bootstrap_crossover(errors, matrix.to_numpy(), rng, boots)
        top = errors.max()
        rows.append({"baseline": baseline, "skew": skew, "workers": workers, "crossover": fmt_cross(point, top),
                     "ci_lo": fmt_cross(lo, top), "ci_hi": fmt_cross(hi, top),
                     "share_boots_censored": censored, "max_error_tested": top, "_point": point})
    return pd.DataFrame(rows)


# --------------------------------------------------------------------------------------------------------
# RQ1
# --------------------------------------------------------------------------------------------------------

def rq1_frame(df):
    """Per rep and (skew, workers): overhead of the four policies (LPT at exact estimates) and LPT at error 0.25."""
    base = grid.baselines(df)[["skew", "workers", "rep", "policy", "overhead"]]
    lpt_rows = grid.lpt(df)
    exact = lpt_rows[lpt_rows["est_error"] == 0.0][["skew", "workers", "rep", "overhead"]].assign(policy="lpt")
    frame = pd.concat([base, exact], ignore_index=True)
    mid = lpt_rows[np.isclose(lpt_rows["est_error"], 0.25)][["skew", "workers", "rep", "overhead"]]
    return frame, mid.assign(policy="lpt (error 0.25)")


def rq1_tables(df):
    frame, mid = rq1_frame(df)
    summary_rows, friedman_rows, pair_rows = [], [], []
    for (skew, workers), g in pd.concat([frame, mid]).groupby(["skew", "workers"]):
        for policy, h in g.groupby("policy"):
            q1, med, q3 = h["overhead"].quantile([0.25, 0.5, 0.75])
            summary_rows.append({"skew": skew, "workers": workers, "policy": policy, "median": med, "q1": q1,
                                 "q3": q3, "mean": h["overhead"].mean(), "n": len(h)})
        wide = g[g["policy"].isin(RQ1_POLICIES)].pivot_table(index="rep", columns="policy", values="overhead")
        wide = wide[RQ1_POLICIES]
        chi2, p = stats.friedmanchisquare(*[wide[c] for c in RQ1_POLICIES])
        medians = wide.median()
        friedman_rows.append({"skew": skew, "workers": workers, "chi2": chi2, "friedman_p": p,
                              "kendall_w": chi2 / (len(wide) * (len(RQ1_POLICIES) - 1)),
                              "best_policy": medians.idxmin(), "worst_policy": medians.idxmax(),
                              "spread_of_medians": medians.max() - medians.min()})
        for a, b in itertools.combinations(RQ1_POLICIES, 2):
            d = (wide[a] - wide[b]).to_numpy()
            pair_rows.append({"skew": skew, "workers": workers, "a": a, "b": b, "mean_diff_a_minus_b": d.mean(),
                              "hodges_lehmann": hodges_lehmann(d), "rank_biserial": rank_biserial(d),
                              "wilcoxon_p": wilcoxon_p(d)})
    friedman = pd.DataFrame(friedman_rows)
    friedman["holm_p"] = holm(friedman["friedman_p"].to_numpy())
    pairs = pd.DataFrame(pair_rows)
    pairs["holm_p"] = holm(pairs["wilcoxon_p"].to_numpy())
    return pd.DataFrame(summary_rows), friedman, pairs


# --------------------------------------------------------------------------------------------------------
# figures
# --------------------------------------------------------------------------------------------------------

def _facets(skews, workers, xlabel, ylabel, title, sharex=True):
    fig, axes = plt.subplots(len(skews), len(workers), figsize=(3.0 * len(workers), 2.5 * len(skews)),
                             squeeze=False, sharex=sharex, sharey="row")
    for r, skew in enumerate(skews):
        for c, n in enumerate(workers):
            ax = axes[r][c]
            ax.grid(alpha=0.25)
            if r == 0:
                ax.set_title(f"N = {n}", fontsize=10)
            if c == 0:
                ax.set_ylabel(f"skew {skew:g}\n{ylabel}", fontsize=9)
            if r == len(skews) - 1:
                ax.set_xlabel(xlabel, fontsize=9)
    fig.suptitle(title, fontsize=11)
    return fig, axes


def fig_rq1_boxplots(df, out):
    frame, mid = rq1_frame(df)
    data = pd.concat([frame, mid])
    order = RQ1_POLICIES + ["lpt (error 0.25)"]
    labels = ["static", "dynamic", "steal", "LPT\n(e=0)", "LPT\n(e=.25)"]
    skews, workers = sorted(data["skew"].unique()), sorted(data["workers"].unique())
    fig, axes = _facets(skews, workers, "", "overhead over lower bound", "RQ1: makespan overhead by policy",
                        sharex=False)
    for r, skew in enumerate(skews):
        for c, n in enumerate(workers):
            sel = data[(data["skew"] == skew) & (data["workers"] == n)]
            box = axes[r][c].boxplot([sel[sel["policy"] == p]["overhead"] for p in order], widths=0.6,
                                     patch_artist=True, flierprops={"markersize": 2})
            for patch, p in zip(box["boxes"], order):
                patch.set_facecolor(COLORS.get(p, "#ffbb78"))
                patch.set_alpha(0.7)
            axes[r][c].set_xticks(range(1, len(order) + 1))
            axes[r][c].set_xticklabels(labels if r == len(skews) - 1 else [], fontsize=7)
    fig.tight_layout()
    fig.savefig(out / "fig_rq1_boxplots.png", dpi=160)
    plt.close(fig)


def fig_rq2_absolute(df, out, rng):
    lpt = grid.lpt(df)
    base = grid.baselines(df)
    skews, workers = sorted(lpt["skew"].unique()), sorted(lpt["workers"].unique())
    fig, axes = _facets(skews, workers, "estimate error (sigma)", "mean overhead",
                        "RQ2: LPT overhead vs estimate error (lines: estimate-free policies)")
    for r, skew in enumerate(skews):
        for c, n in enumerate(workers):
            ax = axes[r][c]
            sel = lpt[(lpt["skew"] == skew) & (lpt["workers"] == n)]
            levels = sorted(sel["est_error"].unique())
            means, los, his = [], [], []
            for e in levels:
                v = sel[sel["est_error"] == e]["overhead"].to_numpy()
                lo, hi = bootstrap_mean_ci(v, rng, 2000)
                means.append(v.mean()); los.append(lo); his.append(hi)
            ax.plot(levels, means, "o-", color=COLORS["lpt"], ms=3, label="LPT")
            ax.fill_between(levels, los, his, color=COLORS["lpt"], alpha=0.25)
            for p in ["static-rr", "dynamic-queue", "work-stealing"]:
                m = base[(base["policy"] == p) & (base["skew"] == skew) & (base["workers"] == n)]["overhead"].mean()
                ax.axhline(m, color=COLORS[p], ls="--", lw=1, label=p)
    axes[0][0].legend(fontsize=6, loc="upper left")
    fig.tight_layout()
    fig.savefig(out / "fig_rq2_lpt_vs_baselines.png", dpi=160)
    plt.close(fig)


def fig_rq2_difference(tests, cross, out):
    skews, workers = sorted(tests["skew"].unique()), sorted(tests["workers"].unique())
    fig, axes = _facets(skews, workers, "estimate error (sigma)", "LPT - baseline overhead",
                        "RQ2: paired difference (negative = LPT better), 95% bootstrap CI; dot = significant (Holm)")
    for r, skew in enumerate(skews):
        for c, n in enumerate(workers):
            ax = axes[r][c]
            ax.axhline(0, color="k", lw=0.8)
            for baseline, style in [(PRIMARY, "-"), ("work-stealing", ":")]:
                t = tests[(tests["baseline"] == baseline) & (tests["skew"] == skew) & (tests["workers"] == n)]
                t = t.sort_values("est_error")
                ax.plot(t["est_error"], t["mean_diff"], style, color=COLORS[baseline], lw=1.2,
                        label=f"vs {baseline}")
                if baseline == PRIMARY:
                    ax.fill_between(t["est_error"], t["mean_ci_lo"], t["mean_ci_hi"], color=COLORS[baseline],
                                    alpha=0.2)
                    sig = t[t["holm_p"] < ALPHA]
                    ax.plot(sig["est_error"], sig["mean_diff"], "o", color=COLORS[baseline], ms=3)
            point = cross[(cross["baseline"] == PRIMARY) & (cross["skew"] == skew) & (cross["workers"] == n)]
            if len(point) and np.isfinite(point["_point"].iloc[0]) and point["_point"].iloc[0] > 0:
                ax.axvline(point["_point"].iloc[0], color="gray", ls="--", lw=0.8)
    axes[0][0].legend(fontsize=6, loc="upper left")
    fig.tight_layout()
    fig.savefig(out / "fig_rq2_difference.png", dpi=160)
    plt.close(fig)


def fig_rq2_crossover(cross, out):
    sel = cross[cross["baseline"] == PRIMARY]
    top = sel["max_error_tested"].iloc[0]
    fig, ax = plt.subplots(figsize=(5.2, 3.6))
    for skew, g in sel.groupby("skew"):
        g = g.sort_values("workers")
        x = g["workers"].to_numpy()
        point = g["_point"].replace(np.inf, top * 1.12).to_numpy(float)
        lo = g["ci_lo"].map(lambda s: top * 1.12 if str(s).startswith(">") else float(s)).to_numpy(float)
        hi = g["ci_hi"].map(lambda s: top * 1.12 if str(s).startswith(">") else float(s)).to_numpy(float)
        line, = ax.plot(x, point, "o-", label=f"skew {skew:g}")
        ax.fill_between(x, lo, hi, color=line.get_color(), alpha=0.15)
        for xi, pi, raw in zip(x, point, g["_point"]):
            if np.isinf(raw):
                ax.annotate("> " + f"{top:g}", (xi, pi), textcoords="offset points", xytext=(0, 5), fontsize=7,
                            ha="center")
    ax.axhline(top, color="gray", ls=":", lw=0.8)
    ax.set_xscale("log", base=2)
    ax.set_xticks(sorted(sel["workers"].unique()))
    ax.set_xticklabels([str(int(w)) for w in sorted(sel["workers"].unique())])
    ax.set_xlabel("workers N")
    ax.set_ylabel("crossover estimate error (sigma)")
    ax.set_title("Where LPT stops beating the dynamic queue\n(band: 95% bootstrap CI; above dotted line = beyond grid)",
                 fontsize=9)
    ax.grid(alpha=0.25)
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(out / "fig_rq2_crossover.png", dpi=160)
    plt.close(fig)


# --------------------------------------------------------------------------------------------------------
# driver
# --------------------------------------------------------------------------------------------------------

def run(grid_path, out_dir, boots, seed):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(seed)
    df = grid.load_grid(grid_path)
    lines = [f"grid: {grid_path}", f"runs: {len(df)} ({int(df['is_baseline'].sum())} baseline, "
             f"{len(grid.lpt(df))} LPT); bootstrap resamples: {boots}; seed: {seed}", ""]

    primary = paired_tests(df, PRIMARY, rng)
    secondary = pd.concat([paired_tests(df, b, rng, hl_ci=False) for b in SECONDARY], ignore_index=True)
    primary.round(6).to_csv(out / "rq2_primary_tests.csv", index=False)
    secondary.round(6).to_csv(out / "rq2_secondary_tests.csv", index=False)
    counts = primary["verdict"].value_counts().to_dict()
    lines += [f"RQ2 primary family (LPT vs {PRIMARY}), {len(primary)} tests, Holm alpha {ALPHA}: {counts}"]
    for b in SECONDARY:
        counts_b = secondary[secondary["baseline"] == b]["verdict"].value_counts().to_dict()
        lines.append(f"RQ2 secondary (LPT vs {b}): {counts_b}")

    cross = pd.concat([crossovers(df, b, rng, boots // 2) for b in [PRIMARY] + SECONDARY], ignore_index=True)
    cross.drop(columns="_point").round(6).to_csv(out / "rq2_crossovers.csv", index=False)
    lines += ["", f"Crossover error level, LPT vs {PRIMARY} (interpolated zero of the mean paired difference):"]
    show = cross[cross["baseline"] == PRIMARY]
    lines.append(show.pivot(index="skew", columns="workers", values="crossover").to_string())

    summary, friedman, pairs = rq1_tables(df)
    summary.round(6).to_csv(out / "rq1_summary.csv", index=False)
    friedman.round(6).to_csv(out / "rq1_friedman.csv", index=False)
    pairs.round(6).to_csv(out / "rq1_pairwise.csv", index=False)
    lines += ["", f"RQ1 Friedman: {(friedman['holm_p'] < ALPHA).sum()} of {len(friedman)} (skew, N) cells significant (Holm)",
              "Median overhead by policy (rows: skew, N):"]
    lines.append(summary.pivot_table(index=["skew", "workers"], columns="policy", values="median").round(3).to_string())

    fig_rq1_boxplots(df, out)
    fig_rq2_absolute(df, out, rng)
    fig_rq2_difference(pd.concat([primary, secondary], ignore_index=True), cross, out)
    fig_rq2_crossover(cross, out)

    (out / "summary.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"\nwrote tables, figures and summary.txt to {out}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--grid", default="results/grid-timed.csv")
    parser.add_argument("--out", default="results/analysis")
    parser.add_argument("--boot", type=int, default=10000, help="bootstrap resamples for mean CIs")
    parser.add_argument("--seed", type=int, default=20261124)
    args = parser.parse_args()
    run(args.grid, args.out, args.boot, args.seed)
