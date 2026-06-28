# sort

`(sort list predicate)`

Sorts `list` destructively using `predicate`, a two-argument comparison function that returns non-nil when its first argument should precede its second. The list's cons cells are rearranged in place, so use the return value rather than the original variable. The sort is not stable, so the relative order of elements considered equal by `predicate` is unspecified.

```lisp
(sort (list 3 1 2) #'<) ; => (1 2 3)
```
