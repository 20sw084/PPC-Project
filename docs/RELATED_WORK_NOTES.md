# Related work: verified sources, reading plan, and what this study adds

Checked on 25 Nov 2026 by web search (title, authors, venue, pages, DOI). That confirms the references exist and
the citation data is right. **It does not replace reading them**: you must be able to explain every cited work.
BibTeX for all of them: `paper/references.bib` (entries marked VERIFY need one more look).

## Sources (10), by how you will use them

| Key | Source | Use in the paper | Read |
|---|---|---|---|
| graham1969 | Graham, *Bounds on Multiprocessing Timing Anomalies*, SIAM J. Appl. Math. 17(2):416-429, 1969 | LPT's worst-case guarantee with exact durations (the "ideal" LPT baseline) | the LPT bound only |
| blumofe1999 | Blumofe & Leiserson, *Scheduling Multithreaded Computations by Work Stealing*, JACM 46(5):720-748, 1999 | Work-stealing theory; why it needs no duration estimates | intro, algorithm, main theorem |
| frigo1998 | Frigo, Leiserson, Randall, *Cilk-5*, PLDI 1998, 212-223 | Work-stealing in a real system. Check how owners and thieves access the deques and compare with your `WorkStealing` | the scheduler section |
| lea2000 | Lea, *A Java Fork/Join Framework*, Java Grande 2000 (VERIFY) | Work-stealing in Java, the language of your harness | skim |
| memon2017 | Memon et al., *Taming Google-Scale Continuous Testing*, ICSE-SEIP 2017, 233-242 | Motivation: test execution cost in CI at scale | intro + problem statement |
| candido2017 | Cândido, Melo, d'Amorim, *Test Suite Parallelization in Open-Source Projects*, ASE 2017, 838-848 | Motivation: parallelising test suites is common practice, how it is done | results on usage |
| hoefler2015 | Hoefler & Belli, *Scientific Benchmarking of Parallel Computing Systems*, SC '15 | Your measurement methodology (reporting, confidence intervals, stopping rules) | all 12 rules |
| georges2007 | Georges, Buytaert, Eeckhout, *Statistically Rigorous Java Performance Evaluation*, OOPSLA 2007, 57-76 | Why you use warm-up, repetitions and confidence intervals on a JVM | intro + methodology |
| wilcoxon1945 | Wilcoxon, *Individual Comparisons by Ranking Methods*, Biometrics Bulletin 1(6):80-83, 1945 | Cite for the signed-rank test | cite only |
| holm1979 | Holm, *A Simple Sequentially Rejective Multiple Test Procedure*, Scand. J. Stat. 6(2):65-70, 1979 | Cite for the Holm correction | cite only |

That is already more than the 6 sources required. Keep the final list to what you actually discuss.

## Reading plan for 25 Nov (about 3 h of daytime reading)

For each paper write 3-5 lines **in your own words** in a file of your own (not copied from abstracts):
(a) the question it answers, (b) the main result, (c) how it relates to your study, (d) one thing it does **not**
cover that your study does.

1. **Hoefler & Belli** (40 min). Questions to answer: which of the 12 rules do you follow (report hardware,
   warm-up, number of repetitions, confidence intervals, non-normal data)? Which do you not (and why)?
2. **Georges et al.** (30 min). Questions: why is a single run misleading on a JVM? How does your warm-up +
   30 repetitions + bootstrap CI match their recommendations?
3. **Blumofe & Leiserson** (40 min). Questions: what does work-stealing guarantee, and what does it assume (a
   computation with dependencies and a critical path)? In your setting the tasks are independent, so why might
   it show no advantage over a shared queue? (Your data: dynamic queue vs work-stealing is never significant.)
4. **Graham 1969** (20 min). Questions: what is the bound for LPT and for plain list scheduling? How does your
   "exact estimates" LPT result (overhead near 0) relate to it?
5. **Memon et al. and Cândido et al.** (40 min). Questions: how do these describe the cost and scheduling of test
   execution? Do they say anything about duration-aware sharding or estimate accuracy?
6. Skim Frigo and Lea (10 min each): how their deques differ from yours.

## What this study adds (state it carefully)

- **Scheduling under duration uncertainty** is studied in the scheduling literature. My search found, for
  example, a sensitivity analysis of list-scheduling rules including LPT to a single uncertain processing time
  (Van Hoesel & Wagelmans, 1990; a working paper plus a TU/e record; title, venue and details to be confirmed),
  and an arXiv paper (2102.08677; authors and title to confirm when you open it) on parallel machine scheduling
  with uncertain durations reporting that rescheduling after each completion is more stable than static list
  policies. That is consistent with your finding that estimate-free dynamic policies beat LPT once estimates
  are bad enough. **Read these two before citing them**; I only saw search excerpts.
- **I did not find, in one search, an empirical study of LPT with multiplicative estimate noise measured on real
  JVM runs** across skew and worker counts. That is a reason to believe your contribution, but do two more
  searches yourself (Google Scholar: "LPT" "processing time" "estimation error"; "test sharding" "duration
  estimates") before you write "to our knowledge, no study ...". A modest "we measure ..." is safer.
- Your contribution is **empirical and local**: a paired, calibrated measurement of where the LPT/dynamic
  crossover lies as a function of skew, N and estimate error, on one machine. Do not claim more.

## Checklist for the Related Work section
- [ ] 1 paragraph: work-stealing and list scheduling (Graham, Blumofe, Frigo, Lea).
- [ ] 1 paragraph: test parallelization/sharding in practice (Memon, Cândido).
- [ ] 1 paragraph: scheduling under uncertain durations (the two papers above, once read).
- [ ] 1 paragraph: measurement methodology (Hoefler & Belli, Georges et al.); statistics (Wilcoxon, Holm).
- [ ] Last sentence of the section: what is different here (paired design, calibrated timed tasks, error sweep).
