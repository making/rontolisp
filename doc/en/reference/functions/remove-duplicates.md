# remove-duplicates

`(remove-duplicates sequence)`

Returns a new sequence with duplicate elements removed, keeping the last occurrence of each (so the order of the surviving elements follows their last appearance). The sequence may be a list or a string; a string yields a new string. Elements are compared with `eql` only -- there is no `:test` or `:key` argument. The original sequence is not modified.

```lisp
(remove-duplicates '(1 2 1 3)) ; => (2 1 3)
```

```lisp
(remove-duplicates "banana") ; => "bna"
```
