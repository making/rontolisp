# read-bytevector

`(read-bytevector k [port])`

Reads at most `k` bytes from the binary input port `port` and returns them as a new bytevector; fewer at the end, and the end-of-file object when none is left. Without `port` it is an error, as for `read-u8`.

```scheme
(read-bytevector 2 (open-input-bytevector #u8(1 2 3))) ; => #u8(1 2)
```
