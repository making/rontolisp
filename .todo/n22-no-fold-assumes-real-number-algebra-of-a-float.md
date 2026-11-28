# No fold assumes real-number algebra of a float

Difficulty: Low

An optimizer that rewrites by real-number identities is wrong on IEEE 754 values: `(- x x)` is
NaN for a NaN or an infinity, not `0.0`; `(* x 0.0)` is NaN or `-0.0`; `(+ x 0.0)` turns `-0.0`
into `0.0`; `(/ x x)` is not `1.0`; `(= x x)` is false for NaN; `(not (< a b))` is not
`(>= a b)` when either is NaN; `min`/`max` and a comparison-swap of a NaN operand.

Audit every fold and simplification that can see a float operand, on every backend:
`compiler/FloatFold`, `.kb/pure-builtin-fold.md`, the JVM and WASM arithmetic compilers
(`JvmArithCompiler`, `WasmArithCompiler`), the int-fusion passes where an operand may be a
float, the no-gc backend, the Java front end's constant folding of `double`/`float`
expressions (javac folds only constant expressions, by IEEE rules), and the Scheme, Starlark
and Clojure front ends where they fold.

- First a failing case if any: one `ci-spec.yaml` case that prints each identity above for a NaN,
  both infinities, `-0.0` and an ordinary value, read from a non-literal source so no fold
  sees a constant, plus the same with literals so the folds do. Expected values are the IEEE
  results the interpreter prints.
- If all hold, keep the case as the pin and close; otherwise fix each fold and record which
  ones in `.kb/jvm-double-arithmetic.md` (or the matching file).
