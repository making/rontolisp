# union

`(union list1 list2)`

Returns a list containing every element that appears in either `list1` or `list2`, treating both as sets. Elements are compared with `eql` only -- there is no `:test` or `:key`. The order of elements in the result is unspecified.

```lisp
(union '(1 2 3) '(2 3 4)) ; => (4 1 2 3)
```
