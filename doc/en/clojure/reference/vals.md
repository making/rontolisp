# vals

`(vals m)`

Answers the list of the map's values, in the table's walk order -- the same unspecified
order `keys` walks. `vals` of `nil` is `nil`.

```clojure
(println (vals {:a 1}))          ; (1)
(println (count (vals {:a 1 :b 2}))) ; 2
(println (vals nil))             ; nil
```
