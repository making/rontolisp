# tagged-literal?

`(tagged-literal? x)`

`clojure.core/tagged-literal?`: `false` for every value: no tagged literal value exists. The argument is still evaluated. As a value a one-argument function.

```clojure
(println (tagged-literal? 1))  ; false
```
