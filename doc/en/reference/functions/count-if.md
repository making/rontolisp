# count-if

`(count-if predicate list)`

Returns the number of elements in `list` that satisfy `predicate`. This is the predicate-based counterpart of `count`.

```lisp
(count-if #'evenp '(1 2 3 4)) ; => 2
```
