# floor-quotient

`(floor-quotient n1 n2)`

`n1` を `n2` で割った商を負の無限大方向に丸めて返します。どちらかの引数が不正確数なら商も不正確数です。整数でない引数はエラーになります。

```scheme
(floor-quotient -7 2) ; => -4
(floor-quotient 7 2) ; => 3
(floor-quotient -7.0 2) ; => -4.0
```
