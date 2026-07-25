# dpb

`(dpb newbyte bytespec integer)`

デポジットバイト: バイト指定子 `bytespec`（[`byte`](byte.md) を参照）が指すフィールドを `newbyte` の下位 `size` ビットで置き換えた `integer` のコピーを返します。それ以外のビットは変わりません。インタプリタと JVM では `integer` は任意の大きさを取れますが、WASM では結果は符号付き 64 ビットの範囲に収まります。

```lisp
(dpb 0 (byte 4 0) 255) ; => 240
```
