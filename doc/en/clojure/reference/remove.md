# remove

`(remove pred coll)`

Answers the members of `coll`'s seq view the predicate rejects, in order -- the
complement of `filter`. As a value a two-argument lambda.

```clojure
(println (remove odd? [1 2 3 4])) ; (2 4)
```
