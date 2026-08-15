# `handler-bind` sees the errors built-ins raise

Difficulty: High

Part of `.todo/372` (rove). A test framework's one job is to turn a broken test
into a recorded failure; rove does it with `handler-bind`, twice:

```lisp
;; core/test.lisp -- around every test body
(handler-bind ((error (lambda (e)
                        (record *stats* (make-instance 'failed-assertion :form t :reason e
                                                       :desc "Raise an error while testing."))
                        (return nil))))
  (funcall function))
;; core/assertion.lisp -- around every asserted form
(handler-bind ((error (lambda (,e)
                        (record-error ,form ,steps ,e ...)
                        (unless (debug-on-error-p) (return-from ,block-label *fail*)))))
  (form-inspect ,expanded-form) ...)
```

`.kb/error-handling.md` Phase 4: handlers run through `%run-handlers`, which
ONLY the `error`/`warn`/`signal` expansions insert at their signal point. An
error a built-in raises -- `(car 1)`, `(aref v 9)`, `(/ 1 0)`, an undefined
function, `(+ 1 "a")` -- never runs a handler-bind handler on any backend:

```lisp
(block b (handler-bind ((error (lambda (e) (return-from b :caught)))) (car 1)))
;; CL: :CAUGHT
;; interpreter: Unhandled condition: car expects a cons cell, got: 1
;; JVM: Unhandled condition: class java.lang.Long cannot be cast to ...
;; wasm: trap (cast failure), no landing at all
```

So under rove one bad `car` in one test aborts the whole run instead of
printing "× 0) Raise an error while testing." and moving on. `handler-case`
does catch these on the interpreter and the JVM (its landing pad synthesizes a
`simple-error` from the raw exception -- "handlers-fall-back contract"), which
is why nothing noticed: rove is the first `handler-bind` consumer whose
protected body is USER code.

Also found while probing (interpreter): `(aref (vector 1 2) 5)` and
`(make-array -1)` escape even `handler-case` ("error: aref: index out of
bounds" at top level -- a raw Java exception, not a `LispEvalException`), and
`(elt (list 1 2) 5)`, `(nth -1 ...)`, `(coerce "abc" 'integer)` return nil
where CL signals. Fold the first two into this item (they are the same
"built-in error is not a condition" family); the silent-nil trio is `.todo/338`
material.

## Shape (recommendation)

Handlers must run at the SIGNAL POINT, before unwinding, with the clusters
rebound per CLHS -- what `%run-handlers` does for the signaled path.

- Interpreter: a built-in raises a `LispEvalException`; the place that has the
  signal point is `LispEvaluator.apply` of a built-in `LispFunction`. When
  `%HANDLER-CLUSTERS%` is non-empty and the exception has not run handlers yet
  (a flag on the exception), synthesize the condition instance (`.todo/380`'s
  typed mapping; `simple-error` until then), call `%run-handlers` right there,
  mark, rethrow. Zero cost when no cluster is established (one global read).
  Wrap the escaping Java exceptions (`IndexOutOfBounds`, `NegativeArraySize`,
  `ArithmeticException`, `ClassCast`) into `LispEvalException` at the same seam
  so `handler-case` sees them too.
- JVM: the `handler-bind` expansion's protected body gets a landing pad (the
  `handler-case` prologue that already synthesizes an instance from a raw
  `RuntimeException`) that runs THIS form's cluster with the outer clusters
  rebound, then rethrows if every handler declined -- and the interpreter must
  agree byte for byte with what that prints. Deviation to write down: for a
  raw exception the intervening `unwind-protect` cleanups between the built-in
  and the `handler-bind` run BEFORE the handler (CL: after), and restarts
  established below it are gone; a SIGNALED condition keeps the exact
  semantics. It is the same divergence `handler-case` already documents for the
  JVM's "catches any RuntimeException" width, now stated for `handler-bind`.
- wasm-GC: the same landing pad for `$lisp-cond` throws (already caught by
  `handler-case`'s try_table); raw TRAPS stay uncatchable and are the
  documented three-point-spectrum divergence -- do not widen this item into
  guarded casts. Write into `.kb/error-handling.md` that a rove test whose body
  traps ends the wasm run, and why.

Acceptance: the block above answers `:CAUGHT` on the interpreter, the JVM and
(for a signaled `type-error`) wasm; a `handler-bind` handler that declines lets
`handler-case` outside it catch; nested clusters; the interpreter `aref`/`make-array`
escapes become `handler-case`-catchable; ci-spec `handler-bind` case extended;
per-backend suites; `RoveE2eTest` (`.todo/372`) includes a test whose body does
`(car 1)` and shows "Raise an error while testing." on the interpreter and JVM.
`.kb/error-handling.md` Phase 4 paragraph + the deviation list.
