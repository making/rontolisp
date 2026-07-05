# ldb

`(ldb bytespec integer)`

ロードバイト: バイト指定子 `bytespec`（[`byte`](byte.md) を参照）が指す `integer` のフィールドを取り出し、フィールドの最下位ビットが第 0 ビットになるよう右詰めして返します。インタプリタと JVM では `integer` は任意の大きさを取れますが、WASM ではフィールドの読み取りは 31 ビットの `i31` の範囲に限られます。

```lisp
(ldb (byte 4 4) 255) ; => 15
```
