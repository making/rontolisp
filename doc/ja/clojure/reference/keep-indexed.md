# keep-indexed

`(keep-indexed f coll)`

`keep` と同じですが、`f` はインデックスと要素を取ります。`nil` でない結果を順に返します。
インデックスは `0` 始まりで、seq ビューに対するものです。値としては2引数のラムダです。

```clojure
(println (keep-indexed (fn [i x] (when (odd? x) i)) [10 11 12])) ; (1)
```
