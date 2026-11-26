# distinct

`(distinct coll)` / `(distinct)`

`coll` の seq ビューから後の重複を落とし、初出を順に残して返します。所属判定はセットと同じく
`=` です。lazy な入力には lazy seq を、strict な入力には strict なリストを返します。値としては1引数のラムダです。

`(distinct)` は[トランスデューサー](transducers.md)を返します（値としても同じです）。

```clojure
(println (distinct [3 1 3 2 1])) ; (3 1 2)
(println (take 3 (distinct (cycle [1 2 1 3])))) ; (1 2 3)
(println (into [] (distinct) [3 1 3])) ; [3 1]
```
