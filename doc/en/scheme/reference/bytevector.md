# bytevector

`(bytevector byte ...)`

Returns a new bytevector holding the arguments. An argument that is not an exact integer in 0-255 is an error: `bytevector: not a byte: 256`.

```scheme
(bytevector 1 2 255) ; => #u8(1 2 255)
(bytevector) ; => #u8()
```
