# split-at

`(split-at n coll)`

`[(take n coll) (drop n coll)]`、つまり `coll` の seq を `n` で分けた2つを返します。
空の側は `nil` です（オラクルは `()`）。値としては2引数の関数です。

```clojure
(println (split-at 2 [1 2 3 4])) ; [(1 2) (3 4)]
(println (split-at 5 [1 2])) ; [(1 2) nil]
```
