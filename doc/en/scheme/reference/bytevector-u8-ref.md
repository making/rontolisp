# bytevector-u8-ref

`(bytevector-u8-ref bytevector k)`

Returns element `k` of `bytevector` (zero-based), an exact integer in 0-255. An index out of range is an error.

```scheme
(bytevector-u8-ref #u8(10 20 30) 1) ; => 20
```
