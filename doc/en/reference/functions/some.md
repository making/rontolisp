# some

`(some predicate sequence)`

Applies `predicate` to each element of `sequence` and returns the first non-nil result, stopping as soon as one is found; if every element fails it returns `nil`. The sequence may be a list or a string (whose elements are characters). Note the return value is the predicate's result, not necessarily `t`. Single-sequence form only.

```lisp
(some #'oddp '(2 4 5)) ; => T
```

```lisp
(some #'digit-char-p "abc1") ; => 1
```
