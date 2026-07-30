# every

`(every predicate &rest sequences)`

Applies `predicate` to one element of each sequence at a time and returns `t` if every call is non-nil, or `nil` as soon as one fails (testing stops at the first failure). Each sequence may be a list or a string (whose elements are characters). With more than one sequence the predicate receives one argument per sequence and the walk stops as soon as the shortest one runs out. An empty sequence yields `t`.

```lisp
(every #'evenp '(2 4 6)) ; => T
```

```lisp
(every #'digit-char-p "123") ; => T
```

```lisp
(every #'< '(1 2) '(3 4)) ; => T
```
