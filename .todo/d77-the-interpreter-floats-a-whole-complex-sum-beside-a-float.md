# d77. The interpreter floats a whole complex `+ - /` beside a float

Difficulty: Medium

`Environment`'s `addComplex`, `subComplex` and `divComplex` convert EVERY argument to float parts
as soon as one is a float, so an exact step never canonicalizes. SBCL folds pairwise from the
first argument, and so do the compiled backends' generic helpers (`_add` & co., `_rat_add` &
co.); `mulComplex` already folds pairwise. Measured 2026-10-06 (SBCL 2.2.9), `z` = `#c(1 2)` and
`m` = `#c(-1 -2)` through parameters:

| call | SBCL | interpreter | JVM, P1, component |
|---|---|---|---|
| `(+ z m 1.5)` | `1.5` | `#C(1.5 0.0)` | `1.5` |
| `(- z z 1.5)` | `-1.5` | `#C(-1.5 0.0)` | `-1.5` |
| `(/ z z 1.5)` | `0.6666666666666666` | `#C(0.6666666666666666 0.0)` | `0.6666666666666666` |
| `(/ z 0 1.5)` | `division-by-zero` | `#C(NaN NaN)` | `division-by-zero` |

Fold the three pairwise, like `mulComplexPair` (an exact pair stays exact, a step with a float
part goes to doubles), keeping Smith's form for a float quotient and the `-0.0` parts
(`ComplexProductSignedZeroFixture`'s shape for `+`, `-` and `/`). A real fold is a separate
question: the interpreter and the compiled f64 paths both float a real `(+ 1/10 1/5 0.0)` from
the start (`0.30000000000000004`), where SBCL answers `0.3`; measure before touching it, since
the f64 paths' contagion-first fold is what makes them raw.
