# hash-table-count

`(hash-table-count table)`

Returns the number of entries currently stored in `table` as an integer (0 for an empty table). The count drops as entries are removed with `remhash` or `clrhash`.

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (setf (gethash 'b h) 2)
  (hash-table-count h)) ; => 2
```
