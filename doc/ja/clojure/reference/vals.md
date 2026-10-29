# vals

`(vals m)`

マップの値のリストを挿入順 -- `keys` と同じ順 -- で返します。`nil` の `vals` は `nil`、マップエントリの列（`(vals (filter f m))`）の `vals` は各エントリの値です。

値としては 1 引数のラムダです。

```clojure
(println (vals {:a 1}))          ; (1)
(println (vals {:a 1 :b 2}))    ; (1 2)
(println (vals nil))             ; nil
(println (map vals [{:a 1}]))    ; ((1))
```
