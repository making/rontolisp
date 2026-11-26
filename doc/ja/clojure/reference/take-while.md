# take-while

`(take-while pred coll)` / `(take-while pred)`

`coll` の seq ビューのうち述語が truthy の間の要素を返します（`false` でも `nil` 同様
止まります）。lazy な `coll` には、読まれた要素ごとに1要素ずつ述語を試す lazy seq を返すため、
述語が偽にならない無限の `coll` でも先頭の要素を答えます。strict な `coll` には strict な
リストを返します。値としては2引数のラムダです。

`(take-while pred)` は[トランスデューサー](transducers.md)を返します（値としても同じです）。

```clojure
(println (take-while pos? [3 1 -1 5])) ; (3 1)
(println (take 3 (take-while pos? (iterate inc 1)))) ; (1 2 3)
(println (into [] (take-while pos?) [3 1 -1 5])) ; [3 1]
```
