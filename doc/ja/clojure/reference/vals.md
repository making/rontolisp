# vals

`(vals m)`

マップの値のリストを、表の走査順 -- `keys` が走るのと同じ未規定の順 -- で返します。`nil` の `vals` は `nil` です。

値としては 1 引数のラムダです。

```clojure
(println (vals {:a 1}))          ; (1)
(println (count (vals {:a 1 :b 2}))) ; 2
(println (vals nil))             ; nil
(println (map vals [{:a 1}]))    ; ((1))
```
