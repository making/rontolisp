# delete-if

`(delete-if predicate list)`

The destructive counterpart of `remove-if`: returns `list` with every element satisfying `predicate` spliced out in place. Because the head may change, use the return value rather than the original variable.

```lisp
(delete-if #'evenp '(1 2 3 4)) ; => (1 3)
```
