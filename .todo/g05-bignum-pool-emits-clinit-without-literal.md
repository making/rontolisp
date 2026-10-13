# g05. Bignum pool emits `<clinit>` for a program with no bignum literal

Difficulty: Small

Found during the g03 final full-suite run (2026-10-13, worktree
rontolisp-wt-g03):
`JvmLispCompilerTest#aProgramWithoutABignumLiteralGetsNoPoolAndNoClassInitializer`
compiles `(print (+ 1 2))` and expects no `_bi$` fields and no `<clinit>`, but
`<clinit>` is present (count 1). Reproduces on clean develop without the g03
changes (verified by stash), so it is fallout of an earlier item in the
bignum-pool / per-defun-box area (f94/f95 era: `af1c467fe` built the pool,
`JvmLispCompiler` gained a `<clinit>` fill for the `_funcrefs` table).

## Plan

1. Decide whether the initializer is required unconditionally (then the test's
   contract changes) or emittable only when the pool/table is non-empty.
2. Fix with its pinning test; re-run `JvmLispCompilerTest` and the f94/f95 pins.
