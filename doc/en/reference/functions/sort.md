# sort

`(sort sequence predicate)`

Sorts `sequence` using `predicate`, a two-argument comparison function that returns non-nil when its first argument should precede its second. A list is sorted destructively: its cons cells are rearranged in place, so use the return value rather than the original variable. A string sorts as a sequence of its characters and returns a new string (the original string is not modified). The sort is not stable, so the relative order of elements considered equal by `predicate` is unspecified.

```lisp
(sort (list 3 1 2) #'<) ; => (1 2 3)
```

```lisp
(sort "cab" #'char<) ; => "abc"
```
