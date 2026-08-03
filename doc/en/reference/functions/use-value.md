# use-value

`(use-value value [condition])`

Invokes the innermost active `use-value` restart with `value`, and returns `nil` when none is active (like [`continue`](continue.md), not an error). Calling it from a [`handler-bind`](../macros/handler-bind.md) handler transfers control to the matching [`restart-case`](../macros/restart-case.md) clause with `value` as its argument — the idiom libraries use to substitute a value at the signal point (trivia's pattern expander lifts guard tests this way).

```lisp
(define-condition needs-value () ())
(handler-bind ((needs-value (lambda (c) (use-value 42))))
  (restart-case (progn (signal 'needs-value) :not-restarted)
    (use-value (v) (list :used v)))) ; => (:USED 42)
```
