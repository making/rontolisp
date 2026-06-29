# hash-table-count

`(hash-table-count table)`

`table` に現在格納されているエントリ数を整数で返します (空のテーブルでは 0)。`remhash` や `clrhash` でエントリが削除されると数は減ります。

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (setf (gethash 'b h) 2)
  (hash-table-count h)) ; => 2
```
