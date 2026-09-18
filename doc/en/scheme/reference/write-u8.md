# write-u8

`(write-u8 byte [port])`

Writes `byte` to the binary output port `port`. A `byte` outside 0-255 is an error. The standard ports are textual, so without `port` this is an error (Gauche writes the byte to standard output).

```scheme
(let ((p (open-output-bytevector))) (write-u8 255 p) (get-output-bytevector p)) ; => #u8(255)
```
