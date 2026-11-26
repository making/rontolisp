# partial

`(partial f args...)`

固定引数に到着分を足した `f` を返します。固定引数は一度だけ評価されます。値としては関数に
続けて固定引数を取ります。

```clojure
(println ((partial + 10) 5)) ; 15
(println (map (partial + 10) [1 2])) ; (11 12)
```
