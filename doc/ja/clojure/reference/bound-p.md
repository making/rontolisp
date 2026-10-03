# bound?

`(bound? & vars)`

`clojure.core/bound?`: すべての var が値を持つとき `true` を返します。ここでは var は常に値を持つため（値なしの `def` は `nil` を束縛し、オラクルでは未束縛のまま）、どの var も `true` です。var でない値はオラクルと同様にシグナルを上げます。値としては任意個の var をとる関数です。

```clojure
(def bp-x 1)
(println (bound? #'bp-x) (bound?))  ; true true
```
