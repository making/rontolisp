# clrhash

`(clrhash table)`

Removes all entries from `table`, leaving it empty, and returns the now-empty table. The same table object is reused, so existing references to it remain valid.

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (clrhash h)
  (hash-table-count h)) ; => 0
```
