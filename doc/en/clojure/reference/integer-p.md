# integer?

`(integer? x)`

`clojure.core/integer?`: `true` for an integer of any size, `false` for a decimal (`1M`), like the oracle. As a value a one-argument function.

```clojure
(println (integer? 1) (integer? 99999999999999999999) (integer? 1.0) (integer? 1M))  ; true true false false
```
