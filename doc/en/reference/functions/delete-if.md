# delete-if

`(delete-if predicate list)`

The destructive counterpart of `remove-if`: returns `list` with every element satisfying `predicate` spliced out in place. A vector or string argument has no cons cells to splice, so it comes back as a fresh sequence instead, like `remove-if`. Because the head may change, use the return value rather than the original variable.

```lisp
(delete-if #'evenp '(1 2 3 4)) ; => (1 3)
```

```lisp
(delete-if #'oddp (vector 1 2 3 4)) ; => #(2 4)
```
