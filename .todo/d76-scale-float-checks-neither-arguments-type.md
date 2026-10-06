# d76. `scale-float` checks neither argument's type

Difficulty: Low

SBCL refuses `(scale-float x n)` unless `x` is a FLOAT and `n` an INTEGER (`type-error`, datum the
argument, expected type `FLOAT` / `INTEGER`). Here nothing checks either, and the backends answer
differently. Measured 2026-10-06 (SBCL 2.2.9), each argument read through a variable, in a program
that may observe a complex:

| call | SBCL | interpreter | JVM, P1, component |
|---|---|---|---|
| `(scale-float #c(2.0 4.0) 1)` | `type-error` FLOAT | `type-error` NUMBER, no operator | `#C(4.0 8.0)` |
| `(scale-float 3 1)` | `type-error` FLOAT | `6.0` | `6.0` |
| `(scale-float 1/2 1)` | `type-error` FLOAT | `1.0` | `1.0` |
| `(scale-float 'a 1)` | `type-error` FLOAT | `type-error` NUMBER, no operator | `*: ... NUMBER` |
| `(scale-float 1.5 1.5)` | `type-error` INTEGER | `type-error` INTEGER | `4.242640687119285` |

The compiled backends lower it (`LispMacroExpander.expandScaleFloat`) to a product of
`(expt 2.0 k)` factors, so the float's only check is the multiplication's, which answers a
complex wherever a float-literal operation can meet one (`.kb/jvm-complex.md`, "A complex beside a
float literal"; the JVM signalled REAL there before, WASM never did). Check both arguments in the
lowering and in `Environment`'s `scale-float`, with SBCL's expected types under the operator
`SCALE-FLOAT`, and pin the rows on all four backends.
