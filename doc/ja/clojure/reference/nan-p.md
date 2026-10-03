# NaN?

`(NaN? x)`

`clojure.core/NaN?`: `##NaN` なら `true`、それ以外の数値なら `false` を返します。数値でない値はオラクルと同様にシグナルを上げます。値としては1引数の関数です。

```clojure
(println (NaN? ##NaN) (NaN? 1))  ; true false
```
