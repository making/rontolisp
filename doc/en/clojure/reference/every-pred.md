# every-pred

`(every-pred p q...)`

Answers a predicate that is `true` when every given predicate holds for every
argument (with no arguments, `true`), else `false`. As a value it takes one or more
predicates.

```clojure
(println ((every-pred odd? pos?) 1 3 5)) ; true
(println ((every-pred odd? pos?) 1 -3)) ; false
```
