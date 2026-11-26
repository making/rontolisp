# notany

`(notany predicate &rest sequences)`

Returns `t` if `predicate` is nil for every element (tuple) of the sequences, and `nil` if any satisfies it -- the complement of `some`. Each sequence may be a list or a string (whose elements are characters). With more than one sequence the predicate receives one argument per sequence and the walk stops as soon as the shortest one runs out. An empty sequence yields `t`.

```lisp
(notany #'evenp '(1 3 5)) ; => T
```

```lisp
(notany #'digit-char-p "abc") ; => T
```

```lisp
(notany #'> '(1 2) '(3 4)) ; => T
```
