# delete

`(delete item list &key test key start end count from-end)`

The destructive counterpart of `remove`: returns `list` with every element matching `item` spliced out, modifying the cons cells in place rather than copying. A vector or string argument has no cons cells to splice, so it comes back as a fresh sequence instead, like `remove`. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to each element before the comparison. Because the head of the list may change, always use the return value rather than relying on the original variable. `:start`/`:end` bound the scanned subsequence -- an element outside it is neither tested nor acted on -- `:count` caps how many matches are acted on, and `:from-end` reverses the order the elements are visited, so with `:count` the matches taken are the last ones. A bound outside the sequence -- negative, not an integer, past its length, or a start past its end -- signals a `type-error` before any element is examined; a nil `:end` means the length.

```lisp
(delete 2 '(1 2 3 2)) ; => (1 3)
```

```lisp
(delete "b" (list "a" "b" "c") :test #'string=) ; => ("a" "c")
```

```lisp
(delete 1 (vector 3 1 2)) ; => #(3 2)
```

```lisp
(delete 2 (list 1 2 3 2) :count 1) ; => (1 3 2)
```
