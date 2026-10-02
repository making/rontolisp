# keep-indexed

`(keep-indexed f coll)` / `(keep-indexed f)`

`keep` と同じですが、`f` はインデックスと要素を取ります。`nil` でない結果を順に返します。
インデックスは `0` 始まりで、seq ビューに対するものです。値としては2引数のラムダです。

`(keep-indexed f)` は[トランスデューサー](transducers.md)を返します（値としても同じです）。

```clojure
(println (keep-indexed (fn [i x] (when (odd? x) i)) [10 11 12])) ; (1)
(println (into [] (keep-indexed (fn [i x] (when (odd? x) i))) [10 11 12])) ; [1]
```
