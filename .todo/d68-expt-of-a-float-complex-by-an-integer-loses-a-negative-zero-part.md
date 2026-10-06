# d68. `expt` of a float complex by an integer loses a negative zero part

Difficulty: Medium

Measured 2026-10-06 against SBCL 2.2.9 on the interpreter, JVM, P1 and the component alike:
`(expt #c(0.0 -0.0) 2)` is `#C(0.0 -0.0)` in SBCL and `#C(0.0 0.0)` here, and
`(expt #c(1.5 -0.0) 3)` is `#C(3.375 -0.0)` and `#C(3.375 0.0)`. SBCL multiplies for an integer
exponent; `Environment.exptComplex`, `_cpow` and the WASM twin send a float base through
`exp(w*log z)`, which also rounds the digits differently
(`(expt #c(1.1d0 2.2d0) 3)` is not the product's value in the last places). Take the
exact-by-squaring path for an integer exponent over float parts too (the product now keeps a
`-0.0` part, `.kb/jvm-complex.md`), pin the answers on all four backends, and check a
negative integer exponent (SBCL: the reciprocal of the power).
