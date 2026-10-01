# partition

`(partition n coll)` / `(partition n step coll)`

`coll` の seq ビューに対する `n` 個ずつの strict なグループを返します。`step` ずつ進み
（省略時は `n`）、端数はオラクル同様落ちます。非正のサイズはシグナルします。pad 引数は未対応で、
2引数か3引数で使います。値としては1引数か2引数のラムダです。

```clojure
(println (partition 2 1 [1 2 3])) ; ((1 2) (2 3))
(println (partition 3 [1 2 3 4 5 6 7])) ; ((1 2 3) (4 5 6))
```
