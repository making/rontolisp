# partitionv-all

`(partitionv-all n coll)` / `(partitionv-all n step coll)`

`partition-all` の各ランをベクターにして順に並べたもの。短い末尾も残る。非正のサイズやステップは通知する（oracle は無限に回る）。lazy な入力には lazy seq を、strict な入力には strict なリストを返す。値としては 2 引数か 3 引数を取る。

```clojure
(println (partitionv-all 2 [1 2 3])) ; ([1 2] [3])
```
