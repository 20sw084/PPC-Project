"""Find runs corrupted by the machine being suspended or stalled mid-run, and optionally remove them.

    python analysis/check_outliers.py results/grid-timed.csv            # report only (exit code 1 if any)
    python analysis/check_outliers.py results/grid-timed.csv --remove    # back up, then delete flagged rows

A suspended laptop makes ONE run absurdly long (seconds become minutes or hours) while the other runs on the
same task set are normal. So a run is flagged when its makespan exceeds RATIO_LIMIT times the median makespan
of the runs on the same task set (same variant, skew, workers, rep), or exceeds the lower bound by more than
EXCESS_LIMIT_S seconds. In the main grid the genuine worst case was a ratio of 2.44 (static round-robin on a
very skewed task set), while the four corrupted rows had ratios of 57 to 1039.

--remove deletes the rows (text kept byte-for-byte otherwise) after copying the file to results/raw-backup/.
Re-running the driver with the same command then redoes exactly the removed runs.
"""
import shutil
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import grid  # noqa: E402

RATIO_LIMIT = 5.0
EXCESS_LIMIT_S = 60.0
SAME_TASK_SET = ["variant", "skew", "workers", "rep"]


def flag(df):
    """Rows of ``df`` that look corrupted, with the evidence columns."""
    median = df.groupby(SAME_TASK_SET)["makespan_ms"].transform("median")
    out = df.assign(ratio_to_median=df["makespan_ms"] / median,
                    excess_s=(df["makespan_ms"] - df["lower_bound_ms"]) / 1000.0)
    return out[(out["ratio_to_median"] > RATIO_LIMIT) | (out["excess_s"] > EXCESS_LIMIT_S)]


def run_key(row):
    err = "NA" if row["est_error"] != row["est_error"] else str(row["est_error"])  # NaN -> "NA"
    return f"{row['variant']},{row['policy']},{row['skew']},{err},{row['workers']},{row['rep']}"


def remove(path, flagged):
    path = Path(path)
    backup_dir = path.parent / "raw-backup"
    backup_dir.mkdir(exist_ok=True)
    backup = backup_dir / f"{path.stem}.before-outlier-removal-{datetime.now():%Y%m%d-%H%M%S}.csv"
    shutil.copy2(path, backup)
    doomed = {run_key(r) for _, r in flagged.iterrows()}
    with open(path, newline="") as f:
        lines = f.readlines()
    kept = [line for i, line in enumerate(lines) if i == 0 or ",".join(line.split(",")[:6]) not in doomed]
    with open(path, "w", newline="") as f:
        f.writelines(kept)
    print(f"backup: {backup}\nremoved {len(lines) - len(kept)} row(s); {len(kept) - 1} data rows remain.")
    if len(lines) - len(kept) != len(doomed):
        raise SystemExit("ERROR: removed a different number of rows than flagged; restore from the backup")


def main(argv):
    path = argv[0]
    df = grid.load_grid(path)
    flagged = flag(df)
    print(f"{path}: {len(df)} runs checked (ratio limit {RATIO_LIMIT}, excess limit {EXCESS_LIMIT_S:g} s)")
    if flagged.empty:
        print("no corrupted runs found")
        return 0
    cols = ["timestamp_utc", "policy", "skew", "est_error", "workers", "rep", "makespan_ms", "overhead",
            "ratio_to_median", "excess_s"]
    print(f"{len(flagged)} suspicious run(s):")
    print(flagged[cols].round(3).to_string(index=False))
    if "--remove" in argv:
        remove(path, flagged)
        print("now re-run the driver with the same command to redo them.")
    else:
        print("re-run with --remove to back up the file and delete these rows.")
    return 1


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    sys.exit(main(sys.argv[1:]))
