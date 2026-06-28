# remove-if

`(remove-if predicate list)`

Returns a new list containing the elements of `list` that do **not** satisfy `predicate` (the satisfying elements are removed). The original list is not modified; use `delete-if` for the destructive version.

```lisp
(remove-if #'evenp '(1 2 3 4)) ; => (1 3)
```
