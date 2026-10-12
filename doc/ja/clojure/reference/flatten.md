# flatten

`(flatten x)`

`x` の入れ子の順序コレクション（リスト・ベクタ・シーケンス）の要素のうち、順序コレクション
でないものを深さ優先の遅延シーケンスで返します。オラクルの
`(filter (complement sequential?) (rest (tree-seq sequential? seq x)))` と同じです。マップや
セットは1つの要素のままで、順序コレクションでない `x` には `()` を返します。関数値としても
使えます。

```clojure
(println (flatten [1 [2 [3 '(4)]] 5])) ; (1 2 3 4 5)
(println (flatten [{:a 1} #{2}])) ; ({:a 1} #{2})
(println (take 3 (flatten (repeat [1 [2]])))) ; (1 2 1)
```
