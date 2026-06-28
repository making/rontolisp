# set-difference

`(set-difference list1 list2)`

Returns a list of the elements of `list1` that do **not** appear in `list2`, treating both as sets. Elements are compared with `eql`. The order of elements in the result is unspecified.

```lisp
(set-difference '(1 2 3) '(2)) ; => (3 1)
```
