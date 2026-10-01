# some->

`(some-> expr step...)`

`->` のように `expr` をステップへ通しますが、最初の `nil` 値で止まり `nil` を返します。`false` はスレッド化を止めません -- 止めるのは `nil` だけです。oracle と同じです。`nil` のないスレッド化は通常の `->` の書き換えです。

```clojure
(println (some-> 5 (inc) (inc))) ; 7
(println (some-> nil (inc)))     ; nil
```
