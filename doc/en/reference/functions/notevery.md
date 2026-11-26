# notevery

`(notevery predicate &rest sequences)`

Returns `t` if `predicate` is nil for at least one element (tuple) of the sequences, and `nil` if every one satisfies it -- the complement of `every`. Each sequence may be a list or a string (whose elements are characters). With more than one sequence the predicate receives one argument per sequence and the walk stops as soon as the shortest one runs out. An empty sequence yields `nil`.

```lisp
(notevery #'evenp '(2 4 5)) ; => T
```

```lisp
(notevery #'digit-char-p "12a") ; => T
```

```lisp
(notevery #'< '(1 2) '(3 0)) ; => T
```
