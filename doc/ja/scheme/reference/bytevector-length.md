# bytevector-length

`(bytevector-length bytevector)`

`bytevector` の要素数を返します。

```scheme
(bytevector-length #u8(1 2 3)) ; => 3
(bytevector-length #u8()) ; => 0
```
