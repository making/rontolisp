# 576. A typed loop declines a declared-float free variable

Difficulty: Low

Since todo-569 a bound-declared float `let` local lives in a raw `double` slot
(`Ctx.rawDoubleLocals`, `.kb/jvm-double-arithmetic.md`). The typed-loop
compiler (`.kb/jvm-typed-loops.md`) resolves free variables through
`ctx.locals`/`ctx.captures`/`ctx.globals` only (`JvmTypedLoopCompiler
.resolvable`), so a `dotimes` whose free accumulator is such a local throws
`Ineligible` and the WHOLE loop falls back to the boxed emission -- correct,
just slower than either feature alone. `bench-report/programs/matmul.lisp`'s
diagonal-sum loop is the live example (200 iterations, so it costs nothing
there today).

The fix is small and strictly additive: a raw double local is the EASIEST free
variable the typed loop could have -- already a raw `double`, no entry guard
needed (the slot is always authoritative), read `dload` at entry, write-back
`dstore` (replacing the `Double.valueOf`/`astore` write-back and the
exception-path copy). Admit it in `resolvable`/`freeVar` as a strict
`T.DOUBLE`, assigned allowed, and skip the boxed write-back for it.

Acceptance: a `dotimes` over a packed float array accumulating into a
bound-declared float local compiles onto the typed path (pin bytes or a debug
counter), answers what the boxed path answers, and
`typedLoopsMatchTheBoxedPathAndTheSizeLevelDeclinesThem` keeps passing.
