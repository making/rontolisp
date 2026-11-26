# partition

`(partition n coll)` / `(partition n step coll)` / `(partition n step pad coll)`

`coll` の seq ビューに対する `n` 個ずつのグループを返します。`step` ずつ進み
（省略時は `n`）、端数はオラクル同様落ちます。`pad` を渡すと端数は落ちずに残り、`pad` の
要素で `n` 個まで埋めます（`pad` は無限 seq でも構いません）。lazy な入力には strict な
グループの lazy seq を、strict な入力には strict なリストを返します。非正のサイズは
シグナルします。値としても2〜4引数で使えます。

```clojure
(println (partition 2 1 [1 2 3])) ; ((1 2) (2 3))
(println (partition 3 [1 2 3 4 5 6 7])) ; ((1 2 3) (4 5 6))
(println (take 2 (partition 2 (iterate inc 0)))) ; ((0 1) (2 3))
(println (partition 3 3 [:a :b] [1 2 3 4])) ; ((1 2 3) (4 :a :b))
```
