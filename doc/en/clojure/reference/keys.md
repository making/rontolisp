# keys

`(keys m)`

Answers the list of the map's keys, in the table's walk order -- unspecified. `keys` of
`nil` is `nil`.

As a value a one-argument lambda.

Deviation: misuse of a non-map may signal the Common Lisp type error instead of the
oracle's.

```clojure
(println (keys {:a 1}))          ; (:a)
(println (count (keys {:a 1 :b 2}))) ; 2
(println (keys nil))             ; nil
(println (map keys [{:a 1}]))    ; ((:a))
```
