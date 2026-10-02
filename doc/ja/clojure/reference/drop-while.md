# drop-while

`(drop-while pred coll)` / `(drop-while pred)`

`coll` の seq ビューのうち truthy な接頭辞を落とした残りを返します（末尾を共有します）。
値としては2引数のラムダです。

`(drop-while pred)` は[トランスデューサー](transducers.md)を返します（値としても同じです）。

```clojure
(println (drop-while neg? [-2 -1 0 1])) ; (0 1)
(println (into [] (drop-while neg?) [-2 0 1])) ; [0 1]
```
