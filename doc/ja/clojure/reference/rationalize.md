# rationalize

`(rationalize x)`

`nil` は `nil`、有理数はそのまま、doubleは表示される最短の10進表現の厳密な有理数を返します。`(rationalize 0.1)` は `1/10`、`(rationalize 0.3333333333333333)` は `3333333333333333/10000000000000000` で、オラクルと同じです。NaN・無限大・非数はシグナルします。値としては1引数の関数です。

```clojure
(println (rationalize 0.5) (rationalize 0.1) (rationalize nil)) ; 1/2 1/10 nil
```
