# make-bytevector

`(make-bytevector k)` `(make-bytevector k byte)`

Returns a new bytevector of length `k`, every element `byte`. Without `byte` every element is `0` (R7RS leaves the contents unspecified). A `byte` outside 0-255 is an error: `make-bytevector: not a byte: 256`.

```scheme
(make-bytevector 3 7) ; => #u8(7 7 7)
(make-bytevector 2) ; => #u8(0 0)
```
