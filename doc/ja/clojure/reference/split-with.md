# split-with

`(split-with pred coll)`

オラクルと同じく `[(take-while pred coll) (drop-while pred coll)]` を返します。lazy な
`coll` にはどちらの側も lazy で、述語は側ごとに1回ずつ接頭部を試します。`false` も `nil` と
同様に接頭部を止めます。strict なコレクションの空の側は `nil` です（オラクルは `()`）。
値としては2引数の関数です。

```clojure
(println (split-with odd? [1 3 4 5])) ; [(1 3) (4 5)]
```
