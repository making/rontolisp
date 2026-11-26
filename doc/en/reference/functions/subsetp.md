# subsetp

`(subsetp list1 list2 &key test key)`

Returns true if every element of `list1` is a member of `list2`, treating both as sets; returns false as soon as one element of `list1` has no match. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to both compared elements. An empty `list1` is always a subset.

```lisp
(subsetp '(1 2) '(1 2 3)) ; => T
```

```lisp
(subsetp '("a" "z") '("a" "b") :test #'string=) ; => NIL
```
