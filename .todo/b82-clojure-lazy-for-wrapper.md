# b82. Lazy `for` answering a wrapper (follow-up to b81)

Difficulty: High

Measured 2026-10-02, oracle `clj` 1.12.6.1673 vs exec jar at `ec836db36`:

- After b81 (measured 2026-10-02), `examples.test.lazy-index-of-any` is
  `2 failures, 0 errors` (oracle `0 failures, 0 errors`): both `with-out-str`
  comparisons capture all 10 `Iterating over` lines where the oracle captures 1
  and 4: oracle realizes `logging-seq` prints lazily
  (`(take 1 (for [x (logging-seq "abcd")] x))` prints once), while ronto's
  `for` is strict by design (lowering-table `for` row: nested `dolist`
  accumulating in reverse into a strict list), so the whole input realizes
  and the captured `"Iterating over ..."` lines differ.
- `.kb/clojure-frontend.md` already lists "a lazy `for` input
  (`lazy-index-of-any`)" as a known gap with the "pass a taken prefix"
  workaround.

## Plan

- Answer a lazy wrapper from `for` (realize on demand through the
  `%clojure-make-lazy` / puller machinery b11/b60 built), so `first`/`take`
  stop realization and the print counts match the oracle.
- This reverses the documented strict-`for` row: audit every
  strictness-dependent `for` user (`dorun`/`doall`, `into`, transducer
  consumers, `ClojureWasm*` size legs) and re-measure the
  `logging-seq-realizes-only-take` and `primes-prefix-over-lazy-sieve` spec
  cases plus the wasm size deltas.
- If the audit shows the blast radius is not worth it, close this item as
  documented-divergence instead (strict `for` + taken-prefix workaround) and
  keep the corpus test on the expect-fail list.

## Pin

- `clojure-spec.yaml` (all four backends):
  `logging-seq-realizes-only-what-first-takes` (the 1-line/4-line shapes).
- E2E: `examples.test.lazy-index-of-any` byte-identical to the oracle.
- Size: raw wasm totals before/after for a `for`-only program (the b21/b60
  measurement convention).
