# set-exclusive-or

`(set-exclusive-or list1 list2 &key test test-not key)`

Returns the symmetric difference of the two lists: the elements of either list that have no match in the other. The comparison is `eql` by default; `:test` takes a function designator, `:test-not` matches where the given function answers false, and `:key` is a selector applied to both compared elements. The comparison is always made with the `list1` element first, in both directions. The result lists the `list1`-only elements in order, then the `list2`-only ones (Common Lisp leaves the order unspecified).

```lisp
(set-exclusive-or '(1 2 3) '(2 3 4)) ; => (1 4)
```

```lisp
(set-exclusive-or '("a" "b") '("B" "c") :test #'string-equal) ; => ("a" "c")
```

```lisp
(set-exclusive-or '((1 a) (2 b)) '((2 x)) :key #'car) ; => ((1 A))
```
