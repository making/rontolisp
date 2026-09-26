# A synthesized condition's report re-formats its already-rendered message

Difficulty: Medium

A condition built for a failure that carries only TEXT (`LispEvaluator.synthesizeCondition`, the
compiled backends' `reportingConditionForm`) stores that text in `format-control` with no
arguments, and the report renders `format-control` through `format`. A `~` in the text is then a
directive. Measured 2026-09-26:

```lisp
(print (handler-case (error "a~~b") (error (e) (princ-to-string e))))
(print (handler-case (car "~a") (error (e) (princ-to-string e))))
(defun te (thunk) (handler-case (funcall thunk) (type-error (e) (princ-to-string e))))
(print (te (lambda () (+ 1 "~a"))))
```

- Interpreter: `"aNIL"`, `"car expects a cons cell, got: \"NIL\""`, `"+: The value \"NIL\" is not
  of type NUMBER"` -- the message of `(error "a~~b")` is formatted once at the signal and again at
  the report.
- JVM: `"a~b"`, the text verbatim for `+` -- but a program whose `type-error` clause also reads
  `type-error-datum` printed `"+: The value \"NIL\" ..."` for the same operand, so the JVM's two
  pad paths disagree.
- wasm-GC: verbatim (`(car "~a")` traps there, outside this item).

The report must print the rendered text verbatim wherever it came from, while
`simple-condition-format-control` of a user's `(error "a~~b")` stays the control the user wrote.
Find the one place each backend renders a synthesized condition's report and pin the three lines
above in `ci-spec.yaml`.
