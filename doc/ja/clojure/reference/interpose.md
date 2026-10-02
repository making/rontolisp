# interpose

`(interpose sep coll)` / `(interpose sep)`

`coll` の seq ビューの要素の間に `sep` を挟んだ結果を strict に返します。要素が1つなら
区切りは現れません。値としては2引数のラムダです。

`(interpose sep)` は[トランスデューサー](transducers.md)を返します（値としても同じです）。

```clojure
(println (interpose 0 [1 2 3])) ; (1 0 2 0 3)
(println (into [] (interpose 0) [1 2 3])) ; [1 0 2 0 3]
```
