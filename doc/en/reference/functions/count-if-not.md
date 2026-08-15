# count-if-not

`(count-if-not predicate sequence &key key start end from-end)`

Returns the number of elements of `sequence` that do **not** satisfy `predicate` -- the complement of `count-if`. The sequence may be a list, a vector or a string. `:key` selects what the predicate sees, `:start`/`:end` bound the scanned region, and `:from-end` is accepted but changes nothing (it only reorders the predicate calls, which cannot change a count).

```lisp
(count-if-not #'evenp '(1 2 3 4 5)) ; => 3
```

```lisp
(count-if-not #'alpha-char-p "ab1c2") ; => 2
```

```lisp
(count-if-not #'oddp '((1) (2) (3)) :key #'car) ; => 1
```
