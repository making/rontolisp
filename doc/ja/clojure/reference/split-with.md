# split-with

`(split-with pred coll)`

`[(take-while pred coll) (drop-while pred coll)]` を `coll` の seq の1回の走査で返します。
`false` も `nil` と同様に接頭部を止めます。空の側は `nil` です（オラクルは `()`）。
値としては2引数の関数です。

```clojure
(println (split-with odd? [1 3 4 5])) ; [(1 3) (4 5)]
```
