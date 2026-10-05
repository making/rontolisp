# delete-if

`(delete-if predicate list &key key start end count from-end)`

The destructive counterpart of `remove-if`: returns `list` with every element satisfying `predicate` spliced out in place. A vector or string argument has no cons cells to splice, so it comes back as a fresh sequence instead, like `remove-if`. Because the head may change, use the return value rather than the original variable. `:start`/`:end` bound the scanned subsequence -- an element outside it is neither tested nor acted on -- `:count` caps how many matches are acted on, and `:from-end` reverses the order the elements are visited, so with `:count` the matches taken are the last ones. A bound outside the sequence -- negative, not an integer, past its length, or a start past its end -- signals a `type-error` before any element is examined; a nil `:end` means the length.

```lisp
(delete-if #'evenp '(1 2 3 4)) ; => (1 3)
```

```lisp
(delete-if #'oddp (vector 1 2 3 4)) ; => #(2 4)
```

```lisp
(delete-if #'evenp (list 1 2 3 4) :count 1 :from-end t) ; => (1 2 3)
```
