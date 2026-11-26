# multiple-value-list

`(multiple-value-list values-form)`

Collects the values of `values-form` into a list. The producer is recognized like in [`multiple-value-bind`](multiple-value-bind.md): a literal `(values ...)` call, the multi-value built-ins (`floor`/`ceiling`/`round`/`truncate`, `gethash`, `parse-integer`, `values-list`) and a call to a user function whose result is a `(values ...)` call supply all of their values; any other producer (a variable, a literal, a function that returns normally) supplies a single value, so the result is a one-element list.

```lisp
(multiple-value-list (floor 17 5)) ; => (3 2)
```

```lisp
(multiple-value-list (+ 1 2)) ; => (3)
```

```lisp
(defun two () (values 1 2))
(multiple-value-list (two)) ; => (1 2)
```
