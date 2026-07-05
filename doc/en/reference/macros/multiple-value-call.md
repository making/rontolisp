# multiple-value-call

`(multiple-value-call function values-form...)`

Calls `function` with all values of every `values-form` as the arguments. The function is evaluated first, then the producers left to right; each producer is recognized syntactically like in [`multiple-value-bind`](multiple-value-bind.md), so the argument count is static and the form lowers to a direct `funcall` (deviates from CL: classified as a macro, not a special operator). Built-in function values passed as `function` keep their fixed wrapper arity (e.g. `#'+` takes exactly 2 arguments); use a user-defined function or a `lambda` for other arities.

```lisp
(multiple-value-call #'+ (values 1 2)) ; => 3
```

```lisp
(defun collect (&rest args) args)
(multiple-value-call #'collect 1 (values 2 3) (floor 9 4)) ; => (1 2 3 2 1)
```
