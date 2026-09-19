# Interpreter's `(-)` / `(/)` should signal the compile path's error, not crash

Difficulty: Low

Split off `.todo/826`'s bullet: "`(-)` on the interpreter reports `Index 0 out of bounds for
length 0`; the compile path says `- requires at least one argument`
(`compiler/ArithmeticIdentities`)."

`+` and `*` have an identity with no arguments (CLHS 12.2: 0 and 1); `-` and `/` do not, and
both the compile path (`compiler/ArithmeticIdentities`, used by `JvmArithCompiler` /
`WasmArithCompiler`) and SBCL / Gauche reject `(-)` / `(/)` outright. The interpreter instead
let `args.get(0)` in `Environment.registerArithmetic`'s `SUB`/`DIV` lambdas throw a raw
`IndexOutOfBoundsException`, which the evaluation seam
(`LispEvaluator.evalConsClassifyingRawFailures`) already turns into a catchable
`program-error` -- so it was never an uncaught crash, just a useless message ("Index 0 out of
bounds for length 0" instead of "- requires at least one argument").

Fix: add an explicit `args.isEmpty()` check at the top of `SUB` and `DIV` throwing
`LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME, "<op> requires at least one
argument")`, matching `ArithmeticIdentities`'s text. `ADD`/`MUL` need no change: their empty-args
fold already lands on the correct identity (0 / 1).
