# vals

`(vals m)`

マップの値のリストを、新しいものから順 -- `keys` と同じ順 -- で返します。`nil` の `vals` は `nil` です。

値としては 1 引数のラムダです。

```clojure
(println (vals {:a 1}))          ; (1)
(println (count (vals {:a 1 :b 2}))) ; 2
(println (vals nil))             ; nil
(println (map vals [{:a 1}]))    ; ((1))
```
