# open-output-bytevector

`(open-output-bytevector)`

Returns a binary output port that accumulates the bytes written to it, for `get-output-bytevector`.

```scheme
(let ((p (open-output-bytevector))) (write-u8 1 p) (write-u8 2 p) (get-output-bytevector p)) ; => #u8(1 2)
```
