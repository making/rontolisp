# every?

`(every? pred coll)`

`true` when `pred` holds for every member of `coll`'s seq view, `false`
otherwise; empty is `true`, like the oracle. As a value a two-argument lambda.

```clojure
(println (every? odd? [1 3])) ; true
(println (every? odd? [1 2])) ; false
```
