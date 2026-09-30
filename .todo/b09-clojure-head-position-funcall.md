# b09: Clojure calls to function values in head position (b08 follow-up)

Difficulty: Medium

## Premise (measured, 2026-09-30)

b08 lowered `ex-info`/`ex-data`/`ex-message` with function-value lambdas and
`memfn` as a head-callable lambda, and found by measurement (not by reading)
that neither can be *called* from a function parameter or a `def`'d variable:

- `((fn [mk] (ex-data (mk "v" 2))) ex-info)` signals
  `The function c%mk is undefined`.
- `(def to-up (memfn toUpperCase)) (to-up "hey")` signals
  `The function c%to-up is undefined`.
- `(defn call-it [f x] (f x)) (call-it inc 41)` signals
  `The function c%f is undefined` on the unmodified b08 tree.

The lowering's `call` emits a direct call of the mangled name, which reads the
function cell; a parameter or a `def` (`Kind.VARIABLE`) holds the lambda in the
value cell, so the call misses. As values everything works (`(map ex-data xs)`,
`(map to-up xs)`, `(apply ex-info [...])` are pinned in `clojure-spec.yaml`).

## Shape

- The open question is whether a head-position call to a `VARIABLE`-kind name
  lowers to `funcall` (or the equivalent) on all four backends, and what that
  does to higher-order `defn` parameters generally (b04 area, not just Clojure
  values). A `declare`d-but-never-defined name must keep its current error.
- Each change stays independently shippable with `clojure-spec.yaml` cases
  (`ClojureSpecE2eTest` on all four backends).

## Tests

- `clojure-spec.yaml` cases as each lands; `ClojureSpecE2eTest` on all four
  backends.
