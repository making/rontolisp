# d57. A complex beside a float literal fails on the compiled backends

Difficulty: High

Measured 2026-10-06, with a complex in a variable `z`: `(+ z 1.5)`, `(* 2.0 z)`, `(- z 0.5)`,
`(/ z 2.0)` and `(exp (* 1.0 z))` answer the complex on the interpreter (SBCL 2.2.9 agrees) and
signal `type-error` on the JVM, P1 and the component. The same operators without the float
literal answer the complex on every backend: their generic helpers carry the run-time arm
(`.kb/jvm-complex.md` / `.kb/wasm-complex.md`, "A complex through a variable").

A double-literal form takes the unboxed double path instead (`JvmArithCompiler.compileUnboxed`
and the unboxed operands of `JvmMathFnCompiler`, `JvmAbsCompiler`, `JvmExptCompiler`; on WASM the
`hasDoubleLiteral` arm of `WasmArithCompiler` and the `castFloatGetF64` operands of the abs,
expt and transcendental sites), and every operand an expression produces is unboxed through
`_dbl` / `_as_f64`, whose complex arm signals.

Do not route those operands through the generic helpers: on WASM every interior node would box
(no escape analysis), and the n-body-shaped float code these paths exist for would slow down.
The shape that keeps the fast path is fusion's: in a program `ComplexCapability` gates, evaluate
the operands a variable or call produces into temps first (left to right, so side effects keep
their order), test them for a complex, and run the generic fold (whose helpers answer the
complex) over the temps when one is; the raw tree otherwise. Measure the guard on an n-body and
on a float loop whose operands can be complex, P1 and JVM (Graal and C2), and the bytes per site.
