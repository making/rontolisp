# some

`(some predicate list)`

Applies `predicate` to each element of `list` and returns the first non-nil result, stopping as soon as one is found; if every element fails it returns `nil`. Note the return value is the predicate's result, not necessarily `t`. Single-list form only.

```lisp
(some #'oddp '(2 4 5)) ; => t
```
