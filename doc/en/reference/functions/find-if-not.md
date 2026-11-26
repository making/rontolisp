# find-if-not

`(find-if-not predicate sequence &key key start end from-end)`

Returns the first element of `sequence` that does **not** satisfy `predicate`, or `nil` if every element satisfies it. The sequence may be a list or a string (whose elements are characters). It returns the element itself. This is the complement of `find-if`. `:key` selects what the predicate sees, `:start`/`:end` bound the scanned region and with `:from-end` true the last such element is returned. A bound outside the sequence -- negative, not an integer, past its length, or a start past its end -- signals a `type-error` before any element is examined; a nil `:end` means the length.

```lisp
(find-if-not #'evenp '(2 4 5 6)) ; => 5
```

```lisp
(find-if-not #'digit-char-p "12a3") ; => #\a
```
