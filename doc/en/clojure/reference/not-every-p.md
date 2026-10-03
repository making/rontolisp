# not-every?

`(not-every? pred coll)`

`clojure.core/not-every?`: `true` when `pred` is falsey for some member of `coll` (the negation of `every?`); it stops at the first falsey answer. As a value a two-argument function.

```clojure
(println (not-every? odd? [1 2]) (not-every? odd? [1 3]))  ; true false
```
