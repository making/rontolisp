# g36: Parallel `pmap` / `pvalues` on top of futures

Difficulty: Medium

Depends on g35 (`future`/`promise` must exist first).

Today `pmap` lowers to the sequential `%CLOJURE-MAP`
(`ClojureLoweringTest.java:4483-4484` pins the alias) and `pvalues` to a
sequential `LIST` (`ClojureLoweringTest.java:1139-1142`), both documented
deviations. The oracle evaluates each `pvalues` expression and each `pmap`
application concurrently, so the same program uses all cores there and one
here.

## What to change

- After g35 lands, lower `pmap` to a future per chunk (or per element with a
  chunked split matching the oracle's `(partition-all ...)` granularity --
  measure, do not guess) and `pvalues` to one future per expression,
  dereferenced in order, so result order and print shape stay byte-identical
  to the sequential lowering.
- Keep a sequential fallback wherever g35 still refuses (the WASM legs):
  same printed seq, documented per-backend difference, not a silent
  divergence -- the corpus pin (`ClojureSpecE2eTest` runs all four backends
  in one row) must stay green by construction.
- Error semantics follow the oracle: an exception in one branch surfaces at
  the `deref`, after the other branches were started. Record what this
  implementation guarantees for cross-thread error identity.

## Acceptance

- `clojure-spec.yaml` rows: `(pmap f coll)` and `(pvalues e...)` print the
  oracle's seq on all four backends; a timing-insensitive test proves overlap
  (e.g. two `Thread/sleep`-backed branches finish in less than their sum on
  interpreter/JVM -- no wall-clock assertions on WASM, only the printed seq).
- `ClojureLoweringTest` alias pins updated to the new lowering shape.
- `.kb/clojure-frontend.md` and `doc/en+ja/clojure/deviations.md` updated
  (en/ja mirrored in the same commit).
- Full suite green (merge `origin/develop` right before the run, one maven
  run per worktree, no `src/` edits during it).

## Non-goals

- The `future`/`promise` primitives themselves -- that is g35.
- `clojure.core.reducers/fold` parallelism (`fold` stays sequential,
  AD-058-equivalent).
