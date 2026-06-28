# notevery

`(notevery predicate list)`

Returns `t` if `predicate` is nil for at least one element of `list`, and `nil` if every element satisfies it -- the complement of `every`. An empty list yields `nil`. Single-list form only.

```lisp
(notevery #'evenp '(2 4 5)) ; => t
```
