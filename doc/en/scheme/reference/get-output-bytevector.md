# get-output-bytevector

`(get-output-bytevector port)`

Returns a new bytevector of the bytes written so far to `port`, which must be a port made by `open-output-bytevector`.

```scheme
(let ((p (open-output-bytevector))) (write-bytevector #u8(5 6) p) (get-output-bytevector p)) ; => #u8(5 6)
```
