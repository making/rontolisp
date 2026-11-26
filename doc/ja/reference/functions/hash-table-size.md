# hash-table-size

`(hash-table-size hash-table)`

テーブルのサイズを返します。rontolisp のテーブルは独自の容量を持たず、拡張はホスト側のマップに任せているため、サイズは格納数そのもの ([`hash-table-count`](hash-table-count.md) と同じ値) です。移植性のあるテーブル複製ユーティリティが期待する 3 つの値を読めるようにするために存在します。

```lisp
(let ((h (make-hash-table))) (setf (gethash 'a h) 1) (hash-table-size h)) ; => 1
```
