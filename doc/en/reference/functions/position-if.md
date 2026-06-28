# position-if

`(position-if predicate list)`

Returns the 0-based index of the first element of `list` that satisfies `predicate`, or `nil` if none does. It returns the integer position rather than the element itself (compare `find-if`).

```lisp
(position-if #'evenp '(1 3 6 7)) ; => 2
```
