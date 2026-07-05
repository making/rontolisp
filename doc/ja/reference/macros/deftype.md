# deftype

`(deftype name lambda-list body...)`

パース済み no-op として受理され `nil` を返します。型定義レジストリは存在しないため、定義した名前を後続の `check-type`/`typecase` の型テストに使うことはできません(未サポートの型指定子としてエラーになります)。`deftype` した名前が(同じく no-op の)`declaim`/`declare` 宣言の中にのみ現れる、ライブラリでよくある形を通すためのものです。

```lisp
(deftype array-index (&optional (length 1000)) `(integer 0 (,length))) ; => nil
```
