# A condition's `:report` is applied when it is SIGNALLED, never when it is PRINTED

Found 2026-07-29 while landing `.todo/202` (postmodern). In Common Lisp,
`princ` / `~A` / `format ... "~a"` of a condition object runs its report;
`prin1` / `~S` prints the `#<...>` unreadable form. rontolisp applies the
report only inside `expandSignalDesignator`, to build the *message string* of
the signal (`.kb/error-handling.md`, "error/warn/signal share
expandSignalDesignator"). The OBJECT keeps the generic instance rendering, so:

```lisp
(define-condition my-e (error) ((msg :initarg :msg :reader my-msg))
  (:report (lambda (c s) (format s "my-e: ~a" (my-msg c)))))
(handler-case (error 'my-e :msg "boom") (error (e) (format t "~a~%" e)))
;; rontolisp: #<MY-E :MSG boom>
;; Common Lisp: my-e: boom
```

The same for the built-in `simple-condition` family, whose report is
*specified* as `(apply #'format stream (simple-condition-format-control c)
(simple-condition-format-arguments c))`:

```lisp
(warn 'simple-warning :format-control "sw ~A/~A" :format-arguments (list 1 2))
;; rontolisp: WARNING: sw ~A/~A      <- the raw control string
;; Common Lisp: WARNING: sw 1/2
```

That second shape is not academic: `cl-postgres`'s `get-warning` surfaces every
server NOTICE as `(warn 'postgresql-warning :format-control "PostgreSQL
warning: ~A~@[~%~A~]" :format-arguments (list ...))`, so today a rontolisp
program running postmodern prints the *format string* where the server's
message belongs. Any library that reports through a condition object has the
same hole.

## Scope

- `princ` / `display()` of a condition instance = its report; `prin1` /
  `print()` unchanged (`#<TYPE :slot value ...>`, `.kb/instance-syntax.md`).
- `format`'s `~A` follows `princ`, `~S` follows `prin1` -- no separate rule.
- The `simple-condition` family (`simple-error` / `simple-warning` /
  `simple-condition` and every subclass, which is what `define-condition
  postgresql-warning (simple-warning)` makes) reports through
  `format-control` + `format-arguments` when it has no `:report` of its own.
  `format-control` may itself be a function, per the standard.
- `warn`'s "WARNING: ..." line renders the same way -- it is where the gap was
  found.
- Where the report is a LAMBDA it takes `(condition stream)`; the existing
  signal path already renders one through `with-output-to-string` + `funcall`,
  so the machinery exists -- what is missing is the hook from the PRINT side.
- All four backends plus the `%obj-*` print path (`.kb/instance-syntax.md`),
  since the rendering must be identical everywhere. `print-object` already
  intercepts instance printing (`.todo/199`); the condition report is the same
  seam and should go through it rather than beside it.

## Why it was not done under `.todo/202`

The milestone program runs without it. Two lines of `PostmodernE2eTest`'s
`RESTARTS_EXPECTED` pin the deviating rendering (they are identical on all
three backends, which is what that test is for) -- update them when this
lands. The sibling deviation on the same lines is `*error-output*` not
reaching the error stream, which `.todo/149` owns.

## Verification

- `LispEvaluatorTest` / `JvmLispCompilerTest` /
  `WasmLispCompilerIntegrationTest`: a `:report` lambda, a `:report` string,
  and a `simple-warning` with `:format-control` / `:format-arguments`, each
  through `~A` and through `warn`.
- A `ci-spec.yaml` case so the native-image job covers all four backends.
- `PostmodernE2eTest.RESTARTS_EXPECTED` and any `doc/{en,ja}` example that
  shows a printed condition.
