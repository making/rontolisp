# g04. `_funcrefs` field survives dead-method elimination on `(print 1)`

Difficulty: Small

Found during the g03 final full-suite run (2026-10-13, worktree
rontolisp-wt-g03): `JvmDeadMethodEliminationTest#dropsUnreferencedFields`
compiles `(print 1)` at `OptimizeLevel.DEFAULT` and expects exactly the fields
`_renderPath _renderDepth _col _main$args _main$thrown`, but the class also
carries `_funcrefs`. Reproduces on clean develop without the g03 changes (verified
by stash), so it is fallout of an earlier item -- suspect f94
(`9770b90f1`, canonical per-defun boxes), which introduced the table
(`JvmLispCompiler`, `_funcrefs` fill) without updating this test's expectation.

## Plan

1. Decide whether `(print 1)` should keep `_funcrefs` (f94 needs the table
   wherever a box can be shared) or the shaker should drop it when no kept body
   references a box.
2. Fix the emitter/shaker with the three-copy rule where it applies, update the
   test expectation.
3. Re-run `JvmDeadMethodEliminationTest` and the f94/f95 pins.
