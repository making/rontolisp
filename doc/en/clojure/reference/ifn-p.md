# ifn?

`(ifn? x)`

`clojure.core/ifn?`: `true` for what the oracle can invoke: a function, keyword, symbol, map, set (sorted ones too), vector or var. A record, a list, a string and a number are `false`. As a value a one-argument function.

```clojure
(println (ifn? inc) (ifn? :a) (ifn? {}) (ifn? '(1)) (ifn? 1))  ; true true true false false
```
