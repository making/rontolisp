# c10. Clojure `==` is an unknown name

Difficulty: Low

Measured 2026-10-03, `(prn (== 0.0 -0.0))`: the oracle (clj 1.12.6.1673) prints `true`, every
backend here stops with `unknown name: ==`. `==` is numeric equality across categories
(`(== 1 1.0)` is `true`, unlike `=`), over one or more numbers, a non-number signals.

## Plan

- Add `==` (and its arity rules) per `.kb/adding-primitives.md`: CL `=` pairwise.
- Pin in clojure-spec with the oracle's output, including `(== 1 1.0)` and `(== 0.0 -0.0)`.
