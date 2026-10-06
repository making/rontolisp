# d49. A complex reaching arithmetic through a variable fails on the compiled backends

Difficulty: High

Measured 2026-10-06: `(defun m (a b) (* a b)) (m (complex 1 1) 2)` answers `#C(2 2)` on the
interpreter; the JVM reports `*: The value #C(1 1) is not of type NUMBER`, P1 and the component
trap (`unreachable`). Same for `sqrt`, `exp` and `expt` through a parameter, and for
`(funcall #'expt 2 #c(1 1))`. The complex steering is syntactic (`containsComplex` /
`hasComplexOperand`), so a call site with no complex literal or `complex` form never reaches the
`_c*` group; `.kb/wasm-complex.md` and `.kb/jvm-complex.md` list it as a known corner.

Decide where the run-time holder arm lives (the generic `_mul`/`_add`/... and the unary math
entries, behind the `_hasComplex` probe on the JVM; the `_rat_*` / `_as_f64` funnels on wasm-GC)
so a complex-free program stays byte-identical, then pin a function-parameter corpus on all four
backends.
