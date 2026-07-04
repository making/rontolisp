# union

`(union list1 list2 &key test key)`

Returns a list containing every element that appears in either `list1` or `list2`, treating both as sets. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to both compared elements. The order of elements in the result is unspecified.

```lisp
(union '(1 2 3) '(2 3 4)) ; => (4 1 2 3)
```

```lisp
(union '("a" "b") '("b" "c") :test #'string=) ; => ("c" "a" "b")
```
