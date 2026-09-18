# bytevector-u8-set!

`(bytevector-u8-set! bytevector k byte)`

Stores `byte` as element `k` of `bytevector`, returning the unspecified value. A `byte` outside 0-255 is an error (`bytevector-u8-set!: not a byte: -1`) rather than being truncated. A literal `#u8(...)` is a fresh bytevector each time it is evaluated, so storing into one does not change the program.

```scheme
(let ((b (bytevector 1 2 3))) (bytevector-u8-set! b 0 255) b) ; => #u8(255 2 3)
```
