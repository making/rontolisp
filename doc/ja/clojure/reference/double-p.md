# double?

`(double? x)`

`clojure.core/double?`: double なら `true` を返します。`##Inf` と `##NaN` も含みます。値としては1引数の関数です。

```clojure
(println (double? 1.5) (double? 1))  ; true false
```
