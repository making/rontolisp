# some->>

`(some->> expr step...)`

`->>` のように `expr` をステップへ通しますが、最初の `nil` 値で止まり `nil` を返します。`false` はスレッド化を止めません -- 止めるのは `nil` だけです。オラクルと同じです。

```clojure
(println (some->> [1 2] (map inc))) ; (2 3)
(println (some->> nil inc))         ; nil
```
