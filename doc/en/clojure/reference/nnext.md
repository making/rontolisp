# nnext

`(nnext coll)`

The `next` of the `next` of `coll`, each tail through the seq view: `nil` of fewer than
three members. As a value a one-argument lambda.

```clojure
(println (nnext [1 2 3])) ; (3)
(println (nnext [1 2])) ; nil
```
