# map-indexed

`(map-indexed f coll)`

`coll` の seq ビューに対する、インデックスと要素への `f` の適用結果を strict に返します。
インデックスは `0` 始まりです。値としては2引数のラムダです。

```clojure
(println (map-indexed vector [:a :b])) ; ([0 :a] [1 :b])
```
