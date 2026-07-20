# notevery

`(notevery predicate sequence)`

Returns `t` if `predicate` is nil for at least one element of `sequence`, and `nil` if every element satisfies it -- the complement of `every`. The sequence may be a list or a string (whose elements are characters). An empty sequence yields `nil`. Single-sequence form only.

```lisp
(notevery #'evenp '(2 4 5)) ; => T
```

```lisp
(notevery #'digit-char-p "12a") ; => T
```
