# iterate

`(iterate f x)`

`x`、`(f x)`、`(f (f x))`、……の lazy seq を返します。`map` と同じ呼び出し経路で適用されます（実関数もコレクション値も同様です）。各要素は読まれたときに1つ前の要素から計算されるため、要素を読んでも次の要素のために `f` は呼ばれません。seq は終わらないので、`take`、`take-while`、`nth` などを通して読みます。

```clojure
(println (take 5 (iterate inc 0))) ; (0 1 2 3 4)
```
