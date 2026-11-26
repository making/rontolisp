# lazy-cat

`(lazy-cat expr...)`

各式を `lazy-seq` で包んで遅延連結した結果を返します。`(concat (lazy-seq expr) ...)` と等価ですが、各要素が lazy のままなので、無限の要素も `take` 越しに終了します。`(lazy-cat)` は `nil` です。

```clojure
(println (take 6 (lazy-cat [0 1] [2 3] (iterate inc 4)))) ; (0 1 2 3 4 5)
```
