# notany

`(notany predicate sequence)`

Returns `t` if `predicate` is nil for every element of `sequence`, and `nil` if any element satisfies it -- the complement of `some`. The sequence may be a list or a string (whose elements are characters). An empty sequence yields `t`. Single-sequence form only.

```lisp
(notany #'evenp '(1 3 5)) ; => T
```

```lisp
(notany #'digit-char-p "abc") ; => T
```
