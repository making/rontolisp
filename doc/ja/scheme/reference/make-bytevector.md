# make-bytevector

`(make-bytevector k)` `(make-bytevector k byte)`

長さ `k`、全要素が `byte` の新しいバイトベクタを返します。`byte` を省くと全要素が `0` です（R7RS では内容は未規定）。0〜255 の外の `byte` はエラーです（`make-bytevector: not a byte: 256`）。

```scheme
(make-bytevector 3 7) ; => #u8(7 7 7)
(make-bytevector 2) ; => #u8(0 0)
```
