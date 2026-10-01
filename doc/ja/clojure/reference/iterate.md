# iterate

`(iterate f x)`

`x`、`(f x)`、`(f (f x))`、……の lazy seq を返します。`map` と同じ呼び出し経路で適用されます（実関数もコレクション値も同様です）。`take` 越しにだけ終了します。

```clojure
(println (take 5 (iterate inc 0))) ; (0 1 2 3 4)
```
