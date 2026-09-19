# open-input-bytevector

`(open-input-bytevector bytevector)`

Returns a binary input port that reads the bytes of `bytevector` (a copy: changing `bytevector` later does not change what is read).

```scheme
(read-u8 (open-input-bytevector #u8(7 8))) ; => 7
```
