# partitionv

`(partitionv n coll)` / `(partitionv n step coll)` / `(partitionv n step pad coll)`

`partition` の各ランをベクターにして順に並べたもの。端数は落ちる（`pad` があれば `n` 件まで埋める）。非正のサイズやステップは通知する（oracle は無限に回る）。lazy な入力には lazy seq を、strict な入力には strict なリストを返す。値としては 2 引数から 4 引数を取る。

```clojure
(println (partitionv 2 [1 2 3 4])) ; ([1 2] [3 4])
(println (partitionv 2 [1 2 3])) ; ([1 2])
```
