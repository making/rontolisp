# truncate-quotient

`(truncate-quotient n1 n2)`

`n1` を `n2` で割った商を 0 方向に切り捨てて返します。どちらかの引数が不正確数なら商も不正確数です。整数でない引数はエラーになります。

```scheme
(truncate-quotient -7 2) ; => -3
(truncate-quotient 7 2) ; => 3
(truncate-quotient 7 2.0) ; => 3.0
```
