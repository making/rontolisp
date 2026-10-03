# c11. Clojure reader lacks `##NaN`, `##Inf`, `##-Inf`

Difficulty: Low

Measured 2026-10-03: `(prn ##NaN)` stops with `unsupported reader form ##` on every backend;
the oracle (clj 1.12.6.1673) reads the three symbolic values as doubles and prints them back as
`##NaN`, `##Inf`, `##-Inf`.

## Plan

- `ClojureReader.readDispatch`: `##` followed by `NaN`, `Inf` or `-Inf` reads a `LispDouble`.
  Check each backend keeps a NaN/infinity constant (JVM constant pool, wasm f64 literal) and that
  `prn` prints the `##` spelling like the oracle.
- Pin in clojure-spec with the oracle's output; `(= ##NaN ##NaN)` is `false` there (two reads are
  two objects), while `(let [x ##NaN] (= x x))` is `true` for a boxed local but `false` for a
  primitive: pin only what the oracle makes deterministic.
