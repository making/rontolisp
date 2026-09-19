# peek-u8

`(peek-u8 [port])`

Returns the next byte of the binary input port `port` without consuming it, or the end-of-file object at the end. Without `port` it is an error, as for `read-u8`.

```scheme
(let ((p (open-input-bytevector #u8(9)))) (list (peek-u8 p) (read-u8 p))) ; => (9 9)
```
