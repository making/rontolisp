# delay?

`(delay? x)`

`clojure.core/delay?`: `false` for every value: `delay` is refused, so no value here is one (the oracle's answer for every other value). The argument is still evaluated. As a value a one-argument function.

```clojure
(println (delay? 1))  ; false
```
