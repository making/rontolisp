# 397. An `unwind-protect` cleanup clobbers the protected form's secondary values

Difficulty: Medium

Found by the dexador spike (`.todo/396`). The cleanup forms of an
`unwind-protect` are evaluated for effect and CL discards their values -- the
form answers the protected form's values, all of them. Here the cleanup's value
count wins:

```lisp
(defun zero () (values))
(defun j3 () (unwind-protect (values 1 2 3) (zero)))
(multiple-value-list (j3))      ; => (1)        expected (1 2 3)

(defun j4 () (unwind-protect (values 1 2 3) (values 7 8)))
(multiple-value-list (j4))      ; => (1 8)      expected (1 2 3)
```

`(1 8)` is the tell: the primary value survives (it is the form's ordinary
result) and every SECONDARY value is read back out of whatever slot the
cleanup left behind. A cleanup that returns one value truncates to one; a
cleanup returning zero values truncates to one as well; a cleanup returning two
splices its second value in as the protected form's second.

**All four backends, identically** (interpreter, JVM, WASM Preview 1, WASM
`--component`, verified 2026-08-16) -- so this is the shared multiple-value
representation, not a codegen accident. A cleanup that is a literal `nil`, or
any form the evaluator answers a single plain value from without touching the
pending-values channel, hides the bug, which is why every hand-written probe
missed it and only a real library found it.

## Why it matters

`(unwind-protect <compute> (release ...))` is THE shape of every
resource-owning function, and a `release` helper ending in `(values)` -- the
idiomatic "I return nothing" -- is exactly the poison. dexador's
`request` is that function: it returns
`(values body status headers uri stream)` out of an `unwind-protect` whose
cleanup calls `dexador.connection-cache:push-connection`, whose last form is
`(values)`. Every dexador caller therefore sees the body and NOTHING else --
no status code, no header table. The user's code is correct; ours is not.

Anything else built on the same shape is silently affected the same way, which
makes this a correctness bug well beyond `.todo/396`.

## The work

- Find the pending/secondary-values channel each backend uses (`.kb/multiple-values.md`)
  and make `unwind-protect` SAVE it across the cleanup and RESTORE it after --
  on the normal exit and on the unwind path both.
- The non-local-exit path needs the same treatment: `(unwind-protect
  (return-from f (values 1 2 3)) (cleanup))` truncates identically, and there
  the values are already in flight.
- Check the sibling forms that also run user code between a value-producing
  form and its consumer -- `handler-case`'s `:no-error`, `catch`/`throw`,
  `restart-case`, the `with-*` macros expanding to `unwind-protect` -- and pin
  whichever share the channel.
- Pin with a table-driven test over cleanup shapes (`nil`, `(values)`, one
  value, two values, a function call, a nested `unwind-protect`) x exit shapes
  (fall-through, `return-from`, `go`, a signalled unwind) x all four backends,
  plus a `ci-spec.yaml` case.
- Update `.kb/multiple-values.md` with the invariant ("a cleanup's values are
  discarded; the protected form's value COUNT is part of what is restored") and
  name the test.
