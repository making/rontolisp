# count-if

`(count-if predicate sequence)`

Returns the number of elements in `sequence` that satisfy `predicate`. The sequence may be a list or a string (whose elements are characters). This is the predicate-based counterpart of `count`.

```lisp
(count-if #'evenp '(1 2 3 4)) ; => 2
```

```lisp
(count-if #'digit-char-p "a1b2") ; => 2
```
