# fnext

`(fnext coll)`

The head of the `next` of `coll`, the tail through the seq view: `(first (next coll))`.
As a value a one-argument lambda.

```clojure
(println (fnext [1 2 3])) ; 2
(println (fnext [1])) ; nil
```
