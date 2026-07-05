# multiple-value-call

`(multiple-value-call function values-form...)`

Calls `function` with all values of every `values-form` as the arguments. The function is evaluated first, then the producers left to right; each producer is recognized like in [`multiple-value-bind`](multiple-value-bind.md), including a user function whose result is a `(values ...)` call, whose values are spread at runtime — a compiled program using `multiple-value-call` therefore embeds the runtime `eval` support, like `apply` (deviates from CL: classified as a macro, not a special operator). Built-in function values passed as `function` are synthesized wrappers: the naturally variadic operators (`#'+`, `#'-`, `#'*`, `#'/`, `#'list`, `#'min`, `#'max`) accept any argument count, but every other multi-argument built-in keeps a fixed wrapper arity (e.g. `#'cons` and the comparison chains are binary); use a user-defined function or a `lambda` for other arities.

```lisp
(multiple-value-call #'+ (values 1 2)) ; => 3
```

```lisp
(defun collect (&rest args) args)
(multiple-value-call #'collect 1 (values 2 3) (floor 9 4)) ; => (1 2 3 2 1)
```
