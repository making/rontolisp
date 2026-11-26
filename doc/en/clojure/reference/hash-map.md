# hash-map

`(hash-map k v ...)`

Answers a map built from the key/value pairs: an `equal` hash table, never mutated in
place -- every verb builds a fresh one, sharing the old one's structure, so persistence
holds observably. It prints its entries in insertion order. Odd pair counts are refused.

As a value a rest lambda over the pairs; an odd rest count signals at run time.

Deviation: `hash-map` and `array-map` are identical here -- both build the same equal
table, which keeps insertion order like the oracle's `array-map`, past eight entries too,
where the oracle's `hash-map` has an order of its own.

```clojure
(println (get (hash-map :a 1 :b 2) :b)) ; 2
(println (count (hash-map)))            ; 0
(println (get ((fn [f] (f :a 1)) hash-map) :a)) ; 1
```
