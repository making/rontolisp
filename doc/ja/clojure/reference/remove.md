# remove

`(remove pred coll)` / `(remove pred)`

`coll` の seq ビューのうち述語が棄却した要素を順に返します。`filter` の補集合です。
値としては2引数のラムダです。

`(remove pred)` は[トランスデューサー](transducers.md)を返します（値としても同じです）。

```clojure
(println (remove odd? [1 2 3 4])) ; (2 4)
(println (into [] (remove odd?) [1 2 3])) ; [2]
```
