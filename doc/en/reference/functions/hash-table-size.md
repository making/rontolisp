# hash-table-size

`(hash-table-size hash-table)`

Returns the table's size. A rontolisp table has no capacity of its own -- growth belongs to the host map -- so the size **is** the entry count, the same value [`hash-table-count`](hash-table-count.md) returns. It exists so a portable table-copying utility can read the triple it expects.

```lisp
(let ((h (make-hash-table))) (setf (gethash 'a h) 1) (hash-table-size h)) ; => 1
```
