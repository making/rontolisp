# last

`(last coll)`

Answers the final member of `coll`'s seq view, or `nil`. As a value a
one-argument lambda.

```clojure
(println (last [1 2 3])) ; 3
(println (last [])) ; nil
```
