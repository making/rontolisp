# bytevector

`(bytevector byte ...)`

引数を要素とする新しいバイトベクタを返します。0〜255 の正確な整数でない引数はエラーです（`bytevector: not a byte: 256`）。

```scheme
(bytevector 1 2 255) ; => #u8(1 2 255)
(bytevector) ; => #u8()
```
