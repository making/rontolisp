# g07. `FetchSpecE2eTest` NATIVE leg deterministically prints 191 of 261 lines (regression, not load)

Difficulty: High

History: f79 (2026-10-13) saw the same 21 failures (`[3]` NATIVE leg,
`Expected size: 261 but was: 191`) and its quiet-machine rerun went 259/259
green, closing as a load flake. On 2026-10-13 ~23:00Z the parent's final full
suite (tree `c1d1f5598`, no workers active) failed identically, and a solo
`FetchSpecE2eTest` rerun right after (load 4.38, log
`/tmp/opencode/fetchspec-rerun.log` on the orchestrator host) failed with the
same 21 and the same 191/261 counts. Deterministic counts across two runs rule
out the f79 load theory for this instance: something merged after f79's green
rerun (f75/f76/f81/f83/f85/f90/f92/f94/f95/f96/g01-g04) regressed the NATIVE
executable leg while interpreter/JVM/P1/component stay green.

Prime suspects (all touch what a native executable builds or runs): f85's
canonical closure globals on wasm, f94's `_funcref` table, f92's
`%clojure-shared-value` memo, g03's kindless run-time resolution.

## Plan

1. Reproduce the solo `FetchSpecE2eTest` NATIVE-leg failure on the merged tree;
   find where the native output ends (which case dies) and how (crash vs error).
2. Bisect `c95da8ce6..c1d1f5598` (or the wider post-f79 range) with the solo leg
   until the regressing commit is found; fix or file.
3. Full `FetchSpecE2eTest` + final full suite before closing.
