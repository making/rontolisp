# partition-all

`(partition-all n coll)` / `(partition-all n step coll)`

`coll` の seq を `n` 個ずつのグループにして返します。`step` ずつ進みます（省略時は
`n`）。`partition` と違って端数のグループも残します。lazy な入力には lazy seq を、strict
な入力には strict なリストを返します。非正のサイズや step はシグナルし（オラクルは
`()` の無限 seq を返します）、1引数のトランスデューサー形は名前で拒否します。値としては
2引数か3引数を取ります。

```clojure
(println (partition-all 2 [1 2 3 4 5])) ; ((1 2) (3 4) (5))
(println (partition-all 2 1 [1 2 3])) ; ((1 2) (2 3) (3))
```
