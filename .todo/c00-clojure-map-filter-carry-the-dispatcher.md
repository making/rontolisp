# c00. Clojure map/filter carry the IFn dispatcher into every program

Difficulty: Medium

Measured 2026-10-03 (raw wasm, default optimize): `(println (filter odd? [1 2 3 4]))`
is 59,248 B; `(println (remove odd? [1 2 3 4]))` is 39,262 B. The difference is
`rontolisp::%clojure-call`, which `%clojure-map`, `%clojure-filter`, `%clojure-mapcat`
(and the other runtime workers calling it) reach from the runtime, so a program passing
a plain function still splices the dispatcher and everything behind it (set/record/
keyword lookups, `%clojure-table-key`, the structural-key runtime). `reduce` and the
dropping verbs (`remove`, `keep`, `keep-indexed`, `map-indexed`, `distinct`) already
take a real function and wrap any other value at the call site
(`ClojureSeqLowering.withRealFun`).

## Plan

- Give `%clojure-map`/`-filter` (and every other runtime worker that calls
  `%clojure-call` on a user function argument: grep it in `clojure.lisp`) a real
  function, wrapped at the call site through `withRealFun`; value forms wrap in their
  lambda. `%clojure-map` with several collections funcalls with `apply`.
- Measure the raw wasm of a `map`-only and a `filter`-only program before/after, and
  the interpreter time of a 100,000-element strict `map`.

## Pin

- `ClojureLoweringTest`: a function argument passes as itself, a set/map variable is
  wrapped at the call site; `clojure-spec.yaml` keeps passing on all four backends.
