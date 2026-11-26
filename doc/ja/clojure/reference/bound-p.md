# bound?

`(bound? & vars)`

`clojure.core/bound?`: すべての var が値を持つとき `true` を返します。宣言されただけで定義されない名前と値なしの `def` は、定義が束縛するまで未束縛です。マクロの var は束縛済みです。オラクルの `every?` と同様に最初の未束縛の var で止まり、var でない値はそこに達したときにシグナルを上げます。値としては任意個の var をとる関数です。

```clojure
(def bp-x 1)
(declare bp-y)
(println (bound? #'bp-x) (bound?) (bound? #'bp-y))  ; true true false
```
