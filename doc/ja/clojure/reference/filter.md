# filter

`(filter pred coll)`

`coll` の seq ビューの要素のうち `pred` が成り立つものを、順に並べたリストを返します。空の結果は `nil` です。値としてはラムダなので、`filter` は述語名を裸のまま取ります。

```clojure
(println (filter odd? '(1 2 3 4)))  ; (1 3)
(println (filter first [[1] [] [2 3]])) ; ([1] [2 3])
```
