# intersection

`(intersection list1 list2 &key test key)`

Returns a list of the elements that appear in **both** `list1` and `list2`, treating them as sets. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to both compared elements. The order of elements in the result is unspecified.

```lisp
(intersection '(1 2 3) '(2 3 4)) ; => (3 2)
```

```lisp
(intersection '("a" "b") '("b" "c") :test #'string=) ; => ("b")
```
