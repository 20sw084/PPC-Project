# Design decisions: what changed since the proposal, and why

Study: *Does Duration-Aware Sharding Survive Imperfect Estimates?* (PPC project, Junaid Aslam).
This file explains every methodological change made after the approved proposal, as questions a reviewer
is likely to ask. Numbers come from `results/calibration-raw.csv` (official calibration) and
`results/pilot.csv` (10-rep pilot, skew 1.0, N = 4 and 16) unless stated otherwise.

## At a glance

| | Proposal | Final design | Section |
|---|---|---|---|
| Policies | static, LPT, dynamic queue, work-stealing | same four | |
| Estimate-error levels | 4 (0, .1, .25, .5) | **7** (0, .05, .1, .15, .25, .35, .5) | Q2 |
| Runs of estimate-free policies | repeated at every error level | **once per (skew, N, rep)** | Q1 |
| Main grid size | ~5,800 runs | **3,600 runs** (+ 900 optional spin runs) | Q1 |
| Workers N | {2, 4, 8, 16} | same | |
| Task duration | timed tasks, >= 50 ms | median 40 ms, floor 10 ms | Q5 |
| Timed task body | `Thread.sleep` | deadline loop (1 ms sleeps + 2 ms spin tail) | Q5 |
| Statistical test | Mann-Whitney U, Cliff's delta | **Wilcoxon signed-rank on paired differences**, matched-pairs effect size, bootstrap CI | Q3 |
| Spin (CPU) variant | N <= 8, size unspecified | 10 reps, N in {2,4,8}, 900 runs | Q6 |

## Q1. Why are static, dynamic and work-stealing run only once per (skew, N, rep)?

**Short answer:** they never read the duration estimates, so the estimate-error level cannot affect them.
Running them at every error level only repeats the same experiment and would inflate the sample size.

- Only LPT reads `Task.estimatedMs()` (`Policy.usesEstimates()` is true only for `Lpt`). The other three
  never look at it.
- Pilot evidence: with the old structure, each estimate-free policy gave the same mean overhead at all
  four error levels (static-rr at N=4: 0.2066, 0.2067, 0.2064, 0.2063; dynamic-queue at N=16: 0.2600,
  0.2599, 0.2599, 0.2599). The differences are timing noise, not an effect.
- **Pseudo-replication:** if those rows were analysed as independent samples, 4 error levels would look
  like 4x more data for the same condition, shrinking confidence intervals and p-values artificially.
  The independent unit is the (skew, rep) task set, not the error level.
- Now each (skew, N, rep) has: one run of each of the 3 estimate-free policies (the **baseline**) and one
  LPT run per error level, all on the **same task set** (task seeds depend only on skew and rep).
- Run count: before, 4 policies x 3 skews x 4 errors x 4 N x 30 reps = 5,760 (about 5,800).
  Now 3 x 3 x 4 x 30 = 1,080 baseline + 1 x 3 x 7 x 4 x 30 = 2,520 LPT = **3,600**, even though the
  error grid grew from 4 to 7 levels. Estimated time falls from about 6 h to about 4.3 h.
- In the CSV the baseline rows have `est_error = NA` and `noise_seed = NA`.

## Q2. Why seven error levels (0, 0.05, 0.1, 0.15, 0.25, 0.35, 0.5)?

**Short answer:** the pilot showed where LPT stops beating the estimate-free policies, and the old grid
did not bracket that point tightly enough to locate it.

Paired mean difference in overhead, LPT minus dynamic-queue (negative = LPT better), pilot, skew 1.0:

| N | err 0 | err 0.1 | err 0.25 | err 0.5 |
|---|---|---|---|---|
| 4 | -0.021 | +0.004 | +0.032 | +0.120 |
| 16 | -0.255 | -0.214 | -0.130 | +0.040 |

- At **N = 4** the sign flips between error 0 and 0.1 (linear interpolation gives about 0.08). The old grid
  had nothing between them. The new 0.05, 0.10 and 0.15 levels sit on both sides of the crossover.
- At **N = 16** the sign flips between 0.25 and 0.5 (interpolation gives about 0.44). The new 0.35 level
  halves that gap.
- Crossovers depend on N (and on skew), so a grid that resolves RQ2 ("at what error does LPT lose its
  advantage?") needs denser levels near the crossover, not an evenly spaced coarse grid.
- Caveat: the pilot only used skew 1.0. Crossovers at other skews are unknown until the full run, and the
  0.35 to 0.5 gap at N = 16 is still wide (an extra level around 0.45 is cheap if wanted).

## Q3. Why paired tests (Wilcoxon signed-rank) and not independent Mann-Whitney?

**Short answer:** the runs being compared are not independent. They used identical task sets, and the
paired design removes the task-set variability that would otherwise swamp the policy effect.

- For a given (skew, rep), every policy and every N is run on the **same generated task set**, and the
  estimate noise is seeded per (skew, error, rep). So "LPT at error e" and "dynamic queue" in rep k are
  two measurements on the same tasks: a paired design.
- Mann-Whitney assumes two independent groups. Using it here would ignore the pairing, lose power (task-set
  variability is large: the pilot's SD of overhead at N = 16 is about 0.15 against a paired-difference
  mean of up to -0.25) and treat dependent data as independent.
- **Analysis plan** (`analysis/grid.py` already builds the pairs; `paired(df, baseline)`):
  1. Per (skew, N, error): the 30 per-rep differences `overhead_LPT - overhead_baseline`.
  2. Wilcoxon signed-rank test on those differences (non-parametric: overhead is skewed, not normal).
  3. Effect size: matched-pairs rank-biserial correlation, plus the Hodges-Lehmann median difference with
     a bootstrap 95% CI (resampling reps).
  4. Multiple comparisons: Holm correction. **Pre-specified primary family** for RQ2: LPT vs
     dynamic-queue, over 7 error levels x 4 N x 3 skews = 84 tests; other baselines are secondary.
  5. RQ1 (policy comparison): Friedman test across the four policies on the same task sets, then paired
     Wilcoxon with Holm.
  6. **Crossover estimate for RQ2:** for each (skew, N), interpolate where the mean paired difference
     crosses zero, with a bootstrap CI. Per-level significance alone cannot locate it (see Q7).
- The proposal said "Mann-Whitney U, Cliff's delta". Both were replaced by their paired counterparts.

## Q4. Why 30 repetitions, and what can that detect?

- A repetition is a fresh random task set (new task seed), so repetitions are independent samples of
  workloads.
- With n = 30 pairs, a paired test at alpha = 0.05 and 80% power detects a mean difference of about
  0.5 standard deviations of the paired differences (2.8 / sqrt(30)).
- The pilot (10 reps) gave the SDs: paired SD about 0.02 at N = 4 and about 0.15 at N = 16. Hence 30 reps
  resolve differences larger than about 0.01 (N = 4) and about 0.08 (N = 16) in overhead, but **not**
  differences near the crossover (Q7).

## Q5. What was changed in the harness after calibration, and why?

| Change | Evidence |
|---|---|
| **Timed tasks wait on a deadline in 1 ms sleeps plus a 2 ms spin tail**, not plain `Thread.sleep` | Windows rounds sleeps to its ~15.6 ms timer tick. Plain `Thread.sleep` had a p95 absolute error of 6.5 ms (10 ms task), 13.0 ms (20 ms), 15.4 ms (50 ms), 13.6 ms (100 ms). The deadline loop had p95 error <= 0.25 ms at every target from 1 to 200 ms. `parkNanos` was as bad as `sleep` at 10 ms and above, and worse below that |
| **Floor 50 ms -> 10 ms, median 100 ms -> 40 ms** | The 50 ms floor existed only because of timer inaccuracy. With the accurate body, 10 ms tasks are reliable. It also cut the grid cost: median 100 ms with n = 200 was an estimated 15.5 h for the old structure |
| **`n = 200` kept** | Value from the approved proposal. TODO before the paper: check it against the real number of screens in the motivating suite and state the comparison |
| **Overhead = makespan / lower bound - 1**, lower bound from the *true* durations | Comparable across skews and N; 0 means the lower bound `max(sum/N, longest)` was met |
| **Random run order (seeded), sequential runs only** | Spreads thermal and background drift across policies instead of tying it to one. Two runs at once would disturb each other |
| **Resumable CSV (one row per run, torn lines redone)** | Runs can be stopped and continued in chunks. A test found and fixed a bug where an interrupted write would corrupt the next row |
| **Warm-up** (3 rounds of 4 policies on tiny tasks before measurement) | Drift check: mean overhead of runs 1-5 was 0.0179, runs 6-20: 0.0145, runs 21-30: 0.0144, a small warm-up effect and no throttling trend |
| **Policy definitions fixed** | LPT = static shards from sorted estimates (like CI sharding), not an LPT-ordered queue. Work-stealing starts from the same round-robin deal as static-rr, so the difference between them isolates the effect of stealing. Whole tasks (screens) are stolen and a started task never moves, which matches the data-affinity constraint in the motivating suite |
| **Platform threads** | Virtual threads would change what is measured |

## Q6. Why a separate CPU-spin variant, and why only 10 reps and N <= 8?

- Timed tasks isolate the scheduling policy from the hardware. They cannot show CPU effects such as
  contention or P/E-core differences, so a small CPU-bound **robustness check** runs the same grid with
  tasks that burn a fixed amount of work (`--iters-per-ms=690624`, median of the official calibration).
- It is deliberately smaller: 10 reps, N in {2, 4, 8} = 900 runs (about 1.1 h).
- **N = 16 is excluded** for the spin variant. Calibration shows 16 CPU-bound workers on 12 hardware
  threads: a 100 ms CPU task takes 188 ms (1.9x), against 128 ms at N = 8 and 134 ms at N = 12. That is
  oversubscription, not scheduling. It is reported from calibration, not from the grid.
- The spin rate is not constant: median 690,624, min 521,782, max 727,627 iterations/ms (about 30% spread,
  the P/E-core effect). Spin results are noisier, which is why they are a robustness check and not the
  main result.

## Q7. What can the study not conclude? (threats to validity to state in the paper)

- **Crossovers are located by interpolation, not by significance at one level.** Near the crossover the
  difference is tiny relative to its SD: pilot, N = 4, error 0.10: mean +0.004, SD 0.021 (about 235 pairs
  needed); N = 16, error 0.50: +0.040, SD 0.17 (about 147). 30 reps cannot declare either level
  "significantly different". The paper should say the crossover is where the interpolated difference is zero.
- **Floor clamping at high skew.** With median 40 ms and floor 10 ms, a lognormal clamps about 18% of
  tasks at skew 1.5, 8% at skew 1.0 and 0.3% at skew 0.5, which slightly reduces the effective skew.
- **N = 16 has a systematic delay.** Official calibration: 16 parallel 50 ms timed tasks took a median
  54.9 ms against 50.1 ms at N = 1 (the 2 ms spin tails of 16 threads compete for 12 hardware threads).
  It applies to every policy at N = 16, so it mostly cancels in paired differences, but it inflates
  absolute overhead at N = 16.
- **Timed tasks are a model.** Sleep-like tasks ignore cache, memory and server contention.
- **One machine** (i5-1334U, 2 P + 8 E cores, 12 threads, Windows 11, Java 21), single OS.
- **Estimate noise is a model:** multiplicative lognormal with sigma = error level, independent per task.
  Real estimates can be biased or correlated (stale history shifts many tasks together).
- **Workload shape:** lognormal durations, n = 200, independent tasks (no dependencies or setup costs).

## Q8. What is in the repo and how is it reproduced?

1. `cd harness` and `mvn -q compile` (the unit tests check the policies and the driver: `mvn test`).
2. Optional re-check: `java -cp target/classes ppc.sharding.Calibration ../results/calibration-raw.csv`.
3. Main grid and spin check commands: see `README` / `PLAN.md` (explicit `--out` paths).
4. Analysis: `python analysis/grid.py results/grid-timed.csv` for the summary and pairing.
