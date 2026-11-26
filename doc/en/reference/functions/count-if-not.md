# count-if-not

`(count-if-not predicate sequence &key key start end from-end)`

Returns the number of elements of `sequence` that do **not** satisfy `predicate` -- the complement of `count-if`. The sequence may be a list, a vector or a string. `:key` selects what the predicate sees. `:start`/`:end` bound the scanned subsequence and `:from-end` reverses the order the elements are visited -- that cannot change a count, but a side-effecting `:key` or `:test` sees the reversed order. A bound outside the sequence -- negative, not an integer, past its length, or a start past its end -- signals a `type-error` before any element is examined; a nil `:end` means the length.

```lisp
(count-if-not #'evenp '(1 2 3 4 5)) ; => 3
```

```lisp
(count-if-not #'alpha-char-p "ab1c2") ; => 2
```

```lisp
(count-if-not #'oddp '((1) (2) (3)) :key #'car) ; => 1
```

```lisp
(count-if-not #'evenp '(1 2 3 4) :start 2) ; => 1
```
