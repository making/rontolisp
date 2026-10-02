# partition-by

`(partition-by f coll)` / `(partition-by f)`

`coll` の seq を、`(f x)` が先頭の値と `=` であり続ける区間ごとに分けます。各区間は
strict です。lazy な入力には区間の lazy seq を、strict な入力には strict なリストを返します。
`(partition-by f)` は[トランスデューサー](transducers.md)で、ベクターを流します。値としては1引数か2引数を取ります。

```clojure
(println (partition-by odd? [1 3 2 4 5])) ; ((1 3) (2 4) (5))
(println (take 2 (partition-by #(quot % 3) (iterate inc 0)))) ; ((0 1 2) (3 4 5))
(println (into [] (partition-by odd?) [1 3 2])) ; [[1 3] [2]]
```
