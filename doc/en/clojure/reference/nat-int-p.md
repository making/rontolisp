# nat-int?

`(nat-int? x)`

`clojure.core/nat-int?`: `true` for an `int?` that is not negative. As a value a one-argument function.

```clojure
(println (nat-int? 0) (nat-int? -1) (nat-int? 1.0))  ; true false false
```
