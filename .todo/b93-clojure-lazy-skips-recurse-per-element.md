# b93. A lazy seq that skips realizes one nested level per skipped element

Difficulty: Medium

Measured 2026-10-03, oracle `clj` 1.12.6.1673 vs exec jar at `2ea187782`:

- `(first (filter #(> % 100000) (iterate inc 0)))`: oracle `100001`; ronto
  `error: stack overflow` (interpreter), `StackOverflowError` (JVM), wasm traps.
  The same before the lazy `for` landed (`a5e80fdf9`).
- Cause: `%clojure-filter-lazy`'s skip branch answers
  `(%clojure-seq (%clojure-filter-lazy pred (cdr s)))` from inside the thunk, so every
  skipped element nests one more `%clojure-realize`; `%clojure-realize` itself recurses
  when a thunk answers another wrapper. The oracle's `LazySeq.sval` unwraps nested lazy
  seqs in a loop, and its `filter` answers `(filter pred r)` unrealized.

## Plan

- Skip inside the thunk with a loop (the `%clojure-for-next` shape), and make
  `%clojure-realize` iterate over a chain of wrappers instead of recursing.
- Audit the other lazy producers for the same per-element nesting (`%clojure-dedupe-lazy`,
  `%clojure-partition-by-lazy`, `%clojure-concat-step` over a run of empty members, the
  transducer puller) and measure the raw wasm size per touched function.

## Pin

- `clojure-spec.yaml` (all four backends): the 100,000-skip `filter` above, and a long
  run through each audited producer.
