# array-map

`(array-map k v ...)`

Answers a map built from the key/value pairs, exactly like `hash-map`: an `equal` hash
table, copy-on-write, walk order unspecified. Odd pair counts are refused.

As a value a rest lambda over the pairs; an odd rest count signals at run time.

Deviation: `array-map` and `hash-map` are identical here -- both build the same equal
table, where the oracle keeps insertion order in `array-map`.

```clojure
(println (get (array-map :a 1) :a)) ; 1
(println (count (array-map)))       ; 0
(println (get ((fn [f] (f :a 1)) array-map) :a)) ; 1
```
