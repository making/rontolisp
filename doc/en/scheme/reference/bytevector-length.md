# bytevector-length

`(bytevector-length bytevector)`

Returns the number of elements of `bytevector`.

```scheme
(bytevector-length #u8(1 2 3)) ; => 3
(bytevector-length #u8()) ; => 0
```
