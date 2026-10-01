# take-while

`(take-while pred coll)`

`coll` の seq ビューのうち述語が truthy の間の strict な接頭辞を返します（`false` でも
`nil` 同様止まります）。値としては2引数のラムダです。

```clojure
(println (take-while pos? [3 1 -1 5])) ; (3 1)
```
