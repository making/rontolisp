# infinite?

`(infinite? x)`

`clojure.core/infinite?`: `##Inf` と `##-Inf` なら `true`、それ以外の数値なら `false` を返します。数値でない値はオラクルと同様にシグナルを上げます。値としては1引数の関数です。

```clojure
(println (infinite? ##Inf) (infinite? 1.5))  ; true false
```
