# read-bytevector!

`(read-bytevector! bytevector [port [start [end]]])`

Reads bytes from the binary input port `port` into `bytevector` from index `start` up to, not including, `end`, and returns how many it read; the end-of-file object when none is left. Without `port` it is an error, as for `read-u8`.

```scheme
(let ((b (make-bytevector 4 0))) (read-bytevector! b (open-input-bytevector #u8(7 8)) 1) b) ; => #u8(0 7 8 0)
```
