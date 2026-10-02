# partition-all

`(partition-all n coll)` / `(partition-all n step coll)` / `(partition-all n)`

`coll` の seq を `n` 個ずつのグループにして返します。`step` ずつ進みます（省略時は
`n`）。`partition` と違って端数のグループも残します。lazy な入力には lazy seq を、strict
な入力には strict なリストを返します。非正のサイズや step はシグナルし（オラクルは
`()` の無限 seq を返します）。`(partition-all n)` は[トランスデューサー](transducers.md)で、
ベクターを流し、端数は完了時に流します。値としては1引数から3引数を取ります。

```clojure
(println (partition-all 2 [1 2 3 4 5])) ; ((1 2) (3 4) (5))
(println (partition-all 2 1 [1 2 3])) ; ((1 2) (2 3) (3))
(println (into [] (partition-all 2) [1 2 3])) ; [[1 2] [3]]
```
