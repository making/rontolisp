# `loop` iteration heads: what is assigned BEFORE vs AFTER the termination test

Shared expansion `LispMacroExpander.LoopExpander` (interpreter + all three compile dispatchers),
so everything here moves on all four backends at once. Shape: `%block` wrapping a `let*` of the
cursors and variables, then `<initially>`, then a `while` whose condition ANDs the per-clause
iteration heads in SOURCE order with `(not (or <while/until>))` and
`(not (or <always/never/thereis>))`, then the body, the end-of-body steps, `<finally>` and
`<result>`.

`head-k` = `(if (not (or <clause k's end tests>)) (progn <clause k's assignments> t) nil)`: a
terminating clause short-circuits the `and`, so **no later clause's assignments run** (CL's
clause-order evaluation). Cursors (list tails, `across` indices, the `repeat` counter) step at the
END of the body; heads only test them and derive the user-visible variables.

## Invariant

**An assignment nobody is supposed to observe must not happen.** Element variables (`for x in`,
destructuring `for (a b) in` / `on ... by`, `for x across`) are assigned in the head AFTER their
own end test passed (`ForPiece.headSyncs`), never at the end of the body. CL loop variables are
ONE binding stepped in place: at exit the variable still holds the last element, which is what a
body closure, `finally`, and a LATER clause's head see when an EARLIER driver ended. In
`for x in '(1 2 3) for y in '(10 20)`, `y` keeps 20 while `x` reaches 3 -- an end-of-body
assignment clobbers both.

- **Do not** turn this into a fresh binding per iteration: `dolist` binds freshly, `loop` steps
  one binding; both match SBCL and are supposed to disagree.
- Clauses that legitimately end at nil keep the end-of-body assignment (`ForPiece.postSteps`):
  `for c on LIST` without destructuring (the variable IS the cursor) and
  `for k being the hash-key/hash-value` (CL leaves it nil after the loop).
- `for VAR = INIT [then STEP]` never had the bug: a `bodyPrefix` record, assigned in the head.

## `repeat` is a driver, not a global end test

`parseRepeat` places its head at its OWN position in the clause order, like `for`. **Trap**: from
the trailing `endTests` bucket instead, a clause written after it runs one extra time on the
exhausting pass -- a silent extra evaluation of a side-effecting form. Mirrored clause order gives
the mirrored answer, correctly: in `for x = ... then ... repeat 3` the `for` is earlier, so its
step runs on the terminating pass too (SBCL agrees).

## Divergence: element variables bind EAGERLY

The outer `let*` binds an element variable to its first element (`(car cursor)`, `guardedElt`),
not nil, so a LATER clause's setup form can reference it; SBCL binds nil there, and the same forms
signal a type error or iterate nothing. Ours is the lenient superset. Setup forms are evaluated
once, in the outer `let*`, and there is no cheap way to hand a later one the first element
otherwise. Re-evaluate only if a real library depends on the nil-at-setup reading.

## Literal `by`/limit values splice in place

`parseForNumeric` binds step and limit behind gensyms only when the form is NOT a literal number
(`isSelfEvaluatingNumber`): `for i from 16 below 64` emits `(+ i 1)` and `(>= i 64)` directly
instead of binding `__by0` / `__limit0`. Identical semantics, and it is what lets the wasm fused
single-op / fused-compare paths see the constant -- a gensym-hidden `1` keeps every iteration on
the checked `_fx_add` helper call. A computed step or limit still binds as before.

## The numeric head's shape is what the wasm counted loop recognizes

`for VAR from A to B [by S]` = a `let*` binding `VAR` to a literal `A`, a `while` testing it
against a literal `B`, and a body ending in `(setq VAR (+ VAR S))`. `WasmCountedLoopCompiler`
proves that sandwich into an unboxed `i64` counter (`.kb/wasm-counted-loops.md`). **Trap**: moving
the step out of the `while` body's statement list, re-hiding a literal bound behind a gensym, or
adding a second assignment of the variable silently stops it firing -- it can never be made WRONG
(the recognizer re-derives from the AST); re-measure the `counted-numeric-for` shapes when you
touch `parseForNumeric`.

## Tests

`LispEvaluatorTest#evalLoopVariableKeepsLastValueAfterTermination` (the closure/`finally` table:
`in`, both destructuring shapes, `on`, `across`, numeric, `= then`, hash, parallel `and`, two
sequential drivers), `#evalLoopRepeatIsAClauseOrderedDriver` (`repeat` side-effect counts in both
clause orders), `#evalLoopSequentialDriverStepping` (the eager-binding divergence); ci-spec
`loop-variable-after-termination` (the same table on all four backends). Every expectation was
measured against SBCL 2.6.5 -- measure new forms there too.
