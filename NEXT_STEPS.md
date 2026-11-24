# Next steps: from finished grid to submitted paper

Status on 24 Nov: harness, calibration and the 3,600-run timed grid are done; `analysis/stats.py` produces all
tables and figures (`results/analysis/`). **Deadline: 2 Dec 2026** (paper + repo tagged `submitted`, before the
deadline). Presentation 3-4 Dec or 10-11 Dec (5-8 min + 3 min questions). Target: everything done by the
evening of **1 Dec**.

## Headline results so far (to be confirmed while writing)
- RQ2 primary family (LPT vs dynamic queue, 84 tests, Holm): 32 LPT better, 18 LPT worse, 34 not significant.
- Crossover error level (interpolated), LPT vs dynamic queue:

  | skew | N=2 | N=4 | N=8 | N=16 |
  |---|---|---|---|---|
  | 0.5 | 0.06 | 0.10 | 0.13 | 0.19 |
  | 1.0 | 0.07 | 0.22 | 0.38 | > 0.5 (beyond grid) |
  | 1.5 | 0.08 | 0.25 | 0.43 | > 0.5 (beyond grid) |

- **H2 as written is refuted**: the crossover is not a fixed error level; it rises strongly with N and skew.
- RQ1: Friedman significant in 12 of 12 (skew, N) cells. static-rr is worst and LPT with exact estimates is
  best in every cell (static-rr median overhead 0.72 at N=16, skew 1.0). **Dynamic queue vs work-stealing:
  no significant difference in any cell** (Holm p = 1.0), so work-stealing gave no measurable benefit over a
  shared queue here (tasks are independent and the queue is cheap; say so, with that caveat).
- H1: "static policies degrade fastest with skew" is supported. "Differences shrink at large N" is supported
  only partly: the spread between best and worst policy grows with N at skew 0.5 (0.03 -> 0.24) and skew 1.0
  (0.07 -> 0.72) and shrinks only at skew 1.5 from N=8 to N=16 (0.69 -> 0.40), where the longest task
  dominates the lower bound. Report it exactly like that.

## Decisions to make now
1. **Extension for the censored cells (optional, about 0.8 h of runs).** For skew >= 1.0 at N=16 the crossover
   is beyond error 0.5. Adding LPT at errors 0.75 and 1.0 (2 levels x 3 skews x 4 N x 30 reps = 720 runs) would
   locate it. It extends the pre-specified grid, so report it as an **exploratory extension**. Recommended:
   yes, run it one evening. Command (resumable, same output file):
   `java -cp target/classes ppc.sharding.ExperimentDriver --errors=0.75,1.0 --out=../results/grid-timed.csv`
   (baseline rows already exist and are skipped; only new LPT levels run.) Re-run `stats.py` afterwards; the
   Holm families then have 120 tests, so state in the paper whether the 84-test family or the 120-test family
   is the primary one (recommended: keep 84 as primary, extension reported separately).
2. **Spin (CPU-bound) robustness check (optional, about 1.1 h).** Re-run `Calibration` first on a quiet laptop
   (the old calibration file was deleted, and the spin rate drifts between sessions), then
   `--variant=spin --iters-per-ms=<new value>`. Skip if time is short: say so in threats to validity.
3. **Check n = 200 against the real number of screens** in the motivating suite and state the comparison.
4. **Which figures go in the 5-page paper** (suggested): crossover plot, paired-difference grid
   (`fig_rq2_difference.png`), RQ1 boxplots (or a table instead). Others in the repo.

## Day-by-day plan

| Date | Work | Hours of laptop needed |
|---|---|---|
| **Tue 25 Nov** | Review `results/analysis/` tables and figures; commit analysis. Start related-work reading (see below). Evening: run the optional extension (decision 1) and then recalibration + spin check (decision 2), laptop quiet, sleep disabled | ~2 h unattended |
| **Wed 26 Nov** | Re-run `stats.py` with extension data; fix figures if needed. Paper skeleton in the course LaTeX template: Introduction, Related Work, Methodology and setup (write from `docs/DESIGN_DECISIONS_QA.md` and `PLAN.md`) | none |
| **Thu 27 Nov** | Results section (figures + tables, test and effect size reported in text) and Analysis/Discussion | none |
| **Fri 28 Nov** | Threats to validity, Conclusion + future work, abstract. First full draft; check the 5-page + 2-page-references limit and >= 6 references | none |
| **Sat 29 Nov** | Revise: every claim traceable to a table/figure; consistency of numbers; H1/H2 stated honestly (including refuted parts) | none |
| **Sun 30 Nov** | Reproducibility check from a fresh clone: `mvn -q compile`, `--dry-run`, run `stats.py` on the committed CSV, rebuild figures. README with exact commands. AI-use disclosure in the paper | ~30 min |
| **Mon 1 Dec** | Final PDF, proofread, push, tag `submitted` on the last commit, verify the tag points to it. Draft slides | none |
| **Tue 2 Dec** | **Deadline.** Buffer day only: submit on Moodle; no new work | none |
| Dec 3-11 | Presentation: 5-8 min talk + 3 min questions. Rehearse answers using `docs/DESIGN_DECISIONS_QA.md` | none |

## Paper: how each part earns points (rubric)
| Criterion (pts) | Where it comes from | Still missing |
|---|---|---|
| Motivation 1.5 | CI test sharding story, falsifiable RQ1/RQ2 | tighten to one paragraph |
| Related work 1.5 | 3 sources so far | **3+ more sources** (see below) |
| Methodology 3 | harness, variables, held-constant, paired design, Q&A doc | n = 200 vs real screen count |
| Experimental rigor 4 | calibration, warm-up, randomised order, 30 reps, power argument, interrupted-run handling | recalibration evidence (old file deleted); report the 4 re-run rows |
| Results 4.5 | `results/analysis/` tables + figures | write-up |
| Analysis 3 | crossover vs N and skew; why LPT survives noise longer with more workers (the longest task dominates the lower bound, LPT isolates it) | write-up |
| Threats 1 | listed in `DESIGN_DECISIONS_QA.md` Q7 | write-up |
| Writing/reproducibility 1.5 | repo, README, tag | README |

## Related-work candidates (recalled from memory: **verify every citation** on Google Scholar before use)
- Graham 1969, Blumofe & Leiserson 1999, Hoefler & Belli 2015 (already in the proposal).
- Georges, Buytaert, Eeckhout: *Statistically rigorous Java performance evaluation* (OOPSLA 2007): methodology.
- Frigo, Leiserson, Randall: Cilk-5 implementation (PLDI 1998): work-stealing in practice.
- Lea: *A Java Fork/Join Framework* (Java Grande 2000): Java work-stealing.
- Memon et al.: *Taming Google-scale continuous testing* (ICSE-SEIP 2017): CI test scheduling.
- Wilcoxon 1945 (signed-rank test) and Holm 1979 (sequentially rejective procedure): analysis methods.
- Search terms: "test suite parallelization", "test sharding CI", "list scheduling stochastic processing times",
  "longest processing time estimation error robustness".

## Risks and fallbacks
- **Time runs short:** skip the spin check and the extension; say so under threats. The core results are done.
- **Page limit:** move secondary figures/tables to the repo, cite them as supplementary material.
- **Claims the data does not support:** write what the data shows (H2 refuted, H1 partly supported); the course
  grades rigor, not confirmation.
- **Interrupted extension run:** resumable; rerun the same command. Check for suspended-run outliers again
  (ratio to the median of the same task set, as for the main grid).
- Whatever is written must be explainable by you: be able to justify each choice in the Q&A document.
