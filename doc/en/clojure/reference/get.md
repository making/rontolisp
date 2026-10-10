# get

`(get m k)` / `(get m k default)`

Answers the value under `k`: in a map or set (the member itself), the element at index
`k` of a vector or character of a string, `default` (nil without one) when missing. Reads
`nil` too; a list answers the default. A record reads through its entry table; a deftype or reify answers the default, like the oracle. The same read backs keyword call position
`(:k m)`.

As a value the two- or three-argument read.

Keys compare by `=`, so a vector, list, map or set key finds an equal one, and so does a
deftype or reify whose type has a hash and an equality of its own
([reify](reify.md#map-keys-and-set-members)). A Java `Map`
looks `k` up itself, by `equals`; a key no Java method takes (a keyword, a symbol, a map,
a set) is in no Java map, and any other Java object answers the default, like the oracle.

```clojure
(println (get {:a 1} :a))      ; 1
(println (get {:a 1} :b :dflt)) ; :dflt
(println (get [10 20 30] 1))   ; 20
(println (get "abc" 1))        ; b
(println (get #{1 2} 2))       ; 2
(println (get {[:a] 1} [:a]))  ; 1
(println (get nil :a :d))      ; :d
(println (map get [{:a 1}] [:a])) ; (1)
```
