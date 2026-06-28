# nconc

`(nconc list1 list2)`

Destructively concatenates two lists by setting the last cdr of `list1` to point at `list2`, then returns the joined list. No new cons cells are allocated, so `list1` is modified in place. Unlike full Common Lisp, rontolisp's `nconc` takes exactly two lists. If `list1` is `nil` the result is simply `list2`.

```lisp
(nconc (list 1 2) (list 3 4)) ; => (1 2 3 4)
```
