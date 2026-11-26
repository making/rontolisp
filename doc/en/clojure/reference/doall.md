# doall

`(doall coll)` / `(doall n coll)`

Like `dorun`, but answers the collection itself -- never coerced, so a vector stays a
vector, and a realized lazy seq prints its members. Also names a function value.

```clojure
(println (doall [1 2 3])) ; [1 2 3]
```
