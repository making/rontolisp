# hash-table-rehash-threshold

`(hash-table-rehash-threshold hash-table)`

標準の既定しきい値 `1.0` を返します。[`hash-table-rehash-size`](hash-table-rehash-size.md) と対になる定数です。

```lisp
(hash-table-rehash-threshold (make-hash-table)) ; => 1.0
```
