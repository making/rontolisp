# take

`(take n coll)` / `(take n)`

`coll` の最初の `n` 要素を返します。lazy な `coll` には、読まれた要素ごとに1要素ずつ realize する lazy seq を返すため、`(take n infinite)` は終了し、読まれない要素は realize されません。strict な `coll` には strict なリストを返します。

仕様との差異: strict なコレクションに対する空の take は `nil` を返します。オラクルは `()` を表示します。

`(take n)` は[トランスデューサー](transducers.md)を返します（値としても同じです）。

```clojure
(println (take 3 (range 10))) ; (0 1 2)
(println (take 2 [1 2 3 4]))  ; (1 2)
(println (take 10 [1 2]))     ; (1 2)
(println (take 5 (iterate inc 0))) ; (0 1 2 3 4)
(println (into [] (take 2) (iterate inc 0))) ; [0 1]
```
