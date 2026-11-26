# ldb-test

`(ldb-test bytespec integer)`

ロードバイトテスト: バイト指定子 `bytespec`（[`byte`](byte.md) を参照）が指す `integer` のフィールドに1のビットがあるかどうかを返します -- `(not (zerop (ldb bytespec integer)))` と等価です。

```lisp
(ldb-test (byte 4 4) 255) ; => T
```
