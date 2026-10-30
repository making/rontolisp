# make-hash-table

`(make-hash-table &key test size)`

Creates and returns a new, empty hash table. The default `:test 'equal` compares keys structurally, so list, string, number, symbol and character keys match by value. `:test 'equalp` widens that: the table folds each key to a case-insensitive representative before placing it, so `"CS"` and `"Cs"` are one key (see [data types](../data-types.md) for what folds and what does not). `:test 'eql` compares numbers by type and value but aggregates (conses, instances) by identity, and `:test 'eq` compares aggregates by identity; an identity-keyed entry survives its key's later mutation. The test is a symbol or one of the four functions (`#'equalp`), written literally or computed at run time. `:size` and other keywords are ignored. Store entries with `(setf (gethash key table) value)` and read them with `gethash`.

```lisp
(let ((h (make-hash-table :test 'equalp)))
  (setf (gethash "x" h) 1)
  (gethash "X" h)) ; => 1
```
