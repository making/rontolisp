# vals

`(vals m)`

Answers the list of the map's values in insertion order -- the order `keys` walks. `vals` of `nil` is `nil`, and of a seq of map entries (`(vals (filter f m))`) each entry's value.

As a value a one-argument lambda.

```clojure
(println (vals {:a 1}))          ; (1)
(println (vals {:a 1 :b 2}))    ; (1 2)
(println (vals nil))             ; nil
(println (map vals [{:a 1}]))    ; ((1))
```
