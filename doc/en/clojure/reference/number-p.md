# number?

`(number? x)`

`clojure.core/number?`: `true` for any number: an integer, ratio or double. As a value a one-argument function.

```clojure
(println (number? 1) (number? 1/2) (number? 1.5) (number? "1"))  ; true true true false
```
