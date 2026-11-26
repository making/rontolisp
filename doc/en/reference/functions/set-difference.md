# set-difference

`(set-difference list1 list2 &key test key)`

Returns a list of the elements of `list1` that do **not** appear in `list2`, treating both as sets. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to both compared elements. The order of elements in the result is unspecified.

```lisp
(set-difference '(1 2 3) '(2)) ; => (3 1)
```

```lisp
(set-difference '("a" "b") '("b" "c") :test #'string=) ; => ("a")
```
