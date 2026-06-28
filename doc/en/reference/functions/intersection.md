# intersection

`(intersection list1 list2)`

Returns a list of the elements that appear in **both** `list1` and `list2`, treating them as sets. Elements are compared with `eql`. The order of elements in the result is unspecified.

```lisp
(intersection '(1 2 3) '(2 3 4)) ; => (3 2)
```
