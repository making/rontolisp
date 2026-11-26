# count-if

`(count-if predicate sequence &key key start end from-end)`

Returns the number of elements in `sequence` that satisfy `predicate`. The sequence may be a list or a string (whose elements are characters). This is the predicate-based counterpart of `count`. `:start`/`:end` bound the scanned subsequence and `:from-end` reverses the order the elements are visited -- that cannot change a count, but a side-effecting `:key` or `:test` sees the reversed order. A bound outside the sequence -- negative, not an integer, past its length, or a start past its end -- signals a `type-error` before any element is examined; a nil `:end` means the length.

```lisp
(count-if #'evenp '(1 2 3 4)) ; => 2
```

```lisp
(count-if #'digit-char-p "a1b2") ; => 2
```

```lisp
(count-if #'evenp '(1 2 3 4) :start 2) ; => 1
```
