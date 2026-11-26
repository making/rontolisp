# quotient

`(quotient n1 n2)`

0 方向に切り捨てた整数除算の商を返します。`truncate-quotient` と同じです。どちらかの引数が不正確数なら商も不正確数です。整数でない引数や、正確なゼロでの除算はエラーになります。

```scheme
(quotient 17 5) ; => 3
(quotient -17 5) ; => -3
(quotient 7.0 2) ; => 3.0
```
