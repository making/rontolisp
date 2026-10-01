# filter

`(filter pred coll)`

`coll` の seq ビューのうち述語 `pred` を満たす要素を順に返します。空の結果は `nil` です。`coll` が lazy の場合、答えは realize しながら非適合要素を飛ばす lazy seq です。値としては lambda なので、素の述語名を取れます。

```clojure
(println (filter odd? '(1 2 3 4)))  ; (1 3)
(println (filter first [[1] [] [2 3]])) ; ([1] [2 3])
(println (take 4 (filter odd? (iterate inc 0)))) ; (1 3 5 7)
```
