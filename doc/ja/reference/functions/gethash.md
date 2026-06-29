# gethash

`(gethash key table &optional default)`

`table` から `key` を検索し、対応する値を返します。キーがない場合は `default` (省略時は nil) を返します。キーは `equal` によるかのように構造的に比較されるため、等しいリスト・文字列・数値のキーが一致します。値を格納するには `gethash` を `setf` の場所として使います: `(setf (gethash key table) value)`。これは `incf`/`decf`/`push` とも連携します。

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (gethash 'a h)) ; => 1
```
