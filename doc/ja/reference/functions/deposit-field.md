# deposit-field

`(deposit-field newbyte bytespec integer)`

バイト指定子 `bytespec`([`byte`](byte.md) 参照)の指すフィールドを、`newbyte` の同じ位置のビットで置き換えた `integer` のコピーを返します。他のビットは変わりません。`newbyte` の下位 `size` ビットを置く [`dpb`](dpb.md) とは異なり、フィールドと同じ位置のビットを読み出します。全バックエンドで任意精度の整数を受け付けます。

```lisp
(deposit-field 0 (byte 4 0) 255) ; => 240
```

```lisp
(deposit-field 5 (byte 4 4) 0) ; => 0
```
