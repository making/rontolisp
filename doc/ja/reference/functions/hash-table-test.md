# hash-table-test

`(hash-table-test hash-table)`

そのテーブルの検索が実際に行っているテストを返します。常にシンボル `equal` です。rontolisp はどのバックエンドでも、[`make-hash-table`](make-hash-table.md) に渡された `:test` に関わらずキーを構造的に比較します。要求されたテストを返すと、実在しない振る舞いを述べることになります。

```lisp
(hash-table-test (make-hash-table)) ; => EQUAL
```
