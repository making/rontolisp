# `%handlers-ran%` is keyed by nothing: a cleanup's walk re-runs an outer condition's handlers

Difficulty: High

Found 2026-09-26 while doing a43 (the JVM's `_condTl` keyed by its throwable). `%run-handlers` sets
the one global `%handlers-ran%` to its condition at the end of every walk -- a walk a `handler-case`
cluster stopped included -- and a `%hb-guard` pad skips its walk only for the condition that mark is
`eq` to. A condition signaled and handled inside an `unwind-protect` cleanup while another is on its
way out replaces the mark, so the outer condition reaches the pad unrecognized and its handlers run
a second time. All four backends:

```lisp
(define-condition typed-failure (error) ())
(define-condition other-failure (error) ())
(defvar *runs* nil)
(print (handler-case
         (handler-bind ((error (lambda (c) (push (list :outer (type-of c)) *runs*))))
           (handler-bind ((error (lambda (c) (push (list :inner (type-of c)) *runs*))))
             (unwind-protect (error 'typed-failure)
               (handler-case (error 'other-failure) (other-failure () nil)))))
         (typed-failure () :typed)))
(print (reverse *runs*))
```

| | SBCL 2026-09-26 | interpreter, JVM, wasm-GC P1 + component |
|---|---|---|
| `*runs*` | `((:INNER TYPED-FAILURE) (:OUTER TYPED-FAILURE))` | the same pair twice |

The interpreter also walks a BUILT-IN failure at its signal point, so there the same happens with a
raw failure handled in the cleanup (`(unwind-protect (bad "a") (handler-case (bad "b") (error ()
nil)))` under two `handler-bind`s: four entries; SBCL and the compiled backends two, since they walk a
raw failure only at the pad and the cleanup's `handler-case` catches `b` before any pad).

- Where: `LispMacroExpander.runHandlersDefun` (the mark), `hbGuardHandlerForm` (the compiled pads'
  test), `LispEvaluator.evalHbGuard` and the interpreter's signal-point walk.
- Directions to weigh: a walk stopped by a `handler-case` needs its mark only while its throwable
  travels to that `handler-case`, so the mark could be saved and restored around a handling
  `handler-case` (the `handlerCaseProtectedForm` wrapper already saves and restores the cluster
  stack) -- check a non-matching `handler-case` in between; or several marks (a bounded list, as
  the JVM's `_jsigTl`); a per-instance flag would change every condition layout. wasm-GC has no
  weak references, so nothing JVM-only.
- Pin: a `ci-spec.yaml` case with the program above, plus the raw-failure one, on all four backends.
