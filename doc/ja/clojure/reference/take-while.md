# take-while

`(take-while pred coll)` / `(take-while pred)`

`coll` の seq ビューのうち述語が truthy の間の strict な接頭辞を返します（`false` でも
`nil` 同様止まります）。値としては2引数のラムダです。

`(take-while pred)` は[トランスデューサー](transducers.md)を返します（値としても同じです）。

```clojure
(println (take-while pos? [3 1 -1 5])) ; (3 1)
(println (into [] (take-while pos?) [3 1 -1 5])) ; [3 1]
```
