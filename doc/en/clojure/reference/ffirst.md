# ffirst

`(ffirst coll)`

The head of the head, each level through the seq view (a vector head coerces
before its own head is read). Of empty, `nil`. As a value a one-argument lambda.

```clojure
(println (ffirst [[1 2]])) ; 1
(println (ffirst [])) ; nil
```
