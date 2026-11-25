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
  | 1.0 | 0.07 | 0.22 | 0.38 | 0.52 (CI 0.43-0.77) |
  | 1.5 | 0.08 | 0.25 | 0.43 | 0.71 (CI 0.49-0.92) |

- **Extension done (25 Nov):** LPT at errors 0.75 and 1.0 located the crossovers that were beyond the grid (N=16, skew >= 1.0: 0.52 and 0.71). Exploratory family: 20 of 24 tests LPT worse, 4 n.s.
- **Spin check done (25 Nov):** no test significant after Holm (10 reps, expected); trend is qualitatively similar but weaker at low skew (see `docs/DESIGN_DECISIONS_QA.md` Q10). Spin overheads must not be compared with timed ones.
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
| **Tue 25 Nov** | **Run sheet below.** Daytime: commit, read the related work (`docs/RELATED_WORK_NOTES.md`), own-words notes. Evening: ONE script runs recalibration + extension + spin check + analysis (~2.5 h, PC quiet) | ~2.5 h unattended |
| **Wed 26 Nov** | Re-run `stats.py` with extension data; fix figures if needed. Paper skeleton in the course LaTeX template: Introduction, Related Work, Methodology and setup (write from `docs/DESIGN_DECISIONS_QA.md` and `PLAN.md`) | none |
| **Thu 27 Nov** | Results section (figures + tables, test and effect size reported in text) and Analysis/Discussion | none |
| **Fri 28 Nov** | Threats to validity, Conclusion + future work, abstract. First full draft; check the 5-page + 2-page-references limit and >= 6 references | none |
| **Sat 29 Nov** | Revise: every claim traceable to a table/figure; consistency of numbers; H1/H2 stated honestly (including refuted parts) | none |
| **Sun 30 Nov** | Reproducibility check from a fresh clone: `mvn -q compile`, `--dry-run`, run `stats.py` on the committed CSV, rebuild figures. README with exact commands. AI-use disclosure in the paper | ~30 min |
| **Mon 1 Dec** | Final PDF, proofread, push, tag `submitted` on the last commit, verify the tag points to it. Draft slides | none |
| **Tue 2 Dec** | **Deadline.** Buffer day only: submit on Moodle; no new work | none |
| Dec 3-11 | Presentation: 5-8 min talk + 3 min questions. Rehearse answers using `docs/DESIGN_DECISIONS_QA.md` | none |

## 25 Nov run sheet (what to do, in order)

**A. Daytime (normal PC use, about 3-4 h)**
1. Commit what is new (analysis changes, scripts, docs, bib):
   `git add -A && git commit -m "Extension-ready analysis, outlier checker, overnight script, related-work notes" && git push`
2. Read the related work with the reading plan in `docs/RELATED_WORK_NOTES.md` (about 3 h). Write 3-5 lines per
   paper in your own words (own file, e.g. `docs/my_reading_notes.md`).
3. Do two Google Scholar searches for the "what this study adds" claim (queries are in the notes).
4. Check `n = 200` against the real number of screens in your suite; write down the number.

**B. Evening: the unattended run (about 2.5 h)**
1. Plug in the charger. Close Claude, Chrome, Teams, Excel and every other app. **Do not open the CSV files
   in Excel** (it reformats numbers).
2. Open PowerShell and run this single command:

   `powershell -ExecutionPolicy Bypass -File D:\globalcare_automation\ppc-sharding\scripts\run_extension_and_spin.ps1`

   It checks everything first, then runs: recalibration (3 min), extension (~1 h, 720 runs), spin check
   (~1.3 h, 900 runs), re-checks for corrupted runs after each and redoes them automatically, and finally runs
   the analysis. It also blocks sleep while it runs. A log is written to `results\overnight-<time>.log`.
3. Leave the PC alone until it prints `ALL DONE`. If it prints `STOPPED`, fix the cause it names and run the
   same command again: every step resumes where it stopped.
4. Afterwards: restore your power settings if you changed them, then message me "overnight run done".

**C. If the script cannot run** (execution policy or no admin rights), run the steps by hand. From `ppc-sharding\harness`:
1. `mvn -q compile`
2. `java -cp target\classes ppc.sharding.Calibration ..\results\calibration-raw.csv` (note the `--iters-per-ms` value it prints)
3. `java -cp target\classes ppc.sharding.ExperimentDriver --errors=0.75,1.0 --out=..\results\grid-timed.csv`
4. `java -cp target\classes ppc.sharding.ExperimentDriver --variant=spin --iters-per-ms=<value> --out=..\results\grid-spin.csv`

Then from `ppc-sharding`:
5. `python analysis\check_outliers.py results\grid-timed.csv` and the same for `results\grid-spin.csv` (add `--remove` if it flags rows, then re-run the matching driver command from step 3 or 4)
6. `python analysis\stats.py --grid results\grid-timed.csv --out results\analysis`
7. `python analysis\stats.py --grid results\grid-spin.csv --out results\analysis-spin --boot 3000`

**D. What the extension and the spin run change in the analysis (already implemented)**
- The 84 pre-specified tests stay the primary family. Error levels 0.75 and 1.0 are reported as an exploratory
  family of their own (`rq2_extension_tests.csv`); crossovers use all levels, so the ">0.5" cells get real values.
- The spin run has only 10 reps: its Wilcoxon p-values cannot survive Holm correction (the smallest possible
  two-sided p is about 0.002, times 63 tests). Use it for **direction and size of effects and for crossover
  agreement with the timed grid**, not for significance.

## Progress tracker (tick as you go)
- [x] Harness, calibration, 3,600-run timed grid, corrupted runs fixed
- [x] Statistics script and figures (`analysis/stats.py`)
- [x] Extension-ready analysis, outlier checker, overnight script (25 Nov)
- [ ] Commit and push the new files
- [ ] Related-work reading and own notes
- [x] Overnight run: recalibration, extension, spin check (done 25 Nov; stats.py bug for grids without extension levels found and fixed, with a regression test)
- [x] Review new results with Claude (done 25 Nov)
- [ ] Paper skeleton and first sections (26 Nov)
- [ ] Results + analysis sections (27 Nov), threats + conclusion (28 Nov), full draft (28 Nov)
- [ ] Revise (29 Nov), fresh-clone reproducibility check + README (30 Nov)
- [ ] Final PDF, tag `submitted`, slides (1 Dec); buffer day 2 Dec

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
