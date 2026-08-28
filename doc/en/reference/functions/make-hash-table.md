# make-hash-table

`(make-hash-table &key test size)`

Creates and returns a new, empty hash table. Keys are compared structurally, as if by `equal`, so list, string, number, symbol and character keys match by value. `:test 'equalp` widens that: the table folds each key to a case-insensitive representative before placing it, so `"CS"` and `"Cs"` are one key (see [data types](../data-types.md) for what folds and what does not, and write the test literally -- the compiled backends read it from the source rather than evaluating it). Every other `:test` is informational only, and `:size` and other keywords are ignored. Store entries with `(setf (gethash key table) value)` and read them with `gethash`.

```lisp
(let ((h (make-hash-table :test 'equalp)))
  (setf (gethash "x" h) 1)
  (gethash "X" h)) ; => 1
```
