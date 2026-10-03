# partition

`(partition n coll)` / `(partition n step coll)`

`coll` の seq ビューに対する `n` 個ずつのグループを返します。`step` ずつ進み
（省略時は `n`）、端数はオラクル同様落ちます。lazy な入力には strict なグループの lazy seq
を、strict な入力には strict なリストを返します。非正のサイズはシグナルします。pad 引数は
未対応で、値としても2引数か3引数で使います。

```clojure
(println (partition 2 1 [1 2 3])) ; ((1 2) (2 3))
(println (partition 3 [1 2 3 4 5 6 7])) ; ((1 2 3) (4 5 6))
(println (take 2 (partition 2 (iterate inc 0)))) ; ((0 1) (2 3))
```
