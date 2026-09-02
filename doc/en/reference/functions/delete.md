# delete

`(delete item list &key test key)`

The destructive counterpart of `remove`: returns `list` with every element matching `item` spliced out, modifying the cons cells in place rather than copying. A vector or string argument has no cons cells to splice, so it comes back as a fresh sequence instead, like `remove`. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to each element before the comparison. Because the head of the list may change, always use the return value rather than relying on the original variable.

```lisp
(delete 2 '(1 2 3 2)) ; => (1 3)
```

```lisp
(delete "b" (list "a" "b" "c") :test #'string=) ; => ("a" "c")
```

```lisp
(delete 1 (vector 3 1 2)) ; => #(3 2)
```
