# contains?

`(contains? coll k)`

Answers whether `k` is present: a key of the map, a member of the set, or a valid index
of the vector or string (a bounds check). `nil` contains nothing; anything else answers
`false`.

As a value a two-argument lambda.

```clojure
(println (contains? {:a 1} :a))  ; true
(println (contains? #{1 2} 9))   ; false
(println (contains? [:a :b] 1))  ; true
(println (contains? "abc" 0))    ; true
(println (contains? nil :a))     ; false
(println (map contains? [#{1}] [1])) ; (true)
```
