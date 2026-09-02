# delete-if-not

`(delete-if-not predicate list)`

The destructive counterpart of `remove-if-not`: returns `list` keeping only the elements that satisfy `predicate`, splicing out the rest in place. A vector or string argument has no cons cells to splice, so it comes back as a fresh sequence instead, like `remove-if-not`. Because the head may change, use the return value rather than the original variable.

```lisp
(delete-if-not #'evenp '(1 2 3 4)) ; => (2 4)
```

```lisp
(delete-if-not #'oddp (vector 1 2 3)) ; => #(1 3)
```
