# make-hash-table

`(make-hash-table &key test size)`

新しい空のハッシュテーブルを作成して返します。キーは `equal` によるかのように構造的に比較されるため、リスト・文字列・数値・シンボル・文字のキーが値で一致します。`:test` キーワードは慣習として受け付けられますが情報的なものにすぎず、比較を変更しません。また `:size` やその他のキーワードは無視されます。エントリは `(setf (gethash key table) value)` で格納し、`gethash` で読み出します。

```lisp
(let ((h (make-hash-table :test 'equal)))
  (setf (gethash "x" h) 1)
  (gethash "x" h)) ; => 1
```
