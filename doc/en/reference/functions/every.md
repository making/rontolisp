# every

`(every predicate list)`

Applies `predicate` to each element of `list` and returns `t` if every call is non-nil, or `nil` as soon as one fails (testing stops at the first failure). An empty list yields `t`. Single-list form only -- the predicate receives one element at a time.

```lisp
(every #'evenp '(2 4 6)) ; => t
```
