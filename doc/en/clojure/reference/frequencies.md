# frequencies

`(frequencies coll)`

Answers the member counts in one pass over `coll`'s seq view, as a fresh map.
Of empty, the empty map. As a value a one-argument lambda.

```clojure
(println (frequencies [:a :a])) ; {:a 2}
```
