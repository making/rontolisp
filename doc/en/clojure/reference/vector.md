# vector

`(vector x...)`

Answers a vector of the arguments, in order. A vector literal lowers to this call. Vector
keys compare by identity (see `get`).

```clojure
(println (vector 1 2))    ; [1 2]
(println (vector))        ; []
(println (vector :a [1 2])) ; [:a [1 2]]
```
