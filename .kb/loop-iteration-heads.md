# `loop` iteration heads: what is assigned BEFORE vs AFTER the termination test

Shared expansion `LispMacroExpander.LoopExpander` (interpreter + all three compile dispatchers),
so everything here moves on all four backends at once. `head-k` =
`(if (not (or <clause k's end tests>)) (progn <clause k's assignments> t) nil)`, ANDed in SOURCE
order inside the `while`; a terminating clause short-circuits, so no later clause's assignments
run. Cursors step at the END of the body; heads only test them.

**Invariant: an assignment nobody is supposed to observe must not happen.** Element variables
(`for x in`, destructuring `in`/`on ... by`, `for x across`) are assigned in the head AFTER their
own end test passed (`ForPiece.headSyncs`), never at end of body. A loop variable is ONE binding
stepped in place: at exit it holds the last element, which is what a body closure, `finally` and a
later clause's head see. Do NOT make it a fresh binding per iteration — `dolist` binds freshly,
`loop` steps one; both match SBCL.

- Clauses that legitimately end at nil keep the end-of-body assignment (`ForPiece.postSteps`):
  `for c on LIST` without destructuring, `for k being the hash-key/hash-value`.
- `for VAR = INIT [then STEP]` is a `bodyPrefix` record, assigned in the head.
- `parseRepeat` places its head at its OWN position in clause order, like `for`. **Trap**: from
  the trailing `endTests` bucket instead, a later clause runs one extra time on the exhausting pass.
- Divergence: the outer `let*` binds an element variable to its first element (`guardedElt`), not
  nil, so a later clause's setup form can reference it; SBCL binds nil. Ours is the lenient superset.
- `parseForNumeric` splices literal step/limit in place (`isSelfEvaluatingNumber`), binding gensyms
  only for computed ones — that is what lets the wasm fused paths see the constant.
- `for VAR from A to B [by S]` = literal-bound `let*` + `while` against a literal + body ending in
  `(setq VAR (+ VAR S))`; `WasmCountedLoopCompiler` proves that sandwich into an unboxed `i64`
  counter (`.kb/wasm-counted-loops.md`). **Trap**: moving the step out of the `while` body,
  re-hiding a literal behind a gensym, or a second assignment silently stops it firing — never
  wrong, just slow; re-measure `counted-numeric-for` when touching `parseForNumeric`.

## Tests
`LispEvaluatorTest#evalLoopVariableKeepsLastValueAfterTermination`,
`#evalLoopRepeatIsAClauseOrderedDriver`, `#evalLoopSequentialDriverStepping`; ci-spec
`loop-variable-after-termination`. Expectations were measured against SBCL 2.6.5 — measure new
forms there too.
