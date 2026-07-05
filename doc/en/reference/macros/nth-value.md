# nth-value

`(nth-value n values-form)`

Returns the `n`-th (0-based) value of `values-form`, or nil when there is no such value. `n` is evaluated before the form. Expands to `nth` over [`multiple-value-list`](multiple-value-list.md), so the producer is recognized syntactically like in [`multiple-value-bind`](multiple-value-bind.md).

```lisp
(nth-value 1 (floor 7 2)) ; => 1
```

```lisp
(nth-value 0 (values 'a 'b)) ; => a
```
