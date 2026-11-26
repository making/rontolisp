# nfirst

`(nfirst coll)`

The tail of the head, each level through the seq view (the `next` shape: `nil`,
not `()`, past the end). Of empty, `nil`. As a value a one-argument lambda.

```clojure
(println (nfirst [[1 2 3]])) ; (2 3)
(println (nfirst [])) ; nil
```
