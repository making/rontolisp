# position-if

`(position-if predicate sequence)`

Returns the 0-based index of the first element of `sequence` that satisfies `predicate`, or `nil` if none does. The sequence may be a list or a string (whose elements are characters). It returns the integer position rather than the element itself (compare `find-if`).

```lisp
(position-if #'evenp '(1 3 6 7)) ; => 2
```

```lisp
(position-if #'digit-char-p "ab3c") ; => 2
```
