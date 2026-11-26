# var?

`(var? x)`

`clojure.core/var?`: `true` for a var (`#'x`); a symbol and the var's value are `false`. As a value a one-argument function.

```clojure
(def vp-x 1)
(println (var? #'vp-x) (var? 'vp-x) (var? vp-x))  ; true false false
```
