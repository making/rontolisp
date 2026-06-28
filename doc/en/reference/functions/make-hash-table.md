# make-hash-table

`(make-hash-table &key test size)`

Creates and returns a new, empty hash table. Keys are compared structurally, as if by `equal`, so list, string, number, symbol and character keys match by value. The `:test` keyword is accepted for familiarity but is informational only -- it does not change the comparison -- and `:size` and other keywords are ignored. Store entries with `(setf (gethash key table) value)` and read them with `gethash`.

```lisp
(let ((h (make-hash-table :test 'equal)))
  (setf (gethash "x" h) 1)
  (gethash "x" h)) ; => 1
```
