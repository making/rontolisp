# d37. A zero base to a complex power answers #C(NaN NaN)

Difficulty: Medium

Measured 2026-10-05, identical on all four backends: `(expt 0 #c(1 1))`, `(expt 0 #c(1.0 1.0))`,
`(expt 0.0 #c(1 1))` and `(expt #c(0 0.0) 2.5)` answer `#C(NaN NaN)`, and `(expt 0 #c(-1 1))`,
`(expt 0 #c(0 1))` the same. SBCL 2.2.9 answers `0`, `#C(0.0 0.0)`, `#C(0.0d0 0.0d0)`,
`#C(0.0 0.0)` and signals `division-by-zero` for the last two: a zero base to a power whose real
part is positive is a zero of the contagion type, and to one whose real part is zero or negative
has no value. The `exp(power * log 0)` route computes `log 0 = -inf` and multiplies it into NaN
parts (interpreter `Environment.exptComplex`, JVM `_cpow`, wasm-GC `WasmComplexCompiler.compileExpt`).

Decide the zero-base arm once (an exact zero signals `division-by-zero` for a non-positive real
part; a float zero follows the IEEE decision in `.kb/error-handling.md`, "Per operator") and pin it
on every backend with `DivisionByZeroFixture` / `ci-spec.yaml`.
