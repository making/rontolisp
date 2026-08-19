# torch:shape

`(torch:shape tensor)`

Returns the dims list of the tensor's data (the linalg `shape`), or `nil` for a scalar tensor (rank 0).

```lisp
(torch:shape (torch:tensor '((1 2 3) (4 5 6)))) ; => (2 3)
(torch:shape (torch:tensor 2.5))                ; => NIL
```
