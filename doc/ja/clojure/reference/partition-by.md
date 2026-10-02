# partition-by

`(partition-by f coll)`

`coll` の seq を、`(f x)` が先頭の値と `=` であり続ける区間ごとに分けます。各区間は
strict です。lazy な入力には区間の lazy seq を、strict な入力には strict なリストを返します。
1引数のトランスデューサー形は名前で拒否します。値としては2引数の関数です。

```clojure
(println (partition-by odd? [1 3 2 4 5])) ; ((1 3) (2 4) (5))
(println (take 2 (partition-by #(quot % 3) (iterate inc 0)))) ; ((0 1 2) (3 4 5))
```
