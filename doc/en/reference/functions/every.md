# every

`(every predicate sequence)`

Applies `predicate` to each element of `sequence` and returns `t` if every call is non-nil, or `nil` as soon as one fails (testing stops at the first failure). The sequence may be a list or a string (whose elements are characters). An empty sequence yields `t`. Single-sequence form only -- the predicate receives one element at a time.

```lisp
(every #'evenp '(2 4 6)) ; => t
```

```lisp
(every #'digit-char-p "123") ; => t
```
