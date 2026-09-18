# quotient

`(quotient n1 n2)`

0 方向に切り捨てた整数除算の商を返します。`truncate-quotient` と同じです。正確なゼロで割るとエラーになります。

```scheme
(quotient 17 5) ; => 3
(quotient -17 5) ; => -3
```
