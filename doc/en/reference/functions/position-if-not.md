# position-if-not

`(position-if-not predicate sequence &key key start end from-end)`

Returns the 0-based index of the first element of `sequence` that does NOT satisfy `predicate`, or `nil` if every element does. The complement of `position-if`, with the same keyword arguments.

```lisp
(position-if-not #'evenp '(2 4 5 6)) ; => 2
```

```lisp
(position-if-not #'digit-char-p "42a7") ; => 2
```
