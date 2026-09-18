# bytevector-copy

`(bytevector-copy bytevector)` `(bytevector-copy bytevector start)` `(bytevector-copy bytevector start end)`

Returns a new bytevector holding the elements of `bytevector` from `start` (inclusive, default 0) to `end` (exclusive, default the end).

```scheme
(bytevector-copy #u8(1 2 3 4 5) 1 3) ; => #u8(2 3)
(bytevector-copy #u8(1 2 3 4 5) 2) ; => #u8(3 4 5)
```
