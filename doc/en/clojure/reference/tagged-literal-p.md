# tagged-literal?

`(tagged-literal? x)`

`clojure.core/tagged-literal?`: whether `x` is a [tagged-literal](tagged-literal.md), which only a tag inside a reader conditional under `{:read-cond :preserve}` and the constructor make. As a value a one-argument function.

```clojure
(println (tagged-literal? (tagged-literal 'js {})))  ; true
(println (tagged-literal? 1))  ; false
```
