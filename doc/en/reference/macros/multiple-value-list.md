# multiple-value-list

`(multiple-value-list values-form)`

Collects the values of `values-form` into a list. The producer is recognized syntactically like in [`multiple-value-bind`](multiple-value-bind.md): a literal `(values ...)` call and the two-value built-ins (`floor`/`ceiling`/`round`/`truncate`, `gethash`) supply all of their values; any other form supplies a single value, so the result is a one-element list.

```lisp
(multiple-value-list (floor 17 5)) ; => (3 2)
```

```lisp
(multiple-value-list (+ 1 2)) ; => (3)
```
