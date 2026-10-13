# random-sample

`(random-sample prob coll)`

`coll` の要素のうち確率 `prob` で残したもの。oracle の `rand` 上の filter と同じ。lazy な入力には lazy seq を、strict な入力には strict なリストを返す。値としては 2 引数を取る。

```clojure
(println (random-sample 1.0 [3 1 2])) ; (3 1 2)
```
