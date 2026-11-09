# next

`(next coll)`

`coll` の seq ビューから最初の要素を除いたものを返し、そのビューが空なら `nil` です -- `(next coll)` は `(seq (rest coll))` です。そのため最初の要素の先に何もない lazy seq でも `nil` を返します（`rest` は空の末尾 `()` を返します）。

```clojure
(println (next '(1 2 3))) ; (2 3)
(println (next [1]))      ; nil
(println (next []))       ; nil
```
