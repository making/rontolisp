# hash-map

`(hash-map k v ...)`

Answers a map built from the key/value pairs: an `equal` hash table, never mutated in
place -- every verb builds a fresh one, so persistence holds observably. Walk order is
unspecified. Odd pair counts are refused.

Deviation: `hash-map` and `array-map` are identical here -- both build the same equal
table, where the oracle keeps insertion order in `array-map`.

```clojure
(println (get (hash-map :a 1 :b 2) :b)) ; 2
(println (count (hash-map)))            ; 0
```
