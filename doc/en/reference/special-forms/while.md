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

`while` is a rontolisp extension, not a standard Common Lisp name, though the `cl` package exports it. A package's own `while` takes precedence: one a package declares (`(:export #:while)`, as parenscript, iterate, arnesi and `for` do) is that package's symbol, and a package using such a package inherits its `while` instead of this one.
