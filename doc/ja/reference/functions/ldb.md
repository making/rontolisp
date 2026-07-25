# ldb

`(ldb bytespec integer)`

ロードバイト: バイト指定子 `bytespec`（[`byte`](byte.md) を参照）が指す `integer` のフィールドを取り出し、フィールドの最下位ビットが第 0 ビットになるよう右詰めして返します。インタプリタと JVM では `integer` は任意の大きさを取れますが、WASM ではフィールドの読み取りは符号付き 64 ビットの範囲です。

```lisp
(ldb (byte 4 4) 255) ; => 15
```
