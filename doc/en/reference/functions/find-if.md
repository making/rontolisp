# find-if

`(find-if predicate sequence &key key start end from-end)`

Returns the first element of `sequence` that satisfies `predicate`, or `nil` if none does. The sequence may be a list or a string (whose elements are characters). It returns the element itself, not its index or tail. Use `find-if-not` for the complementary search. `:key` selects what the predicate sees, `:start`/`:end` bound the scanned region and with `:from-end` true the last such element is returned. A bound outside the sequence -- negative, not an integer, past its length, or a start past its end -- signals a `type-error` before any element is examined; a nil `:end` means the length.

```lisp
(find-if #'evenp '(1 3 6 7)) ; => 6
```

```lisp
(find-if #'digit-char-p "ab3c") ; => #\3
```
