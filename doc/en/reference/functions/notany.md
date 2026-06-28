# notany

`(notany predicate list)`

Returns `t` if `predicate` is nil for every element of `list`, and `nil` if any element satisfies it -- the complement of `some`. An empty list yields `t`. Single-list form only.

```lisp
(notany #'evenp '(1 3 5)) ; => t
```
