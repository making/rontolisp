# bytevector-copy!

`(bytevector-copy! to at from)` `(bytevector-copy! to at from start)` `(bytevector-copy! to at from start end)`

Copies the elements of `from` between `start` (inclusive, default 0) and `end` (exclusive, default the end) into `to`, starting at index `at`, and returns the unspecified value. When `to` and `from` are the same bytevector and the regions overlap, the result is as if the source region were copied to a temporary first.

```scheme
(let ((b (bytevector 1 2 3 4 5))) (bytevector-copy! b 1 #u8(9 8 7) 0 2) b) ; => #u8(1 9 8 4 5)
(let ((b (bytevector 1 2 3 4 5))) (bytevector-copy! b 1 b 0 3) b) ; => #u8(1 1 2 3 5)
```
