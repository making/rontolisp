# future?

`(future? x)`

`clojure.core/future?`: `false` for every value: `future` is refused, so no value here is one (the oracle's answer for every other value). The argument is still evaluated. As a value a one-argument function.

```clojure
(println (future? 1))  ; false
```
