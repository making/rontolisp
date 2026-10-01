# next

`(next coll)`

`coll` の seq ビューから最初の要素を除いたものを返し、そのビューが空なら `nil` です -- `(next coll)` は `(seq (rest coll))` です。

仕様との差異: 1 要素のコレクションの `next` は `nil` を返します。オラクルは `()` を表示します。

```clojure
(println (next '(1 2 3))) ; (2 3)
(println (next [1]))      ; nil
(println (next []))       ; nil
```
