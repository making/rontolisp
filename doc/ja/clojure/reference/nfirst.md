# nfirst

`(nfirst coll)`

先頭の末尾です。各レベルは seq ビューを通ります（`next` 形であり、末尾を過ぎると
`()` ではなく `nil` です）。空の場合は `nil` です。値としては1引数ラムダです。

```clojure
(println (nfirst [[1 2 3]])) ; (2 3)
(println (nfirst [])) ; nil
```
