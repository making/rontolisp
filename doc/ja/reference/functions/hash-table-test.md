# hash-table-test

`(hash-table-test hash-table)`

そのテーブルの検索が実際に行っているテストを返します。`:test 'equalp` で作ったテーブル -- キーは大文字小文字と浮動小数点の違いを吸収した代表値に畳み込んでから配置されます -- では `equalp`、それ以外では `equal` です。`eql` テーブルもどのバックエンドでも `equal` と同じく構造的にキーを比較するため、要求されたテストを返すと実在しない振る舞いを述べることになります。

```lisp
(list (hash-table-test (make-hash-table))
      (hash-table-test (make-hash-table :test 'equalp))) ; => (EQUAL EQUALP)
```
