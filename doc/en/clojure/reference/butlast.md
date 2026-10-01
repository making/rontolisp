# butlast

`(butlast coll)`

Answers everything but the final member of `coll`'s seq view. As a value a
one-argument lambda.

```clojure
(println (butlast [1 2 3])) ; (1 2)
```
