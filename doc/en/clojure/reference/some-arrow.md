# some->

`(some-> expr step...)`

Threads `expr` through the steps like `->`, but stops at the first `nil` value and
answers `nil`; `false` does NOT stop the threading -- only `nil` does, like the
oracle. A nil-free threading is the plain `->` rewrite.

```clojure
(println (some-> 5 (inc) (inc))) ; 7
(println (some-> nil (inc)))     ; nil
```
