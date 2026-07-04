# find-if-not

`(find-if-not predicate sequence)`

Returns the first element of `sequence` that does **not** satisfy `predicate`, or `nil` if every element satisfies it. The sequence may be a list or a string (whose elements are characters). It returns the element itself. This is the complement of `find-if`.

```lisp
(find-if-not #'evenp '(2 4 5 6)) ; => 5
```

```lisp
(find-if-not #'digit-char-p "12a3") ; => #\a
```
