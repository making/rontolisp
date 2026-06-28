# remove-duplicates

`(remove-duplicates list)`

Returns a new list with duplicate elements removed, keeping the last occurrence of each (so the order of the surviving elements follows their last appearance). Elements are compared with `eql` only -- there is no `:test` or `:key` argument. The original list is not modified.

```lisp
(remove-duplicates '(1 2 1 3)) ; => (2 1 3)
```
