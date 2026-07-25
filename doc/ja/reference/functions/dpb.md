# dpb

`(dpb newbyte bytespec integer)`

デポジットバイト: バイト指定子 `bytespec`（[`byte`](byte.md) を参照）が指すフィールドを `newbyte` の下位 `size` ビットで置き換えた `integer` のコピーを返します。それ以外のビットは変わりません。`integer` はどのバックエンドでも任意の大きさを取れます。

```lisp
(dpb 0 (byte 4 0) 255) ; => 240
```
