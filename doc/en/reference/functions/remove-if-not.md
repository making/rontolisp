# remove-if-not

`(remove-if-not predicate list)`

Returns a new list keeping only the elements of `list` that satisfy `predicate` (those failing it are removed). It is the complement of `remove-if`. The original list is not modified.

```lisp
(remove-if-not #'evenp '(1 2 3 4)) ; => (2 4)
```
