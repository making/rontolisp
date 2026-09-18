# read-u8

`(read-u8 [port])`

Reads the next byte from the binary input port `port` and returns it, or the end-of-file object at the end. The standard ports are textual, so without `port` this is an error (Gauche reads a byte of standard input).

```scheme
(read-u8 (open-input-bytevector #u8(1 2))) ; => 1
```
