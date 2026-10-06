# d50. The interpreter's float complex product loses a negative zero part

Difficulty: Low

Measured 2026-10-06: `(* #c(0.0 -0.0) #c(1 -1))` and `(* #c(-0.0 -0.0) 0)` print `#C(0.0 0.0)` on
the interpreter and `#C(0.0 -0.0)` on the JVM, P1 and the component. `Environment.mulComplex`
folds from the seed `(1.0, 0.0)`, and `1.0 * -0.0 + 0.0 * x` is `+0.0`; the compiled `_c_mul` /
`_cmul` multiply the operands pairwise. Seed the fold with the first argument and pin the signed
zeros on all four backends.
