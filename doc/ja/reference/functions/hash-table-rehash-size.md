# hash-table-rehash-size

`(hash-table-rehash-size hash-table)`

標準の既定拡張係数 `1.5` を返します。rontolisp のテーブルは拡張のパラメータを公開していない (ホスト側のマップが自律的に拡張する) ため、テーブルを作り直す前にこの値を読む移植性のあるコードのために定数を返します。

```lisp
(hash-table-rehash-size (make-hash-table)) ; => 1.5
```
