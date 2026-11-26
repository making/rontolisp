# write-bytevector

`(write-bytevector bytevector [port [start [end]]])`

Writes the bytes of `bytevector` from index `start` up to, not including, `end` to the binary output port `port`. Without `port` it is an error, as for `write-u8`.

```scheme
(let ((p (open-output-bytevector))) (write-bytevector #u8(1 2 3 4) p 1 3) (get-output-bytevector p)) ; => #u8(2 3)
```
