# get

`(get m k)` / `(get m k default)`

Answers the value under `k`: in a map or set (the member itself), the element at index
`k` of a vector or character of a string, `default` (nil without one) when missing. Reads
`nil` too; a list answers the default. A record reads through its entry table; a deftype or reify answers the default, like the oracle. The same read backs keyword call position
`(:k m)`.

As a value the two- or three-argument read.

Deviation: vector and table keys compare by identity, not structurally, so
`(get {[:a] 1} [:a])` misses here and answers `1` in the oracle; lists, strings, numbers
and keywords key structurally.

```clojure
(println (get {:a 1} :a))      ; 1
(println (get {:a 1} :b :dflt)) ; :dflt
(println (get [10 20 30] 1))   ; 20
(println (get "abc" 1))        ; b
(println (get #{1 2} 2))       ; 2
(println (get nil :a :d))      ; :d
(println (map get [{:a 1}] [:a])) ; (1)
```
