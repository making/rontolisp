# u8-ready?

`(u8-ready? [port])`

Returns `#t` if a byte can be read from the binary input port `port` without blocking. A bytevector port never blocks, so it is always `#t`. Without `port` it is an error, as for `read-u8`.

```scheme
(u8-ready? (open-input-bytevector #u8())) ; => #t
```
