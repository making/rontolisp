# adjoin

`(adjoin item list &key test key)`

Returns `list` unchanged if `item` is already a member; otherwise returns a new list with `item` prepended. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to both the item and the list elements (the prepended item stays unkeyed). It is the non-destructive way to add an element to a set without creating duplicates.

```lisp
(adjoin 1 '(2 3)) ; => (1 2 3)
```

```lisp
(adjoin "a" '("a" "b") :test #'string=) ; => ("a" "b")
```
