# bytes

`(bytes x)`

Casts `x` to a byte array, as the oracle's `bytes` casts to `byte[]`: a byte array and `nil`
answer themselves, anything else is the oracle's `ClassCastException`. As a value a
one-argument function.

```clojure
(def bs (byte-array 2))
(println (identical? bs (bytes bs)) (bytes nil)) ; true nil
```
