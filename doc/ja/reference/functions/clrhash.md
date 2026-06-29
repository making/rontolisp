# clrhash

`(clrhash table)`

`table` からすべてのエントリを削除して空にし、その空になったテーブルを返します。同じテーブルオブジェクトが再利用されるため、既存の参照は引き続き有効です。

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (clrhash h)
  (hash-table-count h)) ; => 0
```
