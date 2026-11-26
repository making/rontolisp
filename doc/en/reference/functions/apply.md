# apply

`(apply function &rest args)`

Calls `function` with the leading arguments prepended to the elements of the final argument, which must be a list, and returns its result. As with `funcall`, `function` may be a function value (`#'f`, a `lambda`) or a quoted symbol designator. `(apply #'+ 1 2 '(3 4))` is equivalent to `(funcall #'+ 1 2 3 4)`; passing only the spread list as in `(apply #'+ '(1 2 3))` works too.

```lisp
(apply #'+ 1 2 '(3 4)) ; => 10
```
