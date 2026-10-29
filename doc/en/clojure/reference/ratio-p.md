# ratio?

`(ratio? x)`

`clojure.core/ratio?`: `true` for a ratio, a rational that is no integer; `false` for a decimal (`1.5M`), like the oracle. As a value a one-argument function.

```clojure
(println (ratio? 1/2) (ratio? 2) (ratio? 0.5) (ratio? 1.5M))  ; true false false false
```
