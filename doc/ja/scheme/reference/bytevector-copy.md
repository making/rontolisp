# bytevector-copy

`(bytevector-copy bytevector)` `(bytevector-copy bytevector start)` `(bytevector-copy bytevector start end)`

`bytevector` の `start`（含む、既定は 0）から `end`（含まない、既定は末尾）までの要素を持つ新しいバイトベクタを返します。

```scheme
(bytevector-copy #u8(1 2 3 4 5) 1 3) ; => #u8(2 3)
(bytevector-copy #u8(1 2 3 4 5) 2) ; => #u8(3 4 5)
```
