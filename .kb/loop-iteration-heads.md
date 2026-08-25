# `loop` iteration heads: what is assigned BEFORE vs AFTER the termination test

`loop`'s extended form is one shared expansion (`LispMacroExpander.LoopExpander`,
reached by the interpreter and all three compile dispatchers), so everything here
moves on all four backends at once or not at all.

The expansion is a `let*` of every loop variable wrapping a `while` whose condition
is a conjunction of **per-clause iteration heads** in SOURCE order:

```
(%block
  (let* (<cursors and variables>)
    <initially>
    (while (and head-1 head-2 ... (not (or <while/until>)) (not (or <always/never/thereis>)))
      <body>
      <end-of-body steps>)
    <finally>
    <result>))
```

`head-k` is `(if (not (or <clause k's end tests>)) (progn <clause k's assignments> t) nil)`
-- so a clause that terminates short-circuits the `and` and **no later clause's
assignments run**, which is CL's clause-order evaluation. The cursors themselves
(list tails, `across` indices, the `repeat` counter) step at the END of the body;
the head only tests them and derives the user-visible variables from them.

## The invariant

**An assignment whose value nobody is supposed to observe must not happen.** An
element variable (`for x in`, a destructuring `for (a b) in`/`on ... by`,
`for x across`) is assigned in the iteration head, AFTER its own end test passed
(`ForPiece.headSyncs`), never at the end of the body. CL's loop variables are ONE
binding stepped in place, so at loop exit the variable still holds the last element
-- which is what a closure built in the body sees when it is called afterwards, what
`finally` sees, and what a LATER clause's head sees when an EARLIER driver is the one
that ended.

```lisp
(mapcar #'funcall (loop for x in '(1 2 3) collect (lambda () x)))   ; (3 3 3), was (NIL NIL NIL)
(loop for (a b) on '(1 2 3 4) by #'cddr finally (return (list a b))) ; (3 4)
(loop for x in '(1 2 3) for y in '(10 20) finally (return (list x y))) ; (3 20)
```

The last one is the whole point of the per-clause head: `y`'s driver ends first, so
`y` keeps 20 while `x`, whose own test passed, does advance to 3. Assigning at the
end of the body cannot express that -- it clobbers both.

Do NOT "fix" this into a fresh binding per iteration. `dolist` binds freshly (its
three closures answer `1 2 3`); `loop` steps one binding (its three answer `3 3 3`).
Both match SBCL, and they are supposed to disagree.

Clauses that legitimately end at nil keep the end-of-body assignment
(`ForPiece.postSteps`), because there the final assignment IS observable behavior:

- `for c on LIST` (no destructuring) -- the variable IS the cursor; it ends nil.
- `for k being the hash-key/hash-value` -- CL leaves it nil after the loop (measured
  on SBCL 2.6.5 with 1 and 3 entries), so it is synced at the end of the body.

`for VAR = INIT [then STEP]` never had the bug: it is a `bodyPrefix` record assigned
in the head from the start.

## `repeat` is a driver, not a global end test

`repeat N` contributes an iteration head at **its own position in the clause order**
(`parseRepeat`), exactly like `for`. It used to push its test into the trailing
`endTests` bucket (evaluated after every head), which let a clause written after it
run one extra time on the pass that exhausts the count -- a silent extra evaluation
of a side-effecting form, not just a wrong final value:

```lisp
(let ((s (list 1 2 3 4 5))) (loop repeat 3 for x = (pop s) collect x) s) ; (4 5), was (5)
(loop repeat 3 for x = 1 then (* x 2) finally (return x))                ; 4, was 8
```

The mirrored order is the mirrored answer, and that is correct: in
`for x = ... then ... repeat 3` the `for` clause is earlier, so its step DOES run on
the terminating pass (SBCL agrees -- the `then` form runs 3 times, not 2).

## The deliberate divergence: element variables are bound EAGERLY

The outer `let*` binds an element variable to its first element (`(car cursor)`,
`guardedElt`) rather than to nil, so a LATER clause's **setup form** can reference it:

```lisp
(loop for x in '(3 4) for i from x to 10 collect (list x i)) ; ((3 3) (4 4)) here; SBCL: type error
(loop for x in '((1 2) (3 4)) for y in x collect y)          ; (1 2) here; SBCL: NIL
```

SBCL binds those variables to nil and only assigns them in the head, so a later
clause's setup form sees nil and the two forms above signal / iterate nothing.
Ours is the lenient superset; it is pinned by
`LispEvaluatorTest.evalLoopSequentialDriverStepping`. **Reason for the divergence**:
setup forms are evaluated once, in the outer `let*`, and there is no cheap way to
give a later clause's setup form the "first element" other than binding it there --
matching SBCL would mean turning working programs into type errors for no gain.
Re-evaluate if a real library ever depends on the nil-at-setup reading (a nested
`for y in x` that means to iterate nothing).

## Literal `by`/limit values splice in place (todo-413, 2026-08-16)

`parseForNumeric` binds the step and limit behind gensyms only when the form is
NOT a literal number (`isSelfEvaluatingNumber`): `for i from 16 below 64` steps
by `(+ i 1)` and tests `(>= i 64)` directly, where it used to bind `__by0 = 1`
and `__limit0 = 64` and emit `(+ i __by0)` / `(>= i __limit0)`. Semantically
identical (a literal has no side effect for the once-only binding to guard),
and it is what lets the wasm backend's fused single-op / fused-compare paths
see the constant -- the gensym-hidden `1` kept every iteration of ironclad's
`sha256-expand-block` on the checked `_fx_add` helper call (~14% of the PBKDF2
profile). A computed step or limit still binds exactly as before.

## The numeric head's shape is what the wasm counted loop recognizes

`for VAR from A to B [by S]` expands to a `let*` binding `VAR` to a literal `A`
plus a `while` whose test compares `VAR` against a literal `B` and whose body
ends in `(setq VAR (+ VAR S))`. On wasm-GC that exact sandwich is what
`WasmCountedLoopCompiler` proves into an unboxed `i64` counter
(`.kb/wasm-counted-loops.md`) -- worth 2.2x on a 10^8-iteration `sum`. The
recognizer re-derives everything it needs from the AST in front of it, so a
change here cannot make it WRONG; it can only make it stop firing. If the
expansion ever moves the step out of the `while` body's statement list, hides a
literal bound behind a gensym again (see the section above), or introduces a
second assignment of the variable, that speed is silently gone -- re-measure the
`counted-numeric-for` shapes when you touch `parseForNumeric`.

## Pinning

- `LispEvaluatorTest.evalLoopVariableKeepsLastValueAfterTermination` -- the full
  closure/`finally` table (`in`, both destructuring shapes, `on`, `across`,
  numeric, `= then`, hash, parallel `and`, two sequential drivers).
- `LispEvaluatorTest.evalLoopRepeatIsAClauseOrderedDriver` -- the `repeat`
  side-effect counts in both clause orders.
- ci-spec `loop-variable-after-termination` -- the same table on all four backends.

Every expectation in those three was measured against SBCL 2.6.5 first; when you
extend them, measure the new form there too rather than asserting what looks right.
