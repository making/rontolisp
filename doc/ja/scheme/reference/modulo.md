# modulo

`(modulo n1 n2)`

床関数による除算の剰余を返します。符号は除数 `n2` と同じです。`floor-remainder` と同じです。

```scheme
(modulo -17 5) ; => 3
(modulo 17 -5) ; => -3
```
