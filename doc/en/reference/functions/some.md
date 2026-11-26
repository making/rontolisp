# some

`(some predicate &rest sequences)`

Applies `predicate` to one element of each sequence at a time and returns the first non-nil result, stopping as soon as one is found; if every element fails it returns `nil`. Each sequence may be a list or a string (whose elements are characters). Note the return value is the predicate's result, not necessarily `t`. With more than one sequence the predicate receives one argument per sequence and the walk stops as soon as the shortest one runs out.

```lisp
(some #'oddp '(2 4 5)) ; => T
```

```lisp
(some #'digit-char-p "abc1") ; => 1
```

```lisp
(some #'> '(1 5) '(3 4)) ; => T
```
