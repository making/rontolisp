# hash-table-test

`(hash-table-test hash-table)`

そのテーブルの検索が実際に行っているテストを返します。`:test 'equalp` で作ったテーブル -- キーは大文字小文字と浮動小数点の違いを吸収した代表値に畳み込んでから配置されます -- では `equalp`、`:test 'eql` や `:test 'eq` で作ったテーブル -- 集約は同一性でキーを比較します -- では `eql` や `eq`、それ以外では `equal` です。

```lisp
(list (hash-table-test (make-hash-table))
      (hash-table-test (make-hash-table :test 'equalp))) ; => (EQUAL EQUALP)
```
