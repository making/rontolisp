# gethash

`(gethash key table &optional default)`

`table` から `key` を検索し、対応する値を返します。キーがない場合は `default` (省略時は nil) を返します。キーは `equal` によるかのように構造的に比較されるため、等しいリスト・文字列・数値のキーが一致します。値を格納するには `gethash` を `setf` の場所として使います: `(setf (gethash key table) value)`。これは `incf`/`decf`/`push` とも連携します。[`multiple-value-bind`](../macros/multiple-value-bind.md)（および他の多値コンシューマ）の下では、`gethash` 呼び出しは 2 番目の present-p 値も供給し、格納された nil とキーの不在を区別できます。

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (gethash 'a h)) ; => 1
```

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) nil)
  (multiple-value-bind (v present-p) (gethash 'a h)
    (list v present-p))) ; => (nil t)
```
