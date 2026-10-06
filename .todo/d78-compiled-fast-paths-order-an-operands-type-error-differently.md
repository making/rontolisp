# d78. Compiled numeric fast paths order an operand's type error differently

Difficulty: High

The interpreter (and SBCL) evaluates every argument of an operation, then applies it: a
wrong-typed operand signals after the operation's later arguments ran, and an inner operation
signals before its outer one's later arguments run. Three compiled fast paths order it
otherwise. Measured 2026-10-06 (SBCL 2.2.9) in a program that cannot observe a complex, `a`
holding the symbol `A`:

| form | SBCL, interpreter | JVM | P1, component |
|---|---|---|---|
| `(+ a (progn (princ "z") 1.5))`, a statement | `z`, then `+` signals | `+` signals, no `z` | `+` signals, no `z` |
| the same as a `let` init or a `format` argument | `z` first | no `z` | `z` first |
| `(+ (* 2.0 a) (progn (princ "y") 1.5))` | `*` signals, no `y` | `+` signals, no `y` | `*` signals, no `y` |
| `(+ (* 2 a) (progn (princ "x") 1))` | `*` signals, no `x` | `x`, then `*` | `x`, then `*` |
| `(* 2 (+ 1 a) (progn (princ "u") 3))` | `+` signals, no `u` | `u`, then `+` | `u`, then `+` |

- The f64 paths (`JvmArithCompiler.compileUnboxedOperand`, `WasmArithCompiler`'s float-literal
  arm) convert each operand (`_dbl`, `_as_f64`) before the next one runs.
- The JVM inlines an inner float-literal operation with the outer operator current, so its
  wrong-type report names `+`.
- Int fusion (`JvmIntFusionCompiler`'s `_fx$N`, `WasmIntFusionCompiler`) evaluates every leaf
  before the tree, which breaks its own invariant ("never change ... an observable side
  effect"; `.kb/jvm-int-fusion.md`).

In a program that may observe a complex, the float-literal operations already run in the
interpreter's order (`JvmFloatOperands`, `WasmFloatOperands`; `.kb/jvm-complex.md`, "A complex
beside a float literal"), and a complex-free program could take the same per-operation
evaluation without the complex tests. Fusion's single call over all its leaves is its fast
path, so its fix is checking each inner operation's leaves where that operation stands. Measure
each path (the JVM float tree under Graal and C2, fusion's prologue, P1) before changing it.
