# find-if

`(find-if predicate sequence)`

Returns the first element of `sequence` that satisfies `predicate`, or `nil` if none does. The sequence may be a list or a string (whose elements are characters). It returns the element itself, not its index or tail. Use `find-if-not` for the complementary search.

```lisp
(find-if #'evenp '(1 3 6 7)) ; => 6
```

```lisp
(find-if #'digit-char-p "ab3c") ; => #\3
```
