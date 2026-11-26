# keys

`(keys m)`

Answers the list of the map's keys in insertion order -- the order the map prints in
(the oracle's array map's, up to eight entries). `keys` of `nil` is `nil`, and of a seq of map
entries (`(keys (filter f m))`) each entry's key.

As a value a one-argument lambda.

Deviation: misuse of a non-map may signal the Common Lisp type error instead of the
oracle's.

```clojure
(println (keys {:a 1}))          ; (:a)
(println (keys {:a 1 :b 2}))    ; (:a :b)
(println (keys nil))             ; nil
(println (map keys [{:a 1}]))    ; ((:a))
```
