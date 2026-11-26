# map-indexed

`(map-indexed f coll)` / `(map-indexed f)`

`coll` の seq ビューに対する、インデックスと要素への `f` の適用結果を返します。
インデックスは `0` 始まりです。lazy な入力には lazy seq を、strict な入力には strict なリストを返します。値としては2引数のラムダです。

`(map-indexed f)` は[トランスデューサー](transducers.md)を返します（値としても同じです）。

```clojure
(println (map-indexed vector [:a :b])) ; ([0 :a] [1 :b])
(println (take 2 (map-indexed vector (iterate inc 10)))) ; ([0 10] [1 11])
(println (into [] (map-indexed vector) [:a])) ; [[0 :a]]
```
