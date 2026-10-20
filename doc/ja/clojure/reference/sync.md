# sync

`(sync flags body...)`

本体を `dosync` と同じ1つのロックの下で1つのトランザクションとして実行します。フラグは評価せずに捨てます（oracle の `flags-ignored-for-now` と同じです）。最後の値を返します。入れ子の `sync` は実行中のトランザクションに合流し、リトライは起きません（[Deviations](../deviations.md) を見てください）。

```clojure
(def r (ref 0))
(println (sync nil (alter r inc) (alter r inc))) ; 2
```
