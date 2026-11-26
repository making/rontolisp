# not-any?

`(not-any? pred coll)`

`clojure.core/not-any?`: `true` when `pred` is falsey for every member of `coll` (the negation of `some`); it stops at the first truthy answer, so an infinite seq answers once one is found. As a value a two-argument function.

```clojure
(println (not-any? odd? [2 4]) (not-any? odd? [2 3]))  ; true false
```
