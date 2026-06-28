# while

`(while test body...)`

Evaluates `test`; while it is non-nil, evaluates the `body` forms in order and repeats. The loop ends as soon as `test` evaluates to `nil`, and `while` itself always returns `nil`. It is used for side-effecting iteration, typically updating variables with `setq` in the body.

```lisp
(let ((i 0) (sum 0))
  (while (< i 5)
    (setq sum (+ sum i))
    (setq i (+ i 1)))
  sum) ; => 10
```
