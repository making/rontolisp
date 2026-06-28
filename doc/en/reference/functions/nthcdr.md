# nthcdr

`(nthcdr n list)`

Applies `cdr` to `list` `n` times and returns the resulting tail -- i.e. the sublist with the first `n` elements skipped. If `n` reaches or exceeds the list length the result is `nil`. `(nthcdr 0 list)` returns the list unchanged.

```lisp
(nthcdr 2 '(a b c d)) ; => (c d)
```
