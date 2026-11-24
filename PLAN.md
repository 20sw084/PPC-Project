# PPC project plan: Does Duration-Aware Sharding Survive Imperfect Estimates?

Deadline: **02 Dec 2026** (paper + repo tagged `submitted`). Presentation 03-04 or 10-11 Dec.
Target: everything done by the evening of **01 Dec** (one day of buffer).
Setup: JDK 21.0.13, Maven 3.9.12, Python 3.13, i5-1334U (2P+8E, 12 threads), Windows 11.

## 0. Repo hygiene (do first, 10 min)
- [ ] `git init` inside `ppc-sharding/` (own repo, own history, own `submitted` tag).
- [ ] The automation repo currently reports `?? ppc-sharding/`. Hide it locally, without touching any tracked file:
      add the line `ppc-sharding/` to `D:\globalcare_automation\.git\info\exclude`.
- [ ] Never run `mvn` from the automation root for this project; always `cd ppc-sharding` first.
- [ ] Layout: `harness/` (Maven, Java 21), `analysis/` (Python), `results/` (raw CSV), `paper/` (LaTeX), `README.md`.

## 1. Harness (17-18 Nov)
Goal: one program that runs a task set under a policy on N workers and writes one CSV row per run.
- [ ] `Task` (id, trueDurationMs, estimatedDurationMs); `TaskGenerator` (seeded lognormal, skew sigma_task; floor 50 ms).
- [ ] `EstimateNoise`: estimated = true * exp(N(0, sigma_est)), seeded.
- [ ] `Policy` interface and four implementations: `StaticRoundRobin`, `Lpt`, `DynamicQueue`, `WorkStealing` (per-worker deques).
- [ ] `Runner`: N worker threads, start barrier, per-worker finish times, makespan, idle share.
- [ ] `LowerBound`: max(sum d / N, max d).
- [ ] Unit tests: each task runs exactly once; N=1 gives equal makespan for all policies; equal tasks give equal makespan; lower bound hand-checked on 3 cases.
- [ ] CSV schema: policy, skew, est_error, N, seed, rep, makespan_ms, lower_bound_ms, overhead, idle_share, variant (timed|spin).

## 2. Calibration (19 Nov) - graded under "experimental rigor"
- [ ] A "100 ms" timed task really takes ~100 ms (check Windows timer resolution; pick sleep vs `parkNanos`).
- [ ] N=16 timed workers do not distort timing on 12 hardware threads.
- [ ] Warm-up: how many discarded runs until the JIT settles.
- [ ] Record machine state: power plan, JVM flags, Chrome and other apps closed.

## 3. Pilot (20 Nov)
- [ ] Run one slice (N in {4,16}, one skew, all policies and error levels).
- [ ] From the pilot variance, justify the repetition count (30, or lower if the budget forces it).
- [ ] Decide the time scale (see open decisions) and freeze the grid.

## 4. Full grid (21-24 Nov, runs in background)
- [ ] 3,600 runs (timed variant): 3 estimate-free policies run once per (skew, N, rep) = 1,080 baseline runs,
      plus LPT x 7 error levels (0, .05, .1, .15, .25, .35, .5) = 2,520 runs; 3 skews x 4 N x 30 reps.
      Estimate-free policies are not repeated per error level (pseudo-replication; the pilot showed identical means).
      Rationale for every change vs the proposal: `docs/DESIGN_DECISIONS_QA.md`.
- Commands (from `ppc-sharding/harness`, after `mvn -q compile`; defaults are the frozen design):
  - main grid (3,600 runs, ~4.3 h): `java -cp target/classes ppc.sharding.ExperimentDriver --out=../results/grid-timed.csv`
  - optional spin check (900 runs, ~1.1 h): `java -cp target/classes ppc.sharding.ExperimentDriver --variant=spin --out=../results/grid-spin.csv`
  - always pass `--out=../results/...`: the default path is relative to the current directory.
- [ ] CPU-spin variant for N <= 8 (smaller: fewer reps, say 10-15) plus N=16 spin as the oversubscription case.
- [ ] Runs must be **sequential** (never two at once), **resumable** (skip rows already in the CSV), plugged in, high-performance power plan.
- [ ] Randomise cell order so thermal drift is not confounded with one policy.
- [ ] While it runs: write Intro, Related Work, Methodology.

## 5. Analysis (25-26 Nov)
- [ ] Python: pandas, scipy, matplotlib (check these are installed in the venv).
- [ ] Plots: boxplots of overhead per policy; overhead vs estimate error with CI; overhead vs N.
- [ ] Tests: Mann-Whitney U per pairwise comparison, Holm correction, Cliff's delta, 95% bootstrap CI.
- [ ] Answer RQ1 and RQ2 explicitly, even if a hypothesis is refuted.

## 6. Paper (27-29 Nov)
Template from Moodle, max 5 pages + 2 of references, at least 6 references (have 3: Graham 1969, Blumofe-Leiserson 1999, Hoefler-Belli 2015).
- [ ] Related work: find 3+ more (list scheduling, work-stealing implementations, CI test sharding / test-suite parallelisation, benchmarking methodology).
- [ ] Sections: Introduction (motivation + RQs), Related work, Methodology (+ setup), Results, Analysis, Threats to validity, Conclusion (+ future work).
- [ ] Threats to include: single machine, hybrid P/E cores, timed tasks vs real tests, lognormal assumption, estimate-noise model.
- [ ] Optional if time remains: small real-Selenium validation on an open-source app.

## 7. Submission (30 Nov - 1 Dec)
- [ ] README with exact commands to reproduce (build, run grid, analysis, paper).
- [ ] Commit raw CSV + scripts + paper source + PDF; tag `submitted`; check the tag is on the last commit.
- [ ] Slides: 5-8 min + 3 min questions. Be able to explain every line of code and every statistic.
- [ ] AI-use disclosure in the paper.

## Open decisions (settle in calibration/pilot)
1. **Time scale and grid cost.** A lognormal with sigma=1.5 has mean = 3.1 x median, so 200 tasks can mean a minute of work per run at N=2. Pick median duration, n, and scale so the average run is ~3 s (about 5 h for the whole timed grid). If it exceeds ~8 h: reduce n to 100 or reps to 20 and say so in the paper.
2. **Duration profile from the real suite.** Per-scenario durations for the lognormal fit, anonymised numbers only (no screen or scenario names). `allure-results/` in the automation repo is empty right now; the durations may be under `target/` or in an old report. If none exist, fit from a short list you write down from memory, or use published/synthetic skew only and say so.
3. **Java 21 features.** Platform threads for all policies (virtual threads would change what is measured). Keep it that way.
4. **Spin variant size.** Keep small; it is a robustness check, not the main result.

## Time risks
- Grid takes longer than planned -> cut reps for the spin variant first, then n.
- Laptop thermal noise -> randomised order + repeat noisy cells.
- Related-work reading is the usual last-minute trap; do 2 sources per day from now.
